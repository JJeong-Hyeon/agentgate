# Grafana Cloud + n8n 연동

둘 다 클라우드 계정(홈페이지 로그인) 기준 가이드. 실제 계정 연결(URL/API 키 입력)은 각자 콘솔에서 직접 진행 — API 키를 코드/문서에 커밋하지 않는다.

## Grafana Cloud

1. Grafana Cloud 콘솔 → **Connections → Prometheus**에서 Remote Write 엔드포인트 URL, username(인스턴스 ID), API 키 확인
2. 로컬 `prometheus.yml` 하단의 주석 처리된 `remote_write` 블록을 풀고 위 값으로 채움 (이 파일은 git에 커밋되어 있으니, 채운 뒤에는 **커밋하지 말고 로컬에서만 사용**하거나 `git update-index --skip-worktree prometheus.yml`로 로컬 변경을 추적 제외할 것)
3. `docker compose restart prometheus`로 재기동 — 몇 분 내로 Grafana Cloud에 메트릭이 올라옴
4. Grafana Cloud → **Dashboards → New → Import** → `infra/grafana/agentgate-dashboard.json` 업로드 → 데이터소스로 Grafana Cloud의 Prometheus(보통 `grafanacloud-<stack>-prom`) 선택
5. 요청량/URI별 트래픽/5xx 에러율/JVM 힙/CPU/업타임 패널이 뜨는지 확인 — AgentGate가 로컬에서 돌고 있어야 값이 채워짐

## n8n

n8n은 AgentGate를 호출하는 "에이전트" 역할을 한다 — 실제 AI 에이전트가 없어도 이 워크플로우로 전체 흐름(요청 → 평가 → 분기)을 시연할 수 있다.

1. n8n 인스턴스가 AgentGate에 네트워크로 닿아야 함:
   - AgentGate를 AWS에 배포했다면(`docs/DEPLOYMENT.md`) ALB DNS를 사용
   - 아직 로컬만 돌리는 중이면 [ngrok](https://ngrok.com/) 등으로 `localhost:8080`을 임시로 외부에 노출(`ngrok http 8080`)해서 그 URL 사용
2. n8n 콘솔 → **Workflows → Import from File** → `infra/n8n/agentgate-action-gate-workflow.json` 업로드
3. "Action Params" 노드를 열어 `baseUrl`(위에서 정한 URL), `apiKey`(`POST /api/v1/agents`로 발급받은 키), `agentId`를 실제 값으로 교체
4. 수동 실행(Manual Trigger)으로 테스트 — PII 라벨이 붙어 있어 정책상 `APPROVAL_REQUIRED`로 분기되는 걸 기본 시나리오로 확인 가능
5. 노드 파라미터는 n8n 버전에 따라 UI가 조금 다를 수 있음 — import 후 HTTP Request 노드의 URL/헤더/바디가 제대로 매핑됐는지 한 번 확인할 것

## 참고

- 두 워크플로우/대시보드 모두 계정별 세부 UI 차이가 있을 수 있어 import 후 가벼운 수정이 필요할 수 있다.
- n8n 워크플로우는 "Switch by Status" 노드에서 `ALLOWED`/`APPROVAL_REQUIRED`/`BLOCKED` 세 갈래로 나뉜다 — 각 분기 끝에 실제 액션(Gmail 발송, Slack 알림 등) 노드를 이어 붙이면 완전한 자동화가 된다.
