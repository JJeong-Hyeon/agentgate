# AgentGate API 스펙

Agent Runtime(LangGraph `agentgate_client` 등) 또는 외부 Agent가 AgentGate를 호출할 때 쓰는 API 스펙. Runtime은 Tool 실행 직전에 1번 엔드포인트를 호출하고, `status`가 `ALLOWED`일 때만 Tool을 실행한다. 전체 구조는 [`ARCHITECTURE.md`](ARCHITECTURE.md) 참고.

## 1. 핵심 엔드포인트 — 행동 평가

```
POST /api/v1/actions
```

**헤더** (둘 중 하나)
```
Content-Type: application/json
X-API-Key: <agent api key>          # Agent가 자기 이름으로 호출할 때
X-Runtime-Token: <runtime token>    # Agent Runtime: 등록된 어느 Agent의 이름으로든 평가 요청 가능
```
둘 다 없거나 맞지 않으면 401 `INVALID_API_KEY`.

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
- `requireApproval` (선택, boolean): `true`면 정책상 허용(`LOW`/`MEDIUM`)이어도 `APPROVAL_REQUIRED`로 승인 요청을 만든다. 정책상 `BLOCKED`는 그대로 차단
- `reason` (선택, 최대 1000자): 승인 요청에 저장되어 승인자에게 표시되는 설명
- `executionId` (선택, 최대 64자): Runtime 실행 ID(LangGraph `thread_id`). 승인 요청이 생성되면 함께 저장되어 승인 후 어떤 실행을 재개할지 식별하는 데 쓰임
- `agentVersion` (선택, 양의 정수): Agent 정의 버전. 지정하면 그 정의의 Tool 권한을 정책보다 먼저 적용한다 (`action`은 `MCP:<server>:<tool>`로 정의의 Tool과 대응). 정의에 없는 Tool → `BLOCKED`, 권한 `BLOCKED` → `BLOCKED`, 권한 `APPROVAL` → 정책이 허용해도 `APPROVAL_REQUIRED`. Tool에 지정한 라벨은 `labels`와 합쳐 정책 매칭에 쓰인다. 없는 버전이면 404 `AGENT_DEFINITION_NOT_FOUND`

**응답**
```json
{
  "status": "APPROVAL_REQUIRED",
  "riskLevel": "HIGH",
  "approvalId": 12,
  "basis": "POLICY"
}
```
- `status`: `ALLOWED` | `APPROVAL_REQUIRED` | `BLOCKED`
- `riskLevel`: `LOW` | `MEDIUM` | `HIGH` | `BLOCKED` (`LOW`/`MEDIUM` → `ALLOWED`, `HIGH` → `APPROVAL_REQUIRED`, `BLOCKED` → `BLOCKED`)
- `approvalId`: `status`가 `APPROVAL_REQUIRED`일 때만 값이 있고, 그 외엔 `null`
- `basis`: 판정 근거. `POLICY`(정책·위험도), `AGENT_RISK_CAP`(Agent `maxRiskLevel` 초과), `TOOL_NOT_GRANTED`(정의에 없는 Tool), `TOOL_BLOCKED`(권한 차단), `TOOL_REQUIRES_APPROVAL`(권한상 항상 승인), `APPROVAL_REQUESTED`(`requireApproval`). Audit Log에도 같은 값이 남는다

**주의**: `decision`, `approvalRequired`(boolean) 같은 필드는 없음 — `status` 값 하나로 전부 표현됨.

## 2. 에이전트 등록 (최초 1회, 관리자 인증 필요)

```
POST /api/v1/agents
Authorization: Basic <admin 계정>
Content-Type: application/json

{"agentId": "mail-agent", "name": "Mail Agent"}
```

응답에 `apiKey`가 평문으로 **한 번만** 내려옴 — 이 값을 1번 엔드포인트의 `X-API-Key`로 사용. 해시만 저장되므로 다시 조회할 수 없다. 이미 있는 `agentId`면 409 `AGENT_ALREADY_EXISTS`.

키를 잃어버렸거나 유출되면 재발급한다. 기존 키는 즉시 무효가 되고, 새 키는 응답에 한 번만 포함된다. Agent의 정책·정의·Audit 이력은 그대로 유지된다.

```
POST /api/v1/agents/{id}/api-key        → 200 {"id", "agentId", "apiKey", "issuedAt"}
```

### Agent 정의 (관리자 인증 필요)

```
PUT  /api/v1/agents/{id}/definition                     새 버전 저장 → 201
GET  /api/v1/agents/{id}/definition                     최신 버전 (없으면 404 AGENT_DEFINITION_NOT_FOUND)
GET  /api/v1/agents/{id}/definition/versions            버전 목록 (definition 제외)
GET  /api/v1/agents/{id}/definition/versions/{version}  해당 버전
```

```json
{
  "description": "Keeps team notes",
  "systemPrompt": "You manage team notes with the tools you have.",
  "model": null,
  "temperature": null,
  "tools": [
    {"server": "notes", "tool": "save_note", "permission": "APPROVAL", "labels": ["INTERNAL"]},
    {"server": "notes", "tool": "list_notes", "permission": "AUTO"}
  ],
  "maxSteps": 8,
  "outputSchema": {"type": "object", "required": ["summary"]},
  "toolCalling": "NATIVE"
}
```

- `systemPrompt`, `tools` 필수. `permission`: `AUTO`(정책에 따름) / `APPROVAL`(항상 승인) / `BLOCKED`(차단)
- `maxSteps` 1~50 (생략 시 8), `temperature` 0~2, `toolCalling` `NATIVE` / `JSON` / 생략(Runtime 기본값), `outputSchema`는 JSON 객체
- 같은 Tool 중복, 형식 오류는 400 `VALIDATION_FAILED`
- Agent 응답(`GET /api/v1/agents`)에 `description`, `latestDefinitionVersion`(정의 없으면 0) 포함

### MCP 서버 (관리자 인증 필요)

Runtime이 호출할 Streamable HTTP MCP 서버를 AgentGate에 등록한다. stdio 서버는 Runtime 호스트에서 명령을 실행하므로 화면/API로 등록할 수 없고 Runtime 설정 파일(`MCP_CONFIG_PATH`)로만 등록한다.

```
POST   /api/v1/mcp-servers         {"name": "crm", "url": "https://crm.internal/mcp", "description": "...", "enabled": true,
                                    "headers": {"Authorization": "Bearer ..."}}  → 201
GET    /api/v1/mcp-servers         목록
GET    /api/v1/mcp-servers/{id}
PUT    /api/v1/mcp-servers/{id}    url / description / enabled 수정. headers는 주면 전체 교체, 생략하면 유지. name은 바뀌지 않음
DELETE /api/v1/mcp-servers/{id}    → 204
```

- `name`: 영문/숫자/`_`/`-` 최대 64자, 중복 시 409 `MCP_SERVER_ALREADY_EXISTS`. Agent 정의의 Tool과 정책의 `MCP:<name>:<tool>`이 이 이름을 참조한다
- `url`: `http(s)://`만 허용
- `headers`: AES-256-GCM으로 암호화 저장(`AGENTGATE_SECRET_KEY`). 응답에는 `headerNames`(이름)만 포함되고 값은 다시 조회할 수 없다

Runtime 전용: `GET /api/v1/runtime/mcp-servers` (`X-Runtime-Token`) — 사용 중인 서버와 복호화된 헤더 (`Cache-Control: no-store`).

### Tool 목록 (관리자 인증 필요)

```
GET /api/v1/tools?refresh=false
```

Runtime에 설정된 MCP 서버별 Tool 목록. `refresh=true`면 Runtime 캐시(60초)를 무시한다.

```json
[{"server": "notes", "transport": "url", "error": null,
  "tools": [{"name": "save_note", "title": null, "description": "Save a note.",
             "inputSchema": {"type": "object", "required": ["title", "content"], "properties": {...}},
             "annotations": {"destructiveHint": true}}]}]
```

`source`는 서버가 등록된 곳(`agentgate` / `runtime` 설정 파일)이다. 연결되지 않은 서버는 `error`에 사유가 담기고 `tools`는 빈 배열이다. Runtime이 없거나 응답하지 않으면 503 `RUNTIME_UNAVAILABLE`.

### Tool 위험도 (관리자 인증 필요)

MCP Tool별 위험도. 정책 `MCP:<server>:<tool>`(라벨 없음)로 저장되므로 판정·캐시·Audit은 일반 정책과 같다.

```
GET    /api/v1/tool-risks?refresh=false            서버별 Tool과 위험도
PUT    /api/v1/tool-risks                          {"server": "notes", "tool": "list_notes", "riskLevel": "LOW"}
DELETE /api/v1/tool-risks?server=notes&tool=list_notes   지정 해제 → 204
POST   /api/v1/tool-risks/apply-suggestions        지정 안 된 Tool에 추천값 적용 → {"applied": 3}
```

- 각 Tool: `action`, `riskLevel`(지정값, 없으면 null), `policyId`, `effectiveRiskLevel`(라벨 없는 호출에 적용되는 값: 지정값 또는 기본 `HIGH`), `suggestedRiskLevel`
- 추천: `readOnlyHint` → `LOW`, `destructiveHint: false` → `MEDIUM`, 그 외 `HIGH`. annotations는 서버가 스스로 보고하는 값이라 자동 적용하지 않는다
- 라벨 정책(예: `PII` → `HIGH`)보다 Tool 위험도(행동 지정)가 우선한다. 라벨과 함께 더 엄격하게 하려면 행동+라벨 정책을 추가한다

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

- `AGENT` 노드의 `config.agentId` / `config.agentVersion` 참조도 검사한다. 없는 Agent, 정의가 없는 Agent, 없는 버전, 같은 Agent를 서로 다른 버전으로 고정한 경우 422 (`path`: `config.agentId`).

## 7. Execution (관리자 인증 필요)

저장된 Workflow 버전을 Runtime에서 실행하고 진행 상황을 기록한다.

```
POST /api/v1/executions                      {"workflowId": "research", "version": 2(선택, 생략 시 최신), "task": "..."}  → 201, status RUNNING
GET  /api/v1/executions?workflowId=          최근 50건 (nodes 제외)
GET  /api/v1/executions/{executionId}        노드별 기록(nodes) 포함
GET  /api/v1/executions/{executionId}/stream SSE: snapshot 1회 → update(event, execution) 반복, 종료 상태면 스트림 종료
```

- `status`: `RUNNING` | `WAITING_APPROVAL`(`waitingApprovalId`) | `COMPLETED` | `STOPPED`(`error`에 사유: 거절·차단된 Tool / 승인 노드) | `FAILED`(`error`)
- `agentVersions`: 실행에 쓴 Agent 정의 버전 (`{"note-agent": 3}`). 실행 시작 시점에 고정되며, Agent 참조를 쓸 수 없으면 실행을 만들지 않고 422
- Agent 단계의 `output`은 요약 trace다: `{"agent": {"kind": "tool_calls" | "decision" | "result" | "answer" | ..., ...}}` (`ARCHITECTURE.md` 12장)
- `nodes[]`: `nodeId`, `step`(Tool 하위 단계는 `report.approval` 형식), `status`(`RUNNING`/`WAITING`/`COMPLETED`/`FAILED`), `output`, `error`, `approvalId`, `startedAt`, `finishedAt`
- 승인 대기 중인 실행은 `/api/v1/approvals/{waitingApprovalId}/approve|reject`로 결정하면 자동으로 재개된다.
- Runtime이 응답하지 않으면 503 `RUNTIME_UNAVAILABLE` (실행은 `FAILED`로 기록), Runtime이 실행할 수 없는 DSL이면 422 `INVALID_WORKFLOW`.

`POST /api/v1/executions/{executionId}/events`는 Runtime 전용(`X-Runtime-Token`)이다.
