# 단일 서버 설치 (Docker Compose)

사내 서버 한 대에 AgentGate를 설치하는 방법이다. AWS 구성은 [`DEPLOYMENT.md`](DEPLOYMENT.md)를, 설치 후 첫 설정 · 권장 설정 · 운영은 [`OPERATIONS_GUIDE.md`](OPERATIONS_GUIDE.md)를 참고한다.

```text
브라우저 ──HTTPS──▶ Caddy :443 ──▶ app :8080 (AgentGate API + 콘솔)
                                     │  ▲
                                     ▼  │
                                  runtime :8000 (LangGraph) ──▶ 모델 서버 (OpenAI 호환: Ollama / vLLM)
                     app, runtime ──▶ postgres (AgentGate 데이터 + checkpoint), redis (정책 캐시)
```

[`deploy/selfhost/compose.yaml`](../deploy/selfhost/compose.yaml)이 위 구성을 한 번에 띄운다. 모델 서버는 포함하지 않는다. 이미 운영 중인 OpenAI 호환 서버(예: NVIDIA DGX Spark의 Ollama 또는 vLLM)를 `LLM_BASE_URL`로 연결한다. 모델 서버를 별도 장비로 두는 경우는 [아래](#모델-서버를-별도-장비로-ollama)를 참고한다.

## 요구 사항

- Docker Engine 24+ 와 Docker Compose v2
- 메모리 4 GiB 이상 (모델 서버 제외)
- 공개 도메인을 쓰려면 DNS가 이 서버를 가리키고, 외부에서 80/443 포트로 접근할 수 있어야 한다(Let's Encrypt 인증서 발급).

## 설치

```bash
git clone https://github.com/JJeong-Hyeon/agentgate.git
cd agentgate/deploy/selfhost
cp .env.example .env
```

`.env`를 채운다.

| 변수 | 설명 |
|---|---|
| `AGENTGATE_DOMAIN` | 콘솔 주소. 공개 도메인이면 인증서가 자동 발급·갱신된다. 내부망 이름이나 `localhost`이면 Caddy 자체 CA를 쓴다(아래 참고) |
| `AGENTGATE_ADMIN_USERNAME` / `AGENTGATE_ADMIN_PASSWORD` | 첫 ADMIN 계정. 사용자가 없을 때만 만들어진다 |
| `POSTGRES_PASSWORD` | DB 비밀번호. Runtime의 DB URL에도 들어가므로 영문·숫자만 (`openssl rand -hex 32`) |
| `RUNTIME_TOKEN` | AgentGate ↔ Runtime 공유 토큰 (`openssl rand -hex 32`) |
| `AGENTGATE_SECRET_KEY` | DB에 저장하는 인증정보(MCP 서버 헤더) 암호화 키 (`openssl rand -base64 32`). 잃어버리면 저장된 헤더를 다시 입력해야 하므로 따로 백업 |
| `LLM_BASE_URL` / `LLM_MODEL` / `LLM_API_KEY` | 모델 서버. 다른 장비의 Ollama면 `http://<IP>:11434/v1`, 같은 서버에서 돌고 있으면 `http://host.docker.internal:<포트>/v1` |
| `LLM_TOOL_CALLING` | Ollama의 Tool 지원 모델(qwen3 등)이나 `--enable-auto-tool-choice --tool-call-parser <parser>`로 띄운 vLLM이면 `native`, 아니면 `json` |
| `LLM_TIMEOUT_SECONDS` | 모델 응답 하나를 기다리는 시간(초). 큰 모델·추론(thinking) 모델은 늘린다 |
| `LLM_REASONING_EFFORT` | 추론 모델이 답 전에 생각하는 정도(`none` / `minimal` / `low` / `medium` / `high`). 비우면 보내지 않는다. Agent 정의에서 Agent별로 바꿀 수 있다 |
| `LLM_MAX_CONCURRENCY` | 모델 서버로 동시에 보내는 호출 수 상한 (기본 `0` = 제한 없음). 넘는 호출은 Runtime에서 차례를 기다리고, 대기 시간은 `LLM_TIMEOUT_SECONDS`에 들어가지 않는다. Ollama는 `OLLAMA_NUM_PARALLEL`에 맞춘다 |
| `LLM_PARALLEL_TOOL_CALLS` | 모델이 한 번에 여러 Tool을 호출했을 때 모두 실행할지. 기본 `false`: 첫 호출만 실행하고 나머지는 그 결과를 본 뒤 모델이 다시 결정한다 (로컬 모델이 앞 결과에 의존하는 인자를 추측하는 것 방지) |
| `LLM_PROVIDERS_PATH` | 선택. `LLM_BASE_URL` 외에 다른 OpenAI 호환 provider(OpenAI, Gemini 등)를 더 쓰려면: `cp deploy/selfhost/providers.json.example deploy/selfhost/providers.json`로 채우고, `compose.yaml`의 `runtime` 서비스에 `volumes: ['./providers.json:/app/providers.json:ro']`를 추가한 뒤 이 값을 `/app/providers.json`로 설정한다. Agent 정의의 모델 칸에 `gemini@gemini-flash-latest`처럼 `provider@모델`로 쓰면 그 provider로, 그냥 모델 이름만 쓰면 기본 `LLM_BASE_URL`로 간다. 외부 provider는 프롬프트가 사내 밖으로 나가므로, 쓰기 전에 정책 화면에서 액션 `LLM:<provider>`(예: `LLM:gemini`)의 위험도를 정해 둔다(차단하려면 BLOCKED). APPROVAL_REQUIRED로 두면 그 Agent는 승인 요청이 처리될 때까지 멈추고(콘솔의 승인 화면에 나온다), 승인되면 이어서 실행하며 거절되면 Agent가 실패한다. 위임받은 Agent라면 Supervisor는 실패한 위임 결과를 받는다 |
| `EMBEDDING_BASE_URL` / `EMBEDDING_MODEL` / `EMBEDDING_API_KEY` | 선택. Agent 기억의 의미 검색용 임베딩 서버(OpenAI 호환 `/embeddings`, 예: Ollama의 `bge-m3`). 비우면 키워드 검색 |
| `MEMORY_MIN_SCORE` | 의미 검색 결과로 인정할 최소 코사인 유사도. 모델마다 다르며 bge-m3는 약 0.45 |
| `OTEL_EXPORTER_OTLP_ENDPOINT` / `OTEL_EXPORTER_OTLP_HEADERS` / `OTEL_CAPTURE_CONTENT` | 선택. Agent 실행 trace를 OpenTelemetry로 내보낼 곳 ([`MONITORING.md`](MONITORING.md#agent-trace-opentelemetry)) |
| `GRAFANA_ADMIN_PASSWORD` / `PROMETHEUS_RETENTION` | 선택. 모니터링 프로필(아래)의 Grafana 관리자 비밀번호, Prometheus 보존 기간(기본 `30d`) |
| `AGENTGATE_APPROVAL_TTL` | 선택. 결정되지 않은 승인 요청을 만료시키는 시간 (기본 `PT24H`) |
| `AGENTGATE_EXECUTION_RETENTION` | 선택. 끝난 실행의 기록과 Runtime checkpoint를 지우는 기간 (기본 `P90D`, `P0D`면 지우지 않음). 감사 로그와 승인 기록은 지우지 않는다 |
| `AGENTGATE_SESSION_TIMEOUT` / `AGENTGATE_SESSION_COOKIE_SECURE` | 선택. 콘솔 로그인 세션의 유휴 만료(기본 `PT8H`)와 쿠키 `Secure` 여부(compose 기본 `true`: HTTPS 뒤에서 운영) |
| `AGENTGATE_LOGIN_*` | 선택. 로그인 실패 제한(저장소 `STORE`: compose 기본 `redis`, 직접 실행 기본 `memory`): 아이디 + IP당 연속 실패 `MAX_FAILURES`(기본 5), IP당 `MAX_FAILURES_PER_IP`(기본 20), 막는 시간 `LOCK_DURATION`(기본 `PT15M`) |
| `AGENTGATE_NOTIFY_WEBHOOK_*` | 선택. 승인 요청 알림 웹훅 ([`API_SPEC.md`](API_SPEC.md) 승인 처리 참고) |

비어 있는 필수 값이 있으면 `docker compose`가 어떤 값인지 알려주고 시작하지 않는다.

```bash
docker compose up -d --build
# 또는 릴리스 이미지로: .env에 AGENTGATE_VERSION=0.3.0 (원하는 릴리스 버전)을 넣고
docker compose pull && docker compose up -d
docker compose ps        # 모두 Up이면 정상
```

`https://<AGENTGATE_DOMAIN>/`에서 ADMIN 계정으로 로그인한다.

## 첫 설정

1. **Runtime Agent 등록**: 워크플로 수준의 Tool / 승인 노드는 `runtime-agent`로 평가된다. 콘솔의 Agents 화면에서 Agent ID `runtime-agent`로 한 번 등록한다.
2. **사용자 만들기**: Users 화면에서 역할별 계정(EDITOR / APPROVER / VIEWER)을 만들고 공용 ADMIN 계정 사용을 줄인다.
3. **모델 연결 확인**: `docker compose exec runtime python -c "import urllib.request;print(urllib.request.urlopen('http://localhost:8000/llm/health').read())"` — `model_available: true`면 정상.

## 사내 계정(SSO)으로 로그인

사내 IdP(Keycloak, Okta, Azure AD 등 OpenID Connect를 지원하는 것)로 콘솔에 로그인하게 할 수 있다. 기본은 꺼져 있다. SAML은 지원하지 않는다(SAML만 되는 IdP는 Keycloak 같은 OIDC 브로커를 앞에 둔다).

1. **IdP에 클라이언트 등록**: 종류는 OpenID Connect, 클라이언트 인증은 secret(confidential), 흐름은 Authorization Code(PKCE 사용). 리디렉션 URI는 `https://<AGENTGATE_DOMAIN>/login/oauth2/code/oidc`.
2. **그룹을 토큰에 넣기**: ID 토큰에 사용자의 그룹 이름 목록이 `groups` 클레임으로 들어가게 한다(Keycloak은 클라이언트에 "Group Membership" 매퍼를 추가하고 Full group path를 끈다). 다른 이름이면 `AGENTGATE_OIDC_GROUPS_CLAIM`으로 알려 준다.
3. **`.env`를 채운다**:

| 변수 | 설명 |
|---|---|
| `AGENTGATE_OIDC_ENABLED` | `true`로 켠다 |
| `AGENTGATE_OIDC_ISSUER_URI` | IdP의 issuer 주소(예: `https://sso.example.com/realms/company`). `/.well-known/openid-configuration`이 이 아래에 있어야 한다 |
| `AGENTGATE_OIDC_CLIENT_ID` / `AGENTGATE_OIDC_CLIENT_SECRET` | 1단계에서 만든 클라이언트 |
| `AGENTGATE_OIDC_ADMIN_GROUPS` / `_EDITOR_GROUPS` / `_APPROVER_GROUPS` / `_VIEWER_GROUPS` | 각 역할을 줄 IdP 그룹 이름(쉼표로 여러 개, 대소문자 구분). 한 사람이 여러 그룹에 있으면 역할도 여러 개 |
| `AGENTGATE_OIDC_DEFAULT_ROLE` | 어느 그룹에도 없는 사람에게 줄 역할. **비워 두면 로그인을 거부한다**(권장). 회사 전체가 IdP를 쓰는데 이 값을 `VIEWER`로 두면 직원 누구나 감사 로그까지 읽을 수 있다 |
| `AGENTGATE_OIDC_USERNAME_CLAIM` | 콘솔 아이디로 쓸 클레임(기본 `preferred_username`). 영문 · 숫자와 `. _ @ -`만, 3~64자 |
| `AGENTGATE_OIDC_BUTTON_LABEL` | 로그인 화면 버튼 이름(기본 `SSO`) |
| `AGENTGATE_LOCAL_LOGIN_ENABLED` | `false`면 콘솔의 아이디 · 비밀번호 로그인을 끈다. 스크립트용 HTTP Basic은 계속 쓸 수 있으니 SSO가 안 될 때를 위해 ADMIN 자체 계정은 남겨 둔다 |

4. `docker compose up -d`로 다시 띄우고, 로그인 화면의 SSO 버튼으로 로그인해 본다.

동작 규칙:

- 처음 로그인하면 콘솔 사용자가 자동으로 만들어지고(Users 화면에 SSO 계정으로 보인다), **로그인할 때마다 역할이 IdP의 그룹대로 다시 정해진다.** 콘솔에서 바꾼 역할은 다음 로그인에 덮어쓰인다.
- SSO 사용자는 IdP의 고유 식별자(`sub`)로 구분한다. IdP에서 아이디가 바뀌어도 같은 콘솔 사용자로 로그인하고, 이미 쓰이는 아이디를 다른 사람이 받으면 로그인이 거부된다.
- 같은 아이디의 자체 계정이 있으면(`admin` 등) SSO로는 그 계정에 로그인할 수 없다. SSO 계정은 비밀번호로 로그인할 수 없다.
- Users 화면에서 SSO 계정을 비활성화하면 다음 SSO 로그인이 막힌다. 이미 열린 세션은 만료(`AGENTGATE_SESSION_TIMEOUT`)까지 유지된다.
- 로그아웃은 AgentGate 세션만 끝낸다(IdP 세션은 그대로).
- 기동할 때 IdP에 접속하지 않는다. IdP가 내려가 있어도 AgentGate와 자체 계정 로그인은 정상이고, SSO 로그인만 IdP가 돌아올 때까지 실패한다.
- 로그인에 실패하면(허용된 그룹이 없음, 비활성 계정 등) 로그인 화면에 일반적인 안내만 나오고 이유는 AgentGate 로그에 남는다.

## 모델 서버를 별도 장비로 (Ollama)

GPU 장비(예: DGX Spark)는 모델만 돌리고 AgentGate는 다른 서버에 두는 구성이다. Runtime(채팅)과 AgentGate(임베딩)가 모두 모델 서버를 호출한다. 연결 방법은 두 가지다.

### A. SSH 터널 (모델 서버를 바꾸지 않음, 공용 장비에 권장)

모델 서버의 Ollama는 그대로 `127.0.0.1`에서만 받고, AgentGate 서버가 SSH로 포트를 끌어온다. 모델 서버에는 SSH 계정(키 인증)만 있으면 되고 sudo·방화벽 변경이 필요 없다.

```bash
ssh-copy-id <계정>@<모델 서버 IP>                                    # 한 번만
scripts/model-tunnel.sh start <계정>@<모델 서버 IP> 11434 11435      # 채팅 / 임베딩 포트
scripts/model-tunnel.sh status <계정>@<모델 서버 IP>
```

`.env`에서는 `http://host.docker.internal:<포트>/v1`로 연결한다.

- Linux 서버에서는 컨테이너가 접속할 수 있게 Docker 브리지 주소에 연다: `BIND_ADDRESS=172.17.0.1 scripts/model-tunnel.sh start ...` (사내망의 다른 장비에는 열리지 않는다).
- 터널은 재부팅하면 끊긴다. 서버 기동 시 자동으로 열려면 systemd 서비스나 crontab `@reboot`에 `start` 명령을 등록한다.
- 공용 Ollama에 모델을 추가할 권한이 없으면, 자기 계정으로 별도 Ollama를 띄워 임베딩용으로 쓸 수 있다: `OLLAMA_HOST=127.0.0.1:11435 OLLAMA_MODELS=~/.ollama-mine/models ollama serve` 후 `OLLAMA_HOST=127.0.0.1:11435 ollama pull bge-m3`.

### B. 모델 서버를 사내망에 공개 (전용 장비일 때)

모델 서버에서 (sudo 필요, **장비 전체에 적용**되므로 여러 사람이 쓰는 장비라면 관리자와 상의):

```bash
ollama pull bge-m3                      # 기억 검색용 임베딩 (채팅 모델은 이미 받아 둔 것을 사용)

# Ollama는 기본으로 127.0.0.1에서만 받는다 → 사내망에서 받도록
sudo mkdir -p /etc/systemd/system/ollama.service.d
printf '[Service]\nEnvironment="OLLAMA_HOST=0.0.0.0:11434"\n' | sudo tee /etc/systemd/system/ollama.service.d/override.conf
sudo systemctl daemon-reload && sudo systemctl restart ollama

# Ollama에는 인증이 없으므로 11434는 AgentGate 서버에서만 허용한다
sudo ufw allow 22/tcp                  # enable 전에 SSH부터 허용 (접속이 끊기지 않게)
sudo ufw allow from <AgentGate 서버 IP> to any port 11434 proto tcp
sudo ufw enable
```

AgentGate 서버에서 연결 확인:

```bash
curl http://<모델 서버 IP>:11434/v1/models                 # 받아 둔 모델 목록
curl http://<모델 서버 IP>:11434/v1/embeddings -H 'Content-Type: application/json' \
  -d '{"model":"bge-m3","input":["연결 확인"]}' | head -c 200
```

- 다른 PC에서 `curl http://<모델 서버 IP>:11434/v1/models`가 **실패**해야 방화벽이 제대로 걸린 것이다.
- qwen3 같은 추론 모델은 답 전에 생각(thinking)을 하므로 짧은 질문에도 수 초~수십 초가 걸린다(실측: qwen3:14b, DGX Spark에서 호출당 약 10~17초). `LLM_REASONING_EFFORT=none`이면 생각을 건너뛰어 호출당 약 1초로 줄어든다. 시스템 프롬프트의 `/no_think`는 Ollama의 OpenAI 호환 API에서는 효과가 없다. 복잡한 판단이 필요한 Agent만 정의에서 `low`~`high`로 올린다.

## 모니터링 (선택: Prometheus + Grafana)

콘솔의 대시보드로 충분하지 않고 장기 추세나 알림이 필요하면 모니터링 프로필을 켠다.

```bash
docker compose --profile monitoring up -d
```

- `https://<AGENTGATE_DOMAIN>/grafana`에서 `admin` / `GRAFANA_ADMIN_PASSWORD`로 로그인한다. 비워 두면 `admin`이고 첫 로그인 때 바꾸라고 한다.
- Prometheus 데이터소스와 "AgentGate" 대시보드(`infra/grafana/agentgate-dashboard.json`)가 자동으로 등록된다. 지표 설명과 알림 예시는 [`MONITORING.md`](MONITORING.md).
- Prometheus는 compose 네트워크 안에서만 `app:8080/actuator/prometheus`를 수집하며 외부에 포트를 열지 않는다.
- 이후 `docker compose up -d`, `docker compose down`에도 `--profile monitoring`을 붙여야 모니터링 서비스까지 함께 다뤄진다.

## 내부망 / 사설 인증서

- `AGENTGATE_DOMAIN`이 공개 DNS에 없는 이름이면 Caddy가 자체 CA로 인증서를 만든다. 브라우저 경고를 없애려면 CA 인증서를 사내 PC에 배포한다:
  `docker compose cp caddy:/data/caddy/pki/authorities/local/root.crt ./agentgate-root.crt`
- 사내 인증서를 쓰려면 [`Caddyfile`](../deploy/selfhost/Caddyfile)의 사이트 블록에 `tls /etc/caddy/cert.pem /etc/caddy/key.pem`을 추가하고 두 파일을 compose의 `caddy` 볼륨으로 마운트한다.

## 운영

| 작업 | 명령 (`deploy/selfhost`에서) |
|---|---|
| 업데이트 (릴리스) | [`CHANGELOG.md`](../CHANGELOG.md) 확인 → `.env`의 `AGENTGATE_VERSION`을 새 버전으로 → `docker compose pull && docker compose up -d`. DB 스키마는 기동 시 Flyway가 자동 적용 |
| 업데이트 (소스) | `git pull && docker compose up -d --build` |
| 되돌리기 | `AGENTGATE_VERSION`을 이전 버전으로 바꾸고 백업한 DB를 복원 (새 버전이 DB 스키마를 바꿨을 수 있으므로) |
| 로그 | `docker compose logs -f app runtime` |
| 백업 | `docker compose exec -T postgres pg_dump -U agentgate agentgate > agentgate-$(date +%F).sql` |
| 복원 | `docker compose exec -T postgres psql -U agentgate agentgate < agentgate-<날짜>.sql` (빈 DB에) |
| 중지 | `docker compose down` (데이터는 볼륨에 유지, `-v`를 붙이면 삭제) |

- 업데이트 전에는 백업을 먼저 뜬다.
- `/actuator/prometheus`는 외부(Caddy)에서 막혀 있다. Prometheus는 compose 네트워크 안에서 `app:8080`을 수집하도록 붙인다([`MONITORING.md`](MONITORING.md)).

## 설치 확인

```bash
docker compose ps                     # app · runtime이 (healthy)면 준비된 것
curl -k https://<AGENTGATE_DOMAIN>/actuator/health      # {"status":"UP"}
docker compose exec runtime python -c "import urllib.request;print(urllib.request.urlopen('http://localhost:8000/llm/health').read())"   # model_available: true
```

브라우저로 `https://<AGENTGATE_DOMAIN>/`에 접속해 ADMIN으로 로그인하면 대시보드가 보인다. 이어서 [`OPERATIONS_GUIDE.md`](OPERATIONS_GUIDE.md)의 도입 체크리스트를 따른다.

## 문제 해결

| 증상 | 원인 · 조치 |
|---|---|
| `docker compose up`이 `set ... in .env` 오류로 멈춘다 | 필수 값이 비어 있다. 메시지에 나온 변수를 `.env`에 채운다 |
| 80 · 443 포트를 못 연다 | 같은 서버의 다른 웹 서버가 쓰고 있다. 멈추거나 compose의 `caddy` 포트를 바꾼다 |
| 브라우저가 인증서 경고를 띄운다 | 공개 DNS에 없는 이름은 Caddy가 자체 CA로 인증서를 만든다. [내부망 / 사설 인증서](#내부망--사설-인증서) |
| 기동 직후 잠깐 502 | app이 준비되기 전이다. `docker compose ps`가 `(healthy)`가 될 때까지 기다린다 |
| `model_available: false` / 실행이 LLM 오류로 실패 | `LLM_BASE_URL` · `LLM_MODEL`을 확인한다. 모델 서버가 다른 장비면 연결(SSH 터널 등)이 끊겼는지 본다 |
| 로그인이 "잠시 막혔습니다" | 실패가 반복돼 잠긴 것이다. 15분 뒤 풀리거나 [운영 가이드](OPERATIONS_GUIDE.md#7-장애-대응)의 초기화 명령을 쓴다 |
| 업그레이드 뒤 앱이 기동하지 않는다 | `docker compose logs app`에서 스키마 검증 · 필수 설정 오류를 확인한다. 안 되면 `AGENTGATE_VERSION`을 이전 버전으로 되돌리고 백업을 복원한다 |

