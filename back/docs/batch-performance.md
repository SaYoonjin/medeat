# Medication Notification Batch Performance

## 1. 현재 실측 상태

이 문서는 대량 데이터 성능 측정과 EXPLAIN 기반 인덱스 검증을 기록하기 위한 문서다.

현재 Codex 실행 환경에서는 MySQL CLI가 없고 `localhost:3306` 연결도 실패했다.

```text
mysql CLI: not found
localhost:3306: TCP connect failed
```

따라서 이 환경에서는 실제 DB 실행시간과 EXPLAIN 결과를 측정하지 못했다. 대신 다음 산출물을 추가해 DB가 준비된 환경에서 동일 절차로 재현 가능하게 했다.

| 산출물 | 목적 |
|---|---|
| `docs/sql/notification-batch-performance-seed.sql` | 대량 사용자/복약/Outbox 테스트 데이터 생성 |
| `docs/sql/notification-batch-explain.sql` | 후보 조회, 선점, 결과 저장, stale 복구 EXPLAIN |
| `docs/batch-architecture.md` | Batch 구조와 트랜잭션/복구 정책 설명 |
| `docs/batch-performance.md` | 측정 방법과 기록 표 |

## 1.1 Testcontainers MySQL 통합 테스트

실제 MySQL 제약과 실행계획을 검증하기 위해 Testcontainers 기반 통합 테스트를 추가했다.

테스트 파일:

```text
src/test/java/com/medeat/notification/batch/outbox/MedicationNotificationSendDaoMySqlIntegrationTest.java
```

검증 항목:

| 테스트 | 검증 내용 |
|---|---|
| `duplicateOutboxInsertKeepsSingleRowWithActualMySqlUniqueConstraint` | 동일 Outbox 2회 저장 시 실제 MySQL 유니크 제약으로 1행만 유지 |
| `concurrentClaimAllowsOnlyOneWorkerToProcessSameOutbox` | 두 Thread 동시 claim 시 하나만 PROCESSING 선점 |
| `staleClaimTokenCannotOverwriteProcessingResult` | 오래된 claim token으로 결과 저장 시 update 0건 |
| `staleProcessingRecoveryMovesExpiredClaimsToRetryOrFailedAndClearsClaimColumns` | Lease 만료 PROCESSING을 RETRY/FAILED로 복구하고 claim 컬럼 초기화 |
| `dispatchCandidateLookupUsesNotificationIndexInActualMySqlExplain` | 실제 MySQL EXPLAIN에서 후보 조회 인덱스 선택 여부 확인 |

실행 명령:

```powershell
.\mvnw.cmd "-Dtest=MedicationNotificationSendDaoMySqlIntegrationTest" test
```

현재 Codex 환경에서는 Docker CLI는 설치되어 있으나 Docker daemon이 실행 중이 아니어서 Testcontainers 테스트가 skip되었다.

```text
Tests run: 5
Failures: 0
Errors: 0
Skipped: 5
Reason: Could not find a valid Docker environment
```

Docker Desktop을 실행한 뒤 같은 명령을 다시 실행하면 실제 MySQL 컨테이너에서 테스트가 수행된다.

## 2. 측정 환경 기록 양식

실측 시 아래 값을 반드시 기록한다.

| 항목 | 값 |
|---|---|
| 측정일 | TBD |
| CPU | TBD |
| Memory | TBD |
| OS | TBD |
| JDK | 17 |
| Spring Boot | 3.5.7 |
| MySQL | TBD |
| DB 실행 환경 | local / docker / remote |
| JVM 옵션 | TBD |
| 데이터 규모 | TBD |
| Batch scheduler enabled | false 권장 |

## 3. 데이터 생성 절차

성능 DB에서 Flyway 마이그레이션을 적용한 뒤 실행한다.

```sql
SOURCE docs/sql/notification-batch-performance-seed.sql;
```

기본 데이터 규모:

```text
user_count = 10,000
medication_per_user = 5
expected medication rows = 50,000
expected outbox rows = 50,000
```

대규모 측정 시 권장 단계:

| 단계 | user_count | medication_per_user | 예상 medication/outbox |
|---:|---:|---:|---:|
| 1 | 1,000 | 5 | 5,000 |
| 2 | 10,000 | 5 | 50,000 |
| 3 | 20,000 | 5 | 100,000 |
| 4 | 100,000 | 5 | 500,000 |

## 4. EXPLAIN 검증 절차

테스트 데이터 생성 후 실행한다.

```sql
SOURCE docs/sql/notification-batch-explain.sql;
```

주요 검증 쿼리:

```sql
SELECT ...
FROM medication_notification_send
WHERE status IN ('PENDING', 'RETRY')
  AND attempt_count < :maxAttempts
  AND scheduled_at <= :now
  AND (next_retry_at IS NULL OR next_retry_at <= :now)
ORDER BY scheduled_at ASC, notification_send_id ASC
LIMIT :limit;
```

현재 관련 인덱스:

| 인덱스 | 컬럼 | 목적 |
|---|---|---|
| `uk_medication_notification_dose` | `user_id, medication_id, scheduled_at, dose_sequence` | Outbox 멱등 생성 |
| `idx_med_notification_due_status` | `status, next_retry_at, scheduled_at, notification_send_id` | PENDING/RETRY 후보 조회 |
| `idx_med_notification_schedule_status` | `scheduled_at, status, attempt_count, notification_send_id` | 시간 슬롯/스케줄 기준 조회 |
| `idx_med_notification_claim_lease` | `status, claim_expires_at, notification_send_id` | stale PROCESSING 복구 |

기대 기준:

| 쿼리 | 기대 |
|---|---|
| 후보 조회 | Full Table Scan 회피 |
| 후보 조회 | `idx_med_notification_due_status` 또는 `idx_med_notification_schedule_status` 사용 |
| 후보 조회 | LIMIT 전 과도한 filesort 최소화 |
| 단건 선점 | PRIMARY KEY 기반 단건 접근 |
| 결과 저장 | PRIMARY KEY 기반 단건 접근 |
| stale 복구 | `idx_med_notification_claim_lease` 사용 |

## 5. 측정 항목

### 5.1 prepareNotificationStep

| 데이터 규모 | 실행시간 | 처리량 | 후보 복약 | 예정 회차 | 신규 Outbox | 중복 Outbox | 최대 메모리 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 5,000 | TBD | TBD | TBD | TBD | TBD | TBD | TBD |
| 50,000 | TBD | TBD | TBD | TBD | TBD | TBD | TBD |
| 100,000 | TBD | TBD | TBD | TBD | TBD | TBD | TBD |
| 500,000 | TBD | TBD | TBD | TBD | TBD | TBD | TBD |

### 5.2 sendNotificationStep

| Outbox 대상 | batch-size | 실행시간 | 처리량 | SENT | RETRY | FAILED | SKIPPED | claim conflict | stale result |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 5,000 | 100 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD |
| 50,000 | 100 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD |
| 100,000 | 100 | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD |

현재 `sendNotificationStep`은 1회 실행 시 `medeat.notification.batch.send.batch-size`만큼 후보를 조회한다. 대량 발송 전체 처리량을 측정하려면 RetryJob 또는 반복 실행으로 전체 Outbox가 소진될 때까지 수행 시간을 기록한다.

### 5.3 stale PROCESSING 복구

| PROCESSING 대상 | 만료 대상 | 복구 시간 | RETRY 전환 | FAILED 전환 |
|---:|---:|---:|---:|---:|
| TBD | TBD | TBD | TBD | TBD |

## 6. EXPLAIN 기록 표

| 쿼리 | 데이터 규모 | 사용 인덱스 | rows examined | 실행시간 | Extra / 비고 |
|---|---:|---|---:|---:|---|
| 후보 조회 | TBD | TBD | TBD | TBD | TBD |
| 단건 선점 | TBD | PRIMARY | TBD | TBD | TBD |
| SENT 저장 | TBD | PRIMARY | TBD | TBD | TBD |
| stale 복구 | TBD | TBD | TBD | TBD | TBD |

## 7. 현재 결론

이번 단계에서 실제 DB가 없어 수치 측정은 완료하지 못했다. 다만 성능 측정에 필요한 실행 SQL, EXPLAIN SQL, 기록 문서, 아키텍처 문서를 추가했다.

현재 코드 기준으로 성능 검증 대상은 다음 네 가지다.

```text
1. prepareNotificationStep의 Outbox 생성량과 중복 생성 0건 검증
2. sendNotificationStep의 PENDING/RETRY 후보 조회 인덱스 사용 여부
3. PROCESSING 선점/결과 저장의 PRIMARY KEY 단건 갱신 확인
4. stale PROCESSING 복구의 idx_med_notification_claim_lease 사용 여부
```

실측 후 포트폴리오에 넣을 문장은 다음 형식이 좋다.

```text
10만 건 Outbox 기준 PENDING/RETRY 후보 조회 쿼리를 EXPLAIN ANALYZE로 확인해 Full Table Scan을 제거했습니다.
status, next_retry_at, scheduled_at 조건을 반영한 인덱스를 사용하도록 조정했고,
조회 시간은 A ms에서 B ms로, 스캔 행 수는 C건에서 D건으로 감소했습니다.
```
