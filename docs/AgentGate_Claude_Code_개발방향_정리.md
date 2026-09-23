# AgentGate 프로젝트 - Claude Code 전달용 개발 방향 정리

## 1. 프로젝트 개요

프로젝트명: AgentGate

목표: AI Agent가 실제 시스템에 행동(Action)을 수행하기 전에, 정책 검증,
위험 평가, 승인 처리, 감사 기록을 제공하는 AI Agent Governance Gateway
구축.

핵심 컨셉:

    AI Agent
       |
       ↓
    AgentGate
       |
       ├── Policy Check
       ├── Risk Assessment
       ├── Approval Workflow
       └── Audit Logging
       |
       ↓
    External System

------------------------------------------------------------------------

# 2. 현재 개발 환경

## Backend

-   Java 21
-   Spring Boot 4.1.1
-   Gradle
-   Spring Security
-   Spring Data JPA
-   PostgreSQL 예정

## Development

-   macOS Apple Silicon iMac
-   IntelliJ IDEA
-   Claude Code 사용 예정
-   GitHub Repository 연결 완료
-   Docker 설치 완료

현재: - Spring Boot 프로젝트 생성 완료 - 로컬 실행 성공 - GitHub push
완료

------------------------------------------------------------------------

# 3. 현재 목표 개발 범위

AgentGate는 단순 CRUD 서비스가 아니라 Enterprise AI Governance Platform
형태를 목표로 한다.

구현 범위:

## Core Gateway

-   Agent 등록
-   Agent Action 요청 API
-   요청 검증
-   실행 가능 여부 판단

## Policy Engine

역할: Agent 행동이 사전에 정의된 정책에 맞는지 검사.

예:

    개인정보 포함
    +
    외부 전송

    ↓

    HIGH Risk

    ↓

    Approval Required

## Risk Engine

평가 요소:

-   데이터 민감도
-   Action 유형
-   대상 시스템
-   권한 수준

결과:

-   ALLOWED
-   APPROVAL_REQUIRED
-   BLOCKED

## Approval Workflow

-   승인 요청 생성
-   승인/거절 처리
-   승인 상태 관리

## Audit Logging

저장 정보:

-   Agent ID
-   Action
-   요청 시간
-   Policy 결과
-   Risk 결과
-   승인 여부
-   실행 결과

------------------------------------------------------------------------

# 4. Monitoring 구축 방향

## 기존 Grafana 확인 결과

모두의 AI 기술자원도구에서 제공하는 Grafana를 확인했으나, 현재
환경에서는 Data Source 설정 권한이 없어 직접 Prometheus 연결 설정이
어려움.

따라서 해당 Grafana 환경을 사용하지 않고, AWS 환경에서 직접 Monitoring
Stack을 구축한다.

------------------------------------------------------------------------

# 5. AWS 배포 목표 구조

EC2 2대 구성 예정.

## EC2 #1 - AgentGate Application Server

역할:

-   Spring Boot AgentGate 실행
-   API 제공
-   PostgreSQL 연동

구성:

    EC2 #1

    Spring Boot
    AgentGate

    PostgreSQL

------------------------------------------------------------------------

## EC2 #2 - Monitoring Server

역할:

-   Metrics 수집
-   Dashboard 제공
-   Logging 확장

구성:

    EC2 #2

    Prometheus
        |
        ↓
    Grafana

    (추후)
    Loki

------------------------------------------------------------------------

# 6. 최종 아키텍처 방향

                     User
                      |
                      ↓

              AgentGate API Server
                      |
            ┌─────────┴─────────┐
            │                   │
            ↓                   ↓

       PostgreSQL          Audit Log


              EC2 #1
       ┌────────────────┐
       │ Spring Boot    │
       │ AgentGate      │
       └───────┬────────┘
               |
               | Metrics
               ↓


              EC2 #2
       ┌────────────────┐
       │ Prometheus     │
       │ Grafana        │
       │ Loki(optional) │
       └────────────────┘

------------------------------------------------------------------------

# 7. Monitoring Dashboard 목표

## Agent Request Monitoring

표시:

-   전체 Action 요청 수
-   API 요청량
-   성공/실패 비율
-   HTTP Error

## Risk Monitoring

표시:

-   Risk Score
-   Policy 결과
-   승인 요청 수
-   차단 요청 수

## Approval Monitoring

표시:

-   승인 대기
-   승인 완료
-   거절
-   평균 승인 시간

## System Monitoring

표시:

-   CPU
-   Memory
-   JVM Heap
-   Thread
-   API Latency
-   Uptime

------------------------------------------------------------------------

# 8. n8n 연동 방향

n8n은 AI Agent 역할을 시뮬레이션하는 도구로 사용.

구조:

    n8n
     |
     | Action Request
     ↓

    AgentGate API

     |
     ↓

    Policy / Risk 판단

     |
     ↓

    Approval Required 또는 Execute

목표:

실제 AI Agent가 없어도 "요청 → 검증 → 승인 → 실행" 전체 흐름을 데모할 수
있도록 구성.

------------------------------------------------------------------------

# 9. Claude Code 작업 요청 방향

앞으로 구현 시 아래 원칙 유지.

1.  AGENTGATE_PLAN.md 기준으로 개발
2.  MVP 범위를 유지
3.  Controller-Service-Repository 구조 유지
4.  Entity와 DTO 분리
5.  테스트 코드 작성
6.  Docker 기반 배포 고려
7.  운영 환경(Monitoring, Logging)을 고려한 설계

------------------------------------------------------------------------

# 10. 구현 우선순위

Phase 1: - Agent Entity - Action Request API - 기본 Policy 구조 - Risk
평가

Phase 2: - Approval Workflow - Audit Log

Phase 3: - PostgreSQL 연동 - AWS EC2 배포

Phase 4: - Prometheus - Grafana - Loki - n8n Demo Workflow

------------------------------------------------------------------------

# 최종 목표

AgentGate를 단순 API 서버가 아닌,

"AI Agent가 안전하게 행동하도록 통제하는 Enterprise Governance Platform"

으로 구현한다.

주요 포인트:

-   AI Agent 행동 통제
-   위험 기반 승인
-   실행 기록 관리
-   운영 모니터링 환경 구축
-   AWS 기반 배포 경험 확보
