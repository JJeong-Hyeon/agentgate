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

Redis (Phase 3 승인 시스템 구현 시점에 도입)

활용:
- 승인 대기 상태 관리
- 이벤트 처리

## Monitoring

Grafana + Prometheus (Phase 4 이후 도입, Actuator는 기반 마련됨)

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
- Docker
- Docker Compose

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
```

`/api/v1/actions` 호출마다 평가 시점 스냅샷이 불변 기록으로 남는다. 이후 승인이 결정돼도 로그 자체는 갱신되지 않으며, 승인의 현재 상태는 `approvalId`로 승인 API를 조회해서 확인한다.

## Policy 관리

```
POST   /api/v1/policies       - 생성
GET    /api/v1/policies       - 전체 목록
GET    /api/v1/policies/{id}  - 단건 조회
PUT    /api/v1/policies/{id}  - 수정
DELETE /api/v1/policies/{id}  - 삭제
```

Request/Response 예시:

```json
{
  "actionType": "EXPORT_DATA",
  "label": null,
  "riskLevel": "MEDIUM"
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
- 개인정보 정책
- 보안 정책
- 업무 승인 정책

## Multi Agent Governance
- Agent 권한 관리
- Agent 간 통신 검증
- 행동 분석

---

# Project Status

- [x] Spring Boot 프로젝트 생성
- [x] Java 21 환경 구성
- [x] GitHub Repository 생성
- [x] Gateway API 구현
- [x] Policy Engine 구현
- [x] Approval Workflow 구현
- [x] Audit Dashboard 구현 (로그 저장 + 조회 API까지; 대시보드 UI는 향후 과제)
