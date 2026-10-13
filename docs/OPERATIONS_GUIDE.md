# 도입 · 운영 가이드

AgentGate를 사내에 처음 도입하는 담당자를 위한 문서다. 설치 자체는 [`SELF_HOSTING.md`](SELF_HOSTING.md)(단일 서버) 또는 [`DEPLOYMENT.md`](DEPLOYMENT.md)(AWS)를 따르고, 이 문서는 설치 이후의 첫 설정, 권장 설정, 일상 운영, 장애 대응을 다룬다.

## 1. 역할 나누기

| 담당 | 하는 일 | AgentGate 역할 |
|---|---|---|
| 운영 담당 | 설치 · 업데이트 · 백업 · 계정 관리 | `ADMIN` |
| 업무 설계 담당 | MCP 서버 · Agent · Workflow 구성, 실행 | `EDITOR` |
| 승인 담당 | 위험한 Tool 호출 승인 · 거절 | `APPROVER` |
| 감사 · 열람 | 실행 기록 · 감사 로그 조회 | `VIEWER` |

한 사람이 여러 역할을 가져도 되지만, **Workflow를 만드는 사람과 승인하는 사람은 나누는 것**을 권장한다. 공용 ADMIN 계정은 설치 직후에만 쓰고 개인 계정으로 옮긴다.

## 2. 도입 체크리스트

설치가 끝난 뒤 아래 순서로 진행한다.

1. **모델 연결 확인**: `docker compose exec runtime python -c "import urllib.request;print(urllib.request.urlopen('http://localhost:8000/llm/health').read())"` → `model_available: true`
2. **계정 만들기**: Users 화면에서 위 역할별 개인 계정을 만든다.
3. **Runtime Agent 등록**: Agents 화면에서 Agent ID `runtime-agent`를 한 번 등록한다 (Workflow 수준 Tool · 승인 노드가 이 이름으로 평가된다).
4. **승인 알림 연결**: `.env`의 `AGENTGATE_NOTIFY_WEBHOOK_URL`에 Slack Incoming Webhook(또는 사내 메신저용 JSON 수신 URL)을 넣는다. 알림이 없으면 승인 요청이 콘솔에서만 보여 업무가 멈춘 채로 남기 쉽다.
5. **MCP 서버 등록**: Tools 화면에서 사내 시스템을 감싼 MCP 서버(Streamable HTTP)를 등록한다. 인증 헤더는 암호화되어 저장된다.
6. **Tool 위험도 지정**: 아래 [위험도 기준](#3-위험도-설계-기준)에 따라 Tool마다 정한다. 정하지 않은 Tool은 HIGH(승인 필요)로 처리된다.
7. **Agent 정의**: 시스템 프롬프트, 사용할 Tool과 권한(자동 / 항상 승인 / 차단)을 정한다. 한 Agent에는 그 업무에 필요한 Tool만 준다.
8. **Workflow 구성 · 시범 실행**: Builder에서 Agent 노드로 Workflow를 만들고, 실제 데이터에 영향이 없는 요청으로 먼저 돌려 본다. Execution Studio에서 단계별 판단(어떤 Tool을 어떤 인자로 불렀는지)을 확인한다.
9. **승인 · 거절 리허설**: HIGH Tool이 승인 대기로 멈추는지, 승인하면 이어서 실행되는지, 거절하면 실행되지 않는지 한 번씩 확인한다.
10. **백업 예약**: [정기 작업](#6-정기-작업)의 백업을 cron에 등록한다.

## 3. 위험도 설계 기준

| 위험도 | 동작 | 이런 Tool에 |
|---|---|---|
| `LOW` | 바로 실행 | 조회 · 검색 · 읽기 전용 |
| `MEDIUM` | 바로 실행 (감사 로그에 위험도로 구분) | 사내 시스템 안의 되돌릴 수 있는 쓰기 (메모, 초안 저장) |
| `HIGH` | 사람의 승인 후 실행 | 돈이 움직이는 작업, 외부 발송(메일 · 메시지), 고객 데이터 변경 |
| `BLOCKED` | 실행하지 않음 | 삭제 · 권한 변경 등 Agent에게 맡기지 않을 작업 |

- 정책(위험도)은 회사 전체 기준이고, Agent 정의의 Tool 권한은 그 위에 Agent별로 더 엄격하게 거는 장치다. 예: 정책상 LOW인 Tool도 특정 Agent에게는 "항상 승인".
- **승인자는 요청의 인자를 반드시 확인한다.** 승인 요청에는 `Agent 'cs-agent' (v2) wants to call ops/issue_refund with {"order_id": "A-1001", "amount": 42000, ...}`처럼 실제 인자가 표시된다. 실모델 검증에서 모델이 조회 결과를 보기 전에 환불 금액을 지어낸 사례가 있었고, 승인 단계에서 걸러졌다.

## 4. 권장 설정

실모델(DGX Spark, Ollama `qwen3:14b`) 검증에서 얻은 값이다. 모델이 바뀌면 다시 확인한다.

| 설정 | 권장 | 이유 |
|---|---|---|
| 모델 크기 | Tool Calling을 지원하는 14B 이상 | 3B급은 불필요한 Tool 호출을 반복하기 쉽다 |
| `LLM_REASONING_EFFORT` | 전역 `none` | qwen3:14b 기준 호출당 약 15초 → 2초 |
| Agent 정의의 추론 강도 | 돈 · 외부 발송을 다루는 Agent는 `medium` | `none`에서는 조회를 건너뛰고 금액을 추측한 사례가 있었다. `medium`에서는 조회 후 정확한 금액을 썼다 (대신 턴당 약 12~25초) |
| `LLM_TIMEOUT_SECONDS` | 추론을 켜는 Agent가 있으면 `120` 이상 | 추론 턴이 25초를 넘기도 한다 |
| `LLM_MAX_CONCURRENCY` | Ollama면 `OLLAMA_NUM_PARALLEL`과 같게 (예: `2`) | 넘는 호출은 Runtime에서 기다려 모델 서버 대기가 타임아웃에 들어가지 않는다. 동시 5건 실측: 제한 없음 100~124초 → `2`로 55~90초, 실패 없음 |
| `LLM_PARALLEL_TOOL_CALLS` | `false` (기본) | 한 턴의 여러 호출 중 첫 호출만 실행하고, 나머지는 결과를 본 뒤 다시 결정하게 한다 |
| `MEMORY_MIN_SCORE` | bge-m3 기준 `0.45` | 관련 문장 0.55~0.73, 무관 문장 약 0.30 |
| `AGENTGATE_APPROVAL_TTL` | 업무 시간에 맞게 (기본 `PT24H`) | 만료된 요청은 거절과 같이 처리된다. 주말을 넘기는 업무면 늘린다 |
| `AGENTGATE_EXECUTION_RETENTION` | 기본 `P90D` | 끝난 실행의 단계별 기록 · checkpoint 삭제 시점. 감사 로그 · 승인 기록은 남는다 |

## 5. 일상 운영

- **승인 처리**: 알림을 받으면 Approvals 화면에서 요청 이유와 인자를 보고 승인 · 거절한다. 거절된 Tool 호출은 실행되지 않고, Agent는 그 호출 없이 작업을 이어 간다.
- **실행 확인**: Execution Studio에서 실행별로 모델 판단, Tool 결과, 승인 대기, 소요 시간과 토큰을 본다.
- **감사 로그**: 실행 상세 화면과 Agents 화면(또는 `GET /api/v1/audit-logs?agentId=...`)에서 Agent · 실행별 판정 근거를 조회한다.
- **모니터링**: 모니터링 프로필(`--profile monitoring`)을 켜면 `/grafana`에서 대시보드를 볼 수 있다. 최소한 아래 알림을 건다 ([`MONITORING.md`](MONITORING.md)).
  - 승인 적체: `agentgate_approvals_pending > 10`
  - 실행 실패: `agentgate_execution_duration_seconds_count{status="FAILED"}` 증가
  - LLM 지연: 평균 응답 30초 초과

## 6. 정기 작업

`deploy/selfhost`에서 실행한다.

| 주기 | 작업 | 방법 |
|---|---|---|
| 매일 | DB 백업 | 아래 cron 예시 |
| 업데이트 시 | 백업 → 업데이트 | `git pull && docker compose up -d --build` (DB 스키마는 Flyway가 자동 적용) |
| 분기 | 계정 · 역할 점검 | 퇴사자 비활성화, ADMIN 최소화 |
| 분기 | 복원 리허설 | 백업 파일을 빈 DB에 복원해 기동되는지 확인 |
| 변경 시 | `.env` 백업 | 특히 `AGENTGATE_SECRET_KEY` — 잃으면 MCP 서버 인증 헤더를 다시 입력해야 한다 |

복원 (`.sql.gz` 백업을 빈 DB에): `gunzip -c agentgate-<날짜>.sql.gz | docker compose exec -T postgres psql -U agentgate agentgate`

백업 cron 예시 (14일 보관):

```cron
0 3 * * * cd /opt/agentgate/deploy/selfhost && docker compose exec -T postgres pg_dump -U agentgate agentgate | gzip > /backup/agentgate-$(date +\%F).sql.gz && find /backup -name 'agentgate-*.sql.gz' -mtime +14 -delete
```

## 7. 장애 대응

| 증상 | 확인 | 조치 |
|---|---|---|
| 실행이 바로 실패, Studio에 LLM 오류 | `/llm/health`, `docker compose logs runtime` | 모델 서버 기동 여부, `LLM_BASE_URL`. SSH 터널을 쓰면 `scripts/model-tunnel.sh status <host>` (서버 재부팅 시 터널이 끊긴다) |
| 실행이 오래 RUNNING | Studio의 마지막 단계, LLM 지연 지표 | 모델 서버 부하. 추론 강도를 낮추거나 `LLM_TIMEOUT_SECONDS` 조정 |
| Runtime 재시작 뒤 실행이 FAILED ("Interrupted by a runtime restart") | app 로그의 `InterruptedExecutionJob` | 재시작으로 끊긴 실행은 1~2분 안에 마지막 checkpoint부터 자동으로 이어 실행된다. Tool 호출 도중 끊긴 실행만 중복 실행을 막으려고 FAILED로 남긴다. 그 Tool의 결과(예: 환불 여부)를 직접 확인한다 |
| 승인했는데 이어지지 않음 | `docker compose logs app`의 resume 재시도 로그 | AgentGate가 30초마다 Runtime에 재전달한다. Runtime이 떠 있는지, `RUNTIME_TOKEN`이 양쪽에 같은지 확인 |
| 승인 알림이 오지 않음 | app 로그의 webhook 오류 | URL · 포맷(`SLACK` / `JSON`) 확인. 실패한 알림은 `PENDING`인 동안 30초마다 재시도된다 |
| 등록한 MCP Tool이 안 보임 | Tools 화면 새로고침 | Runtime은 30초 안에 반영한다. MCP 서버 URL에 Runtime 컨테이너에서 접근 가능한지 확인 |
| app이 기동하지 않음 | `docker compose logs app` | 관리자 비밀번호가 기본값(`changeme`)이거나 `AGENTGATE_SECRET_KEY` 누락, 또는 DB 스키마 검증 오류 |
| 로그인 화면에 "잠시 막혔습니다" | app 로그의 `Sign-in ... blocked` | 실패 반복으로 막힌 상태. `Retry-After` 시간(기본 15분)이 지나면 풀린다. 급하면 `docker compose exec redis redis-cli eval "for _,k in ipairs(redis.call('keys', ARGV[1])) do redis.call('del', k) end" 0 'agentgate:login:*'`로 초기화 |
| 브라우저 인증서 경고 | 사내 도메인 여부 | Caddy 자체 CA 인증서를 사내 PC에 배포 ([`SELF_HOSTING.md`](SELF_HOSTING.md#내부망--사설-인증서)) |

## 8. 알려진 제약

도입 전에 알고 있어야 할 현재 한계다.

- **로그인 시도 제한은 Redis에 기록된다** (selfhost 기본, `AGENTGATE_LOGIN_STORE=memory`면 메모리). 재시작해도 유지되고 App 여러 대가 함께 센다. Redis에 닿지 못하면 막지 않고 경고만 남긴다. 콘솔은 사외에 공개하지 말고 사내망 · VPN 안에서만 접근하게 한다.
- **IP 단위 차단은 실제 클라이언트 IP가 보여야 의미가 있다.** 로그인 차단 로그(`Sign-in ... from <IP> blocked`)에 모든 사용자가 같은 IP(예: Docker Desktop의 `172.18.0.1`)로 찍히면, 한 사람의 실패로 전원이 막힐 수 있다. 이때는 `AGENTGATE_LOGIN_MAX_FAILURES_PER_IP`를 크게 잡는다.
- **감사 로그와 승인 기록은 자동으로 지우지 않는다.** 끝난 실행의 상세 기록과 checkpoint는 `AGENTGATE_EXECUTION_RETENTION`(기본 90일)이 지나면 지워지지만, 거버넌스 기록은 회사 보존 정책에 맞춰 직접 관리한다.
- **동시 실행 수는 모델 서버가 정한다.** 2026-10 부하 테스트 결과:
  - 거버넌스 API(`/api/v1/actions`, App CPU 2개): 동시 10 → 약 860 req/s, 동시 50 → 약 1,430 req/s. 오류 0, p99 100ms 미만, 감사 로그 누락 없음.
  - 실모델 동시 실행(DGX Spark Ollama `qwen3:14b`, 추론 `medium`, Agent 2턴): 단독 약 27초, 동시 5건은 모두 성공했지만 100~124초. 모델 호출 한 번이 최대 69초까지 늘었다.
  - `LLM_MAX_CONCURRENCY`를 정하지 않으면 동시 실행이 늘 때 모델 호출이 `LLM_TIMEOUT_SECONDS`(권장 120)를 넘겨 실행이 실패할 수 있다. 정해 두면 넘는 호출은 Runtime에서 최대 15분까지 차례를 기다린다. 같은 조건에서 `2`로 두면 동시 5건이 55~90초에 끝났다.
  - LLM 지연 지표(`agentgate_llm_latency_seconds`)와 Studio의 모델 지연에는 이 대기 시간도 포함된다.
- **Agent의 판단은 모델에 좌우된다.** 거버넌스는 "무엇을 실행할 수 있는가"를 통제할 뿐 판단의 정확성을 보장하지 않는다. 되돌리기 어려운 작업은 HIGH로 두고 사람이 확인한다.
