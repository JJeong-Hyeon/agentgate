# Changelog

버전별 변경 사항. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/), 버전은 [Semantic Versioning](https://semver.org/lang/ko/)을 따른다.
릴리스 이미지: `ghcr.io/jjeong-hyeon/agentgate`, `ghcr.io/jjeong-hyeon/agentgate-runtime` (설치 · 업그레이드는 [`docs/SELF_HOSTING.md`](docs/SELF_HOSTING.md)).

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
