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

## AgentGate 지표

`/actuator/prometheus`에 HTTP·JVM 지표와 함께 아래 지표가 나온다. 대시보드(`infra/grafana/agentgate-dashboard.json`)의 "거버넌스 · Agent" 영역이 이 지표를 쓴다. 이미 import한 대시보드는 다시 import하면 패널이 추가된다.

| 지표 | 종류 | 태그 | 내용 |
|---|---|---|---|
| `agentgate_governance_decisions_total` | counter | `status`, `basis`, `risk_level` | 행동 평가 결과 (허용 / 승인 필요 / 차단과 판정 근거) |
| `agentgate_approvals_pending` | gauge | | 지금 결정을 기다리는 승인 요청 수 |
| `agentgate_approval_wait_seconds` | timer | `outcome` | 승인 요청이 결정(승인 / 거절 / 만료)되기까지 걸린 시간 |
| `agentgate_execution_duration_seconds` | timer | `workflow`, `status` | 끝난 실행(완료 / 중단 / 실패)과 소요 시간 |
| `agentgate_llm_latency_seconds` | timer | `model`, `agent` | Agent의 LLM 호출 응답 시간 |
| `agentgate_llm_tokens_total` | counter | `model`, `agent`, `direction` | LLM 입력 / 출력 토큰 (모델 서버가 알려줄 때) |
| `agentgate_tool_duration_seconds` | timer | `tool`, `status`, `agent` | Agent의 Tool 실행 시간과 결과 (`agent/<id>`는 위임) |

- LLM·Tool 지표는 Runtime이 보내는 실행 이벤트에서 계산하므로 AgentGate 한 곳만 수집하면 된다.
- 태그에는 상태·워크플로·Agent·모델·Tool 이름처럼 개수가 정해진 값만 쓴다. 실행 ID 같은 값은 넣지 않는다.

알림 예시 (Prometheus / Grafana alert):

- 승인 적체: `agentgate_approvals_pending > 10`
- 차단 급증: `sum(rate(agentgate_governance_decisions_total{status="BLOCKED"}[15m])) > 0.5`
- 실행 실패: `sum(increase(agentgate_execution_duration_seconds_count{status="FAILED"}[1h])) > 0`
- LLM 지연: `sum(rate(agentgate_llm_latency_seconds_sum[5m])) / sum(rate(agentgate_llm_latency_seconds_count[5m])) > 30`

## Agent trace (OpenTelemetry)

Runtime은 `OTEL_EXPORTER_OTLP_ENDPOINT`(또는 `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`)가 설정되면 Agent 실행을 OTLP/HTTP로 내보낸다. 표준 OpenTelemetry 환경변수(`OTEL_EXPORTER_OTLP_HEADERS`, `OTEL_SERVICE_NAME` 등)를 그대로 따르므로 Langfuse, Jaeger, Grafana Tempo, OpenTelemetry Collector 어디로든 보낼 수 있다.

| Span | 내용 |
|---|---|
| `agentgate.execution` / `agentgate.execution.resume` | 실행 한 번(또는 승인 후 재개). 실행 ID를 `session.id` / `langfuse.session.id`로 넣어 같은 실행의 trace를 한 세션으로 묶는다. 결과(`agentgate.execution.outcome`), 재개 시 승인 결정 |
| `chat <model>` | Agent의 LLM 호출. GenAI 시맨틱 컨벤션(`gen_ai.request.model`, `gen_ai.response.model`, `gen_ai.usage.*`). Langfuse에서는 generation으로 보인다 |
| `governance <action>` | AgentGate 판정: 행동, 판정, 위험도, 근거, 승인 ID |
| `tool <name>` | Tool 실행과 결과. MCP 클라이언트가 만드는 `MCP send ...` span이 그 아래에 붙는다 |

- 프롬프트, 응답, Tool 인자·결과는 `OTEL_CAPTURE_CONTENT=true`일 때만 span에 넣는다(최대 4000자). 개인정보가 오갈 수 있으니 수집 서버의 접근 권한과 보존 기간을 확인한 뒤 켠다.
- Langfuse: `OTEL_EXPORTER_OTLP_ENDPOINT=https://<langfuse 주소>/api/public/otel`, `OTEL_EXPORTER_OTLP_HEADERS=Authorization=Basic <base64(public_key:secret_key)>`
- Jaeger (OTLP 수신 켠 all-in-one): `OTEL_EXPORTER_OTLP_ENDPOINT=http://<jaeger>:4318`
