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
  "labels": ["PII"]
}
```
- `agentId` (필수): 사전에 등록된 에이전트 ID
- `action` (필수): 행동 종류를 나타내는 문자열, 자유 형식(예: `SEND_EMAIL`, `DELETE_USER`, `EXPORT_DATA`)
- `target` (선택): 행동 대상
- `labels` (선택, 배열): 위험 판단에 쓰이는 태그(예: `"PII"`). 없으면 빈 배열로 취급

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
