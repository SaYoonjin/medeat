# Testcontainers MySQL Integration Test Result

## Purpose

This document records the actual MySQL integration test result for the medication notification Outbox pipeline.

The test was added to verify behavior that unit tests cannot fully prove:

- MySQL unique constraint behavior for duplicate Outbox creation
- `ON DUPLICATE KEY UPDATE` affected-row behavior
- Conditional `PROCESSING` claim under concurrent access
- Claim token protection for stale workers
- Stale `PROCESSING` lease recovery
- EXPLAIN-based index selection for dispatch candidate lookup

## Environment

| Item | Value |
|---|---|
| OS | Windows |
| JDK | `C:\Users\Sayoonjin\.jdks\ms-17.0.17` |
| Docker Desktop | 4.65.0 |
| Docker Engine | 29.2.1 |
| Docker API | 1.53 |
| Testcontainers | 1.21.4 |
| MySQL container | `mysql:8.0.36` |
| Test class | `MedicationNotificationSendDaoMySqlIntegrationTest` |

## Docker Issue And Resolution

Initial execution produced this result:

```text
Tests run: 5, Failures: 0, Errors: 0, Skipped: 5
Could not find a valid Docker environment
```

The root cause was not the test code. Docker Desktop was running, but the previous Testcontainers version could not negotiate correctly with Docker Desktop 4.65 / Docker Engine 29.2.1.

Docker Engine 29.2.1 reports:

```text
ApiVersion    : 1.53
MinAPIVersion : 1.44
```

Older API calls such as `/v1.32/info` and `/v1.41/info` returned HTTP 400, while `/v1.53/info` worked.

The project was updated from Testcontainers `1.21.3` to `1.21.4`:

```xml
<testcontainers.version>1.21.4</testcontainers.version>
```

After this change, Testcontainers resolved Docker successfully:

```text
Found Docker environment with Environment variables, system properties and defaults.
Resolved dockerHost=tcp://127.0.0.1:2375
Connected to docker:
  Server Version: 29.2.1
  API Version: 1.53
  Operating System: Docker Desktop
```

## Execution Command

```powershell
$env:JAVA_HOME="C:\Users\Sayoonjin\.jdks\ms-17.0.17"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:DOCKER_HOST="tcp://127.0.0.1:2375"

.\mvnw.cmd "-Dtest=PrepareNotificationServiceTest,MedicationNotificationSendDaoTest,MedicationNotificationSendDaoMySqlIntegrationTest" test
```

## Actual Test Result

```text
Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Breakdown:

| Test class | Tests | Result |
|---|---:|---|
| `MedicationNotificationSendDaoMySqlIntegrationTest` | 5 | Passed |
| `MedicationNotificationSendDaoTest` | 11 | Passed |
| `PrepareNotificationServiceTest` | 4 | Passed |

## Verified Scenarios

| Scenario | Verified Result |
|---|---|
| Duplicate Outbox insert | Actual MySQL unique constraint keeps one row |
| Duplicate no-op upsert | MySQL may return affected rows `1`, not `0` |
| Concurrent claim | Only one worker changes the row to `PROCESSING` |
| Stale claim token result update | Old claim token updates `0` rows |
| Stale `PROCESSING` recovery | Expired lease changes to `RETRY` or `FAILED` and clears claim fields |
| Dispatch candidate EXPLAIN | Candidate lookup uses one of the notification indexes |

## Important Finding

The integration test exposed a production metric issue.

The previous implementation treated JDBC affected rows from `ON DUPLICATE KEY UPDATE` as the number of newly created Outbox rows:

```text
result > 0 -> createdCount++
```

In actual MySQL, a duplicate no-op upsert can still return `1`. Therefore, affected rows are not a reliable source for `createdCount`.

The implementation was corrected so that `createdCount` is calculated from actual rows created by the current Batch execution:

```text
origin_job_execution_id 기준 실제 생성 행 수 조회
```

This keeps operational metrics clearer:

| Metric | Meaning |
|---|---|
| `insertAttemptCount` | Number of Outbox insert attempts |
| `createdCount` | Rows actually created by the current JobExecution |
| `duplicateCount` | Insert attempts that were already present |

## Portfolio Note

This is a useful point to mention in the portfolio:

```text
단위 테스트에서는 확인하기 어려운 MySQL의 실제 upsert 반환값 차이를 Testcontainers 통합 테스트로 확인했습니다.
그 결과 JDBC affected rows를 신규 생성 수로 사용하면 운영 지표가 왜곡될 수 있음을 발견했고,
origin_job_execution_id 기준 실제 생성 행 수를 조회하도록 수정해 createdCount와 duplicateCount의 의미를 명확히 했습니다.
```

