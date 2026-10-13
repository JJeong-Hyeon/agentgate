# AgentGate v2 후속 보완 방향

## 1. 현재 프로젝트 상태

현재 AgentGate는 기존 Governance 기능을 유지하면서 다음 기능까지 구현된 상태임.

```text
React Flow
   ↓
Workflow DSL
   ↓
Spring Boot
   ↓
Python Runtime
   ↓
LangGraph
   ↓
Ollama / vLLM
   ↓
HTTP / MCP Tool
   ↓
AgentGate
   ↓
Policy / Risk
   ↓
Approval
   ↓
LangGraph Resume
   ↓
SSE / Execution Studio
```

MVP 성공 기준도 이미 다음 흐름으로 구현되어 있음.

```text
GUI Workflow 구성
 → 버전 저장
 → 실행
 → DSL → LangGraph 컴파일
 → Local LLM 실행
 → Tool 호출 전 Policy / Risk 검사
 → 위험 시 Approval
 → 승인 / 거절
 → Runtime Resume
 → 실행 기록 + SSE
```

따라서 현재는 **프로젝트를 다시 만드는 단계가 아니라, 이미 구현된 Agent Runtime + Governance를 더 완성도 높은 Agent Platform으로 확장하는 단계**임.

---

## 2. 최종 프로젝트 방향

### Visual AI Workflow & Agent Governance Platform

> 사용자가 AI Agent Workflow를 시각적으로 구성하고, Local LLM 기반으로 실행하며, Agent의 Tool 행동을 Policy / Risk / Human Approval로 통제하는 플랫폼

핵심 구조:

```text
Visual Workflow Builder
        ↓
Workflow DSL
        ↓
Workflow Orchestrator
        ↓
LangGraph Agent Runtime
        ↓
Local LLM
        ↓
MCP / HTTP Tools
        ↓
AgentGate Governance
        ↓
Policy / Risk / Human Approval
        ↓
Execution / Audit
```

---

## 3. 기존 구현은 유지

현재 기능은 삭제하거나 대규모로 재작성하지 않음.

- Agent 관리
- Policy Engine
- Risk Assessment
- Approval
- Audit Log
- Workflow Version
- Execution
- Redis
- PostgreSQL
- Prometheus
- Grafana
- LangGraph Runtime
- Local LLM
- MCP
- SSE
- Execution Studio

특히 다음 구조는 프로젝트의 핵심임.

```text
Tool Request
    ↓
AgentGate
    ↓
Policy
    ↓
Risk
 ├── ALLOWED
 ├── APPROVAL_REQUIRED
 └── BLOCKED
```

---

## 4. 현재 구현에서 잘한 부분

### 4.1 Workflow DSL → LangGraph Compiler

```text
React Flow
    ↓
Workflow DSL
    ↓
Validation
    ↓
Compiler
    ↓
LangGraph Runtime
```

이 구조는 유지함.

장점:

- GUI와 Runtime 분리
- Workflow Version 관리
- DSL Validation
- Runtime 확장
- Workflow Export / Import

### 4.2 Tool Fail-Closed

```text
ALLOWED
   ↓
실행

APPROVAL_REQUIRED
   ↓
interrupt
   ↓
승인
   ↓
실행

BLOCKED / 검사 실패
   ↓
실행하지 않음
```

### 4.3 Human-in-the-loop

```text
Agent
 ↓
Tool Call
 ↓
AgentGate
 ↓
Risk Assessment
 ↓
APPROVAL_REQUIRED
 ↓
LangGraph Interrupt
 ↓
Frontend Approval
 ↓
AgentGate Approve
 ↓
LangGraph Resume
 ↓
Tool Execution
```

---

# 5. Priority 1 — Agent Definition

현재 `AGENT`를 단순 Workflow Node가 아니라 **재사용 가능한 Agent 객체**로 발전시킴.

```text
Agent
├── Name
├── Description
├── Model
├── System Prompt
├── Tools
├── Memory
├── Input Schema
├── Output Schema
└── Permissions
```

Builder에서 Agent를 선택하면 Model, Prompt, Tools, Memory, Permission 등을 설정할 수 있도록 함.

목적은 Agent를 Workflow 안에서만 사용하는 Node가 아니라 **재사용 가능한 Agent 단위**로 만드는 것임.

---

# 6. Priority 2 — Tool Registry

현재 MCP Tool은 서버와 Tool을 직접 입력하는 방식임.

이를 Tool Registry로 발전시킴.

```text
Tool Registry

GitHub
 ├── search_repository
 ├── create_issue
 └── create_pr

Database
 ├── query
 └── insert

Filesystem
 ├── read
 └── write
```

Agent Builder에서는:

```text
Research Agent

Tools
☑ GitHub.search_repository
☑ Database.query
☐ Filesystem.write
```

처럼 선택.

최종적으로:

```text
Tool
 ↓
Permission
 ↓
Policy
 ↓
Risk
```

구조로 연결함.

---

# 7. Priority 3 — Multi-Agent

현재:

```text
Planner
 ↓
Researcher
 ↓
Reviewer
```

구조에서 한 단계 발전시킴.

```text
                 Supervisor
                /    |                   ↓     ↓     ↓
          Research  Coding  Data
             Agent   Agent  Agent
                \     |     /
                 \    |    /
                  Reviewer
```

지원 목표:

- Supervisor Agent
- Worker Agent
- Reviewer Agent
- Agent 간 결과 전달
- Agent별 Tool / Model / Permission 분리

별도의 Multi-Agent 프레임워크를 추가하기보다 **현재 LangGraph Runtime 위에서 구현**하는 것을 우선함.

---

# 8. Priority 4 — Memory

현재 PostgreSQL checkpoint와 Agent Memory를 구분함.

### Checkpoint

현재 Workflow 실행 상태.

```text
Execution #123
현재 Node = Approval
State = {...}
```

### Memory

Agent가 과거 정보나 대화를 활용하는 기능.

```text
User
 ↓
Research Agent
 ↓
Memory Retrieval
 ↓
과거 정보 활용
```

향후:

```text
Workflow Checkpoint
        +
Short-term Memory
        +
Long-term Memory
```

구조로 분리함.

저장 구조:

```text
PostgreSQL
 ├── Workflow Checkpoint
 ├── Execution
 └── Agent Memory

pgvector
 └── Long-term Memory
```

초기 필수 기능으로 구현하지 않고 RAG/Memory 확장 단계에서 도입함.

---

# 9. Priority 5 — Orchestrator 고도화

현재 Runtime이 DSL을 LangGraph로 Compile하여 실행하고 있음.

향후 상위 Orchestrator와 Agent Runtime의 역할을 명확히 분리함.

## Workflow Orchestrator

전체 업무 Workflow:

```text
Webhook
 ↓
Agent Workflow
 ↓
HTTP API
 ↓
Reviewer
 ↓
Approval
 ↓
Notification
```

지원 대상:

- Sequential
- Conditional
- Parallel
- Loop
- Retry
- Timeout
- Agent Invocation

## LangGraph

Agent 내부 상태 기반 실행:

```text
Planner
 ↓
Router
 ↓
Researcher
 ↓
Reviewer
```

최종:

```text
Workflow Orchestrator
        │
        ├── Normal Workflow
        │
        └── Agent Workflow
                 ↓
              LangGraph
```

---

# 10. Execution Studio 고도화

현재 Execution Studio를 기반으로 Agent 실행 Trace를 자세히 보여줌.

```text
START              ✓
 ↓
Planner            ✓
 ↓
Router             ✓
 ↓
Research Agent     ✓
 ↓
Risk Check         ✓
 ↓
Approval           ⏸ WAITING
```

Node별:

- Input
- Output
- State
- 실행 시간
- Tool Call
- LLM Response
- Error
- Retry
- Token Usage

를 확인하도록 함.

---

# 11. Governance를 GUI에서 직접 보여주기

일반 Agent Builder:

```text
Agent
 ↓
Tool
 ↓
실행
```

AgentGate:

```text
Agent
 ↓
Tool Request
 ↓
Policy
 ↓
Risk
 ├── ALLOWED
 ├── APPROVAL_REQUIRED
 └── BLOCKED
```

따라서 Agent 설정 화면에서 Permission / Governance를 직접 보여줌.

예:

```text
Coding Agent

Tools
☑ File.read
☑ Git.status
☑ Git.commit
☑ Git.push

Permissions
Git.commit    APPROVAL
Git.push      APPROVAL
File.delete   BLOCKED
```

이것이 단순 Agent Builder와의 핵심 차별화 요소가 됨.

---

# 12. GUI 방향

Workflow Builder에 Inspector를 확장함.

```text
┌──────────────────────────────────────────────────────┐
│ Workflow Builder                                     │
├────────────┬─────────────────────────┬───────────────┤
│ Node       │       Graph             │ Inspector     │
│            │                         │               │
│ Agent      │   Agent ──→ Tool        │ Agent         │
│ Tool       │      │                  │ Model         │
│ Router     │      ↓                  │ Tools         │
│ Condition  │   Reviewer              │ Memory        │
│ Approval   │                         │ Permission    │
└────────────┴─────────────────────────┴───────────────┘
```

Agent 선택 시:

- Model
- Prompt
- Tools
- Memory
- Permission
- Policy

를 설정할 수 있도록 함.

---

# 13. Local LLM

개발:

```text
LangGraph
 ↓
LLM Gateway
 ↓
Ollama
 ↓
Qwen / Llama / Gemma
```

운영:

```text
LangGraph
 ↓
LLM Gateway
 ↓
vLLM
 ↓
Local Model
```

Agent 코드가 Ollama에 직접 종속되지 않도록 LLM Gateway를 유지함.

DGX Spark에서는 Local LLM 추론 자원을 활용하여 CPU 기반 AWS Runtime보다 높은 수준의 모델을 테스트하는 방향으로 가져감.

---

# 14. DGX Spark

여러 프로젝트가 하나의 DGX Spark를 Docker로 나누어 사용하는 환경을 고려함.

```text
DGX Spark
│
├── AgentGate / Spring Boot
├── Runtime / LangGraph
├── Ollama 또는 vLLM
├── PostgreSQL
└── Redis
```

프로젝트의 핵심은 모델 학습이 아니라 **Agent Workflow 실행 및 Orchestration**이므로 초기에는 대형 모델 학습보다 Local LLM 추론과 Runtime 실행에 자원을 집중함.

---

# 15. Observability

현재 SSE 기반 Execution Studio를 확장하여 전체 실행 Trace를 관리함.

```text
Workflow
 ↓
Agent
 ↓
LLM
 ↓
Tool
 ↓
Policy
 ↓
Risk
 ↓
Approval
 ↓
Result
```

주요 지표:

- Workflow 실행 시간
- Node 실행 시간
- LLM 응답 시간
- Token Usage
- Tool 호출 횟수
- 실패율
- Retry 횟수
- Approval 대기 시간
- Policy Block 횟수

향후:

- Langfuse
- OpenTelemetry

를 추가할 수 있음.

---

# 16. 초기에는 하지 않을 것

- n8n 수준의 수백 개 Node
- 70B 이상 대형 모델
- 여러 LLM 동시 운영
- Agent Marketplace
- A2A 초기 구현
- Temporal 초기 도입
- Kubernetes
- 복잡한 RAG
- 대규모 Evaluation Platform

핵심 기능 안정화 이후 필요성에 따라 추가함.

---

# 17. 개발 우선순위

## P0 — 현재 MVP End-to-End 검증

```text
GUI
 ↓
DSL
 ↓
LangGraph
 ↓
Ollama
 ↓
MCP
 ↓
AgentGate
 ↓
Approval
 ↓
Resume
 ↓
Execution Studio
```

대표 Workflow 하나를 실제 환경에서 완전히 실행함.

## P1 — Agent Definition

```text
Agent
├── Model
├── Prompt
├── Tools
├── Memory
├── Input / Output
└── Permissions
```

## P2 — Tool Registry

```text
MCP Server
 ↓
tools/list
 ↓
Tool Registry
 ↓
Agent Builder
 ↓
Agent Tool Selection
```

## P3 — Multi-Agent

```text
Supervisor
 ├── Research Agent
 ├── Coding Agent
 └── Data Agent
        ↓
     Reviewer
```

## P4 — Memory

```text
Checkpoint
     ≠
Memory

Short-term Memory
        +
Long-term Memory / pgvector
```

## P5 — Observability

LLM / Tool / Policy / Approval까지 연결된 전체 Trace를 제공함.

---

# 18. 최종 MVP 성공 기준

```text
① 사용자가 GUI에서 Workflow 구성
        ↓
② Workflow DSL 저장
        ↓
③ Orchestrator 실행
        ↓
④ LangGraph Agent 실행
        ↓
⑤ Ollama Local LLM 추론
        ↓
⑥ Agent가 MCP Tool 호출
        ↓
⑦ AgentGate Policy / Risk 검사
        ↓
⑧ 위험 작업이면 Approval 요청
        ↓
⑨ 사용자가 GUI에서 승인
        ↓
⑩ LangGraph Resume
        ↓
⑪ Tool 실행
        ↓
⑫ Execution + Audit Log 저장
        ↓
⑬ Execution Studio에서 전체 실행 확인
```

이 흐름이 안정적으로 동작하면 프로젝트의 핵심 기술이 증명됨.

---

# 19. 최종 프로젝트 설명

### 한 줄

> **로컬 LLM 기반 AI Agent Workflow를 시각적으로 설계하고, LangGraph 기반 Orchestrator와 AgentGate Governance를 통해 안전하게 실행하는 플랫폼**

### 기술적 핵심

```text
Visual Workflow
        +
Agent Definition
        +
LangGraph Runtime
        +
Local LLM
        +
MCP
        +
Policy / Risk
        +
Human-in-the-loop
        +
Execution Trace
```

### 차별점

> Visual Workflow 플랫폼이 Workflow 자동화와 Agent 실행에 집중한다면, AgentGate는 **Agent의 Tool 행동을 Policy와 Risk 기반으로 검증하고 필요한 경우 Human Approval을 거친 후 실행**하도록 설계함.

---

# 20. 결론

현재 AgentGate는 이미 **단순 아이디어 단계가 아니라 MVP 핵심 실행 경로가 구현된 상태**임.

따라서 전체 구조를 다시 만드는 것보다:

```text
현재
Agent Workflow + Governance
        ↓
1. Agent Definition
        ↓
2. Tool Registry
        ↓
3. Multi-Agent
        ↓
4. Memory
        ↓
5. Observability
        ↓
최종
Visual AI Workflow
+ Agent Orchestration
+ Local LLM
+ Governance
```

순으로 발전시키는 것이 적절함.

핵심은 **"Agent를 만드는 플랫폼"에서 끝내지 않고, "Agent가 실제 행동을 수행하는 과정 전체를 구성하고 통제하는 플랫폼"으로 발전시키는 것**임.
