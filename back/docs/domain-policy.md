# MEDEAT 복약 알림/리포트 도메인 정책

## 1. 목적

이 문서는 복약 알림 배치와 일일 복약 리포트의 업무 기준을 고정하기 위한 정책 문서다.

구현 목표는 단순히 `@Scheduled` 직접 발송 구조를 Spring Batch로 옮기는 것이 아니라, 다음 운영 기준을 코드와 데이터 구조로 명확히 표현하는 것이다.

- 복약 알림 대상 선정 기준
- 업무 시간 슬롯 기준
- 복약 회차 계산 기준
- 알림 중복 방지 기준
- WebPush 발송 실패와 재시도 기준
- Claim Token/Lease 기반 선점과 복구 기준
- 일일 복약 리포트의 계획/실적 산정 기준

## 2. 용어 정의

| 용어 | 의미 |
|---|---|
| 복약 등록 건 | 사용자가 등록한 특정 약 복용 정보. 현재 `medication` 테이블의 1건에 해당한다. |
| 복약 회차 | 특정 복약 등록 건에서 발생하는 N번째 예정 복약 시각. |
| `scheduled_at` | 실제 복약 알림 발송 예정 일시. |
| `dose_sequence` | 복약 계획 내 회차 번호. 예: 하루 3회 복용 시 1, 2, 3. |
| Outbox | 발송해야 할 논리적 알림을 먼저 DB에 영속화한 데이터. |
| Claim | 특정 Worker가 Outbox 발송 처리를 선점하는 행위. |
| Lease | Claim의 유효 시간. Lease가 만료되면 비정상 종료로 보고 재처리 대상이 될 수 있다. |
| `attempt_count` | 실제 발송 처리 시도 횟수. `PROCESSING` 선점 성공 시 증가한다. |
| Dose Occurrence | 2차 확장에서 도입할 복약 예정 회차 데이터. 리포트의 계획 기준이 된다. |

## 3. intake_time 정책

신규 정책의 기준은 다음과 같다.

```text
intake_time = 첫 복약 시각
interval_hour = 다음 복약까지의 간격
daily_count = 하루 복약 횟수
```

예:

```text
intake_time = 08:00
interval_hour = 8
daily_count = 3

1회차: 2026-07-11 08:00
2회차: 2026-07-11 16:00
3회차: 2026-07-12 00:00
```

다만 기존 시스템에서는 `intake_time`이 다음처럼 명시적 시간 목록으로 사용되어 왔다.

```text
08:00,13:00,18:00
```

따라서 1차 구현의 `DoseScheduleCalculator`는 두 가지 입력 방식을 모두 지원한다.

판정 순서:

```text
1. intake_time을 쉼표 기준으로 파싱
2. 유효한 시간이 2개 이상이면 LEGACY_EXPLICIT_TIMES 방식 사용
3. 유효한 시간이 1개이고 interval_hour, daily_count가 유효하면 INTERVAL 방식 사용
4. 둘 다 만족하지 않으면 잘못된 복약 일정으로 처리
```

기존 명시적 시간 목록을 우선하는 이유는 기존 사용자가 등록한 시간이 interval 계산 결과와 다를 수 있기 때문이다.

예:

```text
intake_time = 08:00,12:30,20:00
interval_hour = 6
daily_count = 3
```

이 데이터를 interval 방식으로 다시 계산하면 `08:00,14:00,20:00`이 되어 기존 `12:30` 일정이 바뀐다. 따라서 유효한 시간이 2개 이상이면 기존 시간 목록을 우선한다.

계산 방식은 로그나 메트릭에서 구분할 수 있도록 다음 값으로 남긴다.

```text
scheduleSource = LEGACY_EXPLICIT_TIMES
scheduleSource = INTERVAL_CALCULATED
```

## 4. 시간 슬롯 정책

복약 알림 배치는 단일 현재 시각이 아니라 업무 시간 슬롯 기준으로 대상을 생성한다.

시간 슬롯은 반열린 구간으로 정의한다.

```text
slotStart <= scheduled_at < slotEnd
```

예:

```text
[08:00:00, 08:05:00)
```

`08:05:00`에 해당하는 데이터는 다음 슬롯 `[08:05:00, 08:10:00)`에서 처리한다. 이렇게 해야 경계 시각 데이터가 두 슬롯에 중복 포함되지 않는다.

1차 구현에서는 스케줄 실행 주기와 슬롯 크기를 5분으로 맞추는 것을 기본 정책으로 둔다. 정기 Job은 실행 시각 직전 5분 슬롯을 처리한다.

```text
08:05 실행 -> [08:00, 08:05)
08:10 실행 -> [08:05, 08:10)
```

이 정책에서는 알림이 예정 시각보다 늦게 발송될 수 있다.

```text
08:00 예정 알림 -> 08:05 Job 실행 -> 최대 약 5분 지연
08:04 예정 알림 -> 08:05 Job 실행 -> 약 1분 지연
```

정확한 분 단위 발송이 필요하면 스케줄 실행 주기와 슬롯 크기를 1분으로 축소한다.

서버 장애로 특정 슬롯 실행 자체가 누락되는 문제는 1차 범위에서는 제한사항으로 문서화한다. 이후 마지막 성공 슬롯을 저장하는 Watermark 방식으로 확장한다.

## 5. 알림 대상 생성 정책

`prepareNotificationStep`은 업무 시간 슬롯에 포함되는 복약 회차를 계산하고, 알림 발송 대상 Outbox를 생성한다.

```text
복약 등록 건 조회
  -> slotStart <= scheduled_at < slotEnd인 회차 계산
  -> 필수값 검증
  -> Payload 스냅샷 생성
  -> PENDING Outbox 저장
```

정기 실행에서는 실행 시각 직전 슬롯을 처리하므로 일반적으로 이미 도래한 회차를 Outbox로 생성한다.

예:

```text
현재 시각: 08:05
slot: [08:00, 08:05)
scheduled_at: 08:04
```

이 경우 Outbox 생성과 발송 선점 모두 가능하다.

단, `scheduled_at <= NOW(6)` 조건은 유지한다. 이 조건은 수동 실행, 비정상 JobParameter, 미래 데이터 오처리를 막는 방어 조건이다.

수동 실행이나 별도 선행 생성 Job에서는 미래 회차를 생성할 수 있으나, send Step은 항상 `scheduled_at <= NOW(6)`인 데이터만 실제 발송한다.

## 6. 중복 방지 정책

Outbox 한 행은 특정 사용자의 특정 복약 등록 건에서 발생한 특정 회차의 논리적 알림을 의미한다. 즉, 1차 구현에서는 복약 회차당 Outbox 1행을 생성한다.

```text
medication_notification_send = 사용자 단위 복약 알림 의도와 처리 상태
WebPush Subscription = 실제 발송 시점에 조회하는 전달 대상
```

1차 구현에서는 별도 `medication_schedule_id` 또는 `occurrence_id`가 없으므로 다음 유니크 키를 사용한다.

```text
UNIQUE(user_id, medication_id, scheduled_at, dose_sequence)
```

중복 처리 정책:

| 상황 | 처리 |
|---|---|
| 동일 복약 회차 Outbox 이미 존재 | 정상 멱등 처리 |
| NOT NULL 위반 | 데이터 오류 |
| 외래키 오류 | 정합성 오류 |

`INSERT IGNORE`는 사용하지 않는다. 중복 키 외의 데이터 오류까지 숨길 수 있기 때문이다.

추천 방식:

```sql
INSERT INTO medication_notification_send (...)
VALUES (...)
ON DUPLICATE KEY UPDATE
    notification_send_id = notification_send_id;
```

또는 애플리케이션에서 `DuplicateKeyException`만 별도로 처리한다.

## 7. Outbox 상태 정책

Outbox 상태는 다음 값을 가진다.

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

각 상태 의미:

| 상태 | 의미 |
|---|---|
| PENDING | 발송 대상이 생성되었지만 아직 시도하지 않음 |
| PROCESSING | 특정 Worker가 Claim하여 발송 처리 중 |
| SENT | WebPush 발송 성공 |
| RETRY | 재시도 가능한 실패 |
| FAILED | 더 이상 재시도하지 않는 실패 |
| SKIPPED | 필수 데이터 누락 또는 활성 Subscription 없음 등으로 발송하지 않음 |

## 8. Claim Token/Lease 정책

발송 대상 선점은 `PROCESSING` 상태만으로 관리하지 않고 Claim Token과 Lease를 함께 사용한다.

필수 컬럼:

```text
claim_token
claimed_at
claimed_by
claim_expires_at
```

선점 시 정책:

- `PENDING` 또는 `RETRY` 상태만 선점 가능
- `attempt_count < maxAttempts`인 경우만 선점 가능
- `scheduled_at <= NOW(6)`인 경우만 실제 발송 선점 가능
- `next_retry_at IS NULL OR next_retry_at <= NOW(6)`인 경우만 선점 가능
- 선점 성공 시 `attempt_count`를 원자적으로 1 증가
- 선점 성공 시 `claim_token`, `claimed_at`, `claimed_by`, `claim_expires_at` 설정

결과 저장 시 정책:

- 반드시 `status = PROCESSING`
- 반드시 동일한 `claim_token` 조건을 만족해야 함
- 영향받은 행이 0건이면 Lease 만료 또는 다른 Worker 선점으로 판단

Claim Token은 늦게 돌아온 Worker의 DB 상태 덮어쓰기를 막는다. 다만 이미 시작된 외부 WebPush 호출 자체를 취소하거나 중복 전송을 완전히 방지하지는 못한다.

## 9. attempt_count / next_retry_at 정책

`retry_count` 대신 `attempt_count`를 사용한다. 최초 발송도 하나의 처리 시도이기 때문이다.

기본값:

```text
maxAttempts = 3
```

시도 흐름:

```text
1번째 선점/발송 실패 -> attempt_count = 1 -> RETRY
2번째 선점/발송 실패 -> attempt_count = 2 -> RETRY
3번째 선점/발송 실패 -> attempt_count = 3 -> FAILED
```

`attempt_count`는 발송 이후가 아니라 PROCESSING 선점 성공 시 증가시킨다. 서버가 발송 과정에서 종료되더라도 실제 처리 시도가 있었다는 사실을 남기기 위해서다.

`next_retry_at` 정책:

```text
1번째 실패 -> next_retry_at = now + 1분
2번째 실패 -> next_retry_at = now + 5분
3번째 실패 -> FAILED, next_retry_at = NULL
```

1차 구현에서는 위 고정 backoff를 사용하고, 이후 오류 유형별 backoff로 확장할 수 있다.

## 10. WebPush 발송 실패 처리 정책

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

주의:

```text
WebPush 전체 Timeout < Claim Lease 시간
```

예를 들어 WebPush 연결/응답 전체 Timeout이 최대 15초라면 Lease는 1~10분으로 충분하다. 외부 요청이 Lease보다 오래 걸릴 수 있으면 Worker A가 발송 중인 데이터를 Worker B가 재선점할 수 있다.

## 11. 트랜잭션 경계 정책

sendNotificationStep에서 WebPush를 Step 기본 트랜잭션에 넣지 않는다.

Spring Batch의 Tasklet은 기본적으로 `execute()` 호출이 Step 트랜잭션으로 감싸질 수 있다. 따라서 Tasklet 내부에 코드를 순서대로 작성하기만 하면 WebPush 호출 중에도 바깥 트랜잭션이 유지될 수 있다.

최종 구현 방향:

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

검증 로그에서 다음 순서가 확인되어야 한다.

```text
PROCESSING 커밋 완료
-> WebPush 요청 시작
-> WebPush 요청 종료
-> SENT 또는 RETRY 별도 커밋
```

## 12. Payload 스냅샷과 Subscription 기준

prepare와 send 사이에 약 이름이나 복약 일정이 변경되면 send Step이 원본 데이터를 다시 조회할 때 다른 메시지가 만들어질 수 있다. 따라서 prepare Step에서 발송 메시지를 확정하고 Outbox에 저장한다.

Outbox에 저장할 필드:

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

정리:

```text
알림 메시지 = prepare 시점의 Payload 스냅샷으로 고정
발송 대상 Subscription = send 시점의 현재 활성 목록으로 결정
```

따라서 다음처럼 처리한다.

| 상황 | 처리 |
|---|---|
| prepare 이후 약 이름 변경 | 생성 당시 Payload로 발송 |
| prepare 이후 Subscription 삭제 | send 시 제외 |
| prepare 이후 Subscription 추가 | send 시 포함 |
| send 시 활성 Subscription 없음 | SKIPPED, failure_code = NO_ACTIVE_SUBSCRIPTION |

## 13. Subscription별 발송 결과 정책

Outbox는 복약 회차당 1행이므로 상태도 사용자에게 알림이 도달했는지를 기준으로 결정한다.

한 번의 Outbox 발송 시도 안에서 사용자의 여러 활성 Subscription으로 발송할 수 있다.

```text
attempt_count = 1
  -> 스마트폰 발송
  -> PC 발송
  -> 태블릿 발송
```

1차 MVP에서는 하나 이상의 기기에 알림이 도달하면 사용자 단위 성공으로 본다.

| 구독별 결과 | Outbox 최종 상태 |
|---|---|
| 모든 구독 성공 | SENT |
| 하나 이상 성공 | SENT |
| 성공 0건, 재시도 가능한 실패 존재 | RETRY |
| 성공 0건, 모두 재시도 불가능 | FAILED |
| 활성 구독 없음 | SKIPPED |

이 정책에서는 일부 기기 실패만 별도로 재시도하지 않는다. Subscription별 성공/실패 추적이 필요해지면 2차 확장에서 하위 테이블을 둔다.

```text
medication_notification_send = 복약 회차 단위 논리 알림
medication_notification_delivery = Subscription별 실제 발송 결과
```

이 경우 실패한 Subscription만 재발송할 수 있다.

## 14. Stale PROCESSING 복구 정책

Lease가 만료된 `PROCESSING` 건은 비정상 종료로 간주한다.

복구 시 정책:

- `claim_token`, `claimed_at`, `claimed_by`, `claim_expires_at` 모두 정리
- `attempt_count >= maxAttempts`이면 `FAILED`
- 아직 시도 가능하면 `RETRY`
- `failure_code = LEASE_EXPIRED`
- `failure_reason = Processing lease expired before completion`

복구 SQL 예:

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

## 15. JobParameter / Restart 정책

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

## 16. 일일 복약 리포트 정책

리포트의 `planned_count`는 Outbox 기준으로 잡지 않는다.

이유:

```text
Outbox = 알림 발송 대상
복약 예정 회차 = 실제 업무 계획
Medication Log = 실제 복약 실적
```

알림을 꺼둔 사용자나 발송 정책 변경이 있어도 복약 계획은 존재할 수 있다. 따라서 리포트는 알림 발송 데이터가 아니라 복약 예정 회차 기준으로 계산해야 한다.

2차 확장 구조:

```text
Dose Occurrence = 계획
Notification Outbox = 전달 수단
Medication Log = 실적
Daily Report = 계획 대비 실적
```

2차 확장에서 `medication_dose_occurrence` 테이블을 추가한다.

리포트 계산:

```text
planned_count = 복약 예정 회차 수
taken_count = 복약 완료 로그 수
missed_count = planned_count - taken_count
adherence_rate = taken_count / planned_count
notification_count = 알림 발송 수
```

## 17. WebPushService 리팩토링 정책

현재 `WebPushServiceImpl`은 구독별 발송 실패를 내부 로그로만 남기고 종료한다. Batch에서는 Outbox 상태를 결정해야 하므로 발송 결과를 반환하는 Batch용 메서드가 필요하다.

예:

```java
public record WebPushDispatchResult(
        int totalCount,
        int successCount,
        int retryableFailureCount,
        int terminalFailureCount,
        List<WebPushFailure> failures
) {
    public boolean hasAnySuccess() {
        return successCount > 0;
    }

    public boolean hasRetryableFailure() {
        return retryableFailureCount > 0;
    }
}
```

Batch 상태 결정 기준:

```text
totalCount == 0 -> SKIPPED, NO_ACTIVE_SUBSCRIPTION
successCount > 0 -> SENT
successCount == 0 && retryableFailureCount > 0 -> RETRY
successCount == 0 && retryableFailureCount == 0 -> FAILED
```

예상하지 못한 시스템 오류나 DB 오류는 예외를 전파해 Step을 실패시킨다. 정상적으로 분류 가능한 WebPush 결과만 비즈니스 상태로 변환한다.

## 18. 1차 완료 기준

1차 구현은 알림 Outbox 배치 안정화까지로 고정한다.

```text
1. spring-boot-starter-batch 추가
2. Batch 메타 테이블 Flyway 추가
3. medication_notification_send Flyway 추가
4. Batch 설정 추가
5. DoseScheduleCalculator 구현
6. prepareNotificationStep 구현
7. Outbox 중복 생성 방지 테스트
8. sendNotificationStep 구현
9. Tasklet 외부 트랜잭션 경계 검증
10. Claim Token/Lease 기반 PROCESSING 선점 구현
11. claim_token 조건 상태 저장 테스트
12. next_retry_at 기반 재시도 구현
13. MedicationNotificationRetryJob 구현
14. stale PROCESSING 복구 테스트
15. 장애/재시작 테스트
16. 인덱스 적용 및 EXPLAIN 비교
17. Chunk 성능 측정
```

## 19. 테스트 기준

| 테스트 | 기대 결과 |
|---|---|
| prepare Step 중간 실패 후 동일 파라미터 Restart | 실패 Step부터 복구 |
| 성공 완료 Job을 같은 파라미터로 재실행 | `JobInstanceAlreadyCompleteException` 확인 |
| 동일 업무 슬롯의 새로운 JobInstance 실행 | Outbox 신규 생성 0건 |
| 동일 PENDING 데이터 동시 선점 | 하나의 claim_token만 PROCESSING 전환 |
| 현재 시각 08:05, scheduled_at 08:04 | Outbox 생성 가능, 발송 선점 가능 |
| 수동 실행으로 미래 Outbox 생성, scheduled_at > now | Outbox 생성 가능, 발송 선점 불가 |
| PENDING 생성 후 약 이름 변경 | 생성 당시 Payload로 발송 |
| prepare 이후 Subscription 삭제 | send 시 제외 |
| prepare 이후 Subscription 추가 | send 시 포함 |
| 여러 Subscription 중 하나 이상 성공 | SENT |
| 모든 Subscription 실패, 재시도 가능 오류 포함 | RETRY |
| 모든 Subscription 실패, 재시도 불가 오류만 존재 | FAILED |
| 활성 Subscription 없음 | SKIPPED |
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
