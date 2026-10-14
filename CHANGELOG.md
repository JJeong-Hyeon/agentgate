# Changelog

버전별 변경 사항. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/), 버전은 [Semantic Versioning](https://semver.org/lang/ko/)을 따른다.
릴리스 이미지: `ghcr.io/jjeong-hyeon/agentgate`, `ghcr.io/jjeong-hyeon/agentgate-runtime` (설치 · 업그레이드는 [`docs/SELF_HOSTING.md`](docs/SELF_HOSTING.md)).

## [Unreleased]

### 추가
- **사내 계정(OIDC) 로그인**: Authorization Code + PKCE. IdP의 그룹을 ADMIN / EDITOR / APPROVER / VIEWER에 매핑하고, 매핑되는 그룹이 없으면 로그인을 거부한다(`AGENTGATE_OIDC_DEFAULT_ROLE`로 최소 권한을 줄 수도 있다). 처음 로그인하면 사용자가 만들어지고 로그인할 때마다 역할이 IdP 그룹대로 다시 정해진다. 같은 이름의 자체 계정(`admin` 등)은 SSO로 가로챌 수 없다. 기본은 꺼져 있고 `AGENTGATE_OIDC_*`로 켠다. `AGENTGATE_LOCAL_LOGIN_ENABLED=false`로 아이디 · 비밀번호 로그인을 끌 수 있다(HTTP Basic은 유지). 설정 방법은 `docs/SELF_HOSTING.md`, 한계는 `docs/SECURITY.md` · `docs/TEST_EVIDENCE.md`
- DB 마이그레이션 V13: `users.auth_source` (기존 사용자는 `LOCAL`)
- SAML은 지원하지 않는다
- **외부 LLM provider 사용을 정책으로 통제**: Agent의 모델이 `provider@모델`로 기본 모델 서버가 아닌 provider를 가리키면, Runtime이 첫 모델 호출 전에 AgentGate에 `LLM:<provider>` 액션을 평가받는다. 정책 화면에서 `LLM:gemini`처럼 액션 타입별로 차단할 수 있고, 평가는 감사 로그에 남는다. 허용(ALLOWED)이 아니거나 AgentGate에 닿지 못하면 Agent 실행을 실패시킨다(fail-closed). 정책이 없으면 기존 정책의 기본 위험도를 따른다. 승인 요구도 지원한다. 기본(`LLM_BASE_URL`) 모델은 검사하지 않는다
  - `LLM:<provider>`를 APPROVAL_REQUIRED로 두면 그 Agent는 사람이 승인할 때까지 멈추고, 승인되면 이어서 실행되며 거절되면 실패한다(재개해도 승인 요청이 다시 만들어지지 않는다)

### 수정
- 실행 원장(`RUNTIME_DATABASE_URL`)을 켠 상태에서, 위임받은 Agent의 Tool 호출이 승인 대기가 되면 승인 후에도 그 Tool이 실행되지 않고 Supervisor가 "a previous attempt at this call did not finish" FAILED를 받던 문제. 승인 대기로 멈춘 위임 호출의 원장 키가 STARTED로 남아, 재개 시 같은 키가 "결과를 모르는 호출"로 replay됐다. 이제 승인 대기로 멈추면 그 STARTED 키만 해제한다 (위임 안의 실제 외부 호출은 각자 키를 따로 가지므로 중복 방지는 그대로)

## [0.3.0] - 2026-10-13

### 변경
- **콘솔 로그인이 서버 세션 쿠키 방식으로 바뀌었다.** 이전에는 비밀번호를 브라우저 세션 저장소에 두고 모든 요청에 Basic 인증으로 보냈다(화면에 XSS가 생기면 유출). 이제 `POST /api/v1/auth/login`이 `HttpOnly` 세션 쿠키를 발급하고, 비밀번호는 브라우저에 남지 않는다. 세션 쿠키로 인증한 요청은 CSRF 토큰(`X-XSRF-TOKEN`)을 요구하고, 세션이 끝나면 콘솔이 로그인 화면으로 돌아간다
- HTTP Basic(스크립트 · 자동화)과 API 키 · Runtime 토큰 인증은 그대로 동작한다
- 설정 추가: `AGENTGATE_SESSION_TIMEOUT`(기본 8시간), `AGENTGATE_SESSION_COOKIE_SECURE`(selfhost 기본 `true`)

### 업그레이드 시 알아둘 것
- 업그레이드하면 열려 있던 콘솔은 다시 로그인해야 한다
- HTTPS 없이 HTTP로 selfhost를 운영한다면 `AGENTGATE_SESSION_COOKIE_SECURE=false`가 필요하다 (권장하지 않음)
- 사내 계정(OIDC · SAML) 연동은 아직 없다

## [0.2.0] - 2026-10-13

### 보안 · 안정성
- **승인과 실행 내용의 결속**: Runtime이 실행할 내용(액션, 대상, 인자)의 SHA-256을 승인 요청에 저장하고, 승인 후 재개할 때 현재 내용의 해시와 비교한다. 다르면 실행하지 않고 실행을 실패시킨다 (Agent의 Tool 호출, HTTP/MCP Tool 노드)
- **승인 화면에 인자 전체 표시** ("인자 전체 보기", 최대 20,000자, 비밀번호 · 토큰 같은 키는 `***`)
- HTTP Tool 호출에 `Idempotency-Key` 헤더 추가
- Runtime은 `RUNTIME_TOKEN` 없이 시작하지 않는다 (로컬 개발만 `RUNTIME_ALLOW_NO_TOKEN=true`)
- AgentGate에 5분 넘게 닿지 못하면 캐시한 MCP 서버 목록을 버린다
- 실행 화면이 승인 대기 상태를 놓치던 문제 수정: 스냅샷을 읽는 사이 들어온 이벤트가 누락되거나 오래된 스냅샷이 최신 상태를 덮어쓸 수 있었다
- 루트 `compose.yaml`은 개발 전용으로 명시하고 포트를 127.0.0.1로 제한, 이미지 버전 고정
- 의존성 취약점 CI 스캔과 Dependabot(월 1회, 종류별 묶음)

### 업그레이드 시 알아둘 것
- DB 마이그레이션 V10, V11이 기동 시 자동 적용된다 (승인 요청에 `payload_hash`, `payload_preview` 열 추가). 업그레이드 전에 백업을 뜬다
- 업그레이드 시점에 대기 중이던 승인은 해시가 없어 검사 없이 기존처럼 동작한다
- 로컬에서 Runtime만 띄운다면 `RUNTIME_TOKEN`을 채우거나 `RUNTIME_ALLOW_NO_TOKEN=true`가 필요하다 (selfhost 스택은 이미 `RUNTIME_TOKEN`을 요구한다)

## [0.1.1] - 2026-10-13

### 변경
- 로그인 실패 횟수를 Redis에 저장(`AGENTGATE_LOGIN_STORE`, selfhost 기본 `redis`): App을 재시작해도 유지되고 여러 대가 함께 센다. Redis에 닿지 못하면 막지 않고 경고만 남긴다. 직접 실행할 때 기본값은 `memory`
- 콘솔에서 승인 · 거절하는 흐름을 헤드리스 브라우저로 검증하는 E2E 테스트를 CI에 추가

## [0.1.0] - 2026-10-13

첫 릴리스.

### 기능
- Workflow Builder(React Flow)와 LangGraph Runtime: LLM · Agent · Router · Reviewer · Condition · HTTP/MCP Tool · Approval 노드
- Agent 정의: 시스템 프롬프트, Tool별 권한(자동 / 항상 승인 / 차단), 출력 스키마, 추론 강도, 버전 관리
- Tool Registry: MCP 서버 등록(인증 헤더 암호화 저장), Tool별 위험도 정책
- Multi-Agent: 다른 Agent에게 위임, 위임 경로 기록
- Memory: Agent 장기 기억(pgvector 의미 검색 + 키워드 폴백), 중복 · 갱신 처리
- 거버넌스: 권한 → 정책 → 위험도 판정, 사람 승인 후 재개, 승인 만료, Slack/JSON 웹훅 알림, 감사 로그
- Execution Studio: 단계별 판단 · Tool 결과 · 승인 대기 · 모델 지연과 토큰
- 관측: Prometheus 지표 + Grafana 대시보드, OpenTelemetry trace
- 운영: RBAC(ADMIN / EDITOR / APPROVER / VIEWER), Flyway, HTTPS(Caddy), self-hosted Docker Compose 스택

### 안정성 · 보안
- 한 턴의 여러 Tool 호출 중 첫 호출만 실행(`LLM_PARALLEL_TOOL_CALLS`), 모델 호출 동시 수 제한(`LLM_MAX_CONCURRENCY`)
- Runtime 재시작으로 끊긴 실행 자동 복구 (Tool 실행 도중이면 중복 방지를 위해 FAILED)
- 로그인 시도 제한, Runtime API 전체 토큰 인증, CSP, 비 root 컨테이너
- 끝난 실행 기록 · checkpoint 보존 기간(`AGENTGATE_EXECUTION_RETENTION`, 기본 90일)
- CI 의존성 취약점 스캔, Dependabot
