# AWS 배포 (ALB + App EC2 + Runtime EC2 + RDS + 모니터링 EC2)

상시 운영이 아니라 필요할 때만 켜는 걸 전제로 한다. EC2/RDS는 `scripts/aws-start.sh` / `aws-stop.sh`로 껐다 켤 수 있고, ALB는 "정지" 개념이 없어 떠있는 동안 계속 과금된다 — 안 쓸 땐 `terraform destroy -target=aws_lb.app`로 지웠다가 필요할 때 `terraform apply`로 다시 만드는 걸 권장한다.

## 구성

```text
브라우저 ──HTTP──▶ ALB :80 ──▶ App EC2 :8080   AgentGate (Spring, 프론트엔드 포함 jar) + Redis
                                   │   ▲
                         resume /  │   │ 행동 평가 / 실행 이벤트
                         검증 / 실행 ▼   │
                               Runtime EC2 :8000  Runtime (LangGraph) + Ollama   (docker compose)
                                   │
            App EC2, Runtime EC2 ──┴──▶ RDS Postgres (AgentGate 데이터 + LangGraph checkpoint)
Monitoring EC2 (Prometheus + Grafana) ──▶ App EC2 /actuator/prometheus
```

| 인스턴스 | 타입 (기본) | 역할 |
|---|---|---|
| App EC2 | `t4g.micro` | AgentGate jar (UI + API), Redis 컨테이너 |
| Runtime EC2 | `t4g.large` (`runtime_instance_type`) | Runtime + Ollama. CPU 추론이라 8 GiB에서는 3B급 모델(`qwen2.5:3b`)이 적당하고, 7B는 `t4g.xlarge` 권장 |
| RDS | `db.t4g.micro` | Postgres |
| Monitoring EC2 | `t4g.micro` | Prometheus + Grafana ([`MONITORING.md`](MONITORING.md)) |

Runtime은 App에서만(8000), App은 ALB·모니터링·Runtime에서만(8080), RDS는 App·Runtime에서만 접근 가능하다.

## 사전 준비

1. AWS 계정 자격증명 설정: `aws configure`
2. EC2 키페어 생성(콘솔 또는 `aws ec2 create-key-pair`) — SSH / 배포 스크립트용
3. 로컬에 Java 21, Node 24(프론트엔드 빌드), Terraform, `rsync`

서버에서 저장소를 clone하지 않는다. 배포 스크립트가 로컬에서 빌드한 jar와 Runtime 소스를 올린다.

## 1. 인프라 생성

```bash
cd infra
terraform init
terraform plan \
  -var="key_pair_name=<키페어 이름>" \
  -var="admin_cidr=<내 IP>/32" \
  -var="db_password=<RDS 비밀번호>" \
  -var="admin_password=<AgentGate 관리자 비밀번호>"
  # 7B 모델을 쓸 거면: -var="runtime_instance_type=t4g.xlarge"
terraform apply  # 위와 동일한 -var 플래그로
```

`terraform output`으로 `alb_dns_name`, `ec2_public_ip`, `app_private_ip`, `runtime_public_ip`, `runtime_private_ip`, `rds_endpoint` 확인.

## 2. AgentGate (App EC2)

최초 1회, App EC2에 환경변수 파일을 만든다.

```bash
ssh -i <키페어.pem> ec2-user@<ec2_public_ip>
mkdir -p ~/agentgate
cat > ~/agentgate/.env <<EOF
SPRING_PROFILES_ACTIVE=prod
SPRING_DATASOURCE_URL=jdbc:postgresql://<rds_endpoint>/agentgate
SPRING_DATASOURCE_USERNAME=agentgate
SPRING_DATASOURCE_PASSWORD=<RDS 비밀번호>
SPRING_DATA_REDIS_HOST=localhost
SPRING_DATA_REDIS_PORT=6379
AGENTGATE_ADMIN_USERNAME=admin
AGENTGATE_ADMIN_PASSWORD=<AgentGate 관리자 비밀번호>
AGENTGATE_RUNTIME_BASE_URL=http://<runtime_private_ip>:8000
AGENTGATE_RUNTIME_TOKEN=<임의의 긴 문자열, Runtime의 RUNTIME_TOKEN과 동일>
AGENTGATE_SECRET_KEY=<openssl rand -base64 32 결과. DB에 저장하는 인증정보(MCP 서버 헤더 등) 암호화 키, 바꾸면 기존 값을 읽을 수 없음>
EOF
```

이후 배포는 로컬에서:

```bash
scripts/deploy-app.sh <키페어.pem>
```

`./gradlew bootJar -PwithFrontend`로 프론트엔드를 포함한 jar를 만들어 올리고, Redis 컨테이너 확인 후 systemd(`infra/systemd/agentgate.service`, `java -jar`)로 재시작한다. `http://<alb_dns_name>/`에서 UI, `/actuator/health`로 상태 확인.

## 3. Runtime + Ollama (Runtime EC2)

워크플로 수준의 Tool / 승인 노드를 평가받을 Agent를 한 번 등록한다. Runtime은 공유 토큰(`RUNTIME_TOKEN`)으로 인증하므로 발급된 apiKey는 쓰지 않는다. Agent 노드는 각자의 Agent 이름으로 평가된다.

```bash
curl -u admin:<관리자 비밀번호> -X POST http://<alb_dns_name>/api/v1/agents \
  -H "Content-Type: application/json" -d '{"agentId":"runtime-agent","name":"Runtime Agent"}'
```

최초 1회, Runtime EC2에 환경변수 파일을 만든다 ([`deploy/runtime/.env.example`](../deploy/runtime/.env.example)).

```bash
ssh -i <키페어.pem> ec2-user@<runtime_public_ip>
mkdir -p ~/agentgate-runtime
cat > ~/agentgate-runtime/.env <<EOF
AGENTGATE_BASE_URL=http://<app_private_ip>:8080
AGENTGATE_AGENT_ID=runtime-agent
RUNTIME_TOKEN=<App의 AGENTGATE_RUNTIME_TOKEN과 동일>
RUNTIME_DATABASE_URL=postgresql://agentgate:<RDS 비밀번호>@<rds_endpoint>/agentgate
LLM_MODEL=qwen2.5:3b
EOF
```

**외부 LLM 서버를 쓰는 경우** (예: NVIDIA DGX Spark의 vLLM): `.env`에 다음을 추가한다. `LLM_BASE_URL`이 있으면 배포 스크립트가 Ollama를 띄우지 않고 모델도 받지 않는다. Runtime EC2에서 그 서버에 네트워크로 닿아야 한다(사설망 / VPN / 보안그룹).

```bash
LLM_BASE_URL=http://<llm 서버>:8000/v1
LLM_MODEL=<서빙 중인 모델 이름>
LLM_API_KEY=<vLLM을 --api-key로 띄운 경우>
# vLLM을 --enable-auto-tool-choice --tool-call-parser <parser>(Qwen 계열은 hermes)로 띄웠으면 native, 아니면 json
LLM_TOOL_CALLING=native
```

연결 확인: App EC2에서 `curl http://<runtime_private_ip>:8000/llm/health` — `model_available: true`면 정상.

이후 배포는 로컬에서:

```bash
scripts/deploy-runtime.sh <키페어.pem>
```

`runtime/` 소스와 [`deploy/runtime/compose.yaml`](../deploy/runtime/compose.yaml)을 rsync로 올리고, 컨테이너를 빌드·기동한 뒤 (외부 LLM이 아니면) `LLM_MODEL`을 Ollama에 받아둔다(최초 1회는 모델 다운로드로 수 분 소요). AgentGate가 Runtime에 닿는지는 UI에서 워크플로를 저장해보면 된다(검증을 Runtime이 수행).

### MCP 서버 (선택)

Streamable HTTP MCP 서버는 **UI의 Tools 화면(또는 `POST /api/v1/mcp-servers`)에서 등록하는 것을 권장**한다. 인증 헤더가 `AGENTGATE_SECRET_KEY`로 암호화되어 RDS에 저장되고, Runtime이 재시작 없이 30초 안에 반영한다. Runtime EC2(보안그룹)에서 해당 서버로 나가는 연결이 허용되어야 한다.

stdio 서버(Runtime 호스트에서 명령을 실행)는 보안상 화면에서 등록할 수 없고 설정 파일로만 등록한다. `~/agentgate-runtime/mcp.json`(`mcpServers` 형식, [ARCHITECTURE 9장](ARCHITECTURE.md#9-tool--mcp))을 만들고 `.env`에 `MCP_CONFIG_PATH=/app/mcp.json`을 추가한 뒤, `compose.yaml`의 runtime 서비스에 `volumes: ['./mcp.json:/app/mcp.json:ro']`를 넣어 재배포한다. Runtime 이미지는 Python만 포함하므로 `npx` 같은 명령이 필요한 stdio 서버보다 `url`(Streamable HTTP) 서버를 권장한다.

## 4. 껐다 켜기

```bash
./scripts/aws-start.sh   # App / Monitoring / Runtime EC2 + RDS 기동
./scripts/aws-stop.sh    # 정지
```

컨테이너(Redis, Runtime, Ollama)는 `restart: unless-stopped` / systemd로 자동 기동되고, 받아둔 모델은 볼륨에 남는다. RDS는 정지 후 7일이 지나면 AWS가 자동으로 재시작시킨다(AWS 정책) — 장기간 안 쓸 거면 `terraform destroy` 전체를 고려할 것.

## DB 스키마 (Flyway)

App이 기동할 때 Flyway가 RDS 스키마를 마이그레이션한다(`db/migration`). 별도 작업은 없다.

- Flyway 도입 전부터 쓰던 RDS는 첫 기동 때 자동으로 baseline된 뒤 V1(이미 있는 테이블은 건너뜀), V2(enum CHECK 제약 갱신, `STOPPED` 상태 허용)가 적용된다.
- 엔티티와 스키마가 다르면 App이 기동하지 않는다(`ddl-auto: validate`). 로그의 Hibernate 스키마 검증 오류를 확인한다.
- 배포 전 RDS 스냅샷을 만들어 두는 것을 권장한다.

## 보안 참고

- `agentgate.admin.password`가 개발용 기본값("changeme")이면 `prod` 프로파일에서 기동이 즉시 실패한다(`SecurityHardeningCheck`).
- `AGENTGATE_RUNTIME_BASE_URL`을 설정했는데 `AGENTGATE_RUNTIME_TOKEN`이 비어 있으면 기동이 실패한다.
- `AGENTGATE_SECRET_KEY`가 없으면 `prod` 프로파일에서 기동이 실패한다. 이 키로 MCP 서버 인증 헤더를 AES-256-GCM으로 암호화해 저장하며, 키를 잃거나 바꾸면 저장된 헤더를 다시 입력해야 한다. 안전한 곳(예: AWS Secrets Manager / SSM Parameter Store)에 백업할 것.
- ALB는 현재 HTTP(80)만 연다. UI 로그인(Basic 인증)이 평문으로 전송되므로, 외부에 공개할 때는 도메인 + ACM 인증서로 HTTPS 리스너를 추가해야 한다.
