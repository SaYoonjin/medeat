package com.medeat.notification.batch.outbox;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class MedicationNotificationSendDaoMySqlIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("medeat_test")
            .withUsername("test")
            .withPassword("test");

    private static JdbcTemplate jdbcTemplate;
    private static MedicationNotificationSendDao dao;

    @BeforeAll
    static void migrate() {
        DataSource dataSource = dataSource();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbcTemplate = new JdbcTemplate(dataSource);
        dao = new MedicationNotificationSendDao(new NamedParameterJdbcTemplate(dataSource));
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM `user` WHERE login_id LIKE 'tc_%'");
    }

    @Test
    void duplicateOutboxInsertKeepsSingleRowWithActualMySqlUniqueConstraint() {
        Seed seed = seedMedication("tc_unique_user");
        MedicationNotificationOutboxCommand command = command(seed, LocalDateTime.of(2026, 7, 13, 8, 0));

        int firstResult = dao.insertPending(command);
        int secondResult = dao.insertPending(command);

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM medication_notification_send WHERE user_id = ? AND medication_id = ?",
                Integer.class,
                seed.userId(),
                seed.medicationId()
        );

        assertThat(firstResult).isEqualTo(1);
        assertThat(secondResult).isEqualTo(1);
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void concurrentClaimAllowsOnlyOneWorkerToProcessSameOutbox() throws Exception {
        Seed seed = seedMedication("tc_claim_user");
        Long sendId = insertOutbox(seed, LocalDateTime.of(2026, 7, 13, 8, 0));
        LocalDateTime now = LocalDateTime.of(2026, 7, 13, 8, 5);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        Future<Boolean> first = executor.submit(() -> claimAfterStart(sendId, "claim-1", "worker-1", now, ready, start));
        Future<Boolean> second = executor.submit(() -> claimAfterStart(sendId, "claim-2", "worker-2", now, ready, start));

        ready.await();
        start.countDown();

        boolean firstClaimed = first.get();
        boolean secondClaimed = second.get();
        executor.shutdown();

        assertThat(List.of(firstClaimed, secondClaimed)).containsExactlyInAnyOrder(true, false);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, attempt_count, claim_token FROM medication_notification_send WHERE notification_send_id = ?",
                sendId
        );
        assertThat(row.get("status")).isEqualTo("PROCESSING");
        assertThat(row.get("attempt_count")).isEqualTo(1);
        assertThat(row.get("claim_token")).isIn("claim-1", "claim-2");
    }

    @Test
    void staleClaimTokenCannotOverwriteProcessingResult() {
        Seed seed = seedMedication("tc_stale_token_user");
        Long sendId = insertOutbox(seed, LocalDateTime.of(2026, 7, 13, 8, 0));
        LocalDateTime now = LocalDateTime.of(2026, 7, 13, 8, 5);

        boolean claimed = dao.claimForProcessing(
                sendId,
                "new-token",
                "worker-1",
                now,
                now.plusMinutes(10),
                3
        );

        boolean staleUpdate = dao.markSent(sendId, "old-token", now.plusSeconds(1));
        boolean actualUpdate = dao.markSent(sendId, "new-token", now.plusSeconds(2));

        assertThat(claimed).isTrue();
        assertThat(staleUpdate).isFalse();
        assertThat(actualUpdate).isTrue();
        assertThat(status(sendId)).isEqualTo("SENT");
    }

    @Test
    void staleProcessingRecoveryMovesExpiredClaimsToRetryOrFailedAndClearsClaimColumns() {
        Seed retrySeed = seedMedication("tc_stale_retry_user");
        Seed failedSeed = seedMedication("tc_stale_failed_user");
        Long retrySendId = insertOutbox(retrySeed, LocalDateTime.of(2026, 7, 13, 8, 0));
        Long failedSendId = insertOutbox(failedSeed, LocalDateTime.of(2026, 7, 13, 8, 0));
        LocalDateTime now = LocalDateTime.of(2026, 7, 13, 8, 30);

        forceProcessing(retrySendId, 1, "retry-token", now.minusMinutes(20));
        forceProcessing(failedSendId, 3, "failed-token", now.minusMinutes(20));

        int recovered = dao.recoverStaleProcessing(now, 3, now.plusMinutes(1));

        assertThat(recovered).isEqualTo(2);
        assertThat(status(retrySendId)).isEqualTo("RETRY");
        assertThat(status(failedSendId)).isEqualTo("FAILED");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT claim_token, claimed_at, claimed_by, claim_expires_at, failure_code "
                        + "FROM medication_notification_send "
                        + "WHERE notification_send_id IN (?, ?) "
                        + "ORDER BY notification_send_id",
                retrySendId,
                failedSendId
        );
        assertThat(rows)
                .allSatisfy(row -> {
                    assertThat(row.get("claim_token")).isNull();
                    assertThat(row.get("claimed_at")).isNull();
                    assertThat(row.get("claimed_by")).isNull();
                    assertThat(row.get("claim_expires_at")).isNull();
                    assertThat(row.get("failure_code")).isEqualTo("LEASE_EXPIRED");
                });
    }

    @Test
    void dispatchCandidateLookupUsesNotificationIndexInActualMySqlExplain() {
        Seed seed = seedMedication("tc_explain_user");
        insertOutbox(seed, LocalDateTime.of(2026, 7, 13, 8, 0));

        List<Map<String, Object>> explainRows = jdbcTemplate.queryForList("""
                EXPLAIN
                SELECT
                    notification_send_id,
                    user_id,
                    medication_id,
                    scheduled_at,
                    dose_sequence,
                    attempt_count,
                    notification_title,
                    notification_body,
                    payload_json
                FROM medication_notification_send
                WHERE status IN ('PENDING', 'RETRY')
                  AND attempt_count < 3
                  AND scheduled_at <= '2026-07-13 08:05:00.000000'
                  AND (next_retry_at IS NULL OR next_retry_at <= '2026-07-13 08:05:00.000000')
                ORDER BY scheduled_at ASC, notification_send_id ASC
                LIMIT 100
                """);

        assertThat(explainRows)
                .extracting(row -> row.get("key"))
                .containsAnyOf("idx_med_notification_due_status", "idx_med_notification_schedule_status");
    }

    private boolean claimAfterStart(
            Long sendId,
            String claimToken,
            String workerId,
            LocalDateTime now,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        return dao.claimForProcessing(sendId, claimToken, workerId, now, now.plusMinutes(10), 3);
    }

    private Seed seedMedication(String loginId) {
        jdbcTemplate.update("""
                INSERT INTO `user` (login_id, password, name, push_enabled)
                VALUES (?, 'password', ?, 1)
                """, loginId, loginId);
        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM `user` WHERE login_id = ?",
                Long.class,
                loginId
        );
        jdbcTemplate.update("""
                INSERT INTO medication (
                    user_id,
                    drug_name,
                    intake_time,
                    interval_hour,
                    daily_count
                )
                VALUES (?, 'TestDrug', '08:00', 8, 3)
                """, userId);
        Long medicationId = jdbcTemplate.queryForObject(
                "SELECT medication_id FROM medication WHERE user_id = ? ORDER BY medication_id DESC LIMIT 1",
                Long.class,
                userId
        );
        return new Seed(userId, medicationId);
    }

    private Long insertOutbox(Seed seed, LocalDateTime scheduledAt) {
        dao.insertPending(command(seed, scheduledAt));
        return jdbcTemplate.queryForObject(
                "SELECT notification_send_id FROM medication_notification_send WHERE user_id = ? AND medication_id = ?",
                Long.class,
                seed.userId(),
                seed.medicationId()
        );
    }

    private MedicationNotificationOutboxCommand command(Seed seed, LocalDateTime scheduledAt) {
        return new MedicationNotificationOutboxCommand(
                seed.userId(),
                seed.medicationId(),
                scheduledAt,
                1,
                "Medication reminder",
                "TestDrug dose is due.",
                "{\"title\":\"Medication reminder\"}",
                100L
        );
    }

    private void forceProcessing(Long sendId, int attemptCount, String claimToken, LocalDateTime expiredAt) {
        jdbcTemplate.update("""
                UPDATE medication_notification_send
                SET status = 'PROCESSING',
                    attempt_count = ?,
                    claim_token = ?,
                    claimed_at = ?,
                    claimed_by = 'integration-test',
                    claim_expires_at = ?
                WHERE notification_send_id = ?
                """, attemptCount, claimToken, expiredAt.minusMinutes(10), expiredAt, sendId);
    }

    private String status(Long sendId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM medication_notification_send WHERE notification_send_id = ?",
                String.class,
                sendId
        );
    }

    private static DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(MYSQL.getJdbcUrl() + "?allowMultiQueries=true&rewriteBatchedStatements=true");
        dataSource.setUsername(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        return dataSource;
    }

    private record Seed(Long userId, Long medicationId) {
    }
}
