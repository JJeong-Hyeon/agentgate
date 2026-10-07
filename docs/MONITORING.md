# 모니터링 (Prometheus + Grafana)

리소스 프로그램에서 받은 Grafana는 Data Source 설정 권한이 없어 사용하지 않는다. 대신 `infra/monitoring.tf`로 만든 EC2 #2에 Prometheus+Grafana를 직접 셀프호스팅한다. 실제 계정/서버 접속 정보는 각자 진행 — 비밀번호를 코드/문서에 커밋하지 않는다.

## Monitoring EC2 (Prometheus + Grafana)

1. `docs/DEPLOYMENT.md` 절차로 `terraform apply`까지 마치면 `monitoring_public_ip`가 출력됨 — EC2 #2에는 user_data로 Prometheus+Grafana 컨테이너가 이미 떠있음(앱 EC2의 프라이빗 IP를 스크래핑 대상으로 자동 설정됨)
2. Grafana는 보안그룹상 `admin_cidr`(내 IP)에서만 3000번 포트로 접근 가능 — 브라우저에서 `http://<monitoring_public_ip>:3000` 접속, 기본 계정 `admin`/`admin`(최초 로그인 시 비밀번호 변경 요구됨)
3. Grafana → **Connections → Data sources → Add data source → Prometheus** → URL에 `http://localhost:9090` 입력(같은 EC2 안에서 Prometheus가 돌고 있으므로) → Save & test
4. **Dashboards → New → Import** → `infra/grafana/agentgate-dashboard.json` 업로드 → 방금 만든 Prometheus 데이터소스 선택
5. 요청량/URI별 트래픽/5xx 에러율/JVM 힙/CPU/업타임 패널이 뜨는지 확인 — 앱 EC2에서 AgentGate가 실제로 요청을 받고 있어야 값이 채워짐

로컬 개발 중에는 기존처럼 `docker compose up -d`의 로컬 Grafana(`localhost:3000`)를 그대로 쓰면 된다 — 이건 그대로 유지.

## 참고

- Grafana 버전에 따라 import UI가 조금 다를 수 있어 import 후 가벼운 수정이 필요할 수 있다.
