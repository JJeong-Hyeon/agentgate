# Monitoring EC2(Grafana) + n8n 연동

리소스 프로그램에서 받은 Grafana는 Data Source 설정 권한이 없어 사용하지 않는다. 대신 `infra/monitoring.tf`로 만든 EC2 #2에 Prometheus+Grafana를 직접 셀프호스팅한다. 실제 계정/서버 접속 정보는 각자 진행 — 비밀번호를 코드/문서에 커밋하지 않는다.

## Monitoring EC2 (Prometheus + Grafana)

1. `docs/DEPLOYMENT.md` 절차로 `terraform apply`까지 마치면 `monitoring_public_ip`가 출력됨 — EC2 #2에는 user_data로 Prometheus+Grafana 컨테이너가 이미 떠있음(앱 EC2의 프라이빗 IP를 스크래핑 대상으로 자동 설정됨)
2. Grafana는 보안그룹상 `admin_cidr`(내 IP)에서만 3000번 포트로 접근 가능 — 브라우저에서 `http://<monitoring_public_ip>:3000` 접속, 기본 계정 `admin`/`admin`(최초 로그인 시 비밀번호 변경 요구됨)
3. Grafana → **Connections → Data sources → Add data source → Prometheus** → URL에 `http://localhost:9090` 입력(같은 EC2 안에서 Prometheus가 돌고 있으므로) → Save & test
4. **Dashboards → New → Import** → `infra/grafana/agentgate-dashboard.json` 업로드 → 방금 만든 Prometheus 데이터소스 선택
5. 요청량/URI별 트래픽/5xx 에러율/JVM 힙/CPU/업타임 패널이 뜨는지 확인 — 앱 EC2에서 AgentGate가 실제로 요청을 받고 있어야 값이 채워짐

로컬 개발 중에는 기존처럼 `docker compose up -d`의 로컬 Grafana(`localhost:3000`)를 그대로 쓰면 된다 — 이건 그대로 유지.

## n8n

n8n은 AgentGate를 호출하는 "에이전트" 역할을 한다 — 실제 AI 에이전트가 없어도 이 워크플로우로 전체 흐름(요청 → 평가 → 분기)을 시연할 수 있다.

**주의**: n8n도 같은 리소스 프로그램 계정이라면 Grafana처럼 권한 제한(예: Credential 저장, Webhook 활성화 불가)이 있을 수 있다 — 아래 절차 진행 전에 워크플로우 생성/HTTP Request 노드 실행이 자유로운지 먼저 확인할 것. 제한이 있으면 알려주면 EC2에 n8n 자체를 셀프호스팅하는 방향으로 바꾼다.

1. n8n 인스턴스가 AgentGate에 네트워크로 닿아야 함:
   - AgentGate를 AWS에 배포했다면 ALB DNS 사용
   - 아직 로컬만 돌리는 중이면 [ngrok](https://ngrok.com/) 등으로 `localhost:8080`을 임시로 외부에 노출(`ngrok http 8080`)해서 그 URL 사용
2. n8n 콘솔 → **Workflows → Import from File** → `infra/n8n/agentgate-action-gate-workflow.json` 업로드
3. "Action Params" 노드를 열어 `baseUrl`(위에서 정한 URL), `apiKey`(`POST /api/v1/agents`로 발급받은 키), `agentId`를 실제 값으로 교체
4. 수동 실행(Manual Trigger)으로 테스트 — PII 라벨이 붙어 있어 정책상 `APPROVAL_REQUIRED`로 분기되는 걸 기본 시나리오로 확인 가능
5. 노드 파라미터는 n8n 버전에 따라 UI가 조금 다를 수 있음 — import 후 HTTP Request 노드의 URL/헤더/바디가 제대로 매핑됐는지 한 번 확인할 것

## 참고

- 두 워크플로우/대시보드 모두 계정별 세부 UI 차이가 있을 수 있어 import 후 가벼운 수정이 필요할 수 있다.
- n8n 워크플로우는 "Switch by Status" 노드에서 `ALLOWED`/`APPROVAL_REQUIRED`/`BLOCKED` 세 갈래로 나뉜다 — 각 분기 끝에 실제 액션(Gmail 발송, Slack 알림 등) 노드를 이어 붙이면 완전한 자동화가 된다.
