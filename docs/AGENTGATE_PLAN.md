# AgentGate
## AI Agents, Before They Act.

> AI 에이전트의 행동 전, 마지막 관문  
> AI Agents, Before They Act.

---

# 1. 프로젝트 개요

## 프로젝트명

**AgentGate**

## 한 줄 소개

AI 에이전트가 실제 업무를 수행하기 전에 행동의 위험성을 분석하고,
권한·정책 검증·승인·감사 기록을 제공하는 AI Agent Governance Gateway

---

# 2. 문제 정의

최근 AI Agent는 단순 질의응답을 넘어 실제 업무 수행 영역으로 확장되고 있다.

예:
- 이메일 발송
- 파일 수정 및 삭제
- 데이터베이스 변경
- 외부 API 호출
- 고객 정보 처리

하지만 AI Agent가 자동으로 행동하는 과정에서 다음과 같은 위험이 발생할 수 있다.

## 주요 위험

### 1) 과도한 권한 사용
AI Agent가 필요 이상의 시스템 권한을 가지고 동작할 수 있음.

### 2) 의도하지 않은 실행
사용자의 요청을 잘못 해석하여 위험한 작업 수행 가능.

### 3) 실행 기록 부족
AI Agent가 수행한 행동에 대한 추적 및 감사 어려움.

---

# 3. 해결 방향

AgentGate는 AI Agent와 실제 시스템 사이에 위치하는
**행동 검증 Gateway** 역할을 수행한다.

```
User
 |
AI Agent
 |
AgentGate
 |
+----------------+
| Policy Check   |
| Risk Analysis  |
| Approval Flow  |
| Audit Logging  |
+----------------+
 |
System
```

---

# 4. 핵심 기능

## 4.1 Agent Action Interception

AI Agent의 모든 행동 요청을 AgentGate에서 먼저 수신한다.

## 4.2 Policy Engine

사전에 정의된 정책을 기반으로 Agent 행동을 검증한다.

예:
- 일반 조회 → 허용
- 개인정보 포함 → 승인 필요
- 외부 전송 → 위험 평가
- 데이터 삭제 → 차단 또는 승인

## 4.3 Risk Assessment

Agent 행동의 위험도를 평가한다.

평가 요소:
- 데이터 민감도
- 실행 대상
- 행동 유형
- 권한 수준
- 외부 영향도

Risk Level:
- LOW
- MEDIUM
- HIGH
- BLOCKED

## 4.4 Human Approval Workflow

위험도가 높은 행동은 사람이 승인해야 실행되고, BLOCKED로 평가된 행동은 승인 요청 없이 즉시 차단된다.

```
Agent Request
      |
Risk Analysis
      |
      +-- BLOCKED --> Reject (즉시 차단, 승인 요청 없음)
      |
     HIGH Risk
      |
Approval Request
      |
Human Approval
      |
Execute
```

## 4.5 Audit Logging

AI Agent 행동 기록을 저장한다.

저장 정보:
- Agent ID
- 요청 시간
- 요청 내용
- 정책 결과
- 위험도
- 승인 여부
- 실행 결과

---

# 5. 시스템 구성

## Backend

Spring Boot 기반 Gateway Server

역할:
- Agent 요청 처리
- 정책 검증
- 위험 분석
- 승인 관리
- 감사 로그 저장

## Database

PostgreSQL

저장:
- Agent 정보
- Policy
- Approval Request
- Audit Log

## Cache / Queue

Redis (도입 완료 — 용도는 아래 참고)

활용:
- Policy 조회 캐싱: `/api/v1/actions` 호출마다 Policy 테이블 전체를 다시 읽지 않도록 캐싱. Policy 생성/수정/삭제 시 즉시 무효화되며, TTL(5분)은 안전망으로만 존재
- Approval 상태는 캐싱하지 않음 — Postgres가 이미 트랜잭션으로 완전히 관리하고 있어 중복 저장할 이유가 없음

## Monitoring

Grafana + Prometheus (도입 완료) — `/actuator/prometheus`로 메트릭 노출, `docker compose up`으로 prometheus(9090)/grafana(3000) 기동

관리:
- Agent 요청량
- 실패율
- 위험 행동 통계
- 승인 처리 시간

---

# 6. 기술 스택

## Backend
- Java 21
- Spring Boot 4.1
- Spring Security
- Spring Data JPA

## Database
- PostgreSQL

## Infrastructure
- Docker, Docker Compose (로컬)
- AWS: EC2(앱), EC2(모니터링, Prometheus+Grafana 셀프호스팅), RDS(Postgres), ALB — `infra/`의 Terraform으로 관리(`docs/DEPLOYMENT.md`)

## Monitoring
- Prometheus
- Grafana

## Development
- IntelliJ IDEA
- Claude Code
- GitHub

---

# 7. MVP 개발 범위

## Phase 1. Gateway Core
- Action Request API
- Agent Entity
- Policy Entity
- Risk Evaluation

## Phase 2. Policy Engine
- Rule 기반 정책 검사
- 위험도 계산
- 실행 가능 여부 판단

## Phase 3. Approval System
- 승인 요청 생성
- 승인/거절 API
- 승인 상태 관리

## Phase 4. Audit System
- 실행 로그 저장
- 조회 API

---

# 8. API 설계

## 인증

- `/api/v1/policies/**`, `/api/v1/approvals/**`, `/api/v1/audit-logs/**`, `/api/v1/agents/**`: HTTP Basic Auth (운영자 계정, `agentgate.admin.username`/`agentgate.admin.password` 설정값)
- `/api/v1/actions`: Agent API Key (`X-API-Key` 헤더). 키는 `POST /api/v1/agents`로 에이전트를 등록할 때 한 번만 평문으로 응답에 포함되며, 이후엔 해시만 저장되어 다시 조회할 수 없다.

## Agent 등록

```
POST /api/v1/agents        - 생성 (관리자 인증 필요), 응답에 평문 apiKey 1회만 포함, 201
GET  /api/v1/agents        - 목록 (관리자 인증 필요, 키 미노출), 200
GET  /api/v1/agents/{id}   - 단건 (관리자 인증 필요, 키 미노출), 200
PUT  /api/v1/agents/{id}/max-risk-level  - 위험도 상한 설정/해제(null), 200
```

`maxRiskLevel`이 설정된 Agent는 평가 결과가 그 상한을 넘으면 정책과 무관하게 `BLOCKED`로 강제 격하된다.

`/api/v1/actions` 호출 예시:

```
curl -u admin:<password> -X POST localhost:8080/api/v1/agents \
  -H "Content-Type: application/json" -d '{"agentId":"mail-agent","name":"Mail Agent"}'
# 응답의 apiKey를 그대로 사용

curl -X POST localhost:8080/api/v1/actions -H "X-API-Key: <apiKey>" \
  -H "Content-Type: application/json" -d '{"agentId":"mail-agent","action":"VIEW_DATA","labels":[]}'
```

## Action 요청

POST

```
/api/v1/actions
```

Request:

```json
{
  "agentId": "mail-agent",
  "action": "SEND_EMAIL",
  "target": "user@test.com",
  "labels": [
    "PII"
  ]
}
```

Response:

```json
{
  "status": "APPROVAL_REQUIRED",
  "riskLevel": "HIGH",
  "approvalId": 1
}
```

`riskLevel`이 `HIGH`일 때만 `approvalId`가 채워지며, 아래 승인 API로 추적한다.

## 승인 (Approval)

```
POST /api/v1/approvals/{id}/approve  - 승인
POST /api/v1/approvals/{id}/reject   - 거절
GET  /api/v1/approvals               - 목록 (?status=PENDING 필터 가능)
GET  /api/v1/approvals/{id}          - 단건 조회
```

## 감사 로그 (Audit Log)

```
GET /api/v1/audit-logs                - 목록 (?agentId=&status=&riskLevel= 필터 가능, AND 조합)
GET /api/v1/audit-logs/{id}           - 단건 조회
GET /api/v1/audit-logs/stats          - 집계 (?agentId= 선택), totalCount/countByRiskLevel/countByStatus/blockRate
```

`/api/v1/actions` 호출마다 평가 시점 스냅샷이 불변 기록으로 남는다. 이후 승인이 결정돼도 로그 자체는 갱신되지 않으며, 승인의 현재 상태는 `approvalId`로 승인 API를 조회해서 확인한다.

## Policy 관리

```
POST   /api/v1/policies             - 생성
GET    /api/v1/policies             - 전체 목록 (?category= 필터 가능)
GET    /api/v1/policies/{id}        - 단건 조회
PUT    /api/v1/policies/{id}        - 수정
DELETE /api/v1/policies/{id}        - 삭제
```

`category`는 `PRIVACY`(개인정보 정책) / `SECURITY`(보안 정책) / `APPROVAL_WORKFLOW`(업무 승인 정책) 중 하나이며 선택값(null 가능) — 매칭 로직에는 영향 없고 분류/조회 필터용.

Request/Response 예시:

```json
{
  "actionType": "EXPORT_DATA",
  "label": null,
  "riskLevel": "MEDIUM",
  "category": "PRIVACY"
}
```

---

# 9. 패키지 구조

```
com.agentgate

├── agent
│   ├── controller
│   ├── service
│   ├── domain
│   └── repository
│
├── policy
│
├── risk
│
├── approval
│
├── execution
│
├── audit
│
└── common
    ├── exception
    └── response
```

---

# 10. 프로젝트 목표

AgentGate는 AI Agent 시대에 필요한
"실행 전 안전 계층(Execution Safety Layer)"을 제공하는 것을 목표로 한다.

AI Agent가 더 많은 권한을 가지고 실제 업무를 수행할수록,
행동 이전의 검증과 통제 시스템이 중요해진다.

AgentGate는 AI Agent와 기업 시스템 사이에서
안전하고 추적 가능한 AI 실행 환경을 제공한다.

---

# 11. 향후 확장 방향

## Connector 확장
- Gmail
- Slack
- Google Drive
- Database

## Enterprise Policy Management
- [x] Policy 카테고리 분류 (`PRIVACY`/`SECURITY`/`APPROVAL_WORKFLOW`, `?category=` 필터)

## Multi Agent Governance
- [x] Agent 권한 관리 (위험도 상한, `maxRiskLevel`)
- Agent 간 통신 검증
- [x] 행동 분석 (`GET /api/v1/audit-logs/stats`)

---

# 12. 배포 (prod 프로파일)

`spring.profiles.active=prod`로 기동 시 admin 비밀번호가 dev 기본값이면 즉시 기동 실패(`SecurityHardeningCheck`). 필수 환경변수:

- `AGENTGATE_ADMIN_USERNAME`, `AGENTGATE_ADMIN_PASSWORD`
- `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`
- `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT`

`spring-boot-docker-compose`는 developmentOnly라 prod 빌드에는 포함되지 않음 — 위 값들을 직접 넣어야 함.

AWS(EC2+RDS+ALB) 실제 배포 절차는 [`docs/DEPLOYMENT.md`](DEPLOYMENT.md) 참고.

---

# Project Status

- [x] Spring Boot 프로젝트 생성
- [x] Java 21 환경 구성
- [x] GitHub Repository 생성
- [x] Gateway API 구현
- [x] Policy Engine 구현
- [x] Approval Workflow 구현
- [x] Audit Dashboard 구현 (로그 저장 + 조회 API까지; 대시보드 UI는 향후 과제)
