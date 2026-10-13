# AgentGate 소스코드 기반 1차 기술 리뷰

검토 대상: 사용자가 제공한 `agentgate.zip` (2026-10-13)

## 검토 범위 및 한계
- 정적 코드 검토: Java Spring Boot, Python LangGraph Runtime, React 소스 구조, Docker Compose, GitHub Actions 및 테스트 구성
- 확인된 파일 수: Java 테스트 파일 59개, Python 테스트 파일 20개, frontend/src 파일 55개
- 테스트 실행, 전체 라인별 감사, 배포된 인프라 침투 테스트는 수행하지 않음. 아래 리스크는 코드상 설계 위험 및 확인이 필요한 사항으로 구분함.

## 실제로 구현 확인된 기능
- 정책/위험도 평가: `src/main/java/com/agentgate/agent/service/AgentActionService.java`
- RBAC와 API 접근 통제: `src/main/java/com/agentgate/config/SecurityConfig.java`
- 승인 요청 및 결정: `src/main/java/com/agentgate/approval/service/ApprovalService.java`
- 커밋 이후 실행 재개 통지 및 재시도: `src/main/java/com/agentgate/approval/runtime/RuntimeResumeNotifier.java`
- LangGraph Human-in-the-loop: `runtime/app/nodes/tool.py`, `runtime/app/nodes/approval.py`, `runtime/app/nodes/agent.py`
- MCP/HTTP 실행: `runtime/app/tools/mcp.py`, `runtime/app/tools/http.py`
- 실행 API와 체크포인트: `runtime/app/executions.py`, `runtime/app/graph/checkpointer.py`
- Agent 정의, 버전, 위임 및 메모리 구현 확인
- 프론트엔드 앱 및 Workflow Builder, CI/E2E 테스트 구성 존재

## 우선순위별 개선 제안

### P0: 승인한 요청과 실제 실행 payload의 결속
근거: `runtime/app/tools/base.py`의 `authorize()`는 기본적으로 action/target/labels/execution ID를 평가함. `runtime/app/nodes/agent.py`의 `gate()`는 LLM의 `call["args"]`를 pending state에 저장하지만 승인 서비스로 전달되는 값은 설명용 reason뿐임. `execute()`는 pending call args로 도구 실행. `runtime/app/nodes/tool.py`에서는 authorize 이후 `build_payload(state)`로 실행 시점에 payload를 다시 생성함.

**위험:** 승인자가 본 내용과 실제 전송되는 HTTP 요청 본문, 수신자 등 중요 인자가 같은지 서버 측에서 보장하기 어려움. 이는 취약점 악용이 입증되었다는 의미가 아니라 승인 무결성의 설계상 공백임.

**조치:** 정규화된 tool arguments + action + target + executionId + agent/version 해시를 승인 요청에 함께 저장하고, 실행 직전 해시 재검증. 단발성 실행 ID/만료/멱등 키 도입. 승인 콘솔에는 민감한 값 마스킹 후 중요 인자 표시. 승인 중 state 변화 시 이전 승인 무효화.

### P0: 런타임 토큰 없을 때 API 노출
근거: `runtime/app/executions.py`의 `require_token_if_configured()`는 `runtime_token`이 빈 문자열이면 인증 검사를 건너뜀. `runtime/app/main.py`에서 Runtime API 라우터 공통 의존성으로 사용. `runtime/app/config.py`의 토큰 기본값은 빈 문자열임.

**조치:** 개발 모드가 아닌 환경에서는 시작 자체를 거부하고, 네트워크에서 Runtime 8000 포트는 내부로만 노출. `/runtime/executions`의 생성, 조회, 삭제, 복구까지 토큰 필수 조건을 확인하는 보안 회귀 테스트 추가.

### P0: Compose 개발용 설정의 운영 유입 방지
근거: `compose.yaml`의 PostgreSQL 암호 `secret`, `RUNTIME_TOKEN=${RUNTIME_TOKEN:-dev-runtime-token}`, Runtime 8000 호스트 포트 바인딩, 버전 고정되지 않은 Redis/Grafana 등의 이미지.

**조치:** Compose 파일을 개발용으로 명시하고 production 전용 구성 분리. 실서비스에서는 필수 환경변수 검증, Secret 관리, 호스트 포트 내부 제한, 컨테이너 이미지 버전/다이제스트 고정. 파일상의 dev 기본값을 실제 운영에 사용한다면 심각한 위험이지만 현재 운영 적용 여부는 미확인.

### P1: Agent 버전 없는 평가 경로
근거: `AgentActionService.evaluate()`에서 `request.agentVersion() == null`이면 `findGrant()`를 실행하지 않고 도구 권한 체크를 통과함. 반면 `runtime/app/nodes/agent.py`는 agent_version을 전달함.

**조치:** 위임된 런타임 요청의 버전 요구와 직접 API 호출의 권한 모델을 문서화. '정의된 Tool만 실행' 정책이 시스템 전체 보장 사항이라면 버전 생략 시 거부하거나 활성 버전으로 평가. Java 권한 회귀 테스트 추가.

### P1: 실행 중 장애와 멱등성
근거: `RuntimeResumeNotifier`는 커밋 후 통지와 재시도 구현. `RuntimeClient.resume()`는 Runtime HTTP 409, 404를 전달 완료로 판단. Runtime은 `_running` 집합으로 중복 resume을 차단하고 실행 중간 장애 복구 시 중복 도구 실행 위험을 인지하고 있음.

**조치:** 외부 도구 호출에 idempotency key/실행 원장 추가. Runtime 재시작, 응답 유실, 승인 이벤트 중복, 도구 성공 후 체크포인트 저장 전 장애를 주입해 확인. 409/404가 모두 안전하게 완료 처리 가능한 상황인지 계약 분리 검증.

### P1: MCP 등록 정보 캐시와 권한 폐기
근거: `runtime/app/tools/registry.py`에서 새 등록 정보 조회 실패 시 마지막 캐시를 유지함.

**조치:** 접근 금지 또는 키 폐기처럼 보안 민감한 변경을 캐시의 실패 시 유지 정책과 분리. Tool 실행 직전 Governance 판단이 실패하면 종료되는 성질은 유지하되, 폐기된 MCP endpoint/credential의 캐시 생존 기간을 명시.

## 유지 및 강화할 강점
1. 모든 Tool에서 authorize 실패 시 실행하지 않는 fail-closed 모델
2. LangGraph interrupt/Command resume을 통한 Human-in-the-loop 구현
3. Spring 트랜잭션 커밋 이후 승인 결정을 Runtime에 전송하고 주기적 재시도
4. Spring Security의 역할 기반 엔드포인트 구분과 액션 API Key 검증
5. Java/Python/Frontend/E2E/취약점 스캔을 분리한 CI 파이프라인

## Claude Code에 전달할 다음 작업
1. **P0 승인 무결성부터**: 현재 API/DB/React/Runtime 계약을 추적하고 `payloadHash`, `executionId`, `agentVersion`, `idempotencyKey`를 포함하는 하위 호환 가능한 설계안을 작성해 변경.
2. Runtime 인증에 대해 `RUNTIME_TOKEN` 부재/오류/정상 케이스, 외부 노출 설정을 테스트. 개발모드 제외 Fail-fast 적용.
3. 배포 환경 분리 및 기본 비밀번호 제거. 실제 배포 시크릿 노출 여부 점검.
4. `agentVersion` 유무별 Tool 허용/차단 정책을 명시하고 테스트.
5. 승인 재개·장애 재시도·외부 실행의 중복 발생에 대한 통합 테스트 추가.

## 추가 검증 필요
- `./gradlew test`, `cd runtime && pytest`, `cd frontend && npm test`, Playwright E2E 실제 실행
- 운영 AWS Security Group, TLS, Secret, 실제 MCP 권한과 호출 경로 확인
- UI에서 승인 대상의 행동·중요 인자 표시를 E2E로 확인

**종합:** 실제 실행·승인·감사·관리까지 구현된 프로젝트다. 새 기능을 대량 추가하기보다 승인 대상과 실행 대상의 무결성, Runtime 인증, 멱등성, 운영환경 보안을 우선 강화하는 것이 효과적이다.
