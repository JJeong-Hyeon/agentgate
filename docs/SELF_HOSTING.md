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
| `LLM_PARALLEL_TOOL_CALLS` | 모델이 한 번에 여러 Tool을 호출했을 때 모두 실행할지. 기본 `false`: 첫 호출만 실행하고 나머지는 그 결과를 본 뒤 모델이 다시 결정한다 (로컬 모델이 앞 결과에 의존하는 인자를 추측하는 것 방지) |
| `EMBEDDING_BASE_URL` / `EMBEDDING_MODEL` / `EMBEDDING_API_KEY` | 선택. Agent 기억의 의미 검색용 임베딩 서버(OpenAI 호환 `/embeddings`, 예: Ollama의 `bge-m3`). 비우면 키워드 검색 |
| `MEMORY_MIN_SCORE` | 의미 검색 결과로 인정할 최소 코사인 유사도. 모델마다 다르며 bge-m3는 약 0.45 |
| `OTEL_EXPORTER_OTLP_ENDPOINT` / `OTEL_EXPORTER_OTLP_HEADERS` / `OTEL_CAPTURE_CONTENT` | 선택. Agent 실행 trace를 OpenTelemetry로 내보낼 곳 ([`MONITORING.md`](MONITORING.md#agent-trace-opentelemetry)) |
| `GRAFANA_ADMIN_PASSWORD` / `PROMETHEUS_RETENTION` | 선택. 모니터링 프로필(아래)의 Grafana 관리자 비밀번호, Prometheus 보존 기간(기본 `30d`) |
| `AGENTGATE_APPROVAL_TTL` | 선택. 결정되지 않은 승인 요청을 만료시키는 시간 (기본 `PT24H`) |
| `AGENTGATE_EXECUTION_RETENTION` | 선택. 끝난 실행의 기록과 Runtime checkpoint를 지우는 기간 (기본 `P90D`, `P0D`면 지우지 않음). 감사 로그와 승인 기록은 지우지 않는다 |
| `AGENTGATE_LOGIN_*` | 선택. 로그인 실패 제한: 아이디 + IP당 연속 실패 `MAX_FAILURES`(기본 5), IP당 `MAX_FAILURES_PER_IP`(기본 20), 막는 시간 `LOCK_DURATION`(기본 `PT15M`) |
| `AGENTGATE_NOTIFY_WEBHOOK_*` | 선택. 승인 요청 알림 웹훅 ([`API_SPEC.md`](API_SPEC.md) 승인 처리 참고) |

비어 있는 필수 값이 있으면 `docker compose`가 어떤 값인지 알려주고 시작하지 않는다.

```bash
docker compose up -d --build
docker compose ps        # 모두 Up이면 정상
```

`https://<AGENTGATE_DOMAIN>/`에서 ADMIN 계정으로 로그인한다.

## 첫 설정

1. **Runtime Agent 등록**: 워크플로 수준의 Tool / 승인 노드는 `runtime-agent`로 평가된다. 콘솔의 Agents 화면에서 Agent ID `runtime-agent`로 한 번 등록한다.
2. **사용자 만들기**: Users 화면에서 역할별 계정(EDITOR / APPROVER / VIEWER)을 만들고 공용 ADMIN 계정 사용을 줄인다.
3. **모델 연결 확인**: `docker compose exec runtime python -c "import urllib.request;print(urllib.request.urlopen('http://localhost:8000/llm/health').read())"` — `model_available: true`면 정상.

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
| 업데이트 | `git pull && docker compose up -d --build` — DB 스키마는 기동 시 Flyway가 자동 적용 |
| 로그 | `docker compose logs -f app runtime` |
| 백업 | `docker compose exec -T postgres pg_dump -U agentgate agentgate > agentgate-$(date +%F).sql` |
| 복원 | `docker compose exec -T postgres psql -U agentgate agentgate < agentgate-<날짜>.sql` (빈 DB에) |
| 중지 | `docker compose down` (데이터는 볼륨에 유지, `-v`를 붙이면 삭제) |

- 업데이트 전에는 백업을 먼저 뜬다.
- `/actuator/prometheus`는 외부(Caddy)에서 막혀 있다. Prometheus는 compose 네트워크 안에서 `app:8080`을 수집하도록 붙인다([`MONITORING.md`](MONITORING.md)).
