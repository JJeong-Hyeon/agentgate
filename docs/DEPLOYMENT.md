# AWS 배포 (EC2 + RDS + ALB + 모니터링 EC2)

상시 운영이 아니라 필요할 때만 켜는 걸 전제로 한다. EC2/RDS는 `scripts/aws-start.sh` / `aws-stop.sh`로 껐다 켤 수 있고, ALB는 "정지" 개념이 없어 떠있는 동안 계속 과금된다 — 안 쓸 땐 `terraform destroy -target=aws_lb.app`로 지웠다가 필요할 때 `terraform apply`로 다시 만드는 걸 권장한다.

## 사전 준비

1. AWS 계정 자격증명 설정: `aws configure`
2. EC2 키페어 생성(콘솔 또는 `aws ec2 create-key-pair`) — SSH 접속용
3. GitHub 저장소가 private이므로 EC2에서 clone하려면 [Deploy Key](https://docs.github.com/en/authentication/connecting-to-github-with-ssh/managing-deploy-keys) 생성(읽기 전용으로 충분) 후 저장소 Settings → Deploy keys에 등록

## 1. 인프라 생성

```bash
cd infra
terraform init
terraform validate
terraform plan \
  -var="key_pair_name=<키페어 이름>" \
  -var="admin_cidr=<내 IP>/32" \
  -var="db_password=<RDS 비밀번호>" \
  -var="admin_password=<AgentGate 관리자 비밀번호>"
terraform apply  # 위와 동일한 -var 플래그로
```

`terraform output`으로 `ec2_public_ip`, `alb_dns_name`, `rds_endpoint`, `monitoring_public_ip` 확인. 모니터링용 EC2 #2는 Prometheus+Grafana가 자동으로 기동되어 있음 — Grafana 접속/데이터소스 설정은 [`docs/GRAFANA_N8N_SETUP.md`](GRAFANA_N8N_SETUP.md) 참고.

## 2. EC2에 앱 배포

```bash
ssh -i <키페어.pem> ec2-user@<ec2_public_ip>

# Redis 컨테이너 (최초 1회)
docker run -d --name agentgate-redis -p 6379:6379 --restart unless-stopped redis:latest

# 앱 clone (deploy key 등록되어 있어야 함)
git clone git@github.com:JJeong-Hyeon/agentgate.git
cd agentgate

# 환경변수 파일
cat > .env <<EOF
SPRING_PROFILES_ACTIVE=prod
SPRING_DATASOURCE_URL=jdbc:postgresql://<rds_endpoint>/agentgate
SPRING_DATASOURCE_USERNAME=agentgate
SPRING_DATASOURCE_PASSWORD=<RDS 비밀번호>
SPRING_DATA_REDIS_HOST=localhost
SPRING_DATA_REDIS_PORT=6379
AGENTGATE_ADMIN_USERNAME=admin
AGENTGATE_ADMIN_PASSWORD=<AgentGate 관리자 비밀번호>
EOF

sudo cp infra/systemd/agentgate.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now agentgate
```

`http://<alb_dns_name>/actuator/health`로 확인.

## 3. 껐다 켜기

```bash
./scripts/aws-start.sh   # EC2 + RDS 기동
./scripts/aws-stop.sh    # EC2 + RDS 정지
```

RDS는 정지 후 7일이 지나면 AWS가 자동으로 재시작시킨다(AWS 정책, 스크립트로 막을 수 없음) — 장기간 안 쓸 거면 `terraform destroy` 전체를 고려할 것.

## prod 프로파일 안전장치

`agentgate.admin.password`가 개발용 기본값("changeme")이면 `prod` 프로파일에서 기동이 즉시 실패한다(`SecurityHardeningCheck`) — `.env`에 반드시 실제 비밀번호를 넣을 것.
