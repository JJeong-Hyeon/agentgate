# AgentGate API 스펙

Agent Runtime(LangGraph `agentgate_client` 등) 또는 외부 Agent가 AgentGate를 호출할 때 쓰는 API 스펙. Runtime은 Tool 실행 직전에 1번 엔드포인트를 호출하고, `status`가 `ALLOWED`일 때만 Tool을 실행한다. 전체 구조는 [`ARCHITECTURE.md`](ARCHITECTURE.md) 참고.

## 1. 핵심 엔드포인트 — 행동 평가

```
POST /api/v1/actions
```

**헤더**
```
Content-Type: application/json
X-API-Key: <agent api key>
```

**요청 바디**
```json
{
  "agentId": "mail-agent",
  "action": "SEND_EMAIL",
  "target": "customer@test.com",
  "labels": ["PII"],
  "executionId": "b80f8eea-fbd9-46f3-9223-2ce401b49e3d"
}
```
- `agentId` (필수): 사전에 등록된 에이전트 ID
- `action` (필수): 행동 종류를 나타내는 문자열, 자유 형식(예: `SEND_EMAIL`, `DELETE_USER`, `EXPORT_DATA`)
- `target` (선택): 행동 대상
- `labels` (선택, 배열): 위험 판단에 쓰이는 태그(예: `"PII"`). 없으면 빈 배열로 취급
- `executionId` (선택, 최대 64자): Runtime 실행 ID(LangGraph `thread_id`). 승인 요청이 생성되면 함께 저장되어 승인 후 어떤 실행을 재개할지 식별하는 데 쓰임

**응답**
```json
{
  "status": "APPROVAL_REQUIRED",
  "riskLevel": "HIGH",
  "approvalId": 12
}
```
- `status`: `ALLOWED` | `APPROVAL_REQUIRED` | `BLOCKED`
- `riskLevel`: `LOW` | `MEDIUM` | `HIGH` | `BLOCKED` (`LOW`/`MEDIUM` → `ALLOWED`, `HIGH` → `APPROVAL_REQUIRED`, `BLOCKED` → `BLOCKED`)
- `approvalId`: `status`가 `APPROVAL_REQUIRED`일 때만 값이 있고, 그 외엔 `null`

**주의**: `decision`, `approvalRequired`(boolean) 같은 필드는 없음 — `status` 값 하나로 전부 표현됨.

## 2. 에이전트 등록 (최초 1회, 관리자 인증 필요)

```
POST /api/v1/agents
Authorization: Basic <admin 계정>
Content-Type: application/json

{"agentId": "mail-agent", "name": "Mail Agent"}
```

응답에 `apiKey`가 평문으로 **한 번만** 내려옴 — 이 값을 1번 엔드포인트의 `X-API-Key`로 사용. 저장해두지 않으면 다시 조회 불가(재발급은 새 에이전트 등록으로).

## 3. 승인 처리 (status가 APPROVAL_REQUIRED일 때, 관리자 인증 필요)

```
POST /api/v1/approvals/{approvalId}/approve
POST /api/v1/approvals/{approvalId}/reject
Authorization: Basic <admin 계정>
Content-Type: application/json

{"decidedBy": "관리자 이름"}
```

승인 목록 조회 (`status`, `executionId` 필터 선택, 함께 사용 가능):

```
GET /api/v1/approvals?status=PENDING&executionId=<실행 ID>
Authorization: Basic <admin 계정>
```

## 4. 에러 응답 공통 형식

```json
{
  "timestamp": "2026-09-23T02:00:00Z",
  "status": 404,
  "error": "Not Found",
  "code": "AGENT_NOT_FOUND",
  "message": "Agent 'x' not found",
  "path": "/api/v1/actions"
}
```

## 5. curl 예시

```bash
# 1) 에이전트 등록
curl -u admin:<관리자 비밀번호> -X POST http://<host>/api/v1/agents \
  -H "Content-Type: application/json" \
  -d '{"agentId":"hr-agent","name":"HR Agent"}'

# 2) 행동 평가
curl -X POST http://<host>/api/v1/actions \
  -H "X-API-Key: <위에서 받은 apiKey>" \
  -H "Content-Type: application/json" \
  -d '{"agentId":"hr-agent","action":"DELETE_USER","target":"employee_1024","labels":["PII"]}'
```

## 6. Workflow (관리자 인증 필요)

GUI에서 만든 Workflow DSL(`docs/ARCHITECTURE.md` 7장)을 버전 단위로 저장한다. 저장 전 Runtime(`AGENTGATE_RUNTIME_BASE_URL`)이 DSL을 검증하며, Runtime이 설정되지 않았거나 응답하지 않으면 503 `RUNTIME_UNAVAILABLE`.

```
POST /api/v1/workflows                              {"workflowId": "research", "name": "선택", "dsl": {...}}  → 201, 버전 1
GET  /api/v1/workflows                              목록 (dsl 제외)
GET  /api/v1/workflows/{workflowId}                 최신 버전 dsl 포함
POST /api/v1/workflows/{workflowId}/versions        {"dsl": {...}}  → 201, 다음 버전
GET  /api/v1/workflows/{workflowId}/versions        버전 목록 (dsl 제외)
GET  /api/v1/workflows/{workflowId}/versions/{n}    해당 버전 dsl
```

- 버전은 불변이며, 저장 시 DSL의 `workflowId` / `version`은 서버가 덮어쓴다.
- `workflowId`는 소문자/숫자/`-` (최대 64자). 중복 생성 시 409 `WORKFLOW_ALREADY_EXISTS`.
- 검증 실패 시 422 `INVALID_WORKFLOW`, `errors`에 노드별 문제 목록:

```json
{
  "status": 422,
  "code": "INVALID_WORKFLOW",
  "message": "Workflow DSL is invalid",
  "errors": [{"path": "config.prompt", "message": "Unknown variable '{ghost}'", "nodeId": "a"}]
}
```

## 7. Execution (관리자 인증 필요)

저장된 Workflow 버전을 Runtime에서 실행하고 진행 상황을 기록한다.

```
POST /api/v1/executions                      {"workflowId": "research", "version": 2(선택, 생략 시 최신), "task": "..."}  → 201, status RUNNING
GET  /api/v1/executions?workflowId=          최근 50건 (nodes 제외)
GET  /api/v1/executions/{executionId}        노드별 기록(nodes) 포함
GET  /api/v1/executions/{executionId}/stream SSE: snapshot 1회 → update(event, execution) 반복, 종료 상태면 스트림 종료
```

- `status`: `RUNNING` | `WAITING_APPROVAL`(`waitingApprovalId`) | `COMPLETED` | `FAILED`(`error`)
- `nodes[]`: `nodeId`, `step`(Tool 하위 단계는 `report.approval` 형식), `status`(`RUNNING`/`WAITING`/`COMPLETED`/`FAILED`), `output`, `error`, `approvalId`, `startedAt`, `finishedAt`
- 승인 대기 중인 실행은 `/api/v1/approvals/{waitingApprovalId}/approve|reject`로 결정하면 자동으로 재개된다.
- Runtime이 응답하지 않으면 503 `RUNTIME_UNAVAILABLE` (실행은 `FAILED`로 기록), Runtime이 실행할 수 없는 DSL이면 422 `INVALID_WORKFLOW`.

`POST /api/v1/executions/{executionId}/events`는 Runtime 전용(`X-Runtime-Token`)이다.
