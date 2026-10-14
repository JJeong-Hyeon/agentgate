# 테스트 증빙 (v0.3.0 이후)

v0.3.0 릴리스 이후 머지된 변경과 실제로 검증한 내용. 기능 구현 여부와 검증 여부를 분리해서 적는다 — "구현됨"과 "실제로 확인됨"은 다르다.

## 머지된 PR과 CI

| PR | 내용 | 머지 커밋 | CI |
|---|---|---|---|
| [#239](https://github.com/JJeong-Hyeon/agentgate/pull/239) | agentVersion 생략 시 Tool 권한 우회 차단, model-latency에서 큐 대기시간 분리, HTTP/MCP 공통 실행 원장 1차 구현 | `6aceff3` | Java/Python/Frontend/E2E/보안스캔 전부 pass |
| [#241](https://github.com/JJeong-Hyeon/agentgate/pull/241) | `tool_call_ledger` 장애주입 테스트, idempotency key에 payload hash 반영(다른 인자 호출이 서로 결과를 재사용하던 버그 수정) | `c921b46` | 동일, CI에 Postgres 서비스 컨테이너 추가(`LEDGER_TEST_DATABASE_URL`) |
| [#243](https://github.com/JJeong-Hyeon/agentgate/pull/243) | 추가 LLM provider(Multi-LLM Gateway MVP) — `LLM_PROVIDERS_PATH`, `provider@model` 라우팅 | `a7c404c` | 동일 |

세 PR 모두 `gh pr checks --watch`로 5개 잡(frontend/security/runtime/e2e/test) 전부 `pass` 확인.

## 장애주입 테스트 (tool_call_ledger)

`runtime/tests/test_ledger_fault_injection.py`, 실제 Postgres(CI는 서비스 컨테이너, 로컬은 `pgvector/pgvector:pg17` 일회용 컨테이너)에 대해 실행.

| 시나리오 | 확인한 것 | 결과 |
|---|---|---|
| 정상 실행 | 외부 호출 1회, 원장에 EXECUTED 기록 | PASS |
| 중복 재개(같은 키 재호출) | 외부 호출은 정확히 1회만, 두 번째는 결과 replay | PASS |
| 중단된 호출(원장에 STARTED만 있고 끝나지 않음) | 재시도하지 않고 FAILED(결과 불명)로 처리 | PASS |
| 동시 경합(같은 키 5개 스레드) | 외부 호출 정확히 1회, 나머지는 replay 또는 안전한 FAILED | PASS |
| 서로 다른 payload, 같은 노드 | 서로의 결과를 재사용하지 않고 둘 다 실행 | PASS (버그 발견 후 수정: idempotency key에 payload hash 포함) |
| 원장 DB 연결 불가 | 보호 없이 실행하지 않고 fail-closed(FAILED) | PASS |
| 실패한 호출 | 원장에 FAILED로 남고 재시도 안 함 | PASS |
| HTTP/MCP 둘 다 | 동일한 보장 적용 | PASS (MCP는 실제 stdio 서브프로세스 + 파일 카운터로 호출 횟수 확인) |
| 위임 + 중첩 승인, 원장 DB 켬 (승인) | 승인 후 하위 Agent의 Tool이 정확히 1회 실행되고 Supervisor가 EXECUTED 위임 결과를 받음 (`test_k`) | PASS (CI, PR #253) |
| 위임 + 중첩 승인, 원장 DB 켬 (거절) | 하위 Agent가 거절 사유를 받고 Supervisor 위임 결과가 정상 반환, Tool은 실행되지 않음 (`test_l`) | PASS (CI, PR #253) |
| 승인 대기로 멈춘 호출의 원장 키 해제 | `release()`는 STARTED만 지우고 기록된 결과는 건드리지 않음 (`test_m`) | PASS (CI, PR #253) |

위임 · 중첩 승인은 원장 DB가 있을 때만 재현되는 버그(위임 호출의 키가 STARTED로 남아 재개 시 FAILED로 replay)였다. 원장 없이 도는 `test_delegation.py`로는 드러나지 않으므로, 이 회귀 테스트는 반드시 원장 DB(`LEDGER_TEST_DATABASE_URL`)를 켜고 실행한다.

**한계**: 이 테스트는 애플리케이션 레벨(원장 begin/finish)의 정확성만 검증한다. "Tool 성공 후 체크포인트 저장 전 장애"(LangGraph 체크포인트와 원장 커밋 사이의 실제 프로세스 kill)처럼 더 낮은 레벨의 타이밍 장애는 시뮬레이션했을 뿐 실제로 프로세스를 죽여서 재현하지는 않았다. 외부 서비스가 네트워크 호출을 실제로 받았지만 응답이 유실된 경우(원장조차 모르는 상태)는 원천적으로 감지 불가능 — 이는 멱등키를 지원하지 않는 외부 API의 근본적 한계로, 코드가 아니라 설계 문서(`AgentGate_FINALIZATION_PLAN.md`)에 명시된 그대로다.

## 백업/복원 리허설 (2026-10-13)

1. 운영 데이터가 든 Postgres에서 `pg_dump | gzip` 백업
2. 완전히 새 볼륨의 빈 Postgres 컨테이너에 복원
3. 원본과 복원본의 핵심 테이블 row 수 비교: `agents`(4), `workflows`(2), `executions`(32), `users`(4), `approval_requests`(11), `audit_logs`(44) — **전부 일치**
4. 복원된 DB에 실제 app 이미지를 붙여서 기동 → Flyway 13개 마이그레이션 검증 통과, 스키마 버전 12 "up to date", Hibernate 스키마 검증 통과, 8초 만에 정상 기동
5. 테스트용 컨테이너/볼륨/백업 파일 전부 정리, 운영 스택은 영향 없음

## 정책·승인 회귀 테스트 감사 (2026-10-13)

새로 작성하지 않고 기존 테스트 커버리지를 감사함(중복 구현 방지):

| 보장 | 테스트 |
|---|---|
| ALLOW는 정책+Tool grant 만족 시만 | `AgentActionServiceTest` (`blocksToolsNotInTheDefinition` 등) |
| APPROVAL_REQUIRED는 승인 전 외부 호출 0건 | `test_http_tool.py::test_approval_required_does_not_send` |
| DENY는 외부 호출 0건 | `test_http_tool.py::test_blocked_does_not_send` |
| 만료/거절 후 재승인 우회 불가 | `ApprovalRequestTest::expireTransitionsFromPendingToExpiredBySystem` (도메인 상태기계 가드) |
| 승인 후 payload 변조 시 실행 거부 | `test_agent_node.py` (`PayloadMismatch`) |
| `agentVersion` 생략해도 Tool 권한 우회 불가 | `AgentActionServiceTest::blocksToolsNotGrantedWhenNoVersionIsNamedButTheAgentHasADefinition` (#239에서 추가) |
| MCP 서버 등록정보 캐시 TTL | `test_mcp_registry.py::test_registered_servers_are_dropped_when_agentgate_stays_unreachable_too_long` |
| 위임받은 Agent가 상위 Agent 권한을 넘어서지 않음 | `AgentDelegationIntegrationTest::delegationIsGovernedByTheSupervisorsPermissions` |

8개 보장 전부 기존 테스트로 커버됨을 확인. 새로 추가한 테스트는 없음(이미 있는 걸 다시 만들지 않는다는 원칙).

## 실 모델 검증

- **로컬 LLM(Ollama, qwen3:14b)**: MVP 흐름(워크플로 구성 → Tool 호출 → 승인 → 재개 → 감사 로그) end-to-end, 2026-10-12에 확인(세션 메모리 기준)
- **Gemini(Google AI Studio 무료 티어)**: `gemini@gemini-flash-latest`로 실제 채팅 완료 + Tool calling(`bind_tools`) 둘 다 성공, 2026-10-13 확인. CI에는 실제 키가 없어 이 검증은 로컬에서만 수동으로 했고 자동화된 테스트에는 없음

## 알려진 한계 (과장 방지)

- `tool_call_ledger`의 exactly-once는 "우리 시스템이 같은 호출을 두 번 보내지 않는다"는 보장이지, "외부 시스템이 그 호출을 정확히 한 번만 처리했다"는 보장은 아니다(외부 API가 멱등키를 지원하지 않으면 후자는 원천적으로 보장 불가)
- OIDC/SAML 미구현(의도적 보류, 회사 IdP 없음)
- Multi-LLM Gateway: provider별 정책 차단은 `LLM:<provider>` 액션으로 구현(`test_llm_policy.py`, `AgentActionServiceTest`). 자동 fallback, 모델 카탈로그, 비용 계측은 미구현. `LLM:` 정책을 APPROVAL_REQUIRED로 두면 승인 요청이 만들어지지만 Runtime은 대기하지 않고 실행을 실패시킨다
- 실제 운영/기업 서버 배포는 하지 않음 — 지금까지는 개인 기기(iMac, Windows PC)에서만 셀프호스트 스택을 운영

## 셀프호스트 보안 설정 점검 (2026-10-14, 설정 파일 읽기)

`deploy/selfhost/compose.yaml`, `application.yaml`, `runtime/app/main.py`를 읽어서 확인했다. 스택을 새로 띄워서 확인한 것은 아니다.

| 점검 항목 | 확인한 것 | 결과 |
|---|---|---|
| `RUNTIME_TOKEN` 누락 | compose는 `:?`로 비어 있으면 기동 거부, Runtime은 토큰 없이 시작하지 않음(`RUNTIME_ALLOW_NO_TOKEN`은 로컬 개발 전용) | 확인 |
| 필수 시크릿 | `POSTGRES_PASSWORD`, `AGENTGATE_ADMIN_PASSWORD`, `RUNTIME_TOKEN`, `AGENTGATE_SECRET_KEY`가 모두 `:?`로 필수 | 확인 |
| 외부 공개 포트 | Caddy(80/443)만 `ports`로 공개, Postgres/Runtime/Prometheus/Grafana는 내부 네트워크 | 확인 |
| 세션 쿠키 | `HttpOnly`, `SameSite=Lax`, `Secure`는 `AGENTGATE_SESSION_COOKIE_SECURE`(selfhost 기본 true) | 확인 |
| Grafana 관리자 비밀번호 | `GRAFANA_ADMIN_PASSWORD`가 비어 있으면 `admin`으로 폴백(첫 로그인 때 변경 요구). 모니터링 프로필을 켜면 `/grafana`가 외부에서 보임 | **잔여 위험**: 모니터링을 켜면 `GRAFANA_ADMIN_PASSWORD`를 반드시 채울 것 |
| TLS/프록시 헤더, CSRF, 로그 내 민감정보, 역할별 접근, CI 시크릿, 이미지 취약점 예외 | 이 점검에서 보지 않음 | 미확인 |

### 새 환경 설치 재현 (2026-10-14, 로컬 Windows PC)

기존 스택과 분리해 `docker compose -p agentgate-t3`로 새 볼륨에 `deploy/selfhost/compose.yaml`을 그대로 빌드·기동했다(Caddy 포트만 18080/18443으로 변경, `AGENTGATE_DOMAIN=localhost`). 확인 후 이 스택과 볼륨은 삭제했다.

| 확인 | 결과 |
|---|---|
| 5개 컨테이너(caddy/app/runtime/postgres/redis) 기동, app·runtime·postgres healthy | 확인 |
| Flyway 마이그레이션 최신 버전 12까지 적용 | 확인 |
| `/actuator/health` UP, Caddy 경유 `/actuator/prometheus`는 404 | 확인 |
| 로그인 없이 API 401, 잘못된 비밀번호 401, 올바른 비밀번호 200 | 확인 |
| 세션 쿠키 `Secure; HttpOnly; SameSite=Lax`, CSRF 토큰 없이 POST는 403 | 확인 |
| 전체 컨테이너 재시작 후 사용자 데이터 유지, 로그인 정상 | 확인 |
| app · runtime 로그에 관리자 비밀번호, Runtime 토큰 원문 없음(기동~재시작 구간) | 확인 |

한계:
- 모델 서버가 없어서 Agent 실행 → 승인 → 재개 시나리오는 이 설치에서 하지 않았다. 그 흐름은 E2E(CI)로 검증한다.
- `tool_call_ledger` 테이블은 첫 외부 호출 때 만들어지므로 이 설치에서는 아직 없다.
- Caddy 자체 CA 인증서는 신뢰하지 않고(`curl -k`) 확인했다. 공개 도메인의 Let's Encrypt 발급은 확인하지 않았다.
- 승인 · 만료 · 차단 경로와 Runtime만 재시작했을 때의 원장 replay는 이 설치에서 확인하지 않았다.

### 점검 항목 중 추가로 확인한 것

| 항목 | 확인 | 결과 |
|---|---|---|
| 역할별 접근 | `SecurityConfig`의 규칙을 읽음: 사용자 관리 ADMIN, 정의 수정 · 워크플로 · 실행 ADMIN/EDITOR, 승인 · 거절 ADMIN/APPROVER. 역할별 호출은 `RbacIntegrationTest`(viewer는 읽기만, editor는 만들고 실행하되 설정 · 승인 불가, approver는 승인 · 거절하고 결정자로 기록, 마지막 ADMIN 삭제 불가 등)와 콘솔 E2E(`test_a_viewer_sees_the_request_but_cannot_decide`)가 자동으로 검증한다 | 확인 |
| 인증 없이 열린 경로 | `permitAll` 4곳(`/api/v1/actions`, 실행 이벤트, MCP 서버 목록, 기억 API)을 컨트롤러에서 확인: actions는 `X-API-Key` 또는 `X-Runtime-Token`이 없으면 거부, 나머지 셋은 Runtime 토큰이 틀리면 `InvalidRuntimeTokenException`. 실제 요청으로 호출해 보지는 않음 | 코드 확인 |
| GitHub Actions 시크릿 | 워크플로에서 쓰는 시크릿은 `GITHUB_TOKEN`뿐, 출력하는 단계 없음 | 확인 |
| 공급망 스캔 | CI에서 Trivy(HIGH/CRITICAL, 수정본이 있는 것만, 발견 시 실패)와 `pip-audit`을 실행하고 예외 목록(ignore)은 없음. 수정본이 없는 취약점은 스캔 대상에서 빠지므로 잔여 위험으로 남을 수 있음 | 확인 |

## 거버넌스 평가 (릴리스 게이트)

`cd runtime && python -m evals.run` (데이터셋: `runtime/evals/cases.json`, pytest `test_governance_eval.py`, CI는 요약을 잡 요약에 남김).

각 케이스는 스크립트로 정해 둔 Agent를 실제 그래프(Agent 노드, 게이트, 승인, 위임, 인자 결속, 모델 정책)에 돌린다. AgentGate의 답과 모델의 응답만 가짜이므로, 측정하는 것은 모델의 판단이 아니라 Runtime이 정책을 지키는지다. 부작용은 테스트 MCP 서버의 `count`(호출마다 파일에 한 줄 추가)로 실제 실행 횟수를 센다.

| 분류 | 케이스 | 지키는 것 |
|---|---|---|
| 정책 | 허용 / 정책 차단 / AgentGate 장애 / 한 턴 다중 호출 | 허용은 1회, 차단 · 장애는 0회 (fail-closed), 중복 호출은 1회 |
| 승인 | 대기 / 승인 / 거절 / 승인 후 인자 변조 | 결정 전 0회, 승인 후 1회, 거절 · 변조는 0회 |
| 위임 | 허용 / 차단 / 중첩 승인(승인 · 거절 · 변조) | 차단되면 워커가 시작하지 않음, 중첩 승인도 같은 규칙 |
| 모델 | 외부 provider 차단 / 허용 | 차단되면 모델도 Tool도 실행되지 않음 |

결과: 15/15 통과, 오실행 0, 미실행 0.

**이 평가가 실제로 실패를 잡는지** 안전장치를 하나씩 일부러 끄고 돌려 확인했다(복사본에서, 저장소 코드는 그대로).

| 끈 안전장치 | 평가 결과 |
|---|---|
| 승인 후 인자 해시 비교 | 2개 실패, 오실행 2 (변조 케이스 2개) |
| 외부 모델 정책 검사 | 1개 실패, 오실행 1 — 처음에는 통과했다. 케이스 스크립트에 워커의 Tool 호출이 없어서 검사를 꺼도 실행될 게 없었기 때문이다. 스크립트를 고친 뒤 잡힌다 |
| 승인 대기(interrupt)를 건너뛰고 자동 승인 | 7개 실패, 오실행 5 |
| BLOCKED 결정을 허용으로 취급 | 2개 실패, 오실행 2 (정책 차단, 위임 차단) |

**한계**
- 모델 응답은 스크립트라서 "모델이 얼마나 똑똑한가"나 프롬프트 인젝션에 모델이 넘어가는지는 측정하지 않는다. 실제 모델 평가(정확도, 비용, 지연)는 하지 않았다.
- AgentGate의 정책 판단(위험도 계산, Tool 권한 검사)은 가짜로 대체한 것이라 여기서는 검증하지 않는다. 그 부분은 Java 테스트와 E2E가 맡는다.
- 원장(`RUNTIME_DATABASE_URL`)을 켠 시나리오는 이 평가에 없다(Postgres가 필요해서 원장 테스트와 E2E가 맡는다).
