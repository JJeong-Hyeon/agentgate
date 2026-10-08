# AgentGate 아키텍처 (v2)

## Visual AI Workflow & Agent Governance Platform

> 로컬 LLM 기반 AI Agent Workflow를 시각적으로 설계하고, LangGraph Agent
> Runtime과 AgentGate Governance Layer를 통해 안전하게 실행·관리하는 플랫폼

기존 [`AGENTGATE_PLAN.md`](AGENTGATE_PLAN.md)의 Governance Core(Policy / Risk /
Approval / Audit)는 그대로 유지하고, 그 위에 Visual Workflow Builder, LangGraph
Runtime, Local LLM, MCP, Execution Studio를 추가한다. 이 문서가 이전의 n8n 연동
방향을 대체한다.

---

## 1. 최종 방향

기존:

```text
AI Agent → AgentGate → Policy → Risk → Approval → System
```

변경:

```text
Visual Builder → Workflow DSL → LangGraph Runtime ─┬→ LLM Gateway → Local LLM
                                                    └→ Tool 호출 → AgentGate Governance → External System
```

핵심 원칙: **AgentGate는 LLM 응답이 아니라 Tool 실행 직전 경계에 위치한다.**
모든 외부 시스템 호출(MCP Tool, HTTP Tool 포함)은 AgentGate를 통과한다.

---

## 2. 참고 프로젝트

| 프로젝트 | 가져올 개념 |
|---|---|
| n8n | Visual Workflow Automation UX (엔진으로 쓰지 않음) |
| LangGraph | Stateful Agent Orchestration, interrupt/checkpoint |
| LangGraph Studio | 실행 상태/디버깅 UX |
| LangGraph-GUI / LangConfig | Visual Graph Builder |
| Ollama | 개발용 Local LLM |
| vLLM | 운영용 LLM Serving |
| MCP | Tool 인터페이스 표준 |
| 기존 AgentGate | Policy / Risk / Approval / Audit |

---

## 3. 전체 아키텍처

```text
┌──────────────────────────────────────────┐
│ Frontend (React + TypeScript + React Flow)│
│ Workflow Builder / Execution Studio      │
│ Approval UI                              │
└───────────┬───────────────────▲──────────┘
            │ Workflow DSL      │ 실행 상태 (SSE)
            ▼                   │
┌──────────────────────────────────────────┐
│ Spring Boot (Control + Governance Plane) │
│ Workflow / Version / Execution 저장      │
│ Policy / Risk / Approval / Audit         │
└───────────┬───────────────────▲──────────┘
            │ 실행 요청          │ Tool 평가 요청 (POST /api/v1/actions)
            ▼                   │ 실행 이벤트 보고
┌──────────────────────────────────────────┐
│ Python Runtime (LangGraph)               │
│ DSL Compiler → StateGraph 실행           │
│ checkpoint / interrupt / resume          │
└──────┬─────────────────────────┬─────────┘
       │                         │ (ALLOWED 일 때만)
       ▼                         ▼
  LLM Gateway               Tool Executor
   ├─ Ollama (dev)           ├─ MCP Tool
   └─ vLLM (prod)            └─ HTTP Tool
                                  │
                                  ▼
                           External System
```

---

## 4. 핵심 역할 분리

| 구성요소 | 역할 |
|---|---|
| Frontend | Node Drag & Drop, Node 설정, Workflow 저장/불러오기, 실행 결과 확인, 승인 처리 |
| Spring Boot | Workflow·Execution 메타데이터 저장, Governance 판단, Audit 기록 |
| LangGraph Runtime | DSL을 StateGraph로 컴파일해 **Workflow 전체를 실행** (분기/병렬/재시도 포함) |
| LLM Gateway | Ollama/vLLM 추상화. Agent 코드는 특정 LLM 서버에 직접 의존하지 않음 |
| AgentGate Governance | Tool 실행 전 `Policy → Risk → ALLOWED / APPROVAL_REQUIRED / BLOCKED` 판단 |

MVP에서는 별도의 Workflow Orchestrator를 만들지 않는다. Trigger / Condition /
Parallel / Retry는 LangGraph 그래프로 컴파일해 처리한다. 장시간 실행·스케줄링이
필요해지면 Phase 7에서 Temporal 등 별도 Orchestrator를 검토한다.

---

## 5. GUI Node 구성

초기에는 Node 종류를 최소화한다. 스키마는 `runtime/app/dsl/schema.py`가 기준이다.

| 분류 | Node | 설명 |
|---|---|---|
| Workflow | `START`, `END` | 시작(1개) / 종료(1개 이상) |
| Workflow | `CONDITION` | 상태 값(`key`)에 따라 분기. 출력 edge label = `cases`의 label + `default` |
| Agent | `LLM` | LLM 단일 호출 |
| Agent | `AGENT` | `agentId`가 있으면 등록된 Agent 정의로 실행하는 **tool-calling Agent** (9장). 없으면 역할을 가진 LLM 단일 호출 |
| Agent | `ROUTER` | LLM이 `routes` 중 하나를 선택. 출력 edge label = route |
| Agent | `REVIEWER` | LLM 판정. 출력 edge label = `APPROVE` / `REVISE`, 재시도 횟수는 `maxRevisions` |
| Tool | `HTTP_TOOL` | AgentGate 검사를 거치는 HTTP 호출 |
| Tool | `MCP_TOOL` | AgentGate 검사를 거치는 MCP 서버 도구 호출 (인자 고정) |
| Governance | `APPROVAL` | 위험도와 무관하게 사람의 승인을 강제 |

- **병렬**: 일반 노드에서 label 없는 edge를 여러 개 내보내면 병렬 실행된다 (별도 `PARALLEL` 노드 없음).
- **반복**: `REVIEWER`를 거치는 cycle로 표현한다. `REVIEWER`가 없는 cycle은 무한 루프가 되므로 검증에서 거부한다 (별도 `LOOP` 노드 없음).
- Policy/Risk 검사는 Node로 배치하지 않는다. 모든 Tool Node 실행 시 Runtime이 자동으로 AgentGate를 호출하므로 사용자가 빠뜨릴 수 없다.

---

## 6. MVP 대표 Workflow

```text
START
  ↓
Planner Agent
  ↓
Router
 ├── Research Agent
 └── DB Agent
       ↓
    Reviewer
       ↓
    HTTP Tool  ── (AgentGate 자동 검사 → HIGH면 Approval 대기)
       ↓
      END
```

GUI에서 이 Workflow를 만들고, 로컬 Qwen으로 실행하고, Tool 호출을 AgentGate가
검사·승인 대기시키는 것을 MVP 성공 기준으로 한다. MVP의 Tool은 HTTP Tool이며,
MCP는 MVP 이후(Phase 6)에 추가한다.

---

## 7. Workflow DSL

React Flow JSON과 LangGraph 코드를 직접 연결하지 않는다.

```text
React Flow JSON → Workflow DSL → Validation → Compiler → LangGraph StateGraph
```

예제: [`runtime/examples/research.json`](../runtime/examples/research.json) (Planner → Researcher → Reviewer → Report)

```json
{
  "schemaVersion": 1,
  "workflowId": "research",
  "version": 1,
  "nodes": [
    { "id": "start", "type": "START" },
    { "id": "plan", "type": "AGENT", "config": { "prompt": "Task: {task}" } },
    { "id": "report", "type": "HTTP_TOOL",
      "config": { "action": "SEND_REPORT", "url": "https://hook.internal/report", "payloadKeys": ["task", "plan"] } },
    { "id": "end", "type": "END" }
  ],
  "edges": [
    { "source": "start", "target": "plan" },
    { "source": "plan", "target": "report" },
    { "source": "report", "target": "end" }
  ]
}
```

규칙:

- JSON 키는 camelCase. `position`은 GUI 배치용이며 Runtime은 무시한다.
- 출력이 있는 노드(`LLM`, `AGENT`, `ROUTER`, `REVIEWER`, `HTTP_TOOL`)는 결과를 상태의 `노드 id` 키에 저장한다.
- 프롬프트에서 `{task}`(입력)와 `{노드id}`(앞선 노드 출력)를 참조한다. 아직 실행되지 않은 노드는 빈 문자열이다. 중괄호 문자 자체는 `{{`, `}}`로 쓴다.
- `HTTP_TOOL`의 `action`, `labels`는 그대로 AgentGate `POST /api/v1/actions` 요청에 사용되고, `payloadKeys`의 상태 값이 요청 body가 된다.
- DSL은 GUI보다 먼저 확정한다. Runtime은 DSL만 알면 되고, GUI는 DSL을 생성하는 도구다.

검증 (`POST /runtime/workflows/validate`, 오류를 모두 모아 `{valid, errors[{path, message, node_id}]}`로 반환):

- 스키마 (노드 타입별 config, 알 수 없는 필드 금지, id 형식, 예약어 `task` 등)
- 노드 id 중복, edge가 존재하는 노드를 참조하는지
- `START` 1개(나가는 edge 1개, 들어오는 edge 없음), `END` 1개 이상
- 분기 노드의 출력 edge label이 기대값과 정확히 일치하는지, 일반 노드 edge에는 label이 없는지
- 모든 노드가 `START`에서 도달 가능하고 `END`에 도달 가능한지
- `REVIEWER`를 거치지 않는 cycle 금지
- 프롬프트 변수, `payloadKeys`, `CONDITION.key`가 존재하는 상태 키인지

JSON Schema는 `GET /runtime/workflows/schema`로 제공한다 (프론트엔드 폼/검증용).

실행 (`runtime/app/dsl/compiler.py`):

- `POST /runtime/executions`에 `{"task", "workflow"}`로 DSL을 넘기면 검증 → 컴파일 → 실행한다 (`workflow` 필수, 보통 AgentGate가 저장된 버전으로 호출).
- DSL은 실행 상태의 `workflow` 키에 함께 저장되어, 조회·재개 시 checkpoint만으로 같은 그래프를 복원한다 (Runtime 재시작 후에도).
- `REVIEWER`가 `maxRevisions`를 다 쓰고도 `REVISE`면 실행을 종료한다 (승인되지 않은 결과로 다음 단계를 진행하지 않음).
- `ROUTER`는 LLM 응답에서 route 이름을 찾고, 없으면 첫 번째 route로 간다.
- `AGENT` 노드의 `config.agentId`는 등록된 Agent를, `config.agentVersion`(선택)은 정의 버전을 고정한다. 이때 `system` / `model` / `temperature`는 정의가 정하므로 노드에 쓸 수 없다. 저장 시 AgentGate가 참조를 검사한다 (없는 Agent, 정의 없는 Agent, 없는 버전, 같은 Agent의 서로 다른 고정 버전 → 422).
- 실행을 시작할 때 AgentGate가 각 Agent의 정의 버전을 고정해 DSL `agents`(`agentId → 정의 스냅샷`)에 넣어 Runtime에 보내고, 실행 기록에 `agentVersions`로 남긴다. 저장된 DSL에는 `agents`가 없다.
- `APPROVAL` 노드는 AgentGate에 `requireApproval: true`로 승인 요청을 만든다. 정책상 허용이어도 승인을 기다리고, 정책상 `BLOCKED`면 차단된다. 승인 시 다음 단계로, 거절·차단·AgentGate 실패 시 실행을 `STOPPED`로 중단하며 결과를 `state[노드id]`에 기록한다.
- Tool 노드(`HTTP_TOOL` / `MCP_TOOL`)가 정책상 차단되거나 승인자가 거절하면(또는 AgentGate 판정 실패) 기본적으로 실행을 `STOPPED`로 중단한다. 실행 상태의 `stopped`(`node`, `status`, `reason`)와 이벤트 `EXECUTION_STOPPED`로 사유를 남긴다. 노드 설정 `onDenied: CONTINUE`면 결과(`REJECTED` / `BLOCKED`)를 `state[노드id]`에 기록하고 다음 노드로 진행한다. Agent 내부의 Tool 호출은 중단하지 않고 사유를 LLM에 돌려준다(9장).

---

## 8. Local LLM

| 환경 | 경로 |
|---|---|
| 개발 | `LangGraph → LLM Gateway → Ollama → Qwen / Llama / Gemma` |
| 운영 | `LangGraph → LLM Gateway → vLLM → Local Model` (예: NVIDIA DGX Spark) |

- Ollama와 vLLM 모두 OpenAI 호환 API를 제공하므로 LLM Gateway는 base URL만 교체하는 Adapter다 (`LLM_BASE_URL`, `LLM_MODEL`, `LLM_API_KEY`).
- Agent의 Tool 호출 방식은 두 가지다 (`LLM_TOOL_CALLING`, Agent 정의의 `toolCalling`으로 Agent별 지정 가능).
  - `native`: OpenAI function calling. vLLM은 `--enable-auto-tool-choice --tool-call-parser <모델에 맞는 parser>`(Qwen 계열은 `hermes`)로 띄워야 한다.
  - `json`: Tool 목록과 응답 형식을 프롬프트로 안내하고 모델의 JSON 응답을 호출로 해석한다. function calling을 지원하지 않는 서버/모델용.
- Tool 호출 품질은 모델 크기에 크게 좌우된다. 3B급은 불필요한 호출을 반복하기 쉬워(로컬 확인 결과) 운영에는 **14B 이상 Tool Calling 지원 모델**을 권장한다. 반복 호출은 `maxSteps`와 거버넌스(승인)로 제한된다.

---

## 9. Tool / MCP

```text
LangGraph Tool Node
      ↓
AgentGate (POST /api/v1/actions)
      ↓ ALLOWED
Tool Executor (app/tools/base.py: GovernedTool)
 ├─ HTTP Tool   (HTTP_TOOL 노드)
 └─ MCP Tool    (MCP_TOOL 노드, stdio / Streamable HTTP MCP 서버)
```

Tool Executor는 AgentGate 응답이 `ALLOWED`일 때만 실제 호출을 수행한다.
Governance를 우회하는 Tool 실행 경로는 두지 않는다. HTTP / MCP 모두 같은 검사 → (승인 대기) → 실행 흐름을 쓴다.

MCP:

- MCP 서버는 두 곳에서 등록한다.
  - **AgentGate** (`/api/v1/mcp-servers`, 화면): Streamable HTTP 서버와 인증 헤더(암호화 저장). Runtime이 Runtime 토큰으로 30초마다 받아오므로 재시작 없이 추가·변경·인증정보 교체가 반영된다. 조회에 실패하면 마지막 목록을 유지한다.
  - **Runtime 설정 파일** (`MCP_CONFIG_PATH`, `mcpServers` 형식): `command`/`args`는 stdio, `url`은 Streamable HTTP. stdio 서버는 Runtime 호스트에서 명령을 실행하므로 여기서만 등록한다. 이름이 겹치면 설정 파일이 우선하고 Tool 목록에 충돌로 표시된다.
- Tool은 호출할 때마다 서버 설정을 다시 조회하므로, 승인 대기 중인 실행에도 URL·인증정보 변경이 반영되고 삭제·비활성화된 서버의 호출은 `FAILED`로 기록된다.

```json
{"mcpServers": {
  "files": {"command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "/data"]},
  "search": {"url": "http://search-mcp:8000/mcp"}
}}
```

- `MCP_TOOL` 노드: `server`, `tool`, `arguments`(값은 프롬프트처럼 `{task}`, `{노드id}` 템플릿, 문자열로 전달), `action`(기본 `MCP:<server>:<tool>`), `labels`.
- AgentGate에는 `action`과 `target = mcp://<server>/<tool>`로 평가를 요청하므로 도구 단위로 정책을 걸 수 있다.
- 호출마다 세션을 새로 연다. 도구 오류(`isError`)나 연결 실패는 `FAILED`로 기록하고, 결과 텍스트는 `state[노드id]`에 저장한다.
- 등록되지 않은 서버를 쓰는 워크플로는 실행 시작 시 거부된다(Runtime 설정 의존이라 저장 시 검증에서는 확인하지 않음).

Tool Catalog (`app/tools/catalog.py`):

- Runtime은 설정된 MCP 서버마다 `tools/list`로 Tool 이름, 설명, 입력 JSON Schema, annotations(`readOnlyHint` 등)를 조회한다 (`GET /runtime/tools`, AgentGate `GET /api/v1/tools`).
- 결과는 서버별로 60초 캐시하고, 연결 실패한 서버는 오류만 보고한다(다른 서버 목록은 정상 반환, 오류는 캐시하지 않음).

### Agent Definition / tool-calling Agent

Agent는 거버넌스 신원(API Key, 정책, `maxRiskLevel`, Audit)과 **버전 관리되는 실행 정의**를 함께 가진다 (`PUT /api/v1/agents/{id}/definition`, 저장할 때마다 새 불변 버전).

```text
Agent Definition (v3)
├── systemPrompt, model, temperature
├── tools[]        server / tool / permission(AUTO | APPROVAL | BLOCKED) / labels
├── maxSteps       LLM 턴 상한 (기본 8)
├── outputSchema   최종 답변 JSON Schema (선택)
└── toolCalling    NATIVE | JSON (선택, 기본은 Runtime 설정)
```

`AGENT` 노드(`agentId`)는 LangGraph 루프로 실행된다 (`app/nodes/agent.py`).

```text
<id> → <id>.think ─(tool calls)→ <id>.gate ─ALLOWED→ <id>.execute ─┐
                 └─(answer)→ 다음 노드   ├─APPROVAL_REQUIRED→ <id>.approval (interrupt)
                                         ├─BLOCKED/FAILED→ 사유를 LLM에 전달
                                         └─(남은 호출 없음)→ <id>.think
```

- LLM에는 `BLOCKED`가 아닌 Tool만 `server__tool` 이름과 입력 스키마로 제공한다.
- 호출마다 AgentGate에 **그 Agent 이름과 정의 버전으로**(`agentId`, `agentVersion`) 평가를 요청하고, 승인자에게는 "어떤 Agent가 어떤 Tool을 어떤 인자로" 호출하려는지 보여준다(`reason`).
- 거절·차단·실패·알 수 없는 Tool은 사유를 Tool 결과로 LLM에 돌려줘 계속 진행하게 한다. `maxSteps` 마지막 턴에는 Tool 없이 최종 답변을 요구한다.
- `outputSchema`가 있으면 최종 답변을 JSON으로 파싱해 검증하고, 맞지 않으면 한 번 다시 요청한 뒤 그래도 맞지 않으면 노드를 실패시킨다. 결과는 정규화된 JSON 문자열로 저장한다.
- 실행을 시작할 때 Tool Catalog의 설명·스키마를 정의 스냅샷에 붙여 실행 상태에 저장하므로, 승인 대기 중 MCP 서버가 내려가도 재개 시 그래프를 복원할 수 있다. 사용할 수 없는 Tool이 있으면 실행 시작을 거부한다.

---

## 10. AgentGate Governance

기존 기능을 그대로 Governance Layer로 사용한다. 판단 기준은 현재 코드
(`RiskLevel.toActionStatus()`)와 동일하다.

```text
Tool Request
 ↓
Policy Check
 ↓
Risk Assessment
 ├─ LOW     → ALLOWED
 ├─ MEDIUM  → ALLOWED
 ├─ HIGH    → APPROVAL_REQUIRED
 └─ BLOCKED → BLOCKED
```

Agent별 `maxRiskLevel` 상한도 그대로 적용된다. 이 부분이 일반적인 LangGraph GUI와의
핵심 차별점이다.

Agent 정의의 Tool 권한은 정책 앞 단계에서 적용된다 (요청에 `agentVersion`이 있을 때).

```text
Tool Request (agentId, agentVersion)
 ↓
Permission ── 정의에 없는 Tool → BLOCKED (TOOL_NOT_GRANTED)
 │         └─ BLOCKED          → BLOCKED (TOOL_BLOCKED)
 ↓
Policy → Risk ── maxRiskLevel 초과 → BLOCKED (AGENT_RISK_CAP)
 ↓
ALLOWED 이고 권한이 APPROVAL → APPROVAL_REQUIRED (TOOL_REQUIRES_APPROVAL)
```

- Tool에 지정한 라벨은 요청 라벨과 합쳐 정책 매칭에 쓰인다.
- 판정 근거 `basis`(`POLICY`, `AGENT_RISK_CAP`, `TOOL_NOT_GRANTED`, `TOOL_BLOCKED`, `TOOL_REQUIRES_APPROVAL`, `APPROVAL_REQUESTED`)는 응답과 Audit Log에 남는다.
- 정책이 없는 행동의 기본 위험도는 `HIGH`(승인 필요)다.
- Runtime은 공유 토큰(`X-Runtime-Token`)으로 인증하는 신뢰된 호출자로, 등록된 어느 Agent의 이름으로든 평가를 요청할 수 있다. 워크플로 수준의 Tool / 승인 노드는 `AGENTGATE_AGENT_ID`(기본 `runtime-agent`)로 평가된다.

---

## 11. Human-in-the-loop

```text
Tool Node 실행
 ↓
Runtime → AgentGate: POST /api/v1/actions
 ↓
status = APPROVAL_REQUIRED, approvalId 반환
 ↓
LangGraph interrupt (checkpoint 저장, execution = WAITING_APPROVAL)
 ↓
Frontend Approval UI → POST /api/v1/approvals/{id}/approve | reject
 ↓
Spring Boot → Runtime: POST /runtime/executions/{executionId}/resume
                       { "approvalId": 12, "decision": "APPROVED" }
 ↓
LangGraph resume
 ├─ APPROVED → Tool 실행
 └─ REJECTED → Tool 건너뛰고 거절 결과를 State에 기록
```

- 재개는 **Spring Boot가 Runtime을 호출하는 콜백 방식**으로 한다 (Runtime 폴링 없음).
- 호출은 `X-Runtime-Token` 공유 토큰으로 인증한다 (`AGENTGATE_RUNTIME_TOKEN` = `RUNTIME_TOKEN`).
- 콜백 실패 시 Spring이 주기적으로(기본 30초) 재시도한다. 전달 성공 시 `approval_requests.runtime_notified_at`을 기록하고, Runtime이 409(이미 재개됨)/404를 반환하면 전달된 것으로 본다.
- Tool 단계는 `검사 → 승인 대기 → 실행` 노드로 나뉜다. LangGraph는 재개 시 중단된 노드를 처음부터 다시 실행하므로, AgentGate 검사 결과를 상태에 저장한 뒤 별도 노드에서 대기해 중복 검사를 막는다.
- checkpoint 저장소는 PostgreSQL(LangGraph PostgresSaver)을 사용한다.

---

## 12. Execution Studio

LangGraph Studio의 UX를 참고한다.

```text
START              ✓
 ↓
Planner            ✓
 ↓
Router             ✓
 ↓
Research Agent     ✓
 ↓
HTTP Tool          ⏸ WAITING_APPROVAL
```

Node별 확인 항목:

- Input / Output, State
- 실행 시간, Token Usage
- Tool Call과 AgentGate 판단 결과 (`status`, `riskLevel`)
- LLM Response
- Error / Retry

실행 상태는 Runtime이 Spring Boot에 이벤트로 보고하고, Frontend는 SSE로 구독한다.

- Runtime은 LangGraph `tasks` 스트림으로 `NODE_STARTED` / `NODE_COMPLETED` / `NODE_WAITING` / `NODE_FAILED`, `EXECUTION_WAITING` / `EXECUTION_COMPLETED` / `EXECUTION_FAILED`를 `POST /api/v1/executions/{id}/events`(`X-Runtime-Token`)로 보고한다. 보고는 best effort이며 실패해도 실행은 계속된다.
- Spring은 `executions` / `node_executions`에 기록하고 `GET /api/v1/executions/{id}/stream`(SSE)으로 `snapshot` 1회 후 `update` 이벤트를 보낸다.
- 승인 대기로 멈췄다 재개된 단계는 같은 task id로 다시 실행되므로 같은 노드 기록이 갱신된다.
- Agent 단계는 대화 전체 대신 요약 trace(`{"agent": {...}}`)를 출력으로 보고한다: `start`(작업), `tool_calls`(Tool·인자, 토큰 사용량), `decision`(상태·위험도·`basis`·승인 번호), `result`(Tool 결과), `answer` / `repair`(최종 답변, 형식 재요청). Studio는 이를 단계별로 표시하고 헤더에 사용한 Agent 정의 버전과 총 토큰 사용량을 보여준다.
- SSE 구독자는 인스턴스 메모리에 있으므로 다중 인스턴스 운영 시 Redis Pub/Sub 등으로 확장이 필요하다.

---

## 13. Backend (Spring Boot)

기존 Spring Boot는 유지하고 Control Plane을 추가한다.

| Plane | 도메인 |
|---|---|
| Control Plane | Workflow, Workflow Version, Execution, Agent Definition, Tool 목록 |
| Governance Plane | Agent, Policy, Risk(Tool 권한 포함), Approval, Audit |

API:

```text
# 기존
/api/v1/agents
/api/v1/actions            ← Runtime이 Tool 실행 전 호출
/api/v1/approvals
/api/v1/policies
/api/v1/audit-logs

# 신규
/api/v1/workflows
/api/v1/workflows/{id}/versions
/api/v1/executions
/api/v1/executions/{id}/stream   (SSE, UI 구독)
/api/v1/executions/{id}/events   (Runtime → AgentGate 진행 이벤트)
/api/v1/agents/{id}/definition   (Agent 정의 버전)
/api/v1/tools                    (Runtime MCP 서버의 Tool 목록)
```

기존 API 상세는 [`API_SPEC.md`](API_SPEC.md) 참고.

---

## 14. Python Runtime

```text
runtime/
├── app/
│   ├── main.py                 # FastAPI 앱 기동 (checkpointer, compiler, runner, catalog)
│   ├── executions.py           # 실행 / 조회 / resume API
│   ├── workflows.py            # DSL 검증 / JSON Schema API
│   ├── tools_api.py            # Tool 목록 API
│   ├── events.py               # tasks 스트림 → AgentGate 이벤트 보고 (Agent 단계는 요약 trace)
│   ├── runner.py               # 실행별 그래프 복원
│   ├── agents/resolve.py       # 실행 시작 시 Agent Tool 스키마 첨부
│   ├── dsl/                    # schema.py · validator.py · compiler.py
│   ├── nodes/                  # agent.py (tool-calling 루프) · tool.py · approval.py
│   ├── tools/                  # base.py (GovernedTool) · http.py · mcp.py · catalog.py
│   ├── governance/agentgate_client.py
│   ├── llm/gateway.py          # Ollama / vLLM 공용 (OpenAI 호환)
│   └── graph/checkpointer.py
└── Dockerfile
```

---

## 15. Repository 구조

```text
agentgate/
├── frontend/              # React Flow Builder + Execution Studio
├── runtime/               # Python + LangGraph
├── src/                   # Spring Boot
│   └── main/java/com/agentgate/
│       ├── agent/         # 기존
│       ├── policy/        # 기존
│       ├── risk/          # 기존
│       ├── approval/      # 기존
│       ├── audit/         # 기존
│       ├── common/        # 기존
│       ├── config/        # 기존
│       ├── workflow/      # 신규
│       └── execution/     # 신규
├── infra/
├── docs/
├── compose.yaml
└── README.md
```

---

## 16. 데이터 저장

### PostgreSQL

```text
# 기존
agents, policies, risk_assessments, approval_requests, audit_logs

# 신규
workflows
workflow_versions          # DSL 전체를 JSONB 컬럼으로 저장
agent_definition_versions  # Agent 정의, 버전별 불변 JSONB
executions                 # agent_versions: 실행에 쓴 Agent 정의 버전
node_executions
langgraph checkpoints      # Runtime이 관리
```

Node/Edge는 별도 테이블로 정규화하지 않고 `workflow_versions.dsl`(JSONB)에 저장한다.
버전 단위로 불변(immutable) 저장하므로 이 편이 단순하다.

### Redis

기존 용도를 유지한다. 실행 Queue / Pub/Sub은 MVP에서 도입하지 않고, 실행 이벤트는
HTTP 보고 + SSE로 처리한다. 동시 실행 부하가 생기면 그때 도입한다.

### pgvector

초기 필수 아님. RAG/Agent Memory 구현 시 추가한다.

---

## 17. 개발 순서

| Phase | 내용 | 완료 기준 |
|---|---|---|
| 1. Governance Core 고정 | 기존 Agent / Policy / Risk / Approval / Audit 유지, 대규모 리팩터링 금지 | 기존 테스트 통과 |
| 2. LangGraph Runtime | GUI 없이 코드로 정의한 그래프 실행 | `Planner → Researcher → Reviewer`가 Ollama + Qwen으로 동작 |
| 3. AgentGate 연결 | HTTP Tool + `agentgate_client`, interrupt/resume 콜백 | `ALLOWED / APPROVAL_REQUIRED / BLOCKED` 세 경로 동작 |
| 4. Workflow DSL | DSL 스키마, Validator, Compiler | JSON DSL로 Phase 3 그래프 재현 |
| 5. React Flow Builder + Execution Studio | GUI → DSL 저장 → 실행 → 상태/승인 UI | **MVP 완료** (19장 시나리오) |
| 6. MCP | MCP Client를 Tool Executor에 추가 | MCP Tool이 AgentGate를 거쳐 실행 |
| 7. Agent Definition (P1) | 버전 관리되는 Agent 정의, Tool 권한, tool-calling Agent, Tool Catalog, JSON 방식 / 출력 스키마, Agent 화면, Studio trace | GUI에서 정의한 Agent가 Tool을 고르고 호출마다 권한 → 정책 → 위험도 판정을 거쳐 실행 (완료) |

이후 단계 : P2 Tool Registry(도구별 기본 위험도, MCP 서버 인증정보), P3 Multi-Agent, P4 Memory(pgvector), P5 Observability(Langfuse / OpenTelemetry), 운영 필수 항목(RBAC, 승인 알림·만료, Flyway 마이그레이션, HTTPS, 설치 패키지).

---

## 18. 초기에는 하지 않을 것

- n8n 수준의 수백 개 Node
- 70B 이상 대형 모델
- 여러 LLM 동시 운영
- Marketplace
- 별도 Workflow Orchestrator / Temporal
- A2A
- RAG를 필수 기능으로 구현

핵심은 **하나의 Agent Workflow를 실제로 완주하는 것**이다.

---

## 19. MVP 성공 기준

```text
① 사용자가 GUI에서 Workflow 구성
        ↓
② Workflow DSL 저장 (workflow_versions)
        ↓
③ Runtime이 DSL을 LangGraph로 컴파일 후 실행
        ↓
④ Ollama의 Local LLM 추론
        ↓
⑤ Agent가 HTTP Tool 호출 시도
        ↓
⑥ AgentGate가 Policy/Risk 검사
        ↓
⑦ HIGH면 APPROVAL_REQUIRED → LangGraph interrupt
        ↓
⑧ 사용자가 GUI에서 승인
        ↓
⑨ Spring Boot 콜백 → LangGraph resume
        ↓
⑩ Tool 실행
        ↓
⑪ Execution + Audit Log 저장
        ↓
⑫ Execution Studio에서 전체 실행 확인
```

---

## 20. 프로젝트 설명

### 짧은 설명

> 로컬 LLM 기반 AI Agent Workflow를 시각적으로 설계하고, LangGraph Runtime과
> AgentGate Governance를 통해 안전하게 실행하는 플랫폼

### 차별점

> 기존 Visual Workflow 플랫폼이 Workflow 자동화와 Agent 실행에 집중한다면,
> AgentGate는 **Agent의 모든 Tool 행동을 Policy와 Risk 기반으로 검증하고, 필요한
> 경우 Human Approval을 거친 후 실행**한다.

### 기술적 핵심

```text
Visual Workflow + LangGraph Runtime + Local LLM + Tool/MCP + Policy/Risk/HITL + Execution Trace
```

---

## 21. 현재 상태

Phase 1~7 완료. GUI에서 Agent를 정의하고(Tool·권한), 워크플로에 넣어 Local LLM으로 실행하면, Agent의 모든 Tool 호출이 Agent 이름으로 권한 → 정책 → 위험도 판정을 거쳐 실행·승인 대기·차단되고, 그 과정이 Execution Studio와 Audit Log에 남는다. 다음 단계는 17장의 이후 단계를 따른다.
