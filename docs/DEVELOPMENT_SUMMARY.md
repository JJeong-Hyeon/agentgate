# AgentGate v2 개발 정리

기존 AgentGate(Policy / Risk / Approval / Audit)를 유지한 채, **Visual Workflow Builder + LangGraph Runtime + Local LLM + MCP**를 붙여 "GUI로 만든 AI Agent 워크플로를 AgentGate 거버넌스 아래에서 실행"하는 플랫폼으로 확장한 작업 정리.

- 기준 설계: [`ARCHITECTURE.md`](ARCHITECTURE.md)
- 범위: PR #41 ~ #79 (20개 PR, 모두 일반 merge)

---

## 1. 결과 요약

### 완성된 흐름 (MVP 성공 기준)

```text
① GUI(Workflow Builder)에서 워크플로 구성
② AgentGate에 버전 단위로 저장 (Runtime이 DSL 검증)
③ 화면에서 작업 입력 → 실행 시작
④ Runtime이 DSL을 LangGraph로 컴파일해 Local LLM(Ollama)으로 실행
⑤ Tool(HTTP / MCP) 호출 직전 AgentGate가 Policy / Risk 검사
⑥ 위험하면 승인 대기로 멈춤 (LangGraph interrupt)
⑦ 화면에서 승인 / 거절
⑧ AgentGate가 Runtime에 재개 요청 → 이어서 실행
⑨ 실행 / 노드별 기록 저장 + 실시간(SSE)으로 화면에 표시
```

### 구성

```text
브라우저 (React + React Flow)
   │  Basic 인증, /api, SSE
   ▼
AgentGate (Spring Boot)  ── Policy / Risk / Approval / Audit / Workflow / Execution
   │  ▲
   │  │ 행동 평가(/api/v1/actions), 실행 이벤트
   ▼  │
Runtime (Python, FastAPI + LangGraph) ── DSL 검증·컴파일·실행, checkpoint(Postgres)
   ├─ LLM Gateway → Ollama / vLLM (OpenAI 호환)
   └─ Tool → HTTP / MCP 서버  (항상 AgentGate 검사 후 실행)
```

### 규모

| 영역 | 내용 | 테스트 |
|---|---|---|
| Spring (`src/`) | Workflow / Execution 도메인 추가, Approval·Action 확장, SPA 제공 | 127개 |
| Runtime (`runtime/`) | 신규 Python 서비스 | 105개 (+ 통합 2개) |
| Frontend (`frontend/`) | 신규 React 앱 | 26개 |
| E2E (`e2e/`) | Spring + Runtime 실제 기동 | 8개 시나리오 |
| CI | `test`, `runtime`, `frontend`, `e2e` 4개 job | |

---

## 2. 단계별 작업

| 단계 | PR | 내용 |
|---|---|---|
| 문서 정리 | #41 | 프로젝트 수정안을 `ARCHITECTURE.md`로 정리, n8n 기반 문서 정리 |
| **Phase 2** Runtime | #43 | Python Runtime 골격 (FastAPI, LangGraph, Dockerfile, compose, CI) |
| | #45 | LLM Gateway (Ollama / vLLM 공용, OpenAI 호환) + `/llm/health` |
| | #47 | 첫 LangGraph 그래프 (Planner → Researcher → Reviewer), Postgres checkpoint |
| **Phase 3** AgentGate 연결 | #50 | (버그) Redis 정책 캐시 역직렬화 오류 수정 |
| | #51 | HTTP Tool: 실행 전 AgentGate 검사, fail-closed |
| | #53 | 승인 요청과 Runtime 실행 연결 (`executionId`) |
| | #55 | 승인 / 거절 후 실행 자동 재개 (interrupt / resume, 재시도) |
| | #57 | E2E 테스트 (CI) |
| **Phase 4** Workflow DSL | #59 | DSL 스키마 + 검증기 |
| | #61 | DSL → LangGraph Compiler |
| | #63 | Workflow 버전 저장 API (Spring) |
| | #65 | Execution API + 실시간 이벤트(SSE) |
| **Phase 5** Frontend | #67 | React + React Flow 기본 구조, 로그인, 목록 화면 |
| | #69 | Workflow Builder |
| | #71 | Execution Studio |
| 추가 기능 | #73 | APPROVAL 노드 (위험도와 무관한 사람 승인) |
| 정리 | #75 | 코드로 짠 리서치 그래프 제거 (DSL로 일원화) |
| 배포 | #77 | AWS 배포에 Runtime EC2 + 프론트엔드 추가 |
| **Phase 6** MCP | #79 | MCP Tool 노드 |

---

## 3. 구성요소별 상세

### 3.1 AgentGate (Spring Boot)

기존 거버넌스 기능은 유지하고 다음을 추가 / 확장.

**행동 평가 `POST /api/v1/actions`** — 선택 필드 추가 (기존 클라이언트 호환)
- `executionId`: 승인 요청을 Runtime 실행과 연결
- `requireApproval`: 정책상 허용이어도 승인 요청 생성 (`BLOCKED`는 그대로 차단)
- `reason`: 승인자에게 보여줄 설명

**승인 → Runtime 재개**
- 승인 / 거절이 커밋된 직후 Runtime에 resume 요청 (`X-Runtime-Token` 공유 토큰)
- 실패 시 30초마다 재시도, 전달 시각(`runtime_notified_at`) 기록
- `GET /api/v1/approvals?executionId=` 필터

**Workflow 버전 저장** `/api/v1/workflows`
- 버전은 불변, DSL은 Postgres `jsonb`
- 저장 전 Runtime이 검증 → 실패 시 422 + 노드별 오류 목록
- 새 버전 번호는 비관적 락으로 중복 방지 (동시성 테스트 포함)

**Execution** `/api/v1/executions`
- 저장된 버전으로 Runtime 백그라운드 실행 시작
- Runtime 이벤트 수신(`/events`, Runtime 토큰) → 실행 / 노드별 기록
- `GET /{id}/stream`: SSE (snapshot 후 update)

**프론트엔드 제공**
- `./gradlew bootJar -PwithFrontend`로 UI를 jar에 포함, SPA 라우트 제공

### 3.2 Runtime (Python)

```text
runtime/app/
├── main.py            앱 기동 (checkpointer, compiler, runner, event reporter)
├── executions.py      실행 / 조회 / 재개 API
├── workflows.py       DSL 검증 / JSON Schema API
├── events.py          LangGraph tasks 스트림 → AgentGate 이벤트 보고
├── runner.py          실행별 그래프 복원 (state에 저장된 DSL로 재컴파일, 캐시)
├── dsl/               schema.py · validator.py · compiler.py
├── nodes/             tool.py (검사→승인 대기→실행) · approval.py
├── tools/             base.py (GovernedTool) · http.py · mcp.py
├── governance/        agentgate_client.py
├── llm/               gateway.py
└── graph/checkpointer.py
```

**Workflow DSL 노드**

| 노드 | 동작 |
|---|---|
| `START` / `END` | 시작 / 종료 |
| `LLM` / `AGENT` | 프롬프트(`{task}`, `{노드id}` 치환) → LLM 호출, 결과를 `state[노드id]`에 저장 |
| `ROUTER` | LLM이 route 선택 → 해당 label의 edge로 분기 |
| `REVIEWER` | `VERDICT: APPROVE / REVISE` 판정, `maxRevisions`만큼 재시도 후 종료 |
| `CONDITION` | 상태 값으로 분기 |
| `HTTP_TOOL` | AgentGate 검사 후 HTTP 호출 |
| `MCP_TOOL` | AgentGate 검사 후 MCP 서버 도구 호출 (stdio / Streamable HTTP) |
| `APPROVAL` | 사람 승인 요청, 승인 시 진행 / 거절·차단 시 종료 |

- 병렬: 한 노드에서 label 없는 edge 여러 개 / 반복: REVIEWER를 거치는 cycle (없는 cycle은 검증에서 거부)
- 검증기는 오류를 모두 모아 노드 id와 함께 반환 (GUI에서 노드 강조용)

**Tool 거버넌스 (HTTP / MCP 공통)**

```text
검사(AgentGate)
 ├─ ALLOWED           → 실행
 ├─ APPROVAL_REQUIRED → interrupt → 승인 시 실행 / 거절 시 REJECTED
 └─ BLOCKED / 실패     → 실행하지 않음 (fail-closed)
```

LangGraph는 재개 시 멈춘 노드를 처음부터 다시 실행하므로, 검사 결과를 먼저 상태에 저장하고 대기를 별도 노드로 분리해 AgentGate 중복 호출을 막음.

### 3.3 Frontend (React + TypeScript + React Flow)

| 화면 | 기능 |
|---|---|
| 로그인 | 관리자 Basic 인증 (탭 세션 동안만 보관) |
| Workflows | 목록, 그래프 미리보기, 바로 실행 |
| Builder | 팔레트로 노드 추가, 드래그 배치, 연결, 인스펙터로 설정 편집, id 변경 시 참조 자동 갱신, 저장 시 검증 오류를 노드에 표시 |
| Execution Studio | 노드별 상태(실행 중 / 승인 대기 / 완료 / 실패)를 그래프에 실시간 표시, 승인 패널, 단계 타임라인(시간·출력·오류) |
| Executions / Approvals | 실행 목록, 승인 대기 목록에서 승인 / 거절 |

- SSE는 인증 헤더가 필요해 EventSource 대신 fetch 스트림 + 자체 파서, 끊기면 재연결
- 되돌아가는 edge(REVISE 등)는 노드 아래로 돌아가는 점선으로 표시

### 3.4 배포 (AWS, Terraform)

```text
ALB ─▶ App EC2 (AgentGate jar + UI, Redis) ⇄ Runtime EC2 (Runtime + Ollama) ─▶ RDS
```

- Runtime EC2 추가 (`t4g.large` 기본, CPU 추론이라 3B급 모델 권장)
- 배포 스크립트: `scripts/deploy-app.sh`(jar 빌드·업로드·재시작), `scripts/deploy-runtime.sh`(소스 동기화·컨테이너 빌드·모델 받기)
- 서버에서 직접 빌드하던 방식(`gradlew bootRun`)을 로컬 빌드 jar + `java -jar`로 변경
- 절차: [`DEPLOYMENT.md`](DEPLOYMENT.md)

---

## 4. 작업 중 발견해 고친 문제

| 문제 | 원인 | 조치 |
|---|---|---|
| 정책 캐시가 채워진 뒤 행동 평가가 전부 500 | Redis 직렬화기에 타입 정보가 없어 `LinkedHashMap`으로 복원 | `List<Policy>` 타입 지정 직렬화기 (#50) |
| 승인 후 Runtime 전달 기록이 저장되지 않음 | 커밋 후 리스너에서 트랜잭션이 이미 끝난 트랜잭션에 합류 | `REQUIRES_NEW` (#55) |
| 동시 새 버전 저장 시 500 | Hibernate가 INSERT를 UPDATE보다 먼저 실행해 낙관적 락보다 유니크 제약 위반이 먼저 발생 | 비관적 락으로 변경, 락 제거 시 실패하는 테스트 추가 (#63) |
| 실행 중 그래프 노드가 화면에서 사라짐 | 갱신마다 노드 객체를 새로 만들어 React Flow 측정값이 사라짐 | React Flow 노드 상태 유지 (#71, 실제 브라우저 확인 중 발견) |
| 비밀번호 오류 시 브라우저 기본 로그인 팝업 | 401 응답의 `WWW-Authenticate` 헤더 | `X-Requested-With` 헤더 추가 (#77) |

---

## 5. 실행 방법 (로컬)

```bash
docker compose up -d                                   # postgres, redis, prometheus, grafana
AGENTGATE_RUNTIME_BASE_URL=http://localhost:8000 \
AGENTGATE_RUNTIME_TOKEN=dev-runtime-token ./gradlew bootRun

# Runtime 전용 Agent 등록 후 받은 apiKey로
AGENTGATE_API_KEY=<apiKey> docker compose --profile runtime up -d --build   # runtime + ollama
docker compose exec ollama ollama pull qwen2.5:7b

cd frontend && npm install && npm run dev               # http://localhost:5173 (admin / changeme)
```

테스트: `./gradlew test`, `runtime/`에서 `pytest`, `frontend/`에서 `npm test`, 전체 E2E는 `PYTHON=python3.12 ./scripts/e2e.sh`.

---

## 6. 남은 과제

| 항목 | 내용 |
|---|---|
| HTTPS | ALB가 HTTP만 열려 있어 로그인 정보가 평문 전송됨. 도메인 + ACM 인증서로 HTTPS 리스너 추가 필요 |
| 다중 인스턴스 SSE | SSE 구독자가 인스턴스 메모리에 있음. 수평 확장 시 Redis Pub/Sub 등 필요 |
| MCP 인자 타입 | 인자를 문자열로만 전달. 숫자 / 객체 인자가 필요한 도구 지원 |
| MCP 도구 목록 | Builder에서 서버 / 도구를 직접 입력. Runtime에서 도구 목록을 받아 선택하게 개선 가능 |
| Phase 7 | 관측성(Langfuse, OpenTelemetry), RAG / pgvector, Memory, vLLM 운영 전환 |
