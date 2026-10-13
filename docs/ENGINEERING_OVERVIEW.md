# AgentGate: Engineering Overview

> AI Agent의 도구 실행을 정책·위험도·사람 승인으로 통제하는 **Agent Governance Platform**.
> 시스템 구조, 주요 설계 결정과 근거, 품질·운영 현황을 정리한 기술 문서입니다.

| 항목 | 내용 |
|---|---|
| 구성 | AgentGate (Spring Boot, Java 21) · Agent Runtime (Python, FastAPI + LangGraph) · Web Console (React + TypeScript + React Flow) |
| 저장소 | PostgreSQL (업무 데이터 + LangGraph checkpoint), Redis (정책 캐시) |
| LLM | OpenAI 호환 엔드포인트 (Ollama 개발 / vLLM 운영, NVIDIA DGX Spark 대상) |
| 도구 연동 | MCP (Streamable HTTP / stdio), HTTP |
| 배포 | AWS (ALB + EC2 + RDS, Terraform), Docker Compose |
| 테스트 | Spring 188 · Runtime 165 · Frontend 60 · E2E 12, CI 4개 job |

---

## 1. 문제 정의와 설계 원칙

LLM Agent가 메일 발송, 데이터 변경, 외부 API 호출 같은 **실제 행동**을 하게 되면 다음 문제가 생깁니다.

- **과도한 권한:** Agent가 필요 이상의 도구에 접근할 수 있습니다.
- **의도치 않은 실행:** 모델이 지시를 잘못 해석하거나, 같은 행동을 반복합니다.
- **추적 불가:** 누가, 왜, 무엇을 했는지 감사하기 어렵습니다.

AgentGate는 다음 원칙으로 설계했습니다.

1. **Governance는 도구 실행 직전에 둔다.** LLM의 응답이 아니라, 외부 시스템에 영향을 주는 Tool 호출 직전에 판정합니다. 모든 Tool 실행 경로가 이 판정을 거치며, 우회 경로는 두지 않습니다.
2. **Fail-closed.** 판정을 받지 못하면(AgentGate 장애, 오류) 실행하지 않습니다.
3. **판정은 설명 가능해야 한다.** 모든 판정에 근거(`basis`)를 남기고, 승인자에게 "어떤 Agent가, 무엇을, 어떤 인자로, 누구의 위임으로" 하려는지 보여줍니다.
4. **Control Plane과 Execution Plane을 분리한다.** 정의·정책·기록은 AgentGate가, 실행은 Runtime이 맡습니다. Runtime은 상태를 checkpoint로만 가집니다.
5. **재현 가능성.** 실행할 때 Workflow 버전과 Agent 정의 버전을 고정하고 기록합니다.

---

## 2. 시스템 아키텍처

```text
 Web Console (React)
   Workflow Builder · Agents · Tools · Execution Studio · Approvals
        │ REST (Basic) / SSE
        ▼
 AgentGate (Spring Boot) ─────────────────────────── Control + Governance Plane
   Workflow·Version │ Agent·Definition │ Policy·Risk │ Approval │ Audit
   Execution 기록·SSE │ MCP Server Registry (암호화 자격증명) │ Tool Risk Registry
        │  ▲
  실행 요청│  │ 행동 평가 (POST /api/v1/actions), 진행 이벤트, 서버 목록
  재개 콜백▼  │ (공유 Runtime 토큰)
 Agent Runtime (FastAPI + LangGraph) ─────────────── Execution Plane
   DSL 검증·컴파일 → StateGraph │ tool-calling Agent loop │ 위임(서브그래프)
   checkpoint(PostgreSQL) · interrupt/resume │ Tool Catalog
        │                         │ (ALLOWED일 때만)
        ▼                         ▼
   LLM Gateway                Tool Executor
   (Ollama / vLLM)            (MCP · HTTP)
```

### 2.1 실행 흐름 (Human-in-the-loop 포함)

```text
1. Console에서 Workflow 실행 요청
2. AgentGate: Workflow 버전 + Agent 정의 버전을 고정해 Runtime에 전달, 실행 레코드 생성
3. Runtime: DSL → LangGraph 컴파일, 실행
4. Agent가 Tool 호출을 결정 → AgentGate에 행동 평가 요청 (Agent 이름·정의 버전·위임 경로 포함)
5. AgentGate 판정
   ├─ ALLOWED            → Runtime이 Tool 실행
   ├─ APPROVAL_REQUIRED  → LangGraph interrupt, checkpoint 저장, 실행 WAITING_APPROVAL
   │      승인자 결정 → AgentGate가 Runtime에 재개 콜백 (실패 시 재시도)
   │      → 멈춘 지점부터 재개 (이미 실행된 LLM 단계는 재실행하지 않음)
   └─ BLOCKED            → 실행하지 않음, 사유를 Agent에게 전달
6. 단계별 이벤트 → AgentGate 기록 → SSE로 Console에 실시간 표시
```

---

## 3. Governance 모델

### 3.1 판정 파이프라인

```text
Tool Request (agentId, action, labels, agentVersion, delegatedBy)
  │
  ├─ 1. Agent 권한 (정의 버전이 있는 호출)
  │     정의에 없는 Tool/위임 → BLOCKED      (TOOL_NOT_GRANTED)
  │     권한 BLOCKED          → BLOCKED      (TOOL_BLOCKED)
  │
  ├─ 2. Policy 매칭 → Risk Level  (행동+라벨 > 행동 > 라벨 순의 구체성)
  │     LOW / MEDIUM → ALLOWED, HIGH → APPROVAL_REQUIRED, BLOCKED → BLOCKED
  │     정책 없음: 일반 행동 HIGH, 위임(AGENT:*) MEDIUM
  │
  ├─ 3. Agent 위험도 상한(maxRiskLevel) 초과 → BLOCKED   (AGENT_RISK_CAP)
  │
  └─ 4. 정책상 허용이어도 권한이 APPROVAL → APPROVAL_REQUIRED (TOOL_REQUIRES_APPROVAL)
```

- 모든 판정은 **Audit Log**에 불변 레코드로 남깁니다. 레코드에는 행동, 대상, 라벨, 위험도, 결과, 판정 근거(`basis`), 위임 경로(`delegatedBy`), 승인 ID가 들어갑니다.
- 정책은 Redis에 캐시하고, 정책이 바뀌면 즉시 무효화합니다.

### 3.2 정책 기본값과 그 근거

| 대상 | 정책이 없을 때 | 근거 |
|---|---|---|
| 일반 행동 / MCP Tool | `HIGH` (승인 필요) | 등록되지 않은 행동은 안전한 쪽으로 처리합니다 |
| Agent 위임 (`AGENT:<id>`) | `MEDIUM` (허용) | 위임 자체는 외부 시스템에 영향을 주지 않고, 위임받은 Agent의 실제 Tool 호출이 따로 판정됩니다 |
| 새로 고른 Tool의 Agent 권한 | `APPROVAL` (서버가 읽기 전용이라 알린 Tool만 `AUTO`) | 기본은 보수적으로 두고, 관리자가 완화합니다 |

**Tool 위험도 레지스트리**는 MCP Tool별 위험도를 정책(`MCP:<server>:<tool>`)으로 관리합니다.
- 서버가 보고하는 annotations(`readOnlyHint`, `destructiveHint`)로 추천값을 계산합니다.
- annotations는 서버의 자기 신고값이라 신뢰할 수 없습니다. 그래서 **자동으로 적용하지 않고, 관리자가 확인한 뒤 적용**합니다.

### 3.3 실행 수준의 거버넌스

- **Workflow Tool 노드가 거절·차단되면:** 기본적으로 실행을 `STOPPED`로 중단하고 사유를 남깁니다. 노드 옵션 `onDenied: CONTINUE`로 계속 진행하게 바꿀 수 있습니다.
- **Agent 안의 Tool 호출이 거절·차단되면:** 사유를 Tool 결과로 LLM에 돌려주고, Agent가 다른 방법으로 계속 진행하게 합니다.
- **APPROVAL 노드:** 위험도와 무관하게 사람의 승인을 강제합니다.

---

## 4. Agent 실행 모델

### 4.1 Agent Definition (버전 관리)

Agent는 거버넌스 신원(API Key, 정책, 위험도 상한)과 실행 정의를 함께 가집니다. 정의는 저장할 때마다 **불변 버전**으로 쌓입니다.

```text
Agent Definition vN
├── systemPrompt · model · temperature
├── tools[]       MCP server/tool · permission(AUTO | APPROVAL | BLOCKED) · labels
├── delegates[]   위임 가능한 Agent · permission
├── maxSteps      LLM 턴 상한 (무한 루프 방지)
├── outputSchema  최종 답변 JSON Schema (선택)
└── toolCalling   NATIVE | JSON
```

- 실행을 시작할 때 AgentGate가 정의 버전을 **고정**하고(위임 대상까지 전이적으로) 실행 레코드에 `agentVersions`로 기록합니다.
- Workflow를 저장할 때와 실행을 시작할 때, 없는 Agent·없는 버전·버전 충돌을 노드 단위 오류로 거부합니다.

### 4.2 tool-calling Agent loop (LangGraph)

```text
<agent> → think(LLM) ─ tool calls ─→ gate(AgentGate) ─ ALLOWED ─→ execute ─┐
              │                          ├─ APPROVAL_REQUIRED → approval(interrupt)
              └─ answer → 다음 노드      └─ BLOCKED/FAILED → 사유를 LLM에 전달
                                         (모든 호출 처리 후 think로 복귀)
```

- **판정을 두 번 받지 않는 구조:** LangGraph는 재개할 때 멈춘 노드를 처음부터 다시 실행합니다. 그래서 판정(gate), 대기(approval), 실행(execute)을 각각 다른 노드로 나눴습니다. 재개해도 AgentGate에 같은 요청을 다시 보내지 않습니다.
- **재개에 필요한 정보를 state에 둠:** 실행을 시작할 때 Tool 설명과 입력 스키마를 실행 state에 저장합니다. 승인 대기 중에 MCP 서버가 내려가거나 Runtime이 재시작해도 checkpoint만으로 그래프를 복원합니다.
- **두 가지 Tool 호출 방식:** OpenAI function calling(`NATIVE`)을 쓰고, 이를 지원하지 않는 모델에는 프롬프트 기반 JSON 방식(`JSON`)을 씁니다. 어느 쪽이든 거버넌스 흐름은 같습니다.
- **구조화된 출력:** `outputSchema`(JSON Schema Draft 2020-12)로 최종 답변을 검증합니다. 맞지 않으면 한 번 재요청하고, 그래도 맞지 않으면 노드를 실패시킵니다. 후속 시스템이 결과를 신뢰할 수 있게 하려는 것입니다.

### 4.3 Multi-Agent: 위임 (agent-as-tool)

```text
Supervisor ── delegate__research(task) ──▶ AgentGate: AGENT:research (Supervisor 권한)
                                               │ 허용
                                               ▼
                          Research Agent (LangGraph 서브그래프)
                          자기 정의 · 자기 Tool · 자기 이름으로 판정 (delegatedBy=supervisor)
                                               │
                          결과 ◀───────────────┘  (Supervisor에게는 Tool 결과로 전달)
```

- **권한이 섞이지 않습니다.** Worker의 Tool 호출은 Worker 이름으로 판정됩니다. Supervisor가 Worker를 통해 자기에게 없는 권한을 우회할 수 없습니다.
- **중첩된 승인 대기:** Worker 안의 승인 대기는 실행 전체를 멈추고, 재개하면 Worker의 중단 지점부터 이어집니다. 서브그래프 checkpoint namespace를 이용합니다. 구현 전에 2단계 중첩에서 재실행 범위를 실험으로 확인했습니다.
- **안전장치:** 위임 순환과 3단계를 넘는 위임은 정의를 저장할 때(AgentGate)와 컴파일할 때(Runtime) 두 번 거부합니다.
- **추적:** 위임 경로(`lead > research`)가 Audit, 승인 화면, Execution Studio에 남습니다.

---

## 5. Workflow (Visual Builder → DSL → LangGraph)

- **DSL 분리:** Workflow DSL(JSON)이 GUI와 Runtime 사이의 계약입니다. React Flow 데이터를 Runtime에 직접 넘기지 않습니다.
- **노드 종류:** `START/END`, `LLM`, `AGENT`, `ROUTER`, `REVIEWER`(APPROVE/REVISE 루프), `CONDITION`, `HTTP_TOOL`, `MCP_TOOL`, `APPROVAL`
- **병렬과 반복:** 병렬은 label 없는 edge를 여러 개 두는 것으로, 반복은 REVIEWER를 거치는 cycle로 표현합니다. REVIEWER가 없는 cycle은 무한 루프가 되므로 검증에서 거부합니다.
- **검증:** 오류를 노드 단위로 모두 모아 반환하고, Builder가 해당 노드를 강조합니다.
- **버전:** Workflow 버전은 불변(PostgreSQL `jsonb`)입니다. 버전 번호는 비관적 락으로 직렬화합니다(동시성 테스트 포함).
- **실행 기록:** 노드·단계별로 저장하고, SSE(snapshot → update)로 실시간 전달합니다.

---

## 6. Tool 연동 (MCP)

| 기능 | 설계 |
|---|---|
| 서버 등록 | Console/API에서 Streamable HTTP 서버를 등록하고, 인증 헤더는 **AES-256-GCM으로 암호화 저장**합니다(관리 API에는 헤더 이름만 노출). stdio 서버는 Runtime 호스트에서 명령을 실행하므로, 관리자 계정이 탈취돼도 호스트 명령 실행으로 이어지지 않도록 **Runtime 설정 파일로만** 등록합니다 |
| Runtime 반영 | Runtime이 Runtime 토큰으로 등록 서버 목록을 30초 캐시로 받아옵니다. 장애 시에는 마지막 목록을 유지합니다. Tool을 **호출할 때마다 서버 설정을 다시 조회**하므로, 재시작 없이 URL·자격증명 교체가 반영됩니다 |
| Tool Catalog | MCP `tools/list`로 설명, 입력 스키마, annotations를 수집합니다. 서버별로 오류를 격리하고, 설정이 바뀌면 캐시를 무효화합니다 |
| 출처와 충돌 | 서버마다 등록 출처(`agentgate` / `runtime`)를 표시합니다. 이름이 겹치면 설정 파일이 우선하고, 충돌로 표시합니다 |

---

## 7. Web Console

| 화면 | 기능 |
|---|---|
| Workflows / Builder | 노드 팔레트, 드래그 배치, 인스펙터, 검증 오류 강조, 버전 저장, Agent 노드에서 등록 Agent와 정의 버전 선택 |
| Agents | 등록(API Key는 한 번만 표시), 키 재발급(2단계 확인), 정의 편집, Tool·권한 선택, 위임 대상, 버전 기록과 불러오기 |
| Tools | Tool 위험도 지정·해제, 추천값 일괄 적용(확인 후), MCP 서버 등록·수정·삭제(자격증명 교체는 명시적일 때만) |
| Execution Studio | 그래프 위 노드 상태를 실시간 표시, 단계 타임라인, Agent trace(호출·판정 근거·결과·토큰), 위임 경로, 승인 패널, 중단 사유 |
| Approvals | 대기 중인 승인 목록, 승인 사유와 위임 경로, 승인·거절 |

**Agent trace:** Runtime은 Agent 단계마다 대화 전체 대신 **요약 trace**를 이벤트로 보냅니다(호출할 Tool과 인자, 판정, 결과, 토큰 사용량, 실행한 Agent). 이벤트 크기를 줄이면서 화면에서 바로 읽을 수 있게 하려는 것입니다.

---

## 8. 보안

| 영역 | 내용 |
|---|---|
| 인증 | 관리자 API는 HTTP Basic. Agent는 개별 API Key(해시 저장, 재발급 시 즉시 교체). Runtime↔AgentGate는 공유 토큰(상수 시간 비교) |
| Runtime 신뢰 모델 | Runtime은 등록된 Agent의 이름으로 행동 평가를 요청할 수 있는 신뢰된 호출자입니다. Audit에 실제로 행동한 Agent가 남습니다 |
| 비밀정보 | MCP 자격증명은 AES-256-GCM(호출마다 새 IV, 변조 감지)으로 저장. 키(`AGENTGATE_SECRET_KEY`)가 없으면 운영 프로파일 기동을 거부. 값은 로그·응답·repr에 노출하지 않음 |
| 운영 가드 | 기본 관리자 비밀번호, 비밀 키 누락, Runtime 토큰 누락 시 기동 실패 |
| 입력 검증 | DSL·정의·서버 URL(`http(s)`만)·헤더 이름 검증, 중복 등록 409 |

---

## 9. 신뢰성과 데이터 관리

- **재개 신뢰성:** 승인을 결정한 뒤 Runtime 재개 콜백이 실패하면 주기적으로 재시도하고, 전달 시각을 기록합니다. 이미 재개된 실행에 대한 응답(409/404)은 전달 완료로 처리합니다.
- **Checkpoint:** LangGraph PostgresSaver를 사용합니다. Runtime이 재시작돼도 승인 대기 중인 실행을 이어갈 수 있습니다.
- **스키마 관리:** Flyway 마이그레이션을 쓰고, Hibernate는 기동할 때 스키마 일치만 검증합니다(`validate`).
  - Flyway 도입 전 DB도 데이터를 유지한 채 자동으로 업그레이드되도록 baseline 전략을 설계했습니다.
  - 마이그레이션은 Testcontainers(실제 PostgreSQL)로 빈 DB와 기존 DB 두 시나리오에서 검증합니다.
- **장애 격리:**
  - MCP 서버 하나가 장애여도 Tool 목록의 다른 서버는 정상으로 동작합니다.
  - AgentGate 서버 목록 조회가 실패하면 Runtime은 마지막 목록을 씁니다.
  - 이벤트 보고는 best-effort라, 보고가 실패해도 실행은 계속됩니다.

---

## 10. 품질 보증

| 영역 | 규모 | 범위 |
|---|---|---|
| Spring | 188 | 단위·통합(MockMvc, H2), 동시성, 마이그레이션(Testcontainers PostgreSQL) |
| Runtime | 165 | DSL 검증·컴파일, Agent loop, 위임, interrupt/resume, checkpoint 복원. 실제 stdio/Streamable HTTP MCP 서버와 인증이 필요한 MCP 서버 포함 |
| Frontend | 60 | 화면 동작(Testing Library), 폼 변환·검증, SSE 파서 |
| E2E | 12 | Spring + Runtime 실제 기동, 가짜 LLM(OpenAI 호환, tool_calls 포함)으로 승인·재개·위임 전체 흐름 |

- **CI:** `test`, `runtime`(ruff lint/format + pytest), `frontend`(typecheck + vitest), `e2e`. 모든 변경은 이슈 → 브랜치 → PR → CI 통과 → merge 순서로 반영합니다.
- **실환경 검증:** 로컬 Ollama(qwen2.5) + MCP 서버 + AgentGate로 다음을 직접 확인했습니다.
  - tool-calling, 승인 대기·재개, 거절 처리
  - JSON 방식 Tool 호출과 출력 스키마
  - 등록된 MCP 서버를 재시작 없이 사용
  - DB 마이그레이션

### 검증 과정에서 발견해 수정한 주요 결함

| 결함 | 원인 | 조치 |
|---|---|---|
| 기존 DB에서 컬럼 추가 실패 | ddl-auto가 기본값 없는 NOT NULL 컬럼을 추가 | 기본값 지정, 업그레이드 회귀 테스트, 근본 대책으로 Flyway 도입 |
| 기존 DB에서 `STOPPED` 상태 저장 실패 | ddl-auto가 enum CHECK 제약을 갱신하지 않음 | 제약을 재정의하는 마이그레이션(V2) |
| 외부 LLM 서버로 배포 불가 | 배포 설정이 LLM 주소를 고정 | 환경변수로 선택, 로컬 LLM 컨테이너는 선택 프로필로 분리 |
| Tool 거절 후에도 다음 단계 진행 | 거절이 정상 결과로 처리됨 | 기본 중단(`STOPPED`)과 노드별 계속 옵션 |
| 정책 캐시 역직렬화 오류, 승인 기록 트랜잭션, 동시 버전 번호 충돌 | 직렬화 타입, 트랜잭션 전파, 락 전략 | 타입 지정 직렬화기, `REQUIRES_NEW`, 비관적 락 |

---

## 11. 운영 / 배포

- **AWS:** Terraform으로 ALB, App EC2(AgentGate + Console), Runtime EC2, RDS PostgreSQL, 모니터링 EC2(Prometheus + Grafana)를 구성합니다. 비용 관리를 위한 start/stop 스크립트가 있습니다.
- **배포 스크립트:** 로컬에서 빌드한 jar를 배포하고(systemd), Runtime은 소스를 동기화한 뒤 컨테이너로 빌드합니다.
- **LLM:** `LLM_BASE_URL`로 외부 서버(DGX Spark의 vLLM 등)를 지정하면 번들 Ollama 없이 동작합니다. vLLM을 `--enable-auto-tool-choice --tool-call-parser <parser>`로 띄우면 `NATIVE`, 아니면 `JSON` 방식을 씁니다.
- **필수 설정:**
  - 관리자 계정
  - `AGENTGATE_SECRET_KEY` (32바이트, Base64)
  - Runtime 토큰(양쪽 동일)
  - DB / Redis 연결
- **관측:** Actuator와 Prometheus 메트릭, Grafana, 실행별 trace(Studio), Audit 통계 API를 제공합니다.

---

## 12. 주요 설계 결정 요약 (ADR)

| 결정 | 대안 | 선택 이유 |
|---|---|---|
| Governance를 Tool 실행 직전에 둠 | LLM 출력 필터링 | 실제 영향은 Tool 호출에서만 생기고, 판정 대상이 명확합니다 |
| Workflow 엔진으로 LangGraph 사용 | n8n, 자체 엔진, Temporal | interrupt/checkpoint/resume이 Human-in-the-loop와 잘 맞습니다. n8n은 UX만 참고했습니다 |
| GUI와 Runtime 사이에 DSL을 둠 | React Flow JSON을 직접 실행 | 검증, 버전 관리, Runtime 교체가 가능합니다 |
| Agent 정의를 거버넌스 Agent에 통합 | 별도 엔티티 | Audit의 행위 주체와 권한이 일치합니다 |
| 정의를 실행 시점에 고정·기록 | 항상 최신 정의 사용 | 재현 가능성과 감사 추적을 위해서입니다 |
| 판정·대기·실행을 다른 노드로 분리 | 하나의 노드 | 재개할 때 판정 요청이 중복되지 않습니다 |
| 위임을 agent-as-tool + 서브그래프로 | 워크플로 수준 Supervisor 노드 | 위임 자체를 판정할 수 있고, 권한이 분리되며, 중첩 승인을 재개할 수 있습니다 |
| UI에서는 HTTP MCP 서버만 등록 | stdio도 허용 | 관리자 계정이 탈취돼도 호스트 명령 실행으로 이어지지 않게 합니다 |
| Tool 위험도를 정책으로 저장 | 별도 저장소 | 판정, 캐시, Audit을 재사용하고 규칙을 하나로 유지합니다 |
| Flyway + `validate` | ddl-auto | 스키마 변경을 버전으로 관리하고, 불일치를 배포 시점에 잡습니다 |

---

## 13. 현재 범위와 로드맵

**완료**
- Governance Core: Policy, Risk, Approval, Audit, Agent 위험도 상한
- Visual Workflow, LangGraph Runtime, MCP, Execution Studio
- Agent Definition, tool-calling Agent, JSON 방식 / 출력 스키마
- Tool Registry: MCP 서버 레지스트리, Tool 위험도
- Multi-Agent 위임
- Flyway 기반 스키마 관리

**진행 예정 (운영 필수)**

| 항목 | 내용 |
|---|---|
| RBAC | 사용자·역할(관리자 / 승인자 / 조회자), 승인 권한 분리, 승인자 기록 |
| 승인 알림·만료 | Slack/Webhook 알림, 승인 기한과 자동 거절 |
| HTTPS | ALB HTTPS 리스너(ACM) |
| 설치 패키지 | 운영용 Compose, 환경 템플릿, 설치 스크립트 |

**이후 확장**

| 항목 | 내용 |
|---|---|
| Memory | 단기 + pgvector 장기 기억 |
| Observability | OpenTelemetry / Langfuse, LLM 입출력 전체 trace |
| 수평 확장 | 다중 인스턴스 SSE(Redis Pub/Sub) |
| 기타 | Tool 기본 라벨, HTTP Tool의 Registry 편입 |

---

## 14. 저장소 구조

```text
agentgate/
├── src/main/java/com/agentgate/   # AgentGate (Spring Boot)
│   ├── agent/      Agent, Definition, 행동 평가, 위임 해석
│   ├── policy/ risk/ approval/ audit/   Governance Core
│   ├── workflow/ execution/              Control Plane
│   ├── mcp/ tool/                        MCP 서버 레지스트리, Tool 목록·위험도
│   ├── runtime/                          Runtime 클라이언트 (검증·실행·재개·Tool 목록)
│   └── common/ config/ web/              공통, 보안, SPA 라우팅
├── src/main/resources/db/migration/      Flyway 마이그레이션
├── runtime/app/                          Agent Runtime (Python)
│   ├── dsl/        schema · validator · compiler
│   ├── nodes/      agent(tool-calling loop) · tool · approval
│   ├── tools/      governed tool · http · mcp · delegate · catalog · registry
│   ├── governance/ AgentGate client
│   └── llm/ graph/ events · executions · runner
├── frontend/src/                         Web Console (React)
├── e2e/                                  E2E (가짜 LLM / 서비스)
├── infra/ deploy/ scripts/               Terraform, 배포 설정·스크립트
└── docs/                                 ARCHITECTURE · API_SPEC · DEPLOYMENT · MONITORING
```

자세한 설계는 `docs/ARCHITECTURE.md`, API는 `docs/API_SPEC.md`, 배포는 `docs/DEPLOYMENT.md`를 참고하세요.
