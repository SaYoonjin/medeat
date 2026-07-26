# RAG 검색 품질·지연시간 벤치마크

## 목적

의약품 RAG 검색이 질문과 관련된 근거 Chunk를 상위 K개 안에서 얼마나 잘 찾는지와
검색 요청의 응답시간을 동일한 평가셋으로 반복 측정한다.

측정 지표는 다음과 같다.

- Recall@1, Recall@3, Recall@5
- MRR(Mean Reciprocal Rank)
- 평균 검색시간
- P50, P95, P99 검색시간

답변 생성 모델의 품질이나 전체 챗봇 응답시간은 이 벤치마크의 측정 범위에 포함되지
않는다. 이 벤치마크는 임베딩 생성과 Qdrant 유사도 검색을 포함한 검색 단계만 측정한다.

## 평가 데이터 작성

평가셋 템플릿을 복사한 뒤 실제 데이터에 맞게 수정한다.

각 평가 항목에는 다음 값을 입력한다.

| 필드 | 설명 |
|---|---|
| `id` | 평가 항목을 구분하는 고유 이름 |
| `query` | 실제 사용자가 입력할 수 있는 자연어 질문 |
| `itemSeq` | 평가할 의약품의 품목기준코드 |
| `sectionType` | 검색할 문서 영역 |
| `documentVersion` | 선택 항목. 생략하면 RDB의 현재 ACTIVE 버전 사용 |
| `relevantChunkIds` | 사람이 정답으로 판정한 Chunk ID 목록 |
| `relevantTextContains` | 정답 Chunk에 반드시 포함되어야 하는 핵심 문구 목록 |
| `notes` | 동의어, 정답 선정 이유 등 검수 메모 |

`relevantChunkIds`와 `relevantTextContains` 중 하나 이상을 반드시 입력해야 한다.
두 필드를 모두 입력하면 각각을 독립된 정답 판정으로 계산한다.

Chunk ID는 판정이 명확하지만 문서를 다시 인덱싱하면 바뀔 수 있다. 핵심 문구 판정은
버전 변경에 더 안정적이지만 너무 짧거나 일반적인 단어를 사용하면 오탐이 생길 수 있다.
운영 평가셋에서는 가능하면 검수된 Chunk ID와 구체적인 핵심 문구를 함께 관리한다.

초기 평가셋은 효능, 복용법, 경고, 주의사항, 상호작용, 부작용, 보관방법을 고르게
포함하고 다음 유형의 질문을 섞어 작성한다.

- 식약처 원문과 동일한 표현의 질문
- “속이 울렁거려요”처럼 동의어나 일상 표현을 사용한 질문
- 여러 근거 Chunk가 모두 정답인 질문
- 관련 근거가 문서 뒤쪽에 위치한 질문
- 유사한 증상이나 용법이 있어 다른 Chunk와 혼동하기 쉬운 질문

템플릿의 품목기준코드와 정답 문구는 형식을 보여주기 위한 예시이므로 실제 측정 전에
반드시 현재 RAG 데이터와 대조해 검수한다.

## 실행 전 조건

- Java 17 이상과 Maven
- 실행 가능한 MySQL
- ACTIVE 상태의 RAG 문서와 Chunk
- 실행 가능한 Qdrant와 해당 벡터 컬렉션
- 임베딩 API 인증 정보
- RAG 관련 환경 변수

## 실행

프로젝트의 백엔드 디렉터리에서 다음과 같이 실행한다.

```powershell
mvn "-Dtest=RagRetrievalBenchmarkTest" `
  "-Dmedeat.rag.benchmark.enabled=true" `
  "-Dmedeat.rag.benchmark.dataset=C:\absolute\path\evaluation-dataset.json" `
  "-Dmedeat.rag.benchmark.warmups=2" `
  "-Dmedeat.rag.benchmark.repetitions=10" `
  "-Dmedeat.rag.benchmark.top-k=5" `
  test
```

평가셋 경로를 생략하면 테스트 리소스에 포함된 템플릿을 사용한다. 템플릿을 실제
정답 데이터로 검수하지 않은 상태에서는 결과를 공식 성능 수치로 사용하면 안 된다.

기본 결과 경로는 다음과 같다.

```text
target/rag-benchmark/result.json
target/rag-benchmark/result.md
```

결과 디렉터리는 다음 시스템 속성으로 변경할 수 있다.

```text
-Dmedeat.rag.benchmark.output-dir=C:\absolute\path\rag-result
```

## 지표 해석

Recall@K는 평가셋에 정의된 전체 정답 중 상위 K개 검색 결과 안에서 찾은 정답의
비율이다. 정답이 두 개이고 상위 5개 안에서 하나만 찾았다면 Recall@5는 0.5다.

MRR은 가장 먼저 등장한 정답의 순위에 역수를 적용한 값이다. 첫 번째 결과가
정답이면 1, 두 번째가 첫 정답이면 0.5이며 정답을 찾지 못하면 0이다.

지연시간은 준비 호출을 제외한 실제 반복 검색 요청을 대상으로 측정한다. P95는 전체
측정 요청의 95%가 해당 시간 이내에 완료되었음을 의미한다.

성능 비교 시에는 같은 평가셋, 같은 임베딩 모델, 같은 Qdrant 데이터, 같은 반복 횟수와
같은 시스템 부하 조건을 유지해야 한다.

## 인덱싱 처리량 측정

인덱싱 벤치마크는 이미 생성되어 있는 `PENDING` Chunk를 실제 임베딩 API와 Qdrant에
저장하면서 총 소요시간과 초당 처리 Chunk 수를 측정한다.

```powershell
mvn "-Dtest=RagIndexingBenchmarkTest" `
  "-Dmedeat.rag.indexing-benchmark.enabled=true" `
  "-Dmedeat.rag.indexing-benchmark.batch-size=50" `
  "-Dmedeat.rag.indexing-benchmark.max-chunks=1000" `
  test
```

결과는 기본적으로 다음 위치에 기록된다.

```text
target/rag-benchmark/indexing-result.json
```

이 벤치마크는 실제 인덱싱 작업 상태를 `COMPLETED`로 변경하고 Qdrant에 벡터를
저장한다. 따라서 운영 DB에서 실행하지 말고 별도의 성능 측정 DB와 컬렉션을 사용한다.

데이터 규모나 Batch Size를 비교하려면 매 실행 전에 동일한 원본 데이터로 DB와
Qdrant 컬렉션을 초기화해야 한다. 예를 들어 100, 500, 1,000, 5,000개 Chunk를 각각
준비한 뒤 Batch Size 10, 50, 100을 같은 조건으로 실행하면 규모별 총 인덱싱 시간과
처리량을 비교할 수 있다.
