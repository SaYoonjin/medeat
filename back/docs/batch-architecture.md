# Medication Notification Batch Architecture

## 1. 목표

기존 복약 알림은 `@Scheduled`가 현재 시각과 일치하는 복약 데이터를 조회한 뒤 WebPush를 즉시 발송하는 구조였다. 이 구조는 단순하지만 대량 처리, 실패 복구, 중복 방지, 재실행, 성능 측정 근거를 남기기 어렵다.

개선된 구조는 Spring Batch와 Outbox를 사용해 다음 흐름을 분리한다.

```text
@Scheduled
  -> MedicationNotificationJob
      -> prepareNotificationStep
      -> sendNotificationStep

MedicationNotificationRetryJob
  -> staleProcessingRecoveryStep
  -> sendNotificationStep
```

## 2. 핵심 설계

### 2.1 시간 슬롯

스케줄러는 5분마다 실행되며 실행 시각 직전 슬롯을 처리한다.

```text
08:05 실행 -> [08:00, 08:05)
08:10 실행 -> [08:05, 08:10)
```

슬롯은 반열린 구간이다.

```text
slotStart <= scheduled_at < slotEnd
```

### 2.2 prepareNotificationStep

역할:

```text
복약 등록 건 조회
  -> DoseScheduleCalculator로 슬롯 내 복약 회차 계산
  -> Payload 스냅샷 생성
  -> medication_notification_send에 PENDING 저장
```

중복 방지는 다음 유니크 키로 처리한다.

```text
UNIQUE(user_id, medication_id, scheduled_at, dose_sequence)
```

### 2.3 sendNotificationStep

역할:

```text
PENDING/RETRY 후보 조회
  -> 조건부 UPDATE로 PROCESSING 선점
  -> 트랜잭션 밖에서 WebPush 발송
  -> claim_token 조건으로 SENT/RETRY/FAILED/SKIPPED 저장
```

후보 조회 조건:

```sql
status IN ('PENDING', 'RETRY')
AND attempt_count < :maxAttempts
AND scheduled_at <= :now
AND (next_retry_at IS NULL OR next_retry_at <= :now)
```

선점 성공 시:

```text
status = PROCESSING
attempt_count = attempt_count + 1
claim_token = UUID
claimed_by = workerId
claim_expires_at = now + lease
```

결과 저장은 반드시 다음 조건을 포함한다.

```sql
WHERE notification_send_id = :id
  AND status = 'PROCESSING'
  AND claim_token = :claimToken
```

따라서 Lease 만료 후 다른 Worker가 재선점했거나 상태가 변경된 경우, 늦게 돌아온 Worker는 결과를 덮어쓰지 못한다.

## 3. WebPush 결과 정책

Outbox 한 행은 복약 회차 단위 논리 알림이다. 실제 발송 시점에는 사용자의 현재 활성 Subscription 목록을 조회한다.

| WebPush 결과 | Outbox 상태 |
|---|---|
| 구독 1개 이상 성공 | SENT |
| 활성 구독 없음 | SKIPPED |
| 모두 실패, 재시도 가능, maxAttempts 미도달 | RETRY |
| 모두 실패, maxAttempts 도달 | FAILED |
| 모두 실패, 재시도 불가능 | FAILED |

재시도 backoff:

```text
1번째 실패 -> now + 1분
2번째 실패 -> now + 5분
3번째 실패 -> FAILED
```

## 4. RetryJob

`MedicationNotificationRetryJob`은 정상 완료된 JobInstance를 다시 실행하지 않고, 상태 기반으로 실패 데이터를 재처리한다.

```text
staleProcessingRecoveryStep
  -> lease 만료 PROCESSING 복구

sendNotificationStep
  -> PENDING/RETRY 재발송
```

Lease 만료 기준:

```sql
status = 'PROCESSING'
AND claim_expires_at < :now
```

복구 결과:

```text
attempt_count >= maxAttempts -> FAILED
attempt_count < maxAttempts  -> RETRY
```

## 5. 트랜잭션 경계

외부 WebPush 호출은 DB 트랜잭션에 넣지 않는다.

```text
SendNotificationTasklet
  -> MedicationNotificationSender
      @Transactional(propagation = NOT_SUPPORTED)
      -> claimForProcessing
      -> WebPush 발송
      -> markSent / markRetry / markFailed / markSkipped
```

이 구조는 외부 발송 성공을 DB 트랜잭션처럼 롤백할 수 없다는 제약을 인정하고, 상태 기반 복구와 claim token으로 중복 가능 구간을 최소화한다.

## 6. 현재 구현 파일

| 영역 | 파일 |
|---|---|
| Job/Step 설정 | `MedicationNotificationJobConfig` |
| 스케줄 실행 | `MedicationNotificationBatchScheduler` |
| 슬롯 계산 | `MedicationNotificationSlotCalculator` |
| 복약 회차 계산 | `DoseScheduleCalculator` |
| Outbox 생성 | `PrepareNotificationService`, `PrepareNotificationTasklet` |
| Outbox DAO | `MedicationNotificationSendDao` |
| WebPush 결과 수집 | `MedicationNotificationDispatchService` |
| send orchestration | `MedicationNotificationSender`, `SendNotificationTasklet` |
| stale 복구 | `StaleProcessingRecoveryTasklet` |

## 6.1 실제 MySQL 통합 테스트

단위 테스트만으로는 실제 DB 제약과 동시 UPDATE 동작을 완전히 증명하기 어렵다. 이를 보완하기 위해 Testcontainers MySQL 통합 테스트를 추가했다.

```text
MedicationNotificationSendDaoMySqlIntegrationTest
```

검증 범위:

```text
1. UNIQUE(user_id, medication_id, scheduled_at, dose_sequence) 실제 동작
2. ON DUPLICATE KEY UPDATE no-op 반환값
3. 동시 claim 시 하나의 Worker만 update count 1
4. 오래된 claim_token 결과 저장 방지
5. stale PROCESSING 복구
6. EXPLAIN 기반 후보 조회 인덱스 선택
```

이 테스트는 Docker Desktop이 실행 중일 때만 수행된다. Docker가 없거나 daemon이 꺼져 있으면 skip되도록 구성했다.

## 7. 운영 한계

Outbox와 Claim Token은 중복 발송 가능성을 줄이지만, 모든 장애 상황에서 정확히 한 번 발송을 보장하지는 않는다.

예를 들어 WebPush 발송 성공 직후 프로세스가 종료되어 `SENT` 저장에 실패하면, 해당 Outbox는 Lease 만료 후 다시 발송될 수 있다. 수신 시스템이 멱등키를 지원하지 않는 한 이 구간은 완전히 제거할 수 없다.

따라서 포트폴리오 표현은 다음처럼 가져간다.

```text
유니크 제약으로 동일 복약 회차의 Outbox 중복 생성을 방지하고,
Claim Token/Lease와 상태 기반 RetryJob으로 장애 이후 미처리 건만 재처리하도록 설계했습니다.
외부 WebPush의 exactly-once 한계는 문서화하고, 중복 가능 구간을 최소화했습니다.
```
