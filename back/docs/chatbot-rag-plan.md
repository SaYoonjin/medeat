# 챗봇 고도화 및 RAG 기반 의약품 검색·응답 시스템 계획

작성일: 2026-07-12

## 1. 문서 목적

이 문서는 MEDEAT 챗봇 기능을 단순 GPT 질의응답 구조에서, 사용자 복약 데이터와 식약처 의약품 데이터를 결합한 근거 기반 검색·응답 시스템으로 고도화하기 위한 계획을 정리한다.

핵심 목표는 “챗봇 기능 추가”가 아니라 다음 구조를 갖춘 백엔드 데이터 처리 시스템으로 확장하는 것이다.

```text
공공 의약품 데이터 수집
→ RDB 원본 저장
→ Section 단위 변경 감지
→ Chunk 생성
→ Vector DB 인덱싱
→ 실패 복구
→ 근거 검색
→ LLM 답변 생성
→ 검색 품질·성능 평가
```

최종적으로는 다음 문장으로 설명할 수 있는 구조를 목표로 한다.

> 개인 복약 상태와 공공 의약품 문서를 결합하고, 변경 감지·버전 전환·실패 복구·검색 평가까지 구현한 근거 기반 데이터 처리 시스템

## 2. 기존 챗봇 구조

기존 챗봇은 사용자의 질문을 받아 LLM API에 전달하고, 응답 텍스트를 사용자에게 반환하는 구조에 가까웠다.

```text
사용자 질문
→ ChatbotController
→ ChatbotService
→ LLM API 호출
→ 응답 텍스트 반환
```

이 구조는 간단한 자연어 응답에는 사용할 수 있지만, MEDEAT 서비스의 핵심 데이터인 사용자 복약 정보와 식약처 의약품 정보를 충분히 활용하지 못한다.

## 3. 기존 구조의 문제점

### 3.1 사용자 복약 데이터 반영 부족

사용자가 등록한 약, 복용 시간, 오늘 복용 여부가 답변에 충분히 반영되지 않으면 다음 질문에 정확히 답하기 어렵다.

```text
오늘 저녁에 먹을 약이 뭐야?
오늘 아침 약 먹었어?
내가 먹는 약 중 술 조심해야 하는 약 있어?
```

이 질문들은 LLM의 일반 지식이 아니라, 서비스 DB의 사용자별 복약 데이터가 필요하다.

### 3.2 식약처 근거 없는 답변 위험

의약품 정보는 효능, 복용법, 주의사항, 상호작용, 부작용처럼 정확성이 중요한 데이터를 포함한다.

LLM의 기억만 사용하면 다음 문제가 생길 수 있다.

- 실제 식약처 자료와 다른 답변을 생성할 수 있음
- 근거가 없는 내용을 그럴듯하게 말할 수 있음
- 오래된 정보나 일반화된 정보를 제공할 수 있음
- 답변 출처를 추적하기 어려움

따라서 LLM이 직접 판단하도록 두는 것이 아니라, 서버가 먼저 식약처 근거를 조회하고 그 근거 안에서만 답변하도록 제한해야 한다.

### 3.3 모든 데이터를 LLM에 넘기는 방식의 한계

질문과 무관한 데이터를 많이 전달하면 다음 문제가 발생한다.

- 토큰 비용 증가
- 답변 품질 저하
- 복용법, 부작용, 주의사항이 섞여 답변될 가능성
- 개인정보가 불필요하게 전달될 가능성

따라서 질문 의도에 따라 필요한 데이터만 선택하는 Context Routing이 필요하다.

### 3.4 긴 의약품 문서 검색의 한계

RDB는 정확한 조건 검색에는 강하지만, 긴 자연어 문서에서 의미적으로 관련 있는 문단을 찾는 데는 한계가 있다.

예를 들어 다음 질문은 단순 키워드 검색만으로는 놓칠 수 있다.

```text
약 먹고 속이 울렁거려.
술 마시고 먹어도 돼?
다른 약이랑 같이 먹어도 돼?
```

이런 질문은 부작용, 주의사항, 상호작용 문서에서 의미적으로 가까운 문단을 검색해야 하므로 Vector DB 기반 검색이 적합하다.

## 4. 개선 목표

챗봇 고도화의 목표는 다음과 같다.

1. 사용자 복약 데이터 기반 답변
   - 사용자가 등록한 약 조회
   - 복용 시간 조회
   - 오늘 복용 여부 조회
   - 개인 복약 질문에 사용자 데이터 반영

2. 식약처 근거 기반 답변
   - `drug_info` DB 우선 조회
   - DB에 없거나 오래된 경우 식약처 API fallback
   - 약품명, 품목기준코드, 출처, 조회 시점 표시
   - 근거 없는 내용은 추측하지 않음

3. 질문 의도 기반 Context Routing
   - 질문 유형과 의도를 분리
   - 필요한 데이터만 RDB 또는 Vector DB에서 조회
   - LLM에는 최소한의 근거 context만 전달

4. Vector Retrieval 기반 RAG 확장
   - 긴 의약품 문서를 Section과 Chunk 단위로 분리
   - 자연어 질문과 의미적으로 가까운 문단 검색
   - 검색된 근거만 LLM 답변에 활용

5. 운영 가능한 인덱싱 파이프라인
   - Section 단위 contentHash 기반 변경 감지
   - 변경되지 않은 Section은 skip
   - 변경된 Section만 새 버전으로 인덱싱
   - 실패 작업 재처리
   - 오래된 PROCESSING 복구
   - 중복 작업 방지

6. 검색 품질·성능 평가
   - Recall@3, Recall@5 측정
   - 평균/P95 검색 시간 측정
   - 전체 인덱싱과 증분 인덱싱 비교
   - Hash 비교 후 skip 비율 측정

## 5. 저장소 역할 분리

가장 중요한 설계 판단은 RDB와 Vector DB의 역할을 분리하는 것이다.

| 데이터 | 저장·조회 방식 | 이유 |
|---|---|---|
| 사용자 등록 약 | MySQL | 정확한 상태 조회 필요 |
| 복용 시간·복용 여부 | MySQL | 조건 검색과 정합성 필요 |
| 약품명·품목기준코드 | MySQL | 정확한 식별 필요 |
| 식약처 원본 | MySQL | 원본 보존과 변경 비교 필요 |
| 긴 부작용·주의사항 문단 | Vector DB | 자연어 의미 검색 필요 |
| 최종 답변 | LLM | 검색된 근거를 자연어로 정리 |

면접 또는 포트폴리오에서는 다음처럼 설명한다.

> 정확한 값과 상태가 필요한 복약 데이터는 RDB에서 조회하고, 의미적으로 유사한 문단을 찾아야 하는 의약품 문서에만 Vector DB를 사용했습니다.

## 6. 질문 처리 구조

질문 분류는 하나의 Enum으로 합치지 않고, `scope`와 `intent` 두 축으로 나눈다.

### 6.1 질문 범위

```text
PERSONAL_MEDICATION
GENERAL_DRUG_INFO
```

`PERSONAL_MEDICATION`은 사용자의 등록 약, 복용 시간, 복용 여부가 필요한 질문이다.

예시:

```text
오늘 저녁에 먹을 약이 뭐야?
내가 먹는 약 중 술 조심해야 하는 약 있어?
오늘 아침 약 먹었어?
```

`GENERAL_DRUG_INFO`는 사용자의 등록 여부와 무관하게 특정 의약품의 일반 정보를 묻는 질문이다.

예시:

```text
타이레놀 부작용 알려줘.
이부프로펜은 어떤 약이야?
판콜 복용법 알려줘.
```

### 6.2 질문 의도

```text
SCHEDULE
TAKEN_STATUS
EFFICACY
USAGE
SIDE_EFFECT
WARNING
INTERACTION
STORAGE
```

예시:

```text
내가 먹는 약 중 술 조심해야 하는 약 있어?
→ scope = PERSONAL_MEDICATION
→ intent = WARNING 또는 INTERACTION
```

```text
타이레놀 부작용 알려줘.
→ scope = GENERAL_DRUG_INFO
→ intent = SIDE_EFFECT
```

### 6.3 검색 정책

개인 복약 질문:

```text
로그인 사용자 ID
→ 사용자 등록 약 조회
→ 등록 약 itemSeq 목록으로 검색 범위 제한
→ 개인 복약 상태와 의약품 근거 결합
```

일반 의약품 질문:

```text
질문에서 약 이름 추출
→ 약 이름으로 itemSeq 후보 조회
→ 후보 0개: 확인 가능한 약품 없음
→ 후보 1개: 해당 itemSeq 사용
→ 후보 N개: 제품명·제조사·제형 후보 제시 후 재질문
```

약품 후보가 여러 개인 경우 임의로 첫 번째 결과를 선택하지 않는다. 의료 정보에서 잘못된 품목기준코드에 근거를 연결하면 답변 출처가 틀어질 수 있기 때문이다.

## 7. 1차 구현: RDB 기반 Grounded Chatbot

1차 목표는 Vector DB 없이도 사용자 복약 데이터와 식약처 근거를 활용하는 RDB 기반 Grounded Chatbot을 완성하는 것이다.

```text
사용자 질문
→ 로그인 사용자 식별
→ 질문 scope/intent 분류
→ 대상 약 식별
→ 필요한 식약처 section 결정
→ 사용자 복약 데이터 조회
→ drug_info DB 우선 조회
→ 필요 시 식약처 API fallback
→ 근거 context 생성
→ LLM 답변 생성
→ 출처·조회 시점 포함
```

이 단계에서 중요한 원칙은 다음과 같다.

- LLM에는 질문에 필요한 근거만 전달한다.
- 근거가 없으면 추측하지 않는다.
- 복용 중단이나 용량 변경을 직접 지시하지 않는다.
- 응급 또는 심각한 증상이 의심되면 의료진 상담을 안내한다.
- 개인 데이터와 공공 의약품 데이터를 구분해 관리한다.

## 8. 2차 구현: RAG 문서·Chunk·Index Job 모델

Vector DB를 붙이기 전에 문서 모델을 먼저 설계한다.

테이블은 다음 세 개로 분리한다.

```text
rag_document
rag_chunk
rag_index_job
```

핵심 원칙은 문서 상태와 작업 상태를 분리하는 것이다.

> 문서의 유효 상태와 인덱싱 작업의 처리 상태는 의미와 변경 주기가 다르다고 판단해 별도로 관리한다.

### 8.1 rag_document

문서 원본과 버전을 관리한다.

```text
id
item_seq
drug_name
section_type
content
content_hash
document_version
source
fetched_at
lifecycle_status
created_at
updated_at
```

`lifecycle_status`:

```text
INDEXING
ACTIVE
OBSOLETE
DELETED
```

추천 제약:

```text
UNIQUE(item_seq, section_type, document_version)
```

### 8.2 rag_chunk

검색에 사용할 문단을 관리한다.

```text
id
document_id
chunk_index
content
chunk_hash
vector_id
created_at
```

추천 제약:

```text
UNIQUE(document_id, chunk_index)
```

### 8.3 rag_index_job

임베딩과 Vector DB 저장 작업의 진행 상태를 관리한다.

```text
id
chunk_id
job_status
attempt_count
next_retry_at
processing_started_at
claim_token
last_error_message
completed_at
created_at
updated_at
```

`job_status`:

```text
PENDING
PROCESSING
RETRY
COMPLETED
FAILED
```

추천 제약:

```text
UNIQUE(chunk_id)
```

## 9. 문서 변경 감지와 증분 인덱싱

문서 변경 감지는 Section 단위로 진행한다.

```text
documentKey = itemSeq + sectionType
contentHash = Section 전체 내용 hash
```

예:

```text
200003811 + EFFICACY
200003811 + USAGE
200003811 + SIDE_EFFECT
200003811 + WARNING
200003811 + INTERACTION
```

처리 흐름:

```text
식약처 데이터 수집
→ Section별 contentHash 계산
→ 기존 ACTIVE 문서 hash와 비교
```

변경 없음:

```text
기존 ACTIVE hash == 신규 Section hash
→ SKIP
```

변경 있음:

```text
기존 ACTIVE hash != 신규 Section hash
→ 새 documentVersion 생성
→ lifecycleStatus = INDEXING
→ 해당 Section의 Chunk 전체 재생성
→ rag_index_job 생성
→ 모든 Chunk 인덱싱 성공 후 ACTIVE 전환
```

Chunk 단위 diff까지 분석하면 복잡도가 커지므로, 초기 구현에서는 Section이 변경되면 해당 Section의 Chunk 전체를 새 버전으로 인덱싱한다. 그래도 약 전체가 아니라 변경된 Section만 처리하므로 증분 인덱싱으로 설명할 수 있다.

## 10. 새 버전 ACTIVE 전환

새 버전은 모든 Chunk가 Vector DB에 저장된 뒤에만 검색 대상으로 전환한다.

```text
새 rag_document 생성
→ lifecycleStatus = INDEXING
→ chunk 생성
→ index job 생성
→ 모든 job COMPLETED 확인
→ 새 document ACTIVE
→ 이전 ACTIVE document OBSOLETE
```

이 구조의 장점은 새 버전 인덱싱 중 일부 Chunk가 실패해도 기존 ACTIVE 버전이 계속 검색에 사용된다는 것이다.

잘못된 방식:

```text
Chunk 1 저장 성공
→ 바로 새 버전 검색 노출
Chunk 2 저장 실패
→ 일부 문단만 있는 불완전한 최신 버전 검색
```

목표 방식:

```text
새 버전 INDEXING
→ 모든 Chunk 저장 성공
→ 새 버전 ACTIVE
→ 이전 버전 OBSOLETE
```

## 11. Qdrant 연동 방식

Vector DB는 Qdrant를 우선 사용한다.

다만 Spring Boot 애플리케이션에서 Qdrant를 사용하는 방식은 다음 원칙으로 고정한다.

> 일반적인 문서 저장·검색·필터링은 Spring AI VectorStore로 처리하고, VectorStore로 해결하기 어려운 Qdrant 전용 관리 기능에만 Native QdrantClient를 사용한다.

### 11.1 Spring AI VectorStore의 역할

초기 구현의 기본 저장·검색 경로는 Spring AI `VectorStore`로 둔다.

담당 기능:

```text
Chunk를 Spring AI Document로 변환
Document 저장
EmbeddingModel을 통한 임베딩 생성
유사도 검색
metadata filter 적용
일반적인 필터 기반 삭제
```

예상 서비스:

```text
RagVectorStoreService
├─ saveChunks()
├─ searchSimilarChunks()
└─ deleteDocumentVersion()
```

이 서비스는 내부적으로 Spring AI `VectorStore`를 사용한다.

초기 단계에서는 별도의 `EmbeddingService`가 직접 벡터를 생성하지 않는다. `VectorStore.add()`와 `VectorStore.similaritySearch()`가 설정된 `EmbeddingModel`을 사용해 문서와 질문을 임베딩하도록 한다.

별도 `EmbeddingService`는 다음 요구가 생겼을 때 분리한다.

```text
Native QdrantClient로 직접 Vector를 Upsert해야 하는 경우
임베딩 요청 시간 측정이 필요한 경우
임베딩 재시도·Rate Limit 관리가 필요한 경우
임베딩 모델 버전 기록이 필요한 경우
Batch Embedding 또는 임베딩 캐싱이 필요한 경우
```

### 11.2 Native QdrantClient의 역할

Qdrant Native Client는 기본 저장 경로가 아니라, Qdrant 전용 관리 기능에 한해 사용한다.

담당 기능:

```text
Collection 생성·존재 확인
Collection 설정 확인
Payload Index 생성
Collection 상태 점검
고아 Point 정리
Qdrant 고유 옵션이 필요한 운영 작업
```

예상 컴포넌트:

```text
QdrantCollectionManager
├─ ensureCollection()
├─ ensurePayloadIndexes()
├─ checkCollectionStatus()
└─ cleanupOrphanPoints()
```

이 컴포넌트는 내부적으로 Native `QdrantClient`를 사용한다.

Spring AI의 `QdrantVectorStore`는 필요 시 내부 Native `QdrantClient`에 접근할 수 있으므로, 두 방식은 배타적이지 않다. 단, Native Client를 사용한다는 이유만으로 같은 Chunk를 중복 저장해서는 안 된다.

### 11.3 저장 경로 단일화 원칙

하나의 Chunk를 저장할 때는 저장 책임자를 하나로 유지한다.

금지되는 구조:

```text
RagIndexingService
├─ VectorStore.add()
└─ QdrantClient.upsert()
```

이 구조는 다음 문제를 만들 수 있다.

```text
임베딩 API가 두 번 호출될 수 있음
같은 Vector가 중복 저장될 수 있음
Point ID가 서로 달라질 수 있음
metadata 구조가 달라질 수 있음
삭제·검색 기준이 불명확해질 수 있음
```

초기 구현의 책임:

```text
저장 책임: Spring AI VectorStore
검색 책임: Spring AI VectorStore
관리 책임: 필요 시 Native QdrantClient
```

향후 요구가 생겨 Native 저장으로 전환할 경우:

```text
저장 책임: Native QdrantClient
검색 책임: VectorStore 또는 Native QdrantClient
관리 책임: Native QdrantClient
```

Native 저장으로 전환하더라도 기존 VectorStore 저장과 동시에 실행하지 않고, 저장 책임 자체를 교체한다.

### 11.4 Payload Index 기준

Qdrant에서는 자주 필터링하는 payload 필드에 index를 생성해 필터 검색 성능을 높일 수 있다.

우선 index 후보:

```text
itemSeq
sectionType
documentVersion
```

필요 시 추가 후보:

```text
documentId
```

초기에는 다음 필드에 payload index를 만들지 않는다.

```text
drugName
source
fetchedAt
chunkIndex
```

이 필드들은 실제 검색 조건으로 자주 사용되는지 확인한 뒤 결정한다.

### 11.5 표현상 주의

이 구조를 문서나 면접에서 “하이브리드 검색”이라고 부르지 않는다.

Qdrant에서 Hybrid Search는 보통 Dense Vector 검색과 Sparse Vector 검색을 결합하는 방식을 의미할 수 있기 때문이다.

대신 다음 표현을 사용한다.

```text
Spring AI VectorStore와 Qdrant Native Client를 역할별로 병행한 구조
```

또는:

```text
검색 추상화와 Vector DB 네이티브 제어를 분리한 구조
```

## 12. Vector DB 검색 기준

검색 가능 여부는 Vector DB의 lifecycle metadata만 믿지 않고, MySQL의 ACTIVE documentVersion을 기준으로 판단한다.

검색 흐름:

```text
질문 분석
→ 대상 itemSeq 결정
→ intent에 맞는 sectionType 결정
→ MySQL에서 ACTIVE documentVersion 조회
→ Vector DB에서 itemSeq + sectionType + documentVersion 필터 검색
→ 검색된 chunk를 LLM context에 전달
```

이 방식은 DB를 최신 버전의 기준 정보로 사용하기 때문에, Vector DB metadata 갱신 타이밍 문제를 줄일 수 있다.

Vector DB metadata에는 최소 다음 정보를 저장한다.

```text
itemSeq
drugName
sectionType
documentId
documentVersion
chunkIndex
source
fetchedAt
```

## 13. 인덱싱 작업 처리 방식

임베딩 API와 Vector DB 호출은 네트워크 작업이므로 DB 트랜잭션 안에서 수행하지 않는다.

나쁜 흐름:

```text
트랜잭션 시작
→ 작업 row lock
→ 임베딩 API 호출
→ Vector DB 저장
→ 상태 변경
→ 트랜잭션 종료
```

목표 흐름:

```text
짧은 트랜잭션 1:
PENDING/RETRY job 조회
→ PROCESSING으로 선점
→ claimToken 저장
→ processingStartedAt 저장
→ 트랜잭션 종료

트랜잭션 밖:
임베딩 API 호출
→ Vector DB upsert

짧은 트랜잭션 2:
jobId + claimToken 일치 확인
→ COMPLETED 또는 RETRY/FAILED 저장
```

이 구조는 복약 알림 배치에서 사용한 Lease·Claim Token 방식과 일관된다.

면접 설명:

> 외부 임베딩 호출 중 DB 락과 커넥션이 장시간 유지되지 않도록 작업 선점과 실제 처리를 분리했습니다. 처리 결과는 Claim Token이 일치하는 Worker만 저장하도록 해 오래된 작업 결과가 상태를 덮어쓰지 못하게 했습니다.

## 14. 실패 복구와 중복 방지

실패 처리:

```text
attemptCount < maxAttempts
→ RETRY
→ nextRetryAt 설정

attemptCount >= maxAttempts
→ FAILED
```

stale PROCESSING 복구:

```text
PROCESSING 상태가 lease-duration 초과
→ RETRY 또는 FAILED
```

중복 방지:

```text
PENDING/RETRY 작업을 PROCESSING으로 변경한 Worker만 처리
jobId + claimToken이 일치하는 Worker만 결과 저장
UNIQUE(chunk_id)로 동일 Chunk에 대한 Job 중복 생성 방지
UNIQUE(item_seq, section_type, document_version)으로 문서 버전 중복 방지
```

## 15. 설정값

RAG 인덱싱과 검색 관련 값은 하드코딩하지 않고 설정으로 분리한다.

예시:

```properties
medeat.rag.indexing.batch-size=50
medeat.rag.indexing.max-attempts=3
medeat.rag.indexing.lease-minutes=10
medeat.rag.indexing.retry-delay-minutes=5
medeat.rag.chunk.size=500
medeat.rag.chunk.overlap=50
medeat.rag.search.top-k=5
medeat.rag.drug-info-cache-ttl-days=30
```

설정으로 분리할 값:

- Chunk 크기
- Chunk overlap
- 검색 Top-K
- 한 번에 가져올 Job 수
- 최대 재시도 횟수
- Lease 제한시간
- 재시도 대기시간
- 식약처 DB 데이터의 오래됨 판단 기간

## 16. 의료 챗봇 안전장치

복약 정보를 다루기 때문에 LLM 답변에는 안전장치가 필요하다.

시스템 지시 원칙:

```text
제공된 사용자 복약 데이터와 식약처 근거만 사용한다.
근거에 없는 내용은 추측하지 않는다.
복용량 변경, 복용 중단을 직접 지시하지 않는다.
응급 증상이 의심되면 의료기관 또는 전문가 상담을 안내한다.
답변에 약품명, 품목기준코드, 출처, 조회 시점을 포함한다.
개인 데이터와 일반 의약품 정보를 구분한다.
```

근거가 없는 경우 답변 예시:

```text
확인 가능한 의약품 정보에서 관련 내용을 찾지 못했습니다.
정확한 복약 판단은 약사 또는 의료진에게 확인해 주세요.
```

## 17. 검색 품질·성능 평가

평가는 다음 네 묶음으로 나눈다.

### 17.1 분류 정확도

- 개인 질문·일반 질문 분류 정확도
- 질문 의도 분류 정확도

### 17.2 엔티티 식별 정확도

- 약 이름 식별 정확도
- itemSeq 선택 정확도
- 다중 후보 감지 성공 여부

### 17.3 검색 품질

- Recall@3
- Recall@5
- MRR
- 키워드 검색 대비 Vector 검색
- metadata filter 적용 전후 비교

Recall@3은 정답 문서가 검색 결과 상위 3개 안에 포함된 비율이다.

### 17.4 시스템 성능

- 평균 검색 시간
- P95 검색 시간
- 전체 인덱싱 시간
- 증분 인덱싱 시간
- Hash 비교 후 SKIP 비율
- 성공·실패·재시도 건수

초기 평가셋은 20~50개 질문으로 시작한다. 자동 평가가 어렵다면 답변 근거성은 수동 검증표로 관리한다.

수동 검증 항목:

- 답변 내용이 검색된 근거에 존재하는가
- 근거에 없는 내용을 만들지 않았는가
- 약품명과 출처가 일치하는가
- 복용 변경을 직접 지시하지 않았는가

## 18. 구현 우선순위

### 18.1 반드시 완성

```text
RDB 기반 Grounded Chatbot
개인 질문 / 일반 질문 분리
질문 의도별 Context Routing
출처·조회 시점·근거 부족 처리
rag_document / rag_chunk / rag_index_job
Vector DB 기본 저장·검색
Section contentHash 기반 SKIP
문서 상태 / 작업 상태 분리
실패 Job 재처리
간단한 평가셋과 Recall@K
```

### 18.2 가능하면 완성

```text
Claim Token 기반 작업 선점
오래된 PROCESSING 복구
새 버전 전체 완료 후 ACTIVE 전환
이전 버전 OBSOLETE 처리
전체/증분 인덱싱 성능 비교
P95 측정
```

### 18.3 후순위

```text
MRR
Batch 크기별 비교
Prometheus/Grafana
다중 Worker 부하 테스트
Vector DB별 성능 비교
복잡한 약 이름 정규화
정교한 답변 자동 평가
```

## 19. 최종 구현 순서

```text
1. 현재 챗봇 미커밋 변경 정리
2. RDB 기반 Grounded Chatbot 완성
3. 개인/일반 질문 scope 분리
4. intent 분리
5. 약품 후보 0/1/N개 처리
6. DB 우선 조회 + 식약처 API fallback
7. 근거 부족 응답, 출처/조회 시점
8. 테스트
9. 커밋

10. rag_document / rag_chunk / rag_index_job 추가
11. documentVersion, contentHash, lifecycleStatus, jobStatus 구현
12. Section 단위 SKIP 구현
13. 커밋

14. Embedding + Vector DB 저장/검색 연결
15. MySQL ACTIVE documentVersion 기준으로 Vector 검색
16. 개인/일반 질문별 metadata filter 적용
17. 커밋

18. 모든 Chunk 완료 후 ACTIVE 전환
19. 이전 버전 OBSOLETE
20. Claim Token 기반 선점
21. Retry / stale PROCESSING 복구
22. 커밋

23. 평가셋 작성
24. Recall@K, 검색 시간, SKIP 비율 측정
25. docs/chatbot-rag-architecture.md 작성
26. docs/chatbot-rag-performance.md 작성
27. 커밋
```

## 20. 커밋 단위 추천

```text
commit 1: complete RDB-grounded medication chatbot
commit 2: stabilize drug data cache and MFDS fallback
commit 3: add RAG document, chunk, and indexing job model
commit 4: integrate vector retrieval with metadata filters
commit 5: add incremental document version indexing
commit 6: add claim-based retry and stale job recovery
commit 7: add retrieval quality and performance evaluation
```

증분 인덱싱과 실패 복구는 하나의 커밋에 모두 넣기보다 분리한다. 코드 리뷰와 포트폴리오 설명이 쉬워지기 때문이다.

## 21. 최종 포트폴리오 문장

구현 완료 후 다음 문장으로 정리한다.

> GPT 기반 복약 챗봇을 개인 복약 데이터와 공공 의약품 데이터를 결합한 근거 기반 검색·응답 시스템으로 고도화했습니다. 등록 약과 복용 여부처럼 정확성이 필요한 데이터는 RDB에서 조회하고, 긴 부작용·주의사항 문서는 Section과 Chunk 단위로 가공해 Vector DB에서 의미 기반으로 검색했습니다. 식약처 데이터의 Section별 내용 Hash를 비교해 변경되지 않은 Section은 건너뛰고, 변경된 Section만 새 버전으로 인덱싱했습니다. 문서 유효 상태와 인덱싱 작업 상태를 분리하고, 모든 Chunk가 저장된 이후에만 새 버전을 검색 대상으로 전환했습니다. 인덱싱 작업에는 Claim Token 기반 선점, 실패 재시도, 오래된 PROCESSING 작업 복구를 적용했으며, Recall@K와 평균·P95 응답시간으로 검색 품질과 성능을 검증했습니다.

## 22. 진행 기록

### 22.1 RDB/API 기반 구조화 RAG 완료

커밋:

```text
def5131 feat: add grounded medication chatbot rag
```

완료 내용:

```text
사용자 등록 약 조회
오늘 복용 기록 조회
개인 복약 질문 / 일반 의약품 질문 분리
질문 의도별 식약처 section 선택
drug_info DB 우선 조회
필요 시 식약처 API fallback
근거 context 생성
출처와 조회 시점 응답
근거 부족 시 답변 제한
```

현재 이 단계는 Vector DB 기반 RAG가 아니라, RDB와 공공 API를 이용한 구조화 RAG 또는 RDB 기반 Grounded Chatbot으로 본다.

### 22.2 RAG 문서·Chunk·Index Job 모델 완료

커밋:

```text
1a4f128 feat: add drug rag indexing model
```

완료 내용:

```text
rag_document 테이블 추가
rag_chunk 테이블 추가
rag_index_job 테이블 추가
문서 lifecycleStatus 분리
작업 jobStatus 분리
DrugInfoDto를 Section 단위 문서로 변환
Section 전체 contentHash 계산
기존 ACTIVE 문서와 hash가 같으면 SKIP
변경된 Section은 새 documentVersion으로 INDEXING 문서 생성
Chunk 분리
각 Chunk에 대한 PENDING index job 생성
```

이 단계에서는 아직 Qdrant에 Vector를 저장하지 않는다. Qdrant 저장 전, 검색 가능한 문서·Chunk·작업 상태를 MySQL에 준비하는 단계다.

### 22.3 현재 진행 단계: Spring AI VectorStore + Qdrant 기본 연결

이번 단계의 목표:

```text
Spring AI Qdrant VectorStore 의존성 추가
Qdrant 연결 설정 추가
RagVectorStoreService 구현
rag_chunk를 Spring AI Document로 변환
VectorStore.add()로 Qdrant 저장
SearchRequest + metadata filter로 유사도 검색
documentVersion 기준 filter 삭제
```

이번 단계에서 지키는 원칙:

```text
저장 책임은 Spring AI VectorStore 하나로 둔다.
Native QdrantClient는 아직 저장 경로로 사용하지 않는다.
EmbeddingModel은 VectorStore 내부에서 사용하도록 둔다.
MySQL은 ACTIVE documentVersion과 논리 상태의 기준이다.
Qdrant는 Vector와 payload 검색 저장소로만 사용한다.
```

이번 단계에서 아직 하지 않는 것:

```text
Claim Token 기반 작업 선점
Retry / stale PROCESSING 복구
Payload Index 생성
새 버전 전체 완료 후 ACTIVE 전환
이전 버전 OBSOLETE 처리 자동화
검색 품질 평가셋 측정
```

### 22.4 Spring AI VectorStore + Qdrant 기본 연결 완료

완료 내용:

```text
Spring AI BOM 1.1.8 추가
spring-ai-starter-vector-store-qdrant 의존성 추가
Qdrant 연결 설정 외부화
RagVectorStoreService 추가
PENDING rag_index_job 대상 Chunk 조회
rag_chunk를 Spring AI Document로 변환
itemSeq, sectionType, documentVersion, ragDocumentId, ragChunkId 등 metadata 구성
VectorStore.add()를 통한 저장 경로 구현
저장 성공 후 rag_chunk.vector_id 반영
저장 성공 후 rag_index_job COMPLETED 처리
SearchRequest 기반 similaritySearch 구현
itemSeq + sectionType + documentVersion metadata filter 적용
documentVersion 기준 VectorStore 삭제 경로 구현
VectorStore 저장·검색·삭제 단위 테스트 추가
```

Spring AI 버전은 현재 프로젝트의 Spring Boot 3.5.x 계열과의 호환성을 고려해 1.1.x 계열로 적용했다. Spring AI 2.x 문서는 Spring Boot 4.x 이상을 기준으로 하므로, 지금 프로젝트에는 바로 올리지 않는다.

이번 구현의 범위:

```text
Qdrant에 저장할 Document와 metadata 구조를 확정한다.
Spring AI VectorStore를 단일 저장·검색 경로로 사용한다.
Qdrant Native Client는 아직 사용하지 않는다.
EmbeddingModel은 VectorStore가 사용할 Bean으로 분리해 둔다.
실제 런타임 사용 시 Qdrant 서버와 EmbeddingModel 설정이 필요하다.
```

## 23. 현재 단계의 결론

현재는 RDB/API 기반 구조화 RAG, RAG 문서 모델, Spring AI VectorStore 기반 Qdrant 저장·검색 진입점까지 완료되었다.

다음 작업의 시작점은 다음이다.

```text
Qdrant 서버 실행 환경 정리
EmbeddingModel 설정 추가
실제 Qdrant 연동 테스트
인덱싱 작업 Claim Token 선점
Retry / stale PROCESSING 복구
새 documentVersion 전체 완료 후 ACTIVE 전환
이전 documentVersion OBSOLETE 처리
```
