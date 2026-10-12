# 단일 서버 설치 (Docker Compose)

사내 서버 한 대에 AgentGate를 설치하는 방법이다. AWS 구성은 [`DEPLOYMENT.md`](DEPLOYMENT.md)를 참고한다.

```text
브라우저 ──HTTPS──▶ Caddy :443 ──▶ app :8080 (AgentGate API + 콘솔)
                                     │  ▲
                                     ▼  │
                                  runtime :8000 (LangGraph) ──▶ 사내 LLM 서버 (OpenAI 호환, 예: vLLM)
                     app, runtime ──▶ postgres (AgentGate 데이터 + checkpoint), redis (정책 캐시)
```

[`deploy/selfhost/compose.yaml`](../deploy/selfhost/compose.yaml)이 위 구성을 한 번에 띄운다. 모델 서버는 포함하지 않는다. 이미 운영 중인 OpenAI 호환 서버(예: NVIDIA DGX Spark의 vLLM)를 `LLM_BASE_URL`로 연결한다.

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
| `LLM_BASE_URL` / `LLM_MODEL` / `LLM_API_KEY` | 모델 서버. 같은 서버에서 돌고 있으면 `http://host.docker.internal:<포트>/v1` |
| `LLM_TOOL_CALLING` | vLLM을 `--enable-auto-tool-choice --tool-call-parser <parser>`로 띄웠으면 `native`, 아니면 `json` |
| `AGENTGATE_APPROVAL_TTL` | 선택. 결정되지 않은 승인 요청을 만료시키는 시간 (기본 `PT24H`) |
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
