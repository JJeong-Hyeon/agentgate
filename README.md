# AgentGate

**AI Agents, Before They Act.**

AI 에이전트가 실제 업무를 수행하기 전에 행동의 위험성을 분석하고, 권한·정책 검증·승인·감사 기록을 제공하는 AI Agent Governance Gateway입니다.

## 문제 의식

AI Agent는 이메일 발송, 파일 삭제, DB 변경, 외부 API 호출처럼 실제 시스템에 영향을 주는 행동까지 수행하기 시작했습니다. 이때 과도한 권한 사용, 의도하지 않은 실행, 실행 기록 부족 같은 위험이 따라옵니다. AgentGate는 Agent와 실제 시스템 사이에 위치해 모든 행동 요청을 가로채고, 정책에 따라 평가한 뒤 필요하면 사람의 승인을 거치게 합니다.

## 동작 방식

```
Agent → POST /api/v1/actions → [정책 검증 → 위험도 평가] → ALLOWED / APPROVAL_REQUIRED / BLOCKED
```

- **ALLOWED**: 즉시 진행
- **APPROVAL_REQUIRED**: 승인 요청이 생성되고, 사람이 `/api/v1/approvals`에서 승인/거절할 때까지 대기
- **BLOCKED**: 즉시 차단

모든 요청은 결과와 무관하게 감사 로그(`/api/v1/audit-logs`)에 남습니다.

## 기술 스택

- Java 21, Spring Boot 4.1 (Spring Security, Spring Data JPA, Validation, WebMVC, Cache)
- PostgreSQL (데이터), Redis (Policy 조회 캐싱)
- Prometheus + Grafana (모니터링)
- Docker Compose (로컬 인프라), GitHub Actions (CI)

## 빠른 시작

```bash
docker compose up -d      # postgres, redis, prometheus, grafana
./gradlew bootRun
```

기동 로그에서 개발용 시드 에이전트(`mail-agent`)의 API 키를 확인할 수 있습니다.

```bash
curl -X POST localhost:8080/api/v1/actions \
  -H "X-API-Key: <시드 로그에서 확인한 키>" \
  -H "Content-Type: application/json" \
  -d '{"agentId":"mail-agent","action":"SEND_EMAIL","target":"user@test.com","labels":["PII"]}'
# {"status":"APPROVAL_REQUIRED","riskLevel":"HIGH","approvalId":1}

curl -u admin:changeme -X POST localhost:8080/api/v1/approvals/1/approve \
  -H "Content-Type: application/json" -d '{"decidedBy":"me"}'
```

## API 개요

| 영역 | 엔드포인트 | 인증 |
|---|---|---|
| 행동 평가 | `POST /api/v1/actions` | Agent API Key (`X-API-Key`) |
| Agent 관리 | `/api/v1/agents` | 관리자 Basic Auth |
| Policy 관리 | `/api/v1/policies` | 관리자 Basic Auth |
| 승인 | `/api/v1/approvals` | 관리자 Basic Auth |
| 감사 로그 | `/api/v1/audit-logs` | 관리자 Basic Auth |

자세한 요청/응답 형식은 [`docs/AGENTGATE_PLAN.md`](docs/AGENTGATE_PLAN.md)를 참고하세요.

## 프로젝트 구조

```
com.agentgate
├── agent      # Agent 등록/인증, 행동 평가 API
├── policy     # Policy 관리 + 매칭 로직
├── risk       # 위험도 평가, 상태 enum
├── approval   # 승인 워크플로우
├── audit      # 불변 감사 로그
├── common     # 예외, 공통 응답, 보안 유틸
└── config     # Security, Cache, 시드 데이터
```

## 테스트

```bash
./gradlew test
```

67개 테스트 (단위 + `@SpringBootTest` 통합 테스트, H2 기반이라 Docker 불필요). PR마다 GitHub Actions로 자동 실행됩니다.

## 배포

`spring.profiles.active=prod`로 기동 시 필수 환경변수가 없거나 기본 admin 비밀번호가 그대로면 기동에 실패합니다. AWS(EC2+RDS+ALB) 배포 절차는 [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md), 필요한 환경변수 목록은 [배포 섹션](docs/AGENTGATE_PLAN.md#12-배포-prod-프로파일)을 참고하세요.

## 문서

- 전체 기획/설계: [`docs/AGENTGATE_PLAN.md`](docs/AGENTGATE_PLAN.md)
- AWS 배포: [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md)
- Grafana Cloud / n8n 연동: [`docs/GRAFANA_N8N_SETUP.md`](docs/GRAFANA_N8N_SETUP.md)
