# 복약 알림 배치 고도화 경험 정리

## 1. 목적

기존 복약 알림은 `@Scheduled`가 직접 알림 대상을 조회하고 WebPush를 발송하는 구조였다. 기능 구현 관점에서는 단순하지만, 대량 데이터 처리, 중복 방지, 장애 복구, 재처리, 성능 측정 관점에서는 운영형 시스템으로 설명하기 어렵다.

따라서 복약 알림을 **Spring Batch + Outbox + Claim Token/Lease 기반 운영형 알림 파이프라인**으로 고도화한다.

핵심 목표는 다음과 같다.

- 대량 복약 알림 대상을 Chunk 단위로 안정적으로 생성
- 동일 복약 회차의 Outbox 중복 생성 방지
- WebPush 발송과 DB 트랜잭션 분리
- Claim Token/Lease로 발송 대상 선점과 오래된 처리 복구
- `attempt_count`, `next_retry_at` 기반 재시도 제어
- Payload 스냅샷 저장으로 prepare/send 사이 데이터 변경 영향 최소화
- 실패 Job의 기술적 Restart와 완료 후 비즈니스 재처리 구분
- EXPLAIN, Chunk Size, 처리량 등 수치 기반 성능 개선 근거 확보
- 복약 예정 회차와 복약 완료 로그를 분리해 계획 대비 실적 리포트로 확장

## 2. 기존 구현

### 2.1 실행 구조

현재 복약 알림은 `MedicationAlarmScheduler`에서 1분마다 실행된다.

```java
@Scheduled(cron = "0 * * * * *")
public void runMedicationAlarmCheck() {
    String nowTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
    List<MedicationDto> medications = medicationService.getMedicationToAlert(nowTime);
    ...
    webPushService.sendMedicationNotification(...);
}
```

기존 흐름은 다음과 같다.

```text
@Scheduled cron = "0 * * * * *"
  -> LocalTime.now()를 HH:mm 문자열로 변환
  -> nowTime과 일치하는 복약 데이터 조회
  -> for-loop로 WebPush 즉시 발송
  -> 실패 시 warn 로그 기록
```

### 2.2 대상 조회 방식

복약 대상 조회는 `MedicationQueryRepository.findMedicationToAlert(nowTime)`에서 수행된다.

```java
.where(Expressions.booleanTemplate(
    "find_in_set({0}, replace({1}, ' ', '')) > 0",
    nowTime,
    medication.intakeTime
))
```

현재 `intake_time`은 문자열이며, 복수 시간이 들어갈 수 있는 구조다. 따라서 `find_in_set`으로 현재 시간 문자열이 포함되어 있는지 검사한다.

### 2.3 발송 방식

`WebPushServiceImpl.sendMedicationNotification`은 사용자별 Push Subscription을 조회한 뒤 각 구독에 대해 WebPush를 발송한다.

```text
push_enabled 확인
  -> 사용자 subscription 목록 조회
  -> payload 생성
  -> subscription별 pushService.send(notification)
```

발송 중 예외가 발생하면 서비스 내부에서 로그를 남기고 종료한다.

```java
catch (Exception e) {
    log.warn("Failed to send web push notification. userId={}, medicationId={}", userId, medicationId, e);
}
```

### 2.4 현재 기록 가능한 기준값

| 항목 | 현재 값 |
|---|---:|
| 복약 알림 스케줄 주기 | 1분마다 실행 |
| 스케줄러 cron | `0 * * * * *` |
| 스케줄러 ThreadPool 크기 | 2 |
| 기준시각 표현 | `HH:mm` 문자열 |
| 대상 조회 방식 | `find_in_set(nowTime, intake_time)` |
| 대상 조회 제한 | 없음 |
| 발송 방식 | for-loop 순차 발송 |
| 발송 상태 저장 | 없음 |
| 발송 실패 영속화 | 없음 |
| 중복 알림 방지용 Outbox | 없음 |
| WebPush 예외 전파 | 내부 로그 후 종료 |
| 복약 완료 중복 방지 | `medication_id + taken_date + taken_index` 유니크 제약 |

## 3. 기존 구조의 문제 가능성

### 3.1 발송 상태가 영속화되지 않음

현재 구조에서는 WebPush 발송 결과가 별도 테이블에 저장되지 않는다. 따라서 다음을 알기 어렵다.

- 어떤 사용자에게 알림을 발송했는지
- 어떤 복약 회차가 발송 실패했는지
- 실패한 알림을 다시 처리해야 하는지
- 같은 알림이 이미 발송 대상이었는지

### 3.2 WebPush는 DB 트랜잭션으로 롤백할 수 없음

WebPush는 외부 시스템 호출이다. DB 트랜잭션처럼 롤백할 수 없다.

예상 장애 구간:

```text
1. WebPush 발송 성공
2. 애플리케이션 강제 종료
3. 발송 성공 상태 저장 불가
4. 재처리 시 동일 알림 재발송 가능
```

따라서 "정확히 한 번 발송"을 완벽히 보장하기보다, 중복 가능 구간을 줄이고 상태 기반 복구가 가능하도록 설계해야 한다.

### 3.3 동일 복약 회차의 알림 중복 방지 근거가 약함

복약 완료 로그에는 유니크 제약이 있다.

```text
UNIQUE(medication_id, taken_date, taken_index)
```

하지만 알림 발송 자체에는 다음과 같은 유니크 기준이 없다.

```text
user_id + medication_id + scheduled_at + dose_sequence
```

따라서 알림 발송 대상 생성과 발송의 멱등성을 별도로 증명하기 어렵다.

### 3.4 `LocalTime.now()` 기반 단일 시각 조회는 누락 가능성이 있음

현재는 실행 시점의 `HH:mm`과 정확히 일치하는 데이터를 조회한다. 스케줄러가 지연되면 예정 시각의 알림을 놓칠 수 있다.

```text
예정 실행 시각: 08:00
실제 실행 시각: 08:03
조회 기준: 08:03
결과: 08:00 알림 누락 가능
```

운영형 배치에서는 단일 시각보다 업무 시간 구간을 기준으로 처리하는 것이 안전하다.

### 3.5 대량 처리와 재시작 기준이 없음

현재는 조회된 전체 목록을 메모리에 올린 뒤 순차 반복한다.

```text
List<MedicationDto> medications = medicationService.getMedicationToAlert(nowTime);
for (MedicationDto medication : medications) { ... }
```

대량 데이터가 들어오면 다음을 확인하기 어렵다.

- 총 처리 건수
- Chunk 단위 처리 시간
- 실패 지점
- 재시작 지점
- Skip/Retry 건수
- 처리량

## 4. 최종 개선 방향

### 4.1 전체 구조

```text
@Scheduled
  -> MedicationNotificationJob
      -> prepareNotificationStep
      -> sendNotificationStep
      -> summarizeNotificationStep

MedicationNotificationRetryJob
  -> staleProcessingRecoverStep
  -> retryPendingNotificationStep
```

`@Scheduled`는 실행 시점만 담당하고, Spring Batch는 대량 대상 생성, 상태 관리, 재처리, 성능 측정을 담당한다.

### 4.2 Job 구성

#### MedicationNotificationJob

```text
prepareNotificationStep [Chunk 기반]
  - [slotStart, slotEnd) 업무 시간 슬롯의 복약 대상 조회
  - doseSequence, scheduledAt 계산
  - 필수값 검증
  - 발송 Payload 스냅샷 생성
  - PENDING Outbox 멱등 저장
  - 동일 복약 회차 중복 생성 방지

sendNotificationStep [외부 발송 분리]
  - PENDING/RETRY 조회
  - Claim Token/Lease 기반 조건부 UPDATE로 PROCESSING 선점
  - 트랜잭션 밖에서 WebPush 발송
  - claim_token 조건으로 SENT/RETRY/FAILED/SKIPPED 상태 저장

summarizeNotificationStep
  - 상태별 처리 건수
  - 실패/재시도/스킵 건수
  - 소요시간 집계
```

#### MedicationNotificationRetryJob

```text
staleProcessingRecoverStep
  - claim_expires_at이 지난 PROCESSING 건을 RETRY 또는 FAILED로 복구

retryPendingNotificationStep
  - PENDING/RETRY 상태
  - attempt_count < maxAttempts
  - next_retry_at <= now()
  - scheduled_at <= now()
  - 조건을 만족하는 알림만 재발송
```

## 5. Outbox 상태 모델

`medication_notification_send.status`는 다음 상태를 가진다.

```text
PENDING
PROCESSING
SENT
RETRY
FAILED
SKIPPED
```

상태 흐름:

```text
PENDING -> PROCESSING -> SENT
                      -> RETRY
                      -> FAILED
                      -> SKIPPED

RETRY -> PROCESSING -> SENT / RETRY / FAILED
```

`PROCESSING`은 1차 MVP부터 포함한다. 정기 실행과 수동 재처리가 겹쳐도 조건부 UPDATE로 하나의 실행만 발송을 선점할 수 있기 때문이다.

## 6. Claim Token, Lease, nextRetryAt

### 6.1 필요한 이유

단순히 `PROCESSING` 상태만 두면 오래된 처리와 새 처리의 소유권을 구분하기 어렵다. 따라서 다음 컬럼을 둔다.

```text
claim_token
claimed_at
claimed_by
claim_expires_at
next_retry_at
```

상태 저장 시에도 `claim_token`을 조건에 포함해야 한다. 그래야 이전 실행이 늦게 돌아와서 새 실행이 선점한 데이터를 덮어쓰는 문제를 줄일 수 있다.

### 6.2 선점 SQL

`retry_count` 대신 `attempt_count`를 사용한다. 최초 발송도 하나의 시도이므로 더 명확하다.

```sql
UPDATE medication_notification_send
SET status = 'PROCESSING',
    attempt_count = attempt_count + 1,
    claim_token = :claimToken,
    claimed_at = NOW(6),
    claimed_by = :workerId,
    claim_expires_at = DATE_ADD(NOW(6), INTERVAL 10 MINUTE),
    last_attempted_at = NOW(6)
WHERE notification_send_id = :id
  AND status IN ('PENDING', 'RETRY')
  AND attempt_count < 3
  AND scheduled_at <= NOW(6)
  AND (next_retry_at IS NULL OR next_retry_at <= NOW(6));
```

`attempt_count`는 발송 이후가 아니라 PROCESSING 선점 성공 시 원자적으로 증가시킨다. 서버가 발송 과정에서 종료되더라도 실제 처리 시도가 있었다는 사실을 남기기 위해서다.

### 6.3 결과 저장 SQL

```sql
UPDATE medication_notification_send
SET status = 'SENT',
    sent_at = NOW(6),
    claim_token = NULL,
    claimed_at = NULL,
    claimed_by = NULL,
    claim_expires_at = NULL,
    failure_code = NULL,
    failure_reason = NULL
WHERE notification_send_id = :id
  AND status = 'PROCESSING'
  AND claim_token = :claimToken;
```

영향받은 행이 0건이면 이미 Lease가 만료되어 다른 실행이 선점했거나 상태가 바뀐 것으로 본다.

### 6.4 nextRetryAt 정책

실패 직후 바로 재시도하지 않고 다음 재시도 가능 시각을 둔다.

```text
1번째 실패 -> attempt_count = 1 -> RETRY, next_retry_at = now + 1분
2번째 실패 -> attempt_count = 2 -> RETRY, next_retry_at = now + 5분
3번째 실패 -> attempt_count = 3 -> FAILED
```

정책:

```text
attempt_count < maxAttempts -> RETRY + next_retry_at 설정
attempt_count >= maxAttempts -> FAILED
```

## 7. 트랜잭션 경계

sendNotificationStep에서 WebPush를 Step 기본 트랜잭션에 넣지 않는다.

Spring Batch의 Tasklet은 기본적으로 `execute()` 호출이 Step 트랜잭션으로 감싸질 수 있다. Tasklet 내부에 코드를 순서대로 작성하기만 하면 WebPush 호출 중에도 바깥 트랜잭션이 유지될 수 있다.

최종 구현 구조:

```text
SendNotificationTasklet
  -> NonTransactionalNotificationSender
      @Transactional(propagation = NOT_SUPPORTED)
      -> claimService.claim()
           @Transactional(propagation = REQUIRES_NEW)
      -> webPushService.send()
           트랜잭션 없음
      -> resultService.markResult()
           @Transactional(propagation = REQUIRES_NEW)
```

또는 프로젝트의 Spring Batch 버전에서 지원되는 구성을 확인해 send Step 바깥쪽에 DB 트랜잭션을 사용하지 않는 TransactionManager를 적용한다.

검증 로그에서 다음 순서가 확인되어야 한다.

```text
PROCESSING 커밋 완료
-> WebPush 요청 시작
-> WebPush 요청 종료
-> SENT 또는 RETRY 별도 커밋
```

WebPush 실패는 가능하면 Job 전체 실패가 아니라 비즈니스 상태로 변환한다.

| 오류 | 처리 |
|---|---|
| 네트워크 타임아웃 | RETRY |
| WebPush 5xx | RETRY |
| 일시적 연결 장애 | RETRY |
| 잘못된 토큰/만료 토큰 | FAILED |
| 필수 데이터 누락 | SKIPPED |
| DB 연결 장애 | Step 실패 |
| 예상하지 못한 코드 오류 | Job 실패 |

## 8. 시간 슬롯 정책

시간 슬롯은 반열린 구간으로 확정한다.

```text
slotStart <= scheduledAt < slotEnd
```

예:

```text
[08:00:00, 08:05:00)
```

이렇게 해야 `08:05:00` 데이터가 이전 슬롯과 다음 슬롯에 중복 포함되지 않는다.

prepare Step은 슬롯에 포함된 회차를 미리 Outbox로 생성할 수 있다. 단, send Step은 실제 발송 시점을 지켜야 한다.

```text
prepare Step:
  [slotStart, slotEnd)에 포함된 회차를 Outbox로 생성 가능

send Step:
  scheduled_at <= NOW(6)인 데이터만 PROCESSING 선점 및 발송 가능
```

예:

```text
현재 시각: 08:03
scheduled_at: 08:04

Outbox 생성: 가능
발송 선점: 불가
```

스케줄러 실행 주기와 슬롯 크기는 일치시키는 것이 가장 단순하다.

```text
5분마다 실행
08:05 실행 -> [08:00, 08:05)
08:10 실행 -> [08:05, 08:10)
```

서버 장애로 슬롯 자체가 누락되는 문제는 1차 MVP에서 제한사항으로 문서화하고, 이후 마지막 성공 슬롯을 기록하는 Watermark 방식으로 확장한다.

## 9. Payload 스냅샷

prepare와 send 사이에 약 이름이나 복약 일정이 변경되면 send Step이 원본 데이터를 다시 조회할 때 다른 메시지가 만들어질 수 있다. 따라서 prepare Step에서 발송 메시지를 확정하고 Outbox에 저장한다.

권장 컬럼:

```text
notification_title
notification_body
payload_json
```

정책:

```text
prepare Step
  - 대상과 복약 회차 계산
  - 발송 메시지 생성
  - Payload 스냅샷과 함께 PENDING 저장

send Step
  - medication을 다시 조회해 메시지를 재생성하지 않음
  - Outbox에 저장된 Payload를 발송
  - user_id 기준으로 현재 활성 Subscription 목록을 조회해 발송
```

알림 메시지는 prepare 시점에 Payload 스냅샷으로 고정하고, 실제 전달 대상은 send 시점에 사용자의 활성 Subscription 목록을 조회해 결정한다.

1차 MVP에서는 하나 이상의 기기에 알림이 도달하면 사용자 단위 성공으로 본다. Subscription별 성공/실패 추적과 실패 기기만 재발송하는 기능은 `medication_notification_delivery` 하위 테이블을 추가하는 2차 확장으로 분리한다.

## 10. Stale PROCESSING 복구

단순히 상태만 RETRY로 바꾸면 이전 Claim 정보가 남아 운영 분석이 혼란스러울 수 있다. Lease 만료 복구 시 Claim 정보를 모두 정리한다.

```sql
UPDATE medication_notification_send
SET status = CASE
        WHEN attempt_count >= 3 THEN 'FAILED'
        ELSE 'RETRY'
    END,
    next_retry_at = CASE
        WHEN attempt_count >= 3 THEN NULL
        ELSE DATE_ADD(NOW(6), INTERVAL 1 MINUTE)
    END,
    failure_code = 'LEASE_EXPIRED',
    failure_reason = 'Processing lease expired before completion',
    claim_token = NULL,
    claimed_at = NULL,
    claimed_by = NULL,
    claim_expires_at = NULL
WHERE status = 'PROCESSING'
  AND claim_expires_at < NOW(6);
```

다음 관계가 성립해야 한다.

```text
WebPush 전체 Timeout < Claim Lease 시간
```

예를 들어 WebPush 연결/응답 전체 Timeout이 최대 15초라면 Lease는 1~10분으로 충분하다. 외부 요청이 Lease보다 오래 걸릴 수 있으면 Worker A가 발송 중인 데이터를 Worker B가 재선점할 수 있다.

Claim Token은 늦게 돌아온 Worker의 DB 상태 덮어쓰기는 막아 주지만, 이미 시작된 외부 WebPush 호출 자체를 취소하거나 중복 전송을 완전히 방지하지는 못한다.

## 11. 최종 테이블 설계

### 11.1 Outbox 테이블

```sql
CREATE TABLE medication_notification_send (
    notification_send_id BIGINT AUTO_INCREMENT PRIMARY KEY,

    user_id BIGINT NOT NULL,
    medication_id BIGINT NOT NULL,
    scheduled_at DATETIME(6) NOT NULL,
    dose_sequence INT NOT NULL,

    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NULL,

    claim_token VARCHAR(36) NULL,
    claimed_at DATETIME(6) NULL,
    claimed_by VARCHAR(100) NULL,
    claim_expires_at DATETIME(6) NULL,

    notification_title VARCHAR(200) NOT NULL,
    notification_body VARCHAR(1000) NOT NULL,
    payload_json JSON NULL,

    last_attempted_at DATETIME(6) NULL,
    sent_at DATETIME(6) NULL,

    failure_code VARCHAR(50) NULL,
    failure_reason VARCHAR(500) NULL,

    origin_job_execution_id BIGINT NULL,

    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uk_medication_notification_dose
        UNIQUE (
            user_id,
            medication_id,
            scheduled_at,
            dose_sequence
        )
);
```

`origin_job_execution_id`는 필수는 아니지만 다음 지표를 정확히 구분할 때 유용하다.

- candidateCount
- createdCount
- duplicateCount
- skippedCount

Spring Batch의 `writeCount`만으로는 Writer에 전달된 항목 수와 실제 신규 Insert 건수를 항상 동일하게 볼 수 없기 때문에, 실행별 신규 생성량을 별도로 측정한다.

### 11.2 시도 이력 테이블

2차 확장에서 발송 시도 이력을 남기고 싶을 때 추가한다.

```text
medication_notification_attempt
```

주요 컬럼:

| 컬럼 | 설명 |
|---|---|
| `attempt_id` | 발송 시도 ID |
| `notification_send_id` | Outbox ID |
| `attempt_number` | 시도 번호 |
| `claim_token` | 시도 소유권 |
| `result` | 성공/실패 결과 |
| `failure_code` | 실패 코드 |
| `failure_reason` | 실패 사유 |
| `attempted_at` | 시도 시각 |

## 12. INSERT 방식

`INSERT IGNORE`는 사용하지 않는다. 중복 키 외의 데이터 오류까지 숨길 수 있기 때문이다.

추천 방식:

```sql
INSERT INTO medication_notification_send (...)
VALUES (...)
ON DUPLICATE KEY UPDATE
    notification_send_id = notification_send_id;
```

또는 애플리케이션에서 `DuplicateKeyException`만 별도로 처리한다.

정책:

| 상황 | 처리 |
|---|---|
| 중복 키 | 정상 멱등 처리 |
| NOT NULL 위반 | 데이터 오류 |
| 외래키 오류 | 정합성 오류 |

## 13. JobParameter와 재실행 정책

기술적 Restart와 비즈니스 재처리를 구분한다.

기술적 Restart:

```text
slotStart = 2026-07-11T08:00:00
slotEnd   = 2026-07-11T08:05:00
zoneId    = Asia/Seoul
```

Job이 중간에 실패했다면 동일 JobParameter로 재실행해 실패 Step부터 재시작한다.

주의:

```text
성공 완료된 JobInstance는 같은 파라미터로 다시 실행할 수 없다.
같은 파라미터로 완료 Job을 실행하면 JobInstanceAlreadyCompleteException이 발생한다.
```

정상 완료 후 비즈니스 재처리는 별도 `MedicationNotificationRetryJob`으로 처리한다.

## 14. Spring Batch 메타 테이블 관리

현재 프로젝트는 Flyway를 사용하므로 Spring Batch 메타 테이블도 Flyway로 관리한다.

설정:

```properties
spring.batch.jdbc.initialize-schema=never
spring.batch.job.enabled=false
```

프로젝트에 실제 설치된 Spring Batch 버전의 공식 MySQL DDL을 Flyway Migration으로 추가한다.

```text
Vxx__create_spring_batch_metadata.sql
Vxx+1__create_medication_notification_send.sql
```

스키마 자동 생성과 Flyway를 섞지 않는다.

## 15. 인덱스와 SQL 튜닝 방향

인덱스는 예상이 아니라 실제 쿼리와 `EXPLAIN` 기준으로 결정한다.

Outbox 발송 조회 후보:

```sql
CREATE INDEX idx_notification_due_status
ON medication_notification_send (
    status,
    next_retry_at,
    scheduled_at,
    notification_send_id
);
```

시간 슬롯 조회 중심 후보:

```sql
CREATE INDEX idx_notification_schedule_status
ON medication_notification_send (
    scheduled_at,
    status,
    attempt_count,
    notification_send_id
);
```

단, `status`는 값 종류가 적어서 선두 컬럼으로 두는 것이 항상 유리하지 않다. 최종 컬럼 순서는 실제 데이터 분포와 실행계획을 보고 결정한다.

기존 대상 조회 쿼리는 `find_in_set` 기반이라 인덱스 활용이 어려울 수 있다. 장기적으로는 복약 예정 시각을 정규화한 `medication_dose_occurrence` 또는 schedule 기준 조회로 전환하는 것이 좋다.

## 16. 성능 측정 계획

성능은 prepare와 send를 분리해서 측정한다. WebPush 네트워크 시간이 섞이면 DB 배치 성능을 정확히 비교하기 어렵기 때문이다.

### 16.1 현재 기록 가능한 기준값

| 항목 | 현재 기록값 |
|---|---:|
| 스케줄 실행 주기 | 1분 |
| 스케줄러 ThreadPool 크기 | 2 |
| 기존 대상 조회 방식 | 전체 fetch 후 순차 처리 |
| 기존 발송 방식 | subscription별 순차 `pushService.send` |
| 기존 발송 이력 저장 | 없음 |
| 기존 실패 이력 저장 | 없음 |
| 계획한 업무 슬롯 | 5분 |
| 계획한 maxAttempts | 3회 |
| 계획한 Claim Lease | 10분 |
| 계획한 PROCESSING 복구 기준 | `claim_expires_at < now()` |
| 비교할 Chunk Size | 100 / 500 / 1000 / 5000 |

### 16.2 prepareNotificationStep 측정 항목

| 측정 항목 | 기록 방식 |
|---|---|
| 알림 대상 조회시간 | Step/SQL 실행시간 |
| Outbox 생성시간 | Step 실행시간 |
| candidateCount | 후보 복약 회차 수 |
| createdCount | 신규 Outbox 생성 수 |
| duplicateCount | 유니크 키로 중복 처리된 수 |
| skippedCount | 필수값 누락 등으로 제외된 수 |
| Chunk Size별 처리량 | 건수 / 초 |
| Insert 쿼리 수 | SQL 로그 또는 datasource proxy |
| 최대 메모리 | JVM/Actuator/Micrometer |
| 인덱스 적용 전후 스캔 행 수 | EXPLAIN |
| 동일 업무 슬롯 재실행 시 신규 생성 건수 | 0건 목표 |

### 16.3 sendNotificationStep 측정 항목

| 측정 항목 | 기록 방식 |
|---|---|
| 초당 발송 요청 수 | 발송 건수 / 초 |
| 평균 WebPush 응답시간 | Timer 평균 |
| P95 WebPush 응답시간 | Timer percentile |
| SENT/RETRY/FAILED/SKIPPED 건수 | 상태별 count |
| next_retry_at 도달 후 재처리 시간 | Retry Job 실행시간 |
| PROCESSING 선점 충돌 건수 | 조건부 UPDATE 0건 count |
| Lease 만료 복구 건수 | stale recover count |
| 상태 저장 시 claim_token 불일치 건수 | 결과 UPDATE 0건 count |

### 16.4 성능 측정 표 양식

#### 데이터 규모별 prepare Step

| 데이터 규모 | Chunk Size | 실행시간 | 처리량 | 최대 메모리 | 신규 생성 | 중복 생성 |
|---:|---:|---:|---:|---:|---:|---:|
| 10,000 | 100 | TBD | TBD | TBD | TBD | 0 |
| 10,000 | 500 | TBD | TBD | TBD | TBD | 0 |
| 100,000 | 500 | TBD | TBD | TBD | TBD | 0 |
| 100,000 | 1000 | TBD | TBD | TBD | TBD | 0 |
| 500,000 | 1000 | TBD | TBD | TBD | TBD | 0 |

#### 인덱스 적용 전후

| SQL | 인덱스 | 실행시간 | 스캔 행 수 | 실행계획 | 비고 |
|---|---|---:|---:|---|---|
| PENDING/RETRY 조회 | 적용 전 | TBD | TBD | TBD | 기준 |
| PENDING/RETRY 조회 | 적용 후 | TBD | TBD | TBD | 개선 |
| slot 대상 조회 | 적용 전 | TBD | TBD | TBD | 기준 |
| slot 대상 조회 | 적용 후 | TBD | TBD | TBD | 개선 |

#### send Step

| 발송 방식 | 대상 건수 | 성공 | RETRY | FAILED | SKIPPED | 평균 응답 | P95 | 총 시간 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Fake WebPush | 10,000 | TBD | TBD | TBD | TBD | TBD | TBD | TBD |
| Fake WebPush | 100,000 | TBD | TBD | TBD | TBD | TBD | TBD | TBD |
| 실제 WebPush | TBD | TBD | TBD | TBD | TBD | TBD | TBD | TBD |

## 17. 테스트 기준

반드시 검증할 항목은 다음과 같다.

| 테스트 | 기대 결과 |
|---|---|
| prepare Step 중간 실패 후 동일 파라미터 Restart | 실패 Step부터 복구 |
| 성공 완료 Job을 같은 파라미터로 재실행 | `JobInstanceAlreadyCompleteException` 확인 |
| 동일 업무 슬롯의 새로운 JobInstance 실행 | Outbox 신규 생성 0건 |
| 동일 PENDING 데이터 동시 선점 | 하나의 claim_token만 PROCESSING 전환 |
| 현재 시각 08:03, scheduled_at 08:04 | Outbox 생성 가능, 발송 선점 불가 |
| PENDING 생성 후 약 이름 변경 | 생성 당시 Payload로 발송 |
| WebPush 성공 | claim_token 조건으로 SENT 저장 |
| WebPush Timeout | RETRY, next_retry_at 설정 |
| WebPush Timeout 발생 | Lease 만료 전 RETRY 상태 저장 |
| 장시간 Hang | Lease 만료 후 Retry Job 복구 |
| attempt_count = 2인 알림 선점 후 실패 | attempt_count = 3, FAILED |
| attempt_count = 3 | 추가 선점 불가 |
| 잘못된 구독 정보 | 즉시 FAILED |
| 필수 데이터 누락 | SKIPPED |
| 이전 claim_token을 가진 실행이 늦게 상태 저장 시도 | 0건 업데이트 |
| 발송 성공 직후 상태 저장 실패 | 제한사항과 복구 정책 문서화 |
| 10만 건 인덱스 적용 전후 | 조회시간, 스캔 행 수 비교 |
| Chunk Size별 실행 | 처리량과 메모리 비교 |

Batch Job/Step의 종단 테스트에는 프로젝트 버전에 맞는 `spring-batch-test` 유틸리티를 사용할 수 있다.

## 18. 리포트 확장 방향

리포트의 `planned_count`는 Outbox 기준으로 잡지 않는다.

이유:

```text
Outbox = 알림 발송 대상
복약 예정 회차 = 실제 업무 계획
Medication Log = 실제 복약 실적
```

알림을 꺼둔 사용자나 발송 정책 변경이 있어도 복약 계획은 존재할 수 있다. 따라서 리포트는 알림 발송 데이터가 아니라 복약 예정 회차 기준으로 계산해야 한다.

최종 구조:

```text
Dose Occurrence = 계획
Notification Outbox = 전달 수단
Medication Log = 실적
Daily Report = 계획 대비 실적
```

2차 확장에서 별도 테이블을 둔다.

```text
medication_dose_occurrence
```

주요 컬럼:

| 컬럼 | 설명 |
|---|---|
| `occurrence_id` | 복약 예정 회차 ID |
| `user_id` | 사용자 ID |
| `medication_id` | 복약 ID |
| `scheduled_at` | 예정 복약 시각 |
| `dose_sequence` | 복약 계획 내 회차 |
| `report_date` | 리포트 귀속일 |
| `plan_status` | 예정/취소/변경 등 |
| `created_at` | 생성 시각 |
| `updated_at` | 수정 시각 |

Outbox는 이 회차를 참조한다.

```text
medication_notification_send.occurrence_id
```

리포트 계산:

```text
planned_count = 복약 예정 회차 수
taken_count = 복약 완료 로그 수
missed_count = planned_count - taken_count
adherence_rate = taken_count / planned_count
notification_count = 알림 발송 수
```

생산계획 직무와의 연결:

```text
복약 예정 회차 = 생산계획
복약 완료 로그 = 생산실적
복약 이행률 = 계획 대비 실적률
```

## 19. 단계별 구현 순서

### 19.1 1차 완료 기준

```text
1. 기존 medication / medication_log / WebPush 구조 확인
2. docs/domain-policy.md 작성
3. spring-boot-starter-batch 추가
4. Batch 메타 테이블 Flyway 추가
5. medication_notification_send Flyway 추가
6. Batch 설정 추가
7. DoseScheduleCalculator 구현
8. prepareNotificationStep 구현
9. Outbox 중복 생성 방지 테스트
10. sendNotificationStep 구현
11. Tasklet 외부 트랜잭션 경계 검증
12. Claim Token/Lease 기반 PROCESSING 선점 구현
13. claim_token 조건 상태 저장 테스트
14. next_retry_at 기반 재시도 구현
15. MedicationNotificationRetryJob 구현
16. stale PROCESSING 복구 테스트
17. 장애/재시작 테스트
18. 인덱스 적용 및 EXPLAIN 비교
19. Chunk 성능 측정
20. docs/batch-architecture.md 작성
21. docs/batch-performance.md 작성
```

### 19.2 현재 진행 기록

현재까지 완료한 작업은 다음과 같다.

```text
1. docs/domain-policy.md 작성
2. spring-boot-starter-batch 추가
3. Batch 자동 실행 비활성화 설정 추가
4. Batch 메타 테이블 Flyway 추가
5. medication_notification_send Outbox 테이블 Flyway 추가
6. DoseScheduleCalculator 구현
7. DoseScheduleCalculator 단위 테스트 추가
8. MedicationNotificationPayloadFactory 구현
9. MedicationNotificationOutboxFactory 구현
10. MedicationNotificationSendDao 구현
11. PayloadFactory/Outbox DAO 단위 테스트 추가
12. PrepareNotificationService 구현
13. PrepareNotificationService 단위 테스트 추가
14. MedicationNotificationJobParameters 구현
15. PrepareNotificationTasklet 구현
16. MedicationNotificationJob / prepareNotificationStep 설정 추가
17. Batch JobParameter / Tasklet 단위 테스트 추가
18. MedicationNotificationSlotCalculator 구현
19. MedicationNotificationBatchScheduler 구현
20. Batch 스케줄러 속성 추가
21. 슬롯 계산 / Batch 스케줄러 단위 테스트 추가
22. MedicationNotificationSendCandidate 구현
23. PENDING/RETRY Outbox 발송 후보 조회 DAO 구현
24. Claim Token/Lease 기반 PROCESSING 조건부 선점 DAO 구현
25. Outbox 후보 조회 / 선점 DAO 단위 테스트 추가
26. claim_token 조건 기반 SENT/RETRY/FAILED/SKIPPED 결과 저장 DAO 구현
27. Outbox 결과 저장 DAO 단위 테스트 추가
28. WebPushDispatchResult / WebPushFailure 구현
29. Batch 전용 MedicationNotificationDispatchService 구현
30. MedicationNotificationSender 구현
31. SendNotificationTasklet 구현
32. MedicationNotificationJob에 sendNotificationStep 연결
33. stale PROCESSING 복구 DAO / Tasklet 구현
34. MedicationNotificationRetryJob 구현
35. 장애/재시도/Lease 만료 단위 테스트 추가
```

구현된 `DoseScheduleCalculator`의 역할:

```text
MedicationDto
  + slotStart
  + slotEnd
  -> 슬롯에 포함되는 ScheduledDose 목록 계산
```

반영한 정책:

```text
intake_time에 유효 시간이 2개 이상이면 LEGACY_EXPLICIT_TIMES 사용
유효 시간이 1개이고 interval_hour, daily_count가 유효하면 INTERVAL_CALCULATED 사용
slotStart <= scheduled_at < slotEnd 반열린 구간 적용
slotStart는 포함하고 slotEnd는 제외
자정을 넘는 interval 회차는 이전 기준일의 계획에서 계산
잘못된 일정 정책은 빈 결과로 처리
```

검증한 테스트:

| 테스트 | 검증 내용 |
|---|---|
| `usesLegacyExplicitTimesWhenMultipleValidTimesExist` | 명시적 시간 목록이 interval 계산보다 우선되는지 확인 |
| `calculatesIntervalDosesWhenSingleStartTimeExists` | 단일 시작 시각 + 간격 + 횟수 기반 회차 계산 확인 |
| `includesSlotStartAndExcludesSlotEnd` | `[slotStart, slotEnd)` 반열린 구간 확인 |
| `calculatesCrossMidnightIntervalDoseFromPreviousPlanDate` | 자정을 넘는 3회차 계산 확인 |
| `returnsEmptyWhenSchedulePolicyIsInvalid` | 잘못된 일정 정책은 제외되는지 확인 |
| `createsPayloadSnapshotFromMedicationAndDose` | prepare 시점 Payload 스냅샷 생성 확인 |
| `usesDefaultDrugNameWhenMedicationNameIsMissing` | 약 이름 누락 시 기본 메시지 생성 확인 |
| `insertPendingUsesIdempotentOutboxInsert` | PENDING Outbox 멱등 저장 SQL과 파라미터 확인 |
| `insertPendingBatchUsesSameInsertSql` | Outbox 배치 저장 호출 확인 |
| `preparesDueDosesAsPendingOutboxRows` | 후보 복약 조회, 회차 계산, Payload 생성, PENDING Outbox 저장 흐름 확인 |
| `doesNotWriteWhenNoDoseIsDueInSlot` | 슬롯 내 회차가 없으면 Outbox 저장을 호출하지 않는지 확인 |
| `countsDuplicateOutboxRowsFromNoOpInsertResults` | 중복 키로 인한 no-op 저장 결과를 duplicate 후보로 집계하는지 확인 |
| `rejectsInvalidSlot` | 잘못된 슬롯 범위를 사전에 거부하는지 확인 |
| `parsesRequiredSlotParameters` | `slotStart`, `slotEnd`, `zoneId` JobParameter 파싱 확인 |
| `rejectsMissingRequiredParameter` | 필수 JobParameter 누락 시 실패하는지 확인 |
| `rejectsInvalidSlotRange` | Batch JobParameter의 슬롯 범위 검증 확인 |
| `executesPrepareServiceWithJobParametersAndStoresResultInStepContext` | `prepareNotificationStep` Tasklet이 JobParameter로 서비스를 호출하고 실행 결과를 Step ExecutionContext에 저장하는지 확인 |
| `calculatesPreviousFiveMinuteSlotAtBoundary` | 08:05 실행 시 `[08:00, 08:05)` 슬롯을 계산하는지 확인 |
| `alignsCurrentTimeDownToPreviousSlotEnd` | 08:07:30 같은 지연 실행 시 이전 5분 경계로 정렬하는지 확인 |
| `calculatesPreviousSlotAcrossHourBoundary` | 08:00 실행 시 `[07:55, 08:00)` 슬롯을 계산하는지 확인 |
| `rejectsInvalidSlotMinutes` | 60분을 나눌 수 없는 슬롯 크기를 거부하는지 확인 |
| `launchesJobWithPreviousSlotParameters` | 스케줄러가 직전 슬롯을 JobParameter로 만들어 JobLauncher를 호출하는지 확인 |
| `ignoresAlreadyCompletedSlot` | 이미 완료된 JobInstance는 스케줄러 장애로 보지 않고 무시하는지 확인 |
| `catchesLaunchFailureSoSchedulerThreadCanContinue` | Job 실행 실패가 스케줄러 스레드 밖으로 전파되지 않는지 확인 |
| `findDispatchCandidatesQueriesOnlyDuePendingOrRetryRows` | 발송 후보 조회가 `PENDING/RETRY`, `attempt_count`, `scheduled_at`, `next_retry_at` 조건을 사용하는지 확인 |
| `claimForProcessingUpdatesOnlyClaimableRows` | 조건부 UPDATE로 `PROCESSING` 선점, `attempt_count` 증가, Claim Token/Lease 저장을 수행하는지 확인 |
| `claimForProcessingReturnsFalseWhenAnotherWorkerAlreadyClaimed` | 다른 실행이 먼저 선점한 경우 update 0건을 선점 실패로 처리하는지 확인 |
| `markSentStoresResultOnlyForMatchingClaimToken` | `PROCESSING` + 동일 `claim_token` 조건에서만 SENT 저장과 claim 정리를 수행하는지 확인 |
| `markRetryStoresBackoffAndFailureForMatchingClaimToken` | RETRY 저장 시 `next_retry_at`, 실패 코드/사유, claim 정리를 수행하는지 확인 |
| `markFailedClearsClaimAndStoresFailureForMatchingClaimToken` | FAILED 저장 시 재시도 시각을 제거하고 실패 정보를 남기는지 확인 |
| `markSkippedClearsClaimAndStoresSkipReasonForMatchingClaimToken` | SKIPPED 저장 시 skip 사유와 claim 정리를 수행하는지 확인 |
| `resultUpdateReturnsFalseWhenClaimTokenNoLongerMatches` | 늦게 돌아온 Worker의 이전 claim token으로는 상태를 덮어쓸 수 없는지 확인 |
| `recoverStaleProcessingClearsClaimAndMovesRowsToRetryOrFailed` | Lease 만료 PROCESSING 건을 RETRY 또는 FAILED로 복구하고 claim 정보를 정리하는지 확인 |
| `marksSentWhenAnySubscriptionSucceeds` | 여러 Subscription 중 하나 이상 성공하면 사용자 단위 SENT로 저장하는지 확인 |
| `marksRetryWhenAllSubscriptionsFailTemporarilyBeforeMaxAttempts` | 재시도 가능 실패가 maxAttempts 전이면 RETRY와 nextRetryAt을 저장하는지 확인 |
| `marksFailedWhenRetryableFailureReachesMaxAttempts` | 3번째 시도 실패 시 FAILED로 전환하는지 확인 |
| `marksSkippedWhenNoActiveSubscriptionExists` | 활성 구독이 없으면 SKIPPED로 저장하는지 확인 |
| `doesNotDispatchWhenClaimFails` | 조건부 선점 실패 시 WebPush를 발송하지 않는지 확인 |
| `countsStaleResultWhenClaimTokenNoLongerMatchesOnResultUpdate` | 발송 후 결과 저장 시 claim token 불일치를 stale result로 집계하는지 확인 |
| `storesSendResultInStepExecutionContext` | send Step 처리 결과를 Step ExecutionContext에 저장하는지 확인 |
| `recoversStaleProcessingRowsAndStoresCount` | stale 복구 Step의 복구 건수를 ExecutionContext에 저장하는지 확인 |

검증 결과:

```text
DoseScheduleCalculatorTest 통과
MedicationNotificationPayloadFactoryTest 통과
MedicationNotificationSendDaoTest 통과
PrepareNotificationServiceTest 통과
MedicationNotificationJobParametersTest 통과
PrepareNotificationTaskletTest 통과
MedicationNotificationSlotCalculatorTest 통과
MedicationNotificationBatchSchedulerTest 통과
MedicationNotificationSendDaoTest 통과
MedicationNotificationSenderTest 통과
SendNotificationTaskletTest 통과
StaleProcessingRecoveryTaskletTest 통과
전체 compile 통과
```

이번 단계에서 구현한 `PrepareNotificationService`의 역할:

```text
MedicationRepository
  -> MedicationMapper
  -> DoseScheduleCalculator
  -> MedicationNotificationOutboxFactory
  -> MedicationNotificationSendDao
```

즉, 아직 Spring Batch Step 자체는 아니지만 `prepareNotificationStep`에서 호출할 핵심 처리 단위를 먼저 분리했다.

현재 집계 가능한 값:

| 지표 | 의미 |
|---|---|
| `medicationCandidateCount` | 슬롯 계산 대상으로 조회한 복약 등록 건수 |
| `dueDoseCount` | 해당 슬롯에 포함된 복약 예정 회차 수 |
| `outboxInsertAttemptCount` | Outbox 저장을 시도한 건수 |
| `outboxCreatedCount` | 저장 결과가 양수로 반환된 건수 |
| `outboxDuplicateCount` | 저장 시도 중 no-op으로 간주한 중복 후보 건수 |

주의할 점:

```text
ON DUPLICATE KEY UPDATE notification_send_id = notification_send_id 의 반환 row count는
JDBC/MySQL 설정에 따라 완전한 신규 생성 수와 1:1로 대응하지 않을 수 있다.
따라서 1차 구현에서는 테스트와 운영 로그의 보조 지표로 사용하고,
정확한 createdCount/duplicateCount가 필요해지면 별도 SELECT 또는 attempt 이력 테이블로 보완한다.
```

이번 단계에서 추가한 Spring Batch 연결:

```text
MedicationNotificationJob
  -> prepareNotificationStep
      -> PrepareNotificationTasklet
          -> PrepareNotificationService
  -> sendNotificationStep
      -> SendNotificationTasklet
          -> MedicationNotificationSender
```

현재 `MedicationNotificationJob`은 `prepareNotificationStep`과 `sendNotificationStep`을 포함한다.
`summarizeNotificationStep`은 이후 Micrometer/Actuator 기반 실행 요약 지표를 붙일 때 추가한다.

현재 JobParameter:

| 파라미터 | 예시 | 의미 |
|---|---|---|
| `slotStart` | `2026-07-12T08:00:00` | 처리 슬롯 시작 시각 |
| `slotEnd` | `2026-07-12T08:05:00` | 처리 슬롯 종료 시각 |
| `zoneId` | `Asia/Seoul` | 업무 기준 시간대 |

`PrepareNotificationTasklet`은 실행 결과를 Step ExecutionContext에 저장한다.

| ExecutionContext Key | 의미 |
|---|---|
| `prepare.medicationCandidateCount` | 후보 복약 등록 건수 |
| `prepare.dueDoseCount` | 슬롯 내 복약 예정 회차 수 |
| `prepare.outboxInsertAttemptCount` | Outbox 저장 시도 건수 |
| `prepare.outboxCreatedCount` | 신규 생성 후보 건수 |
| `prepare.outboxDuplicateCount` | 중복 처리 후보 건수 |

이번 단계는 Spring Batch 실행 모델을 먼저 연결한 것이다. 대량 처리 관점의 최종 형태는
`prepareNotificationStep`을 Paging/Cursor Reader, Processor, Writer 기반 Chunk Step으로 확장하면서 완성한다.

이번 단계에서 추가한 스케줄러 연결:

```text
@Scheduled
  -> MedicationNotificationBatchScheduler
      -> MedicationNotificationSlotCalculator
      -> JobLauncher.run(medicationNotificationJob, JobParameters)
```

스케줄러는 5분마다 실행되며 실행 시각 직전 슬롯을 처리한다.

```text
08:05 실행 -> [08:00, 08:05)
08:10 실행 -> [08:05, 08:10)
```

지연 실행이 발생하면 현재 시각을 직전 5분 경계로 내림 처리한다.

```text
08:07:30 실행 -> slotEnd = 08:05, slotStart = 08:00
```

추가한 설정:

| 설정 | 기본값 | 의미 |
|---|---|---|
| `medeat.notification.batch.scheduler.enabled` | `false` | Batch 스케줄러 활성화 여부 |
| `medeat.notification.batch.scheduler.cron` | `0 */5 * * * *` | 5분 단위 실행 cron |
| `medeat.notification.batch.scheduler.zone-id` | `Asia/Seoul` | 업무 기준 시간대 |
| `medeat.notification.batch.scheduler.slot-minutes` | `5` | 슬롯 크기 |

스케줄러는 기본 비활성화로 둔다. 기존 `MedicationAlarmScheduler` 직접 발송 구조가 아직 남아 있으므로,
Batch 기반 `sendNotificationStep` 전환 전에는 의도치 않은 병행 실행을 피하기 위해
`MEDEAT_NOTIFICATION_BATCH_ENABLED=true`로 명시했을 때만 동작하게 했다.

이미 완료된 슬롯을 같은 JobParameter로 다시 실행하면 Spring Batch는
`JobInstanceAlreadyCompleteException`을 발생시킨다. 스케줄러는 이 경우를 정상적인 중복 실행 방지로 보고
info 로그만 남긴다.

이번 단계에서 추가한 Outbox 발송 후보 조회/선점:

```text
MedicationNotificationSendDao.findDispatchCandidates
  -> PENDING/RETRY
  -> attempt_count < maxAttempts
  -> scheduled_at <= now
  -> next_retry_at IS NULL OR next_retry_at <= now
  -> scheduled_at, notification_send_id 순서로 제한 조회

MedicationNotificationSendDao.claimForProcessing
  -> 조건부 UPDATE
  -> status = PROCESSING
  -> attempt_count = attempt_count + 1
  -> claim_token / claimed_at / claimed_by / claim_expires_at 저장
  -> last_attempted_at 저장
```

선점 SQL은 `notification_send_id`만으로 갱신하지 않고, 조회 조건을 다시 한 번 `WHERE`에 포함한다.
따라서 후보를 읽은 뒤 다른 실행이 먼저 선점했거나 상태를 바꾼 경우 update row count가 0이 되고,
현재 실행은 발송하지 않는다.

현재 선점 조건:

| 조건 | 목적 |
|---|---|
| `status IN ('PENDING', 'RETRY')` | 미처리 또는 재시도 대상만 선점 |
| `attempt_count < :maxAttempts` | 최대 시도 횟수 초과 방지 |
| `scheduled_at <= :now` | 미래 알림 조기 발송 방지 |
| `next_retry_at IS NULL OR next_retry_at <= :now` | backoff 이전 재시도 방지 |

아직 구현하지 않은 부분:

```text
sendNotificationStep Tasklet
stale PROCESSING 복구 DAO/Retry Job
```

이번 단계에서 추가한 Outbox 결과 저장:

```text
MedicationNotificationSendDao.markSent
  -> status = SENT
  -> sent_at 저장
  -> next_retry_at / failure / claim 정보 제거
  -> WHERE status = PROCESSING AND claim_token = :claimToken

MedicationNotificationSendDao.markRetry
  -> status = RETRY
  -> next_retry_at 저장
  -> failure_code / failure_reason 저장
  -> claim 정보 제거
  -> WHERE status = PROCESSING AND claim_token = :claimToken

MedicationNotificationSendDao.markFailed
  -> status = FAILED
  -> next_retry_at 제거
  -> failure_code / failure_reason 저장
  -> claim 정보 제거
  -> WHERE status = PROCESSING AND claim_token = :claimToken

MedicationNotificationSendDao.markSkipped
  -> status = SKIPPED
  -> next_retry_at 제거
  -> failure_code / failure_reason 저장
  -> claim 정보 제거
  -> WHERE status = PROCESSING AND claim_token = :claimToken
```

모든 결과 저장은 `notification_send_id`만으로 갱신하지 않고 `PROCESSING` 상태와 동일한 `claim_token`을
요구한다. 따라서 Lease 만료 후 다른 Worker가 재선점했거나 상태가 이미 변경된 경우 update row count가
0이 되고, 늦게 돌아온 Worker는 결과를 덮어쓰지 못한다.

현재 결과 저장 DAO는 상태 저장만 담당한다. 다음 단계에서 WebPush 발송 결과를 해석하는 서비스가
`successCount`, `retryableFailureCount`, `terminalFailureCount`, `attempt_count`를 기준으로
`markSent`, `markRetry`, `markFailed`, `markSkipped` 중 하나를 선택하게 만든다.

이번 단계에서 추가한 sendNotificationStep:

```text
sendNotificationStep
  -> SendNotificationTasklet
      -> MedicationNotificationSender
          -> findDispatchCandidates
          -> claimForProcessing
          -> MedicationNotificationDispatchService.dispatch
          -> markSent / markRetry / markFailed / markSkipped
```

`MedicationNotificationJob` 최종 1차 흐름은 다음처럼 변경되었다.

```text
MedicationNotificationJob
  -> prepareNotificationStep
  -> sendNotificationStep
```

`sendNotificationStep`의 트랜잭션 경계:

```text
SendNotificationTasklet
  -> MedicationNotificationSender
       @Transactional(propagation = NOT_SUPPORTED)
       -> claimForProcessing
       -> WebPush 발송
       -> 결과 저장
```

Step 자체는 Spring Batch 트랜잭션 안에서 호출될 수 있지만, 실제 발송 orchestration은
`NOT_SUPPORTED`로 분리했다. 따라서 외부 WebPush 호출이 prepare/write 트랜잭션에 묶이지 않는다.

WebPush 결과 결정 기준:

| 조건 | Outbox 상태 |
|---|---|
| `totalCount == 0` | SKIPPED |
| `successCount > 0` | SENT |
| 성공 0건 + 재시도 가능 실패 + 현재 시도 횟수 < maxAttempts | RETRY |
| 성공 0건 + maxAttempts 도달 또는 재시도 불가 실패 | FAILED |

재시도 backoff:

```text
1번째 실패 -> now + 1분
2번째 실패 -> now + 5분
3번째 실패 -> FAILED
```

이번 단계에서 추가한 RetryJob:

```text
MedicationNotificationRetryJob
  -> staleProcessingRecoveryStep
  -> sendNotificationStep
```

stale PROCESSING 복구 기준:

```text
status = PROCESSING
claim_expires_at < now
```

복구 결과:

```text
attempt_count >= maxAttempts -> FAILED
attempt_count < maxAttempts  -> RETRY, next_retry_at = now + recoveryBackoff

공통:
  failure_code = LEASE_EXPIRED
  failure_reason = Processing lease expired before completion
  claim_token / claimed_at / claimed_by / claim_expires_at = NULL
```

추가한 실행 설정:

| 설정 | 기본값 | 의미 |
|---|---|---|
| `medeat.notification.batch.send.max-attempts` | `3` | 최대 발송 시도 횟수 |
| `medeat.notification.batch.send.batch-size` | `100` | send Step 1회 후보 조회 수 |
| `medeat.notification.batch.send.lease-minutes` | `10` | PROCESSING Claim Lease 시간 |
| `medeat.notification.batch.send.worker-id` | 빈 값 | Worker 식별자. 빈 값이면 host name 사용 |
| `medeat.notification.batch.retry.recovery-backoff-minutes` | `1` | stale 복구 후 재시도 지연 |

이번 단계에서 검증한 장애/재시도/Lease 만료 시나리오:

```text
1. Claim 실패 -> WebPush 발송하지 않음
2. 부분 성공 -> SENT
3. 전체 일시 실패 + 시도 가능 -> RETRY
4. 전체 일시 실패 + maxAttempts 도달 -> FAILED
5. 활성 Subscription 없음 -> SKIPPED
6. 결과 저장 시 claim_token 불일치 -> staleResultCount 증가
7. Lease 만료 PROCESSING -> RETRY 또는 FAILED 복구
```

실행 시 참고 사항:

```text
로컬 환경에서 JAVA_HOME이 기본 설정되어 있지 않아,
테스트와 compile 실행 시 JAVA_HOME을 C:\Users\Sayoonjin\.jdks\ms-17.0.17 로 지정해 검증했다.
```

### 19.3 2차 확장

```text
1. medication_dose_occurrence 추가
2. 복약 예정 회차 생성 배치 또는 서비스 분리
3. Outbox가 occurrence_id 참조하도록 확장
4. DailyMedicationReportJob 구현
5. planned_count / taken_count / missed_count / adherence_rate 집계
6. 계획 대비 실적 리포트 성능 측정
```

## 20. 포트폴리오 어필 문장

```text
기존 @Scheduled 직접 발송 구조를 Spring Batch 기반 Outbox 파이프라인으로 고도화했습니다.
스케줄러는 실행 시점만 담당하고, Batch Job은 업무 시간 슬롯 기준으로 복약 알림 대상을 Chunk 처리해 PENDING 상태로 영속화했습니다.
이후 별도 Step에서 Claim Token과 Lease 기반 조건부 UPDATE로 PROCESSING을 선점하고, WebPush 발송은 트랜잭션 밖에서 수행한 뒤 동일 Claim Token 조건으로 SENT/RETRY/FAILED/SKIPPED 상태를 저장했습니다.
또한 attempt_count와 next_retry_at 기반 재시도, 오래된 PROCESSING 복구, 실패 Job Restart와 완료 후 Retry Job 분리를 적용했습니다.
발송 메시지는 Outbox에 Payload 스냅샷으로 저장해 prepare와 send 사이의 원본 데이터 변경 영향을 줄였고, 리포트는 알림 Outbox가 아닌 복약 예정 회차를 계획 데이터로 삼아 계획 대비 실적 구조로 확장할 수 있도록 설계했습니다.
```

한 줄 요약:

```text
복약 알림을 Spring Batch + Outbox + Claim Token/Lease 기반으로 고도화하고, 복약 예정 회차와 실적 로그를 분리해 계획 대비 실적 리포트까지 확장 가능한 운영형 배치 파이프라인으로 설계한다.
```
