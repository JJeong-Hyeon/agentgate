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
- `delegatedBy` (선택, 최대 500자): 이 행동을 위임한 Agent 경로(바깥부터, 예: `supervisor>research-agent`). Audit Log와 승인 요청에 기록된다
- `agentVersion` (선택, 양의 정수): Agent 정의 버전. 지정하면 그 정의의 Tool 권한을 정책보다 먼저 적용한다 (`action`은 `MCP:<server>:<tool>`로 정의의 Tool과 대응). 정의에 없는 Tool → `BLOCKED`, 권한 `BLOCKED` → `BLOCKED`, 권한 `APPROVAL` → 정책이 허용해도 `APPROVAL_REQUIRED`. Tool에 지정한 라벨은 `labels`와 합쳐 정책 매칭에 쓰인다. 없는 버전이면 404 `AGENT_DEFINITION_NOT_FOUND`. **생략하면 Tool 권한 검사 없이 정책만 평가한다.** Runtime이 실행하는 Agent는 항상 버전을 보내므로 Tool 권한이 적용되고, 버전 없이 호출하는 것은 Agent가 API 키로 직접 부르는 경우와 워크플로 수준 Tool(HTTP/MCP Tool 노드)이다
- `payloadHash` (선택, 최대 64자): Runtime이 실행할 내용(action, target, 인자)의 SHA-256. `APPROVAL_REQUIRED`일 때 승인 요청에 저장되어 승인 응답의 `payloadHash`로 보이고, 승인 결정을 Runtime에 알릴 때(`resume`) 함께 전달된다. Runtime은 실행 직전 현재 내용의 해시와 비교해 다르면 실행하지 않고 실행을 실패시킨다(승인한 것과 실행하는 것의 결속). 해시 없이 만들어진 승인 요청은 검사하지 않는다
- `payloadPreview` (선택, 최대 20,000자): 승인자가 읽을 인자 전체(들여쓴 JSON). Runtime이 `password` · `secret` · `token` · `api_key` · `authorization` · `credential`이 들어간 키의 값을 `***`로 가려서 보내며, 승인 요청에 저장되어 승인 응답의 `payloadPreview`로 나온다. 콘솔은 승인 화면에 "인자 전체 보기"로 펼쳐 보여 준다. 해시는 가리기 전 실제 인자 기준이다

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

## 사용자와 역할 (콘솔 인증)

콘솔 API는 HTTP Basic으로 DB 사용자를 인증한다. 역할은 여럿 가질 수 있다.

같은 아이디 + 같은 IP에서 로그인이 연속 5번 실패하면 15분 동안, 한 IP에서 아이디와 상관없이 15분 안에 20번 실패하면 그 IP 전체가 15분 동안 막힌다. 막힌 동안은 비밀번호가 맞아도 `429` + `Retry-After`(초)와 `code: TOO_MANY_LOGIN_ATTEMPTS`를 돌려준다. 로그인에 성공하면 그 아이디 + IP의 실패 횟수는 초기화된다. 횟수와 시간은 `AGENTGATE_LOGIN_MAX_FAILURES`, `AGENTGATE_LOGIN_MAX_FAILURES_PER_IP`, `AGENTGATE_LOGIN_LOCK_DURATION`으로 바꾼다.

| 역할 | 할 수 있는 일 |
|---|---|
| `ADMIN` | 아래 전부 + 사용자, 정책, Tool 위험도, MCP 서버, Agent 등록·API Key·위험도 상한 |
| `EDITOR` | Workflow 저장, 실행 시작, Agent 정의 저장 |
| `APPROVER` | 승인 · 거절 |
| `VIEWER` | 조회 (GET) |

모든 역할이 조회할 수 있고, 권한이 없으면 403이다. 사용자가 없을 때 기동하면 `agentgate.admin.*` 설정으로 첫 ADMIN이 만들어진다.

```
GET    /api/v1/me                    내 정보 (username, roles)
PUT    /api/v1/me/password           {"currentPassword", "newPassword"(8자 이상)} → 204
POST   /api/v1/users                 (ADMIN) {"username", "displayName", "password", "roles": ["EDITOR"], "enabled"} → 201
GET    /api/v1/users, /api/v1/users/{id}
PUT    /api/v1/users/{id}            이름·역할·사용 여부, password는 주면 변경. username은 바뀌지 않음
DELETE /api/v1/users/{id}            → 204
```

- 자기 자신 삭제, 마지막 활성 ADMIN의 삭제·비활성화·ADMIN 역할 제거는 400 `INVALID_USER_CHANGE`
- 중복 사용자 409 `USER_ALREADY_EXISTS`, 비밀번호는 BCrypt로 저장
- 승인·거절의 `decidedBy`는 로그인한 사용자로 기록된다(요청 본문의 값은 무시)

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
  "toolCalling": "NATIVE",
  "delegates": [{"agentId": "research-agent", "permission": "AUTO"}]
}
```

- `systemPrompt`, `tools` 필수. `permission`: `AUTO`(정책에 따름) / `APPROVAL`(항상 승인) / `BLOCKED`(차단)
- `maxSteps` 1~50 (생략 시 8), `temperature` 0~2, `toolCalling` `NATIVE` / `JSON` / 생략(Runtime 기본값), `outputSchema`는 JSON 객체
- 같은 Tool 중복, 형식 오류는 400 `VALIDATION_FAILED`
- `delegates`(선택, 최대 10): 이 Agent가 일을 맡길 수 있는 Agent와 권한. 위임은 행동 `AGENT:<agentId>`로 평가되며(정책이 없으면 기본 MEDIUM·허용, 승인이 필요하면 위임 권한을 `APPROVAL`로), 맡은 Agent는 자기 정의·권한·이름으로 Tool을 실행한다. 자기 자신, 없는 Agent, 정의 없는 Agent, 중복, 위임 순환, 3단계를 넘는 위임 체인은 400
- `reasoningEffort`(선택): 추론 모델이 답 전에 생각하는 정도 — `none`(끄기, 빠름) / `minimal` / `low` / `medium` / `high`. 없으면 Runtime 기본값(`LLM_REASONING_EFFORT`)
- `memory`(선택): 장기 기억 사용 권한. `{"read": "AUTO", "write": "APPROVAL", "recall": 3}`
  - `read`: 기억 검색(Tool `memory_search`, 행동 `MEMORY:SEARCH`), `write`: 기억 저장(Tool `memory_save`, 행동 `MEMORY:SAVE`). 값은 Tool 권한과 같다(`AUTO` / `APPROVAL` / `BLOCKED`)
  - `recall`(0~10, 기본 0): 실행 시작 시 작업 프롬프트로 관련 기억을 검색해 시스템 프롬프트에 넣을 개수. 1 이상이면 `read`는 `AUTO`여야 한다(400). 정책상 바로 허용되지 않으면 회상을 건너뛴다
  - `MEMORY:*` 행동에 정책이 없으면 기본 `MEDIUM`(허용). 정책으로 위험도·승인을 지정할 수 있다
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

### Agent Memory (장기 기억)

실행 상태(Checkpoint)와 별개로 Agent가 실행을 넘어 기억하는 내용. Agent별로 저장된다.

```
GET    /api/v1/agents/{agentId}/memories?query=<검색어>   최신 100개, query가 있으면 관련도 순 (모든 역할)
POST   /api/v1/agents/{agentId}/memories                  {"content": "VIP 고객은 라떼 쿠폰을 받는다"} → 201 (ADMIN)
DELETE /api/v1/agents/{agentId}/memories/{id}             → 204 (ADMIN)
```

- 응답: `id`, `agentId`, `content`(최대 4000자), `source`(`AGENT`: 실행 중 Agent가 저장 / `USER`: 콘솔에서 추가), `executionId`, `createdBy`, `createdAt`, 검색 시 `score`·`matchedBy`(`SEMANTIC` / `KEYWORD`)
- 검색: 임베딩 모델(`AGENTGATE_EMBEDDING_BASE_URL` + `AGENTGATE_EMBEDDING_MODEL`, OpenAI 호환 `/embeddings`)과 PostgreSQL pgvector 확장이 있으면 의미 검색(코사인 유사도 `AGENTGATE_MEMORY_MIN_SCORE` 이상)을 하고, 부족한 만큼 키워드(부분 일치, "고객"이 "고객이"와 일치) 결과로 채운다. 둘 중 하나라도 없거나 임베딩 서버가 응답하지 않으면 키워드 검색만 한다
- 임베딩 없이 저장된 기억(서버 장애, 모델 설정 전)과 다른 모델로 임베딩된 기억은 5분마다 현재 모델로 다시 임베딩된다
- 저장 응답: `{"memory", "duplicate", "replaced", "similar"}`. 공백·문장부호·대소문자만 다른 **같은 문장**이 이미 있으면 새로 만들지 않고 기존 기억을 돌려준다(`duplicate: true`, 200). 새로 저장하면 201
- `similar`: 의미가 비슷한 기존 기억(코사인 유사도 `AGENTGATE_MEMORY_SIMILAR_SCORE`, 기본 0.85 이상, 최대 3개). 유사도로는 반복과 변경을 구분할 수 없어(예: bge-m3에서 같은 내용 0.964, 회의 시간 변경 0.974) 자동으로 합치지 않고 판단을 맡긴다

Runtime 전용 (`X-Runtime-Token`): `POST /api/v1/runtime/memories` `{"agentId","content","executionId","replaces"}` → 201 / 200(같은 문장). `replaces`(선택)는 이 기억이 대체하는 옛 기억 id로, 옛 기억은 삭제되지 않고 "대체됨"으로 남아 검색·회상·목록에서 빠진다(활성 기억이 아니면 404). `POST /api/v1/runtime/memories/search` `{"agentId","query","limit"(1~20, 기본 5)}`.

### 대시보드 (모든 역할)

```
GET /api/v1/dashboard?period=24h     또는 7d (그 외 400 INVALID_PERIOD)
```

- `executions`: 기간 내 시작한 실행의 결과별 건수(`byStatus`), 지금 진행 중(`running`)·승인 대기(`waitingApproval`), 끝난 실행의 평균 소요(`avgDurationMs`), 시간(24h)·일(7d)별 건수(`buckets`)
- `approvals`: 지금 대기 중(`pending`)과 오래된 순 5건(`oldestPending`), 기간 내 결정의 결과별 건수(`decided`)와 평균 대기(`avgWaitMs`)
- `governance`: 기간 내 판정 수(`total` / `allowed` / `approvalRequired` / `blocked`), 차단율, 많이 차단된 행동 5개(`topBlocked`)
- `models`: 모델별 Agent 호출 수, 평균 응답 시간, 입력/출력 토큰 (단계마다 저장된 값이라 재시작해도 유지)
- `recentProblems`: 기간 내 실패·중단된 최근 실행 5건

### Audit Log (모든 역할)

모든 행동 평가 결과(판정·근거·위험도)가 남는다.

```
GET /api/v1/audit-logs?agentId=&executionId=&status=&riskLevel=&limit=200   최신순, limit 1~1000 (기본 200)
GET /api/v1/audit-logs/{id}
GET /api/v1/audit-logs/stats?agentId=                                       위험도·판정별 건수, 차단율
```

- 응답: `id`, `agentId`, `action`, `target`, `labels`, `riskLevel`, `status`, `approvalId`, `basis`, `delegatedBy`, `executionId`(Runtime 실행 중 판정이면 그 실행 ID), `createdAt`
- 실행 화면의 "실행 분석"은 `executionId`로 이 목록과 승인 요청(`/api/v1/approvals?executionId=`)을 모아 판정·승인 대기 시간을 보여준다

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

승인 요청의 `status`: `PENDING` | `APPROVED` | `REJECTED` | `EXPIRED`. `PENDING`인 채로 `agentgate.approval.ttl`(기본 24시간, 환경변수 `AGENTGATE_APPROVAL_TTL`)이 지나면 `EXPIRED`가 되고(`decidedBy`는 `system`), 대기 중인 실행은 거절과 같이 처리된다. 이미 결정·만료된 요청을 승인·거절하면 409.

승인 요청이 생기면 `AGENTGATE_NOTIFY_WEBHOOK_URL`로 알림을 보낸다(실패 시 `PENDING`인 동안 30초마다 재시도). `JSON` 포맷 본문:

```json
{"event": "approval.requested", "approval": { /* 위 승인 요청 응답과 같은 필드 */ }, "url": "https://<AGENTGATE_PUBLIC_URL>/executions/<실행 ID>"}
```

`AGENTGATE_NOTIFY_WEBHOOK_SECRET`을 설정하면 `X-AgentGate-Signature: sha256=<본문의 HMAC-SHA256 hex>` 헤더가 붙는다. `SLACK` 포맷은 `{"text": "..."}` 한 필드.

승인 요청과 Audit Log 응답의 `delegatedBy`는 이 행동을 위임한 Agent 경로다(바깥부터, 예: `lead>research`; 직접 실행이면 `null`). 승인 요청의 `agentId`는 실제로 행동한(위임받은) Agent다.

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
