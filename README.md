# AgentGate

**AI Agents, Before They Act.**

AI 에이전트가 실제 업무를 수행하기 전에 행동의 위험성을 분석하고, 권한 · 정책 · 승인 · 감사 기록을 제공하는 **AI Agent Governance Platform**입니다. 사내 서버에 직접 설치해서(self-hosted) 쓰고, 모델도 회사가 가진 로컬 LLM(Ollama, vLLM 등)을 연결합니다. 데이터와 인증 정보는 사내망 밖으로 나가지 않습니다.

## 문제 의식

AI Agent는 이메일 발송, 파일 삭제, DB 변경, 외부 API 호출처럼 실제 시스템에 영향을 주는 행동까지 수행하기 시작했습니다. 이때 과도한 권한 사용, 의도하지 않은 실행, 실행 기록 부족 같은 위험이 따라옵니다. AgentGate는 Agent와 실제 시스템 사이에 위치해 모든 행동 요청을 가로채고, **권한 → 정책 → 위험도** 순서로 평가한 뒤 필요하면 사람의 승인을 거치게 합니다.

```
Agent / Workflow ──▶ AgentGate ──▶ ALLOWED            즉시 진행
                     (권한·정책·위험도)  APPROVAL_REQUIRED  사람이 승인 · 거절할 때까지 대기
                                      BLOCKED            즉시 차단
```

모든 요청은 결과와 무관하게 감사 로그에 남습니다. 평가에 실패하면 실행하지 않습니다(fail-closed).

## 주요 기능

- **Workflow Builder**: React Flow 화면에서 LLM · Agent · Router · Reviewer · Condition · HTTP/MCP Tool · Approval 노드로 워크플로를 그립니다. LangGraph Runtime이 실행합니다.
- **Agent 정의**: 시스템 프롬프트, 사용할 Tool과 Tool별 권한(자동 / 항상 승인 / 차단), 출력 스키마, 추론 강도. 버전으로 관리합니다.
- **Tool Registry**: MCP 서버를 화면에서 등록(인증 헤더는 암호화 저장)하고 Tool마다 위험도(LOW / MEDIUM / HIGH / BLOCKED)를 지정합니다.
- **Multi-Agent**: 한 Agent가 다른 Agent에게 일을 맡기고, 맡은 Agent는 자기 권한으로 실행합니다. 위임 경로가 기록에 남습니다.
- **승인(Human-in-the-loop)**: 위험한 호출은 멈추고, 승인자가 인자 전체를 보고 결정하면 이어서 실행합니다. 승인 요청 알림(Slack · JSON 웹훅), 만료, 승인 내용과 실행 내용의 일치 검사를 지원합니다.
- **Memory**: Agent의 장기 기억(pgvector 의미 검색, 없으면 키워드 검색).
- **Execution Studio**: 단계별 모델 판단, Tool 호출과 결과, 승인 대기, 지연 시간과 토큰을 실시간으로 봅니다.
- **관측**: Prometheus 지표 + Grafana 대시보드, OpenTelemetry trace.
- **운영**: 역할(ADMIN / EDITOR / APPROVER / VIEWER), 로그인 실패 제한, DB 마이그레이션(Flyway), Docker Compose 스택(HTTPS 자동), 실행 기록 보존 기간, Runtime 재시작 후 자동 복구.

## 구성

```
브라우저 ──HTTPS──▶ Caddy ──▶ app (Spring Boot: API + 콘솔)  ◀──▶ PostgreSQL, Redis
                                │  ▲
                                ▼  │
                             runtime (Python · LangGraph) ──▶ 모델 서버 (OpenAI 호환: Ollama / vLLM)
                                │
                                └──▶ 사내 시스템의 MCP 서버 / HTTP API  (AgentGate가 허용한 호출만)
```

- Java 21 · Spring Boot 4.1 (Security, JPA, Flyway), PostgreSQL(+pgvector), Redis
- Python 3.12 · FastAPI · LangGraph, MCP 클라이언트
- React · TypeScript · Vite · React Flow
- Caddy, Prometheus, Grafana, OpenTelemetry, Docker Compose, GitHub Actions

## 설치해서 써 보기

릴리스 이미지(`ghcr.io/jjeong-hyeon/agentgate`, `agentgate-runtime`)를 받아 Docker Compose로 한 번에 띄웁니다. 서버에 Docker와 모델 서버(Ollama, vLLM 등 OpenAI 호환)가 있어야 합니다. AgentGate는 모델을 포함하지 않습니다.

```bash
git clone https://github.com/JJeong-Hyeon/agentgate.git
cd agentgate/deploy/selfhost
cp .env.example .env     # 도메인, 비밀번호, 모델 서버 주소를 채우고 AGENTGATE_VERSION=<릴리스 버전>을 적는다
docker compose pull && docker compose up -d
```

전체 절차(모델 서버 연결, 첫 설정, 백업 · 업그레이드)는 [`docs/SELF_HOSTING.md`](docs/SELF_HOSTING.md), 도입 후 운영은 [`docs/OPERATIONS_GUIDE.md`](docs/OPERATIONS_GUIDE.md)를 보세요. 버전별 변경은 [`CHANGELOG.md`](CHANGELOG.md), 릴리스는 [GitHub Releases](https://github.com/JJeong-Hyeon/agentgate/releases)에 있습니다.

## 개발 환경 빠른 시작

```bash
docker compose up -d      # postgres, redis, prometheus, grafana (개발 전용, 포트는 127.0.0.1)
./gradlew bootRun
```

기동 로그에서 개발용 시드 에이전트(`mail-agent`)의 API 키를 확인할 수 있습니다.

```bash
curl -X POST localhost:8080/api/v1/actions \
  -H "X-API-Key: <시드 로그에서 확인한 키>" \
  -H "Content-Type: application/json" \
  -d '{"agentId":"mail-agent","action":"SEND_EMAIL","target":"user@test.com","labels":["PII"]}'
# {"status":"APPROVAL_REQUIRED","riskLevel":"HIGH","approvalId":1}

curl -u admin:changeme -X POST localhost:8080/api/v1/approvals/1/approve
```

개발용 기본 관리자 계정은 `admin` / `changeme`입니다. 운영(`prod` 프로파일)에서는 이 값이면 기동에 실패합니다.

### Agent Runtime 직접 띄우기 (Python + LangGraph)

```bash
docker compose --profile runtime up -d --build   # runtime(:8000) + ollama(:11434)
docker compose exec ollama ollama pull qwen2.5:7b
curl localhost:8000/health
```

Runtime의 Tool은 실행 직전에 AgentGate(`POST /api/v1/actions`)를 호출하고, `ALLOWED`일 때만 실제 요청을 보냅니다. Runtime은 공유 토큰(`RUNTIME_TOKEN`)으로 인증하며, 워크플로 수준의 Tool / 승인 노드는 `runtime-agent`로, Agent 노드는 각 Agent 이름으로 평가됩니다. `runtime-agent`를 한 번 등록해 주세요.

```bash
curl -u admin:changeme -X POST localhost:8080/api/v1/agents \
  -H "Content-Type: application/json" -d '{"agentId":"runtime-agent","name":"Runtime Agent"}'
docker compose --profile runtime up -d
```

실행할 워크플로는 Workflow DSL로 정의합니다 (예: [`runtime/examples/research.json`](runtime/examples/research.json)). 보통은 프론트엔드 Builder로 만들어 AgentGate에 저장하고, `POST /api/v1/executions`(또는 화면의 실행 버튼)로 실행합니다.

AgentGate가 `APPROVAL_REQUIRED`를 반환하면 실행은 `WAITING_APPROVAL` 상태로 멈추고, `/api/v1/approvals/{id}/approve|reject` 결정 후 AgentGate가 Runtime에 재개를 요청합니다. 이를 위해 Spring에 Runtime 주소와 토큰을 설정합니다 (compose의 Runtime 기본 토큰은 `dev-runtime-token`).

```bash
AGENTGATE_RUNTIME_BASE_URL=http://localhost:8000 AGENTGATE_RUNTIME_TOKEN=dev-runtime-token ./gradlew bootRun
```

MCP 도구를 쓰려면 `mcpServers` 형식의 설정 파일을 만들고 `MCP_CONFIG_PATH`로 지정합니다 (형식은 [ARCHITECTURE 9장](docs/ARCHITECTURE.md#9-tool--mcp)).

### Tool 준비 (Tools 화면)

1. **Tools** 화면 하단에서 MCP 서버(Streamable HTTP)를 등록합니다. 인증 헤더(API 토큰 등)는 암호화해 저장되고, Runtime은 재시작 없이 30초 안에 반영합니다. stdio 서버는 Runtime 설정 파일(`MCP_CONFIG_PATH`)로만 등록합니다.
2. Tool 목록에서 Tool별 위험도(LOW / MEDIUM / HIGH / BLOCKED)를 정합니다. 정하지 않은 Tool은 기본값 HIGH(승인 필요)입니다. 서버가 알린 읽기 전용 / 파괴적 여부로 계산한 추천값을 확인 후 일괄 적용할 수 있습니다.

### Agent 사용 흐름

1. **Agents** 화면에서 Agent를 등록하고 정의(시스템 프롬프트, 모델, 최대 단계, 출력 스키마)를 저장합니다.
2. 같은 화면에서 Runtime의 MCP 서버가 제공하는 Tool을 고르고 Tool마다 권한(자동 / 항상 승인 / 차단)을 지정합니다.
3. (선택, Multi-Agent) 같은 화면의 "위임할 수 있는 Agent"에서 이 Agent가 일을 맡길 다른 Agent와 위임 권한을 고릅니다. 맡은 Agent는 자기 정의·권한·이름으로 Tool을 실행하고, 기록과 승인 요청에 위임 경로(예: `lead → research`)가 남습니다.
4. Builder에서 Agent 노드를 추가해 "등록된 Agent"를 고르고 작업 프롬프트를 적습니다 (정의 버전 고정 가능).
5. 실행하면 Agent가 Tool을 골라 호출(또는 다른 Agent에게 위임)하고, 호출마다 AgentGate가 그 Agent 이름으로 **권한 → 정책 → 위험도**를 판정합니다. 승인이 필요하면 멈추고, 승인 후 이어서 실행합니다(위임받은 Agent 안의 승인도 같음). 과정은 Execution Studio에 단계별로 표시됩니다.

모델 서버가 function calling을 지원하지 않으면 `LLM_TOOL_CALLING=json`(또는 Agent 정의의 Tool 호출 방식 `JSON`)을 사용합니다.

로컬에서 직접 실행할 때는 Python 3.12 이상이 필요합니다.

```bash
cd runtime
python3.12 -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"
cp .env.example .env      # RUNTIME_TOKEN을 채운다 (Runtime만 로컬에서 띄울 때는 RUNTIME_ALLOW_NO_TOKEN=true)
uvicorn app.main:app --reload
pytest
```

## API 개요

| 영역 | 엔드포인트 | 인증 |
|---|---|---|
| 행동 평가 | `POST /api/v1/actions` | Agent API Key (`X-API-Key`) 또는 Runtime 토큰 (`X-Runtime-Token`) |
| 콘솔 로그인 | `POST /api/v1/auth/login`, `/logout` | 아이디 · 비밀번호 → 세션 쿠키 |
| Agent 관리 / 정의 | `/api/v1/agents`, `/api/v1/agents/{id}/definition` | 로그인한 사용자 (역할별 권한) |
| Tool 목록 / 위험도 | `GET /api/v1/tools`, `/api/v1/tool-risks` | 〃 |
| MCP 서버 | `/api/v1/mcp-servers` | 〃 |
| Workflow / Execution | `/api/v1/workflows`, `/api/v1/executions` | 〃 |
| Policy 관리 | `/api/v1/policies` | 〃 |
| 승인 | `/api/v1/approvals` | 〃 (APPROVER 이상이 승인 · 거절) |
| 감사 로그 | `/api/v1/audit-logs` | 〃 |

콘솔은 세션 쿠키로 인증하고, 스크립트와 자동화는 같은 API를 HTTP Basic으로 호출합니다. 자세한 요청/응답 형식은 [`docs/API_SPEC.md`](docs/API_SPEC.md)를 참고하세요.

## 보안 설계 요약

- **fail-closed**: 평가 서버에 닿지 못하거나 응답이 이상하면 Tool을 실행하지 않습니다.
- **승인과 실행 내용의 결속**: 실행할 내용(액션 · 대상 · 인자)의 SHA-256을 승인 요청에 저장하고, 실행 직전에 다시 비교합니다. 다르면 실행하지 않고 실행을 실패시킵니다. 승인자에게는 비밀번호 · 토큰 같은 값을 가린 인자 전체를 보여 줍니다.
- **로그인**: 서버 세션 쿠키(`HttpOnly`, `SameSite=Lax`) + CSRF 토큰. 비밀번호는 브라우저에 남지 않습니다. 실패가 반복되면 아이디+IP, IP 단위로 잠급니다(Redis에 저장).
- **Runtime 보호**: Runtime API는 공유 토큰이 없으면 기동하지 않고, 모든 요청에 토큰을 요구합니다. 컨테이너는 root가 아닌 사용자로 실행됩니다.
- **비밀값**: MCP 서버 인증 헤더는 AES-256-GCM으로 암호화해 저장합니다. 의존성 취약점은 CI에서 검사합니다.
- **중복 실행 방지**: HTTP/MCP Tool 호출은 모두 실행 원장(`tool_call_ledger`)을 거칩니다. 같은 호출이 다시 들어오면(승인 재개 중복, Runtime 재시작 후) 재실행 대신 기록된 결과를 반환하고, 결과를 모르는 채 끊긴 호출은 재시도하지 않고 안전하게 실패시킵니다. 검증 내용은 [`docs/TEST_EVIDENCE.md`](docs/TEST_EVIDENCE.md), 설계상 한계는 [`docs/SECURITY.md`](docs/SECURITY.md)를 보세요.

알려진 한계(사내 계정 연동 없음, 단일 인스턴스 구성 등)는 [`docs/OPERATIONS_GUIDE.md`](docs/OPERATIONS_GUIDE.md)의 "알려진 제약"에 있습니다.

## 프로젝트 구조

```
src/main/java/com/agentgate   Spring Boot 앱
├── agent / policy / risk     행동 평가: Agent 정의 · 권한, 정책 매칭, 위험도
├── approval / audit          승인 워크플로 · 알림 · 만료, 불변 감사 로그
├── execution / workflow      Workflow 저장, 실행 기록 · 실시간 스트림 · 복구 · 보존
├── mcp / tool / memory       MCP 서버 등록, Tool 위험도, Agent 기억
├── user / config / common    계정 · 로그인, 보안 설정, 공통
└── dashboard / observability 대시보드 집계, 지표
runtime/       Python LangGraph Runtime (Workflow 실행, LLM 호출, Tool 실행)
frontend/      React 콘솔 (Builder, Execution Studio, 승인, 관리 화면)
e2e/           통합 · 브라우저 E2E 테스트
deploy/        selfhost(Docker Compose 스택), runtime(Runtime 단독 배포)
infra/         AWS 배포용 Terraform
scripts/       배포 · E2E · 모델 서버 SSH 터널 스크립트
docs/          설계 · API · 설치 · 운영 문서
```

## 테스트

```bash
./gradlew test                       # 백엔드: 단위 + @SpringBootTest 통합 (H2, Redis는 Testcontainers)
cd runtime && pytest                 # Runtime (Python 3.12+)
cd frontend && npm install && npm test
PYTHON=python3.12 ./scripts/e2e.sh   # 전체 흐름 + 콘솔 브라우저 E2E (Docker, Java 21, Python 3.12, Playwright)
```

PR마다 GitHub Actions가 백엔드 · Runtime · 프런트엔드 · E2E · 의존성 취약점 검사를 실행합니다. 프런트엔드 개발 서버는 `cd frontend && npm run dev`(`http://127.0.0.1:5173`, `/api`는 `localhost:8080`으로 프록시)이고, 다른 기기에서 접속하려면 `VITE_HOST=0.0.0.0 npm run dev`로 실행합니다. 로그인은 개발용 기본 계정(`admin` / `changeme`)을 씁니다.

## 문서

- 설치: [`docs/SELF_HOSTING.md`](docs/SELF_HOSTING.md) (단일 서버, Docker Compose) · [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) (AWS)
- 운영: [`docs/OPERATIONS_GUIDE.md`](docs/OPERATIONS_GUIDE.md) (첫 설정, 권장 설정, 장애 대응, 알려진 제약) · [`docs/MONITORING.md`](docs/MONITORING.md)
- 설계: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) (구조 · 보안 · 안정성 설계) · [`docs/AGENTGATE_PLAN.md`](docs/AGENTGATE_PLAN.md) (초기 기획과 Governance Core 설계)
- 참조: [`docs/API_SPEC.md`](docs/API_SPEC.md) · [`docs/SECURITY.md`](docs/SECURITY.md) (신뢰 경계 · 비밀값 관리 · 잔여 위험) · [`docs/TEST_EVIDENCE.md`](docs/TEST_EVIDENCE.md) (검증 내용과 한계) · [`CHANGELOG.md`](CHANGELOG.md)

## 라이선스

[Apache License 2.0](LICENSE)
