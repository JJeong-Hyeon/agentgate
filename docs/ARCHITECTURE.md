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
| Agent | `LLM`, `AGENT` | LLM 호출. `AGENT`는 이후 Tool Calling이 추가될 노드 |
| Agent | `ROUTER` | LLM이 `routes` 중 하나를 선택. 출력 edge label = route |
| Agent | `REVIEWER` | LLM 판정. 출력 edge label = `APPROVE` / `REVISE`, 재시도 횟수는 `maxRevisions` |
| Tool | `HTTP_TOOL` | AgentGate 검사를 거치는 HTTP 호출 (`MCP_TOOL`은 Phase 6) |
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

- `POST /runtime/executions`에 `{"task", "workflow"}`로 DSL을 넘기면 검증 → 컴파일 → 실행한다 (`workflow` 생략 시 내장 리서치 그래프).
- DSL은 실행 상태의 `workflow` 키에 함께 저장되어, 조회·재개 시 checkpoint만으로 같은 그래프를 복원한다 (Runtime 재시작 후에도).
- `REVIEWER`가 `maxRevisions`를 다 쓰고도 `REVISE`면 실행을 종료한다 (승인되지 않은 결과로 다음 단계를 진행하지 않음).
- `ROUTER`는 LLM 응답에서 route 이름을 찾고, 없으면 첫 번째 route로 간다.
- `APPROVAL` 노드는 AgentGate에 `requireApproval: true`로 승인 요청을 만든다. 정책상 허용이어도 승인을 기다리고, 정책상 `BLOCKED`면 차단된다. 승인 시 다음 단계로, 거절·차단·AgentGate 실패 시 실행을 종료하며 결과를 `state[노드id]`에 기록한다.

---

## 8. Local LLM

| 환경 | 경로 |
|---|---|
| 개발 | `LangGraph → LLM Gateway → Ollama → Qwen / Llama / Gemma` |
| 운영 | `LangGraph → LLM Gateway → vLLM → Local Model` |

- Ollama와 vLLM 모두 OpenAI 호환 API를 제공하므로 LLM Gateway는 base URL만 교체하는 Adapter로 시작한다.
- 초기에는 **7B~14B급 Tool Calling 지원 모델**을 사용한다. 0.5B급 모델은 Tool Calling 품질이 낮아 연결 테스트 용도로만 쓴다.

---

## 9. Tool / MCP

```text
LangGraph Tool Node
      ↓
AgentGate (POST /api/v1/actions)
      ↓ ALLOWED
Tool Executor
 ├─ HTTP Tool   (MVP)
 └─ MCP Client  (Phase 6)
     ├─ File
     ├─ Database
     └─ Git
```

Tool Executor는 AgentGate 응답이 `ALLOWED`일 때만 실제 호출을 수행한다.
Governance를 우회하는 Tool 실행 경로는 두지 않는다.

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
- SSE 구독자는 인스턴스 메모리에 있으므로 다중 인스턴스 운영 시 Redis Pub/Sub 등으로 확장이 필요하다.

---

## 13. Backend (Spring Boot)

기존 Spring Boot는 유지하고 Control Plane을 추가한다.

| Plane | 도메인 |
|---|---|
| Control Plane (신규) | Workflow, Workflow Version, Execution, Model, Tool |
| Governance Plane (기존) | Agent, Policy, Risk, Approval, Audit |

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
```

기존 API 상세는 [`API_SPEC.md`](API_SPEC.md) 참고.

---

## 14. Python Runtime

```text
runtime/
├── app/
│   ├── main.py                 # FastAPI: 실행 / resume 엔드포인트
│   ├── dsl/
│   │   ├── schema.py
│   │   └── validator.py
│   ├── graph/
│   │   ├── compiler.py         # DSL → StateGraph
│   │   ├── state.py
│   │   └── executor.py
│   ├── nodes/
│   │   ├── agent.py
│   │   ├── llm.py
│   │   ├── condition.py
│   │   ├── tool.py
│   │   └── approval.py
│   ├── llm/
│   │   ├── base.py
│   │   └── openai_compat.py    # Ollama / vLLM 공용
│   ├── tools/
│   │   ├── http.py
│   │   └── mcp_client.py       # Phase 6
│   └── governance/
│       └── agentgate_client.py
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
workflow_versions     # DSL 전체를 JSONB 컬럼으로 저장
executions
node_executions
model_configs
tool_configs
langgraph checkpoints # Runtime이 관리
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
| 7. 고도화 | 필요 시 선택 | — |

Phase 7 후보: RAG, pgvector, Memory, Retry/Timeout 고도화, Scheduler, Langfuse,
OpenTelemetry, vLLM 운영 전환, A2A, Temporal.

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

## 21. 지금 바로 할 일

1. 기존 Spring Boot Governance 기능은 건드리지 않는다
2. `runtime/` 생성 (Python + LangGraph + FastAPI)
3. LLM Gateway(OpenAI 호환) + Ollama + Qwen 연결
4. `Planner → Researcher → Reviewer` 그래프 실행
5. HTTP Tool + `agentgate_client`로 `/api/v1/actions` 연동
6. interrupt / resume 콜백 구현, `ALLOWED / APPROVAL_REQUIRED / BLOCKED` 확인
7. Workflow DSL 스키마 / Compiler
8. React Flow Builder + Execution Studio
9. MCP 추가
10. 이후 RAG / Memory / Observability 선택적 확장

**가장 먼저 구현할 것은 GUI가 아니라 `LangGraph → Local LLM → AgentGate`의 실제 실행 경로다.**
