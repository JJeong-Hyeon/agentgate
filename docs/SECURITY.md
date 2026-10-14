# 보안 모델

AgentGate는 **self-hosted 개인 프로젝트**다. 엔터프라이즈 보안팀의 감사를 거치지 않았고, 아래는 설계 의도와 알려진 한계를 정직하게 적은 것이지 보증이 아니다.

## 신뢰 경계

```
신뢰됨                           신뢰 안 됨(검증 필요)
──────────────────────          ──────────────────────
AgentGate(app, runtime)         LLM의 출력(모델이 '허용됐다'고 말해도 안 믿는다)
운영자가 설정한 정책/권한        MCP 서버가 돌려주는 Tool 설명·결과
DB에 암호화 저장된 비밀값        외부 HTTP/MCP 서버 자체
```

- **fail-closed 원칙**: Governance 판단이 실패하거나(AgentGate 응답 없음, DB 에러) 불확실하면(원장에 STARTED만 있고 끝난 기록이 없음) 실행하지 않는다. "일단 실행하고 나중에 확인"은 하지 않는다.
- **LLM은 신뢰 경계 밖**: 모델이 Tool을 호출하겠다고 해도 AgentGate가 별도로 권한·정책·위험도를 평가한다. 모델 출력이 Governance 판단을 대신하지 않는다.
- **LangGraph 체크포인트와 외부 Tool 호출은 별개의 상태**: 체크포인트가 저장됐다고 Tool이 실행됐다는 뜻이 아니고, 반대도 마찬가지다 — 그래서 `tool_call_ledger`가 둘 사이의 간극을 메운다([`TEST_EVIDENCE.md`](TEST_EVIDENCE.md) 참고).

## 비밀값 관리

| 비밀값 | 저장 위치 | 비고 |
|---|---|---|
| `POSTGRES_PASSWORD`, `AGENTGATE_ADMIN_PASSWORD`, `RUNTIME_TOKEN`, `AGENTGATE_SECRET_KEY` | `.env` (Docker Compose 환경변수) | 값 없으면 컨테이너가 기동하지 않음(약한 기본값 없음) |
| MCP 서버 인증 헤더 | PostgreSQL, `AGENTGATE_SECRET_KEY`로 암호화 | 콘솔(Tool Registry)에서 등록, 평문으로 로그에 남기지 않음(`repr=False`) |
| LLM/임베딩/추가 provider API 키 | `.env`, `providers.json` | `.gitignore`로 커밋 방지 |
| 세션 쿠키 | `HttpOnly`, `SameSite=Lax`, HTTPS 뒤에서 `Secure=true` | CSRF는 쿠키+헤더 이중제출(`CookieCsrfTokenRepository`) |

`.env`/`providers.json`은 저장소에 커밋되지 않는다(`!.env.example`, `!providers.json.example`만 추적됨). `AGENTGATE_SECRET_KEY`를 잃어버리면 DB에 암호화 저장된 MCP 헤더를 다시 입력해야 한다 — 별도 백업 권장.

## 네트워크 노출

`deploy/selfhost/compose.yaml` 기준: Caddy(80/443)만 호스트 포트로 노출되고, PostgreSQL·Redis·Runtime(8000)·Grafana는 전부 내부 Docker 네트워크 전용이다(호스트 포트 매핑 없음). Runtime의 `/runtime/executions/*` API는 `RUNTIME_TOKEN` 없이는 어떤 요청도 받지 않는다(개발 모드만 예외, `RUNTIME_ALLOW_NO_TOKEN=true`일 때).

## 실행 무결성 — exactly-once의 실제 한계

`tool_call_ledger`(#239, #241)가 보장하는 것과 보장하지 못하는 것을 구분한다.

**보장함**
- 같은 idempotency key(실행 ID + 노드 + payload hash)로 다시 들어오면, AgentGate 자신이 그 Tool을 다시 호출하지 않는다.
- 원장에 결과가 없는 채로 중단된 호출(결과 불명)은 재시도 대신 안전하게 FAILED 처리한다.
- 원장 자체에 닿지 못하면(DB 장애) 보호 없이 조용히 실행하지 않는다.

**보장하지 못함**
- 외부 서버가 실제로 요청을 받았는데 응답만 유실된 경우, 원장은 그 사실을 알 방법이 없다. 외부 API가 자체 멱등키(`Idempotency-Key` 헤더 등)를 지원하지 않으면, 네트워크 유실 시점에 따라 그 Tool이 실제로는 두 번 실행됐을 수 있다 — 이건 AgentGate가 아니라 멱등키 미지원 API의 근본적 한계다.
- 승인된 payload와 실제 실행 payload의 해시 일치는 검사하지만(V10/V11), 그 사이에 외부 시스템 자체의 상태가 바뀌는 경우(예: 승인 대기 중 재고가 바뀜)까지는 다루지 않는다.

## 잔여 위험 (알고 있고, 아직 안 고친 것)

- **MCP 서버 자체의 신뢰성**: 등록된 MCP 서버가 악의적이거나 침해당했다면, Tool 설명·결과에 프롬프트 인젝션을 심어 모델을 속일 수 있다. 결과는 신뢰 경계 밖(위 표)이라 LLM 판단에만 의존하지 않지만, 모델이 그 내용을 바탕으로 **다음 Tool 호출의 인자**를 결정하는 경로 자체는 막지 않는다.
- **단일 서버 배포**: 지금까지는 개인 기기(iMac, Windows PC)에서만 셀프호스트 스택을 운영했고, 실제 기업 서버에 배포한 적은 없다. 다중 서버/고가용성 구성은 검증되지 않았다.
- **SAML 미지원, OIDC는 연동 가능**: 사내 계정은 OIDC로 로그인한다(꺼져 있는 것이 기본). ID 토큰의 서명 · 발급자 · 대상 · 만료 · nonce 검증은 Spring Security가 하고, AgentGate는 그룹을 역할로 바꾼다. 로그아웃은 AgentGate 세션만 끝내고 IdP 세션은 그대로이며(RP-initiated logout 없음), IdP에서 계정을 비활성화해도 열려 있는 AgentGate 세션은 만료까지 유지된다. 같은 이름의 자체 계정은 SSO로 로그인할 수 없다.
- **Marketplace/패키지 신뢰 경계**: 아직 존재하지 않는 기능이라 해당 없음.

## 취약점 신고

개인 프로젝트라 별도 보안 연락처는 없다. 이슈로 남겨도 되지만, 민감한 내용이면 공개 이슈 대신 저장소 소유자에게 직접 연락하는 걸 권장.
