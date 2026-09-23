# AgentGate 프로젝트 방향 업데이트 (n8n AI Agent 연동)

## 1. 변경 방향

기존 AgentGate는 "AI가 행동하기 전 마지막 관문" 역할의 AI Agent 보안
Gateway를 목표로 한다.

기존 구조:

    AI Agent (GPT / Claude 등)
            |
            v
          n8n
            |
            v
       AgentGate
            |
            v
     실제 Tool 실행

에서 한 단계 확장하여 n8n 내부에서 AI Agent Workflow까지 구성한다.

최종 목표:

                     User
                      |
                      v
              n8n Chat Trigger
                      |
                      v
              LLM (GPT / Claude)
                      |
                      v
              n8n AI Agent Node
                      |
            +---------+---------+
            |                   |
        일반 작업             위험 작업
                                |
                                v
                           AgentGate API
                                |
                  +-------------+-------------+
                  |                           |
                Allow                       Block
                  |                           |
            실제 Tool 실행              승인 요청/로그 저장

------------------------------------------------------------------------

## 2. 역할 정의

### n8n

-   AI Agent Workflow Orchestrator 역할
-   사용자 입력 처리
-   LLM 호출
-   Tool 실행 흐름 관리
-   AgentGate API 호출

### GPT / Claude

-   자연어 이해
-   사용자 의도 분석
-   실행 계획 생성

### AgentGate

AI Agent 행동 통제 Gateway

담당 기능: - 요청 검증 - 권한 확인 - 위험도 평가 - 정책 적용 - 승인 필요
여부 판단 - 감사 로그 저장

### Grafana

-   Agent 행동 모니터링
-   차단/승인 이력 시각화
-   운영 Dashboard 제공

------------------------------------------------------------------------

## 3. 현재까지 검증 완료

### n8n HTTP Request 테스트 완료

구성:

    Manual Trigger
            |
            v
    HTTP Request
            |
            v
    httpbin.org/post

검증 내용: - n8n Workflow 실행 가능 - HTTP API 호출 가능 - JSON Payload
전달 가능

테스트 Payload:

``` json
{
  "agentId": "mail-agent",
  "action": "SEND_EMAIL",
  "target": "customer@test.com",
  "labels": [
    "PII"
  ]
}
```

------------------------------------------------------------------------

## 4. 다음 구현 단계

### Step 1. n8n AI Agent 구성

목표:

    User Input
        |
        v
    AI Agent Node
        |
        v
    Action 판단

예시:

사용자: "퇴사자 계정을 삭제해줘"

AI Agent 결과:

``` json
{
  "action": "DELETE_USER",
  "target": "employee_database",
  "riskLevel": "HIGH"
}
```

------------------------------------------------------------------------

### Step 2. AgentGate API 연결

n8n HTTP Request를 아래 형태로 변경

    n8n
     |
     | POST /api/check
     v
    Spring Boot AgentGate

요청 예시:

``` json
{
  "agentId": "hr-agent",
  "action": "DELETE_USER",
  "target": "employee_1024"
}
```

응답 예시:

``` json
{
  "decision": "BLOCK",
  "riskLevel": "HIGH",
  "approvalRequired": true
}
```

------------------------------------------------------------------------

## 5. 구현 목표 시나리오

### 위험 작업 차단 Demo

사용자: "전체 고객 데이터를 삭제해줘"

Flow:

1.  n8n AI Agent가 요청 분석
2.  삭제 Action 생성
3.  AgentGate 전달
4.  위험도 HIGH 판단
5.  실행 차단
6.  Audit Log 저장
7.  Grafana Dashboard 표시

------------------------------------------------------------------------

## 6. 프로젝트 핵심 메시지

단순한 AI API 개발이 아니라,

"AI Agent가 실제 업무 환경에서 Tool을 실행하기 전, 보안 정책과 권한
검증을 수행하는 Enterprise AI Governance Gateway"

구현을 목표로 한다.
