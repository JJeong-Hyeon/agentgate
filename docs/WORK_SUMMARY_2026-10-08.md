# AgentGate 작업 정리 (2026-10-08)

> 피드백용 정리 문서입니다. 커밋하지 않은 로컬 파일입니다.

- 범위: PR #81 ~ #119 (PR 20개, 이슈 #80 ~ #118)
- 상태: #119(P2 문서)만 merge 대기 중이고, 나머지는 모두 일반 merge로 반영했습니다. main CI 4개 job(test, runtime, frontend, e2e)이 모두 통과한 상태입니다.

---

## 1. 한눈에 보기

| 구분 | 내용 | PR |
|---|---|---|
| P0 점검 | 로컬 실환경(Ollama, MCP, AgentGate)에서 MVP 전체 흐름 검증, 버그 3건 발견 | — |
| **P1 Agent Definition** | 재사용 가능한 Agent 정의, Tool 권한, tool-calling Agent, Agent 화면, Studio trace | #81 ~ #101 |
| P0 버그 수정 | 중복 Agent 409, API Key 재발급, 거절·차단된 Tool은 실행 중단(`STOPPED`) | #103 ~ #107 |
| **P2 Tool Registry** | MCP 서버 레지스트리(암호화된 인증정보), Runtime 연동, Tool 위험도, Tools 화면 | #109 ~ #119 |

**이전과 달라진 점**
- 전에는 Agent가 프롬프트를 한 번 보내고 답을 받는 LLM 단계였습니다.
- 이제는 **LLM이 스스로 Tool을 고르고 인자를 만듭니다. 호출할 때마다 그 Agent 이름으로 권한 → 정책 → 위험도 판정을 거쳐** 실행되거나, 승인 대기에 들어가거나, 차단됩니다. 이 모든 과정을 화면에서 정의·실행·추적합니다.

---

## 2. P0: 실환경 MVP 검증

로컬 Docker(Postgres, Redis, Ollama qwen2.5:3b)와 데모 MCP 서버로 전체 흐름을 확인했습니다. 대표 워크플로는 `START → AGENT → MCP_TOOL(save_note) → MCP_TOOL(list_notes) → END`입니다.

| 확인 항목 | 결과 |
|---|---|
| 저장(Runtime 검증) → 실행 → LLM → MCP 호출 직전 AgentGate 검사 | ✅ |
| HIGH → 승인 대기 → 승인 → 재개 → MCP 실제 호출 | ✅ |
| 거절 시 Tool 미실행, LOW 정책은 즉시 실행 | ✅ |
| SSE, 노드별 기록, Audit | ✅ |

**발견한 문제 (모두 이후에 수정)**
1. 같은 agentId로 등록하면 500 → #103
2. API Key 재발급 수단 없음 → #105
3. Tool이 거절돼도 다음 노드로 진행하고 `COMPLETED`로 끝남 → #107
4. 정책이 없는 행동의 기본 위험도가 HIGH라 모든 Tool이 승인 필요 → P2 Tool 위험도 레지스트리(#113)로 해결

---

## 3. P1: Agent Definition

### 3.1 PR 목록

| PR | 내용 |
|---|---|
| #81 | Agent별 버전 관리되는 정의(모델, 시스템 프롬프트, Tool, 권한, maxSteps, 출력 스키마) |
| #83 | Tool 권한을 행동 평가에 적용, 판정 근거(`basis`) 기록, Runtime 토큰으로 Agent 대리 평가 |
| #85 | Runtime이 MCP `tools/list`로 Tool 목록·입력 스키마 조회 (`/api/v1/tools`) |
| #87 | (버그) 기존 Agent가 있는 DB에서 스키마 갱신 실패 수정 |
| #89 | **tool-calling Agent 실행 루프** (LangGraph) |
| #91 | 실행 시작 시 Agent 정의 버전 고정·주입·기록(`agentVersions`) |
| #93 | JSON 방식 tool-calling 대체 경로, 출력 JSON Schema 검증 |
| #95 | Agents 화면, Builder에서 Agent 선택 |
| #97 | Execution Studio에 Agent 단계 trace 표시 (호출·판정·결과·토큰) |
| #99 | 문서 |
| #101 | (버그) AWS 배포에서 외부 LLM 서버(DGX Spark 등) 사용 불가 수정 |

### 3.2 핵심 설계

**판정 순서** (Agent 정의 버전이 있는 호출)

```text
Tool 호출 (agentId, agentVersion)
 ↓
권한 ── 정의에 없는 Tool → BLOCKED (TOOL_NOT_GRANTED)
 │     └ BLOCKED 권한     → BLOCKED (TOOL_BLOCKED)
 ↓
정책 → 위험도 ── Agent 위험도 상한 초과 → BLOCKED (AGENT_RISK_CAP)
 ↓
정책상 허용이어도 권한이 APPROVAL → 승인 필요 (TOOL_REQUIRES_APPROVAL)
```

**Agent 실행 루프**

```text
think(LLM) → gate(AgentGate 검사) → approval(필요 시 interrupt) → execute → think …
```

- 거절·차단·실패는 사유를 LLM에 돌려주고, LLM은 계속 진행합니다.
- `maxSteps` 마지막 턴에는 Tool 없이 최종 답변을 요구합니다(무한 루프 방지).
- Tool 스키마는 실행 state에 저장합니다. 그래서 승인 대기 중에 MCP 서버가 내려가도 재개할 수 있습니다.
- 승인 사유에 "어떤 Agent가, 어떤 Tool을, 어떤 인자로" 호출하는지 표시합니다.

**그 밖의 결정 사항**
- Agent 정의는 별도 객체가 아니라 기존 거버넌스 Agent를 확장했습니다. 그래서 Audit에 실제로 행동한 Agent가 남습니다.
- Runtime은 공유 토큰(`X-Runtime-Token`)으로 인증하는 신뢰된 호출자로, 등록된 어느 Agent의 이름으로든 평가를 요청할 수 있습니다.
- 실행할 때 Agent 정의 버전을 고정하고 기록합니다. 나중에 "어떤 정의로 돌았는지" 추적할 수 있습니다.
- 새로 고른 Tool의 기본 권한은 "항상 승인"입니다. 서버가 읽기 전용이라고 알린 Tool만 "자동"으로 시작합니다.

### 3.3 실환경 확인 (qwen2.5:3b)

- LLM이 `save_note(title, content)`를 올바른 인자로 호출했고, note-agent 이름으로 승인을 요청한 뒤 승인 후 실행됐습니다.
- 3B 모델이 불필요한 저장을 반복하려 했지만, 매번 승인 대기로 막혔습니다. 거절 사유가 LLM에 전달됐고 `maxSteps`에서 종료했습니다.
- JSON 방식에 출력 스키마를 함께 지정해 `{"titles":[...],"count":3}`를 받았습니다.
- 토큰 사용량을 수집해 Studio에 표시합니다.
- **3B급 모델은 Tool 호출 품질이 낮습니다. 운영에는 14B 이상의 Tool Calling 지원 모델을 권장합니다.**

---

## 4. P0 버그 수정

| PR | 내용 |
|---|---|
| #103 | 중복 agentId 등록 시 409 `AGENT_ALREADY_EXISTS` (동시 등록 포함) |
| #105 | `POST /api/v1/agents/{id}/api-key` 재발급, 이전 키 즉시 무효, 화면에서 두 단계 확인 |
| #107 | **결정에 따른 동작 변경:** Tool 노드가 거절·차단되면 기본으로 실행 중단(`STOPPED`, 사유 기록). 노드 옵션 `onDenied: CONTINUE`면 계속 진행. APPROVAL 노드 거절도 `STOPPED` |

> 기존 워크플로에서 거절 후에도 다음 단계를 실행해야 한다면, 해당 노드에 "거절·차단돼도 다음 단계로 계속"을 켜야 합니다.

---

## 5. P2: Tool Registry

| PR | 내용 |
|---|---|
| #109 | MCP 서버 레지스트리: 화면/API 등록, 인증 헤더 AES-256-GCM 암호화, 응답에는 헤더 이름만, Runtime 전용 조회 API |
| #111 | Runtime이 등록 서버를 사용: 30초 캐시, 장애 시 마지막 목록 유지, 호출 시점 조회(재시작 없이 반영), 인증 헤더 전달 |
| #113 | Tool 위험도 레지스트리: Tool별 위험도를 정책(`MCP:서버:Tool`)으로 저장, annotations 기반 추천값, 일괄 적용 |
| #115 | (테스트) 간헐 실패하던 프론트 테스트 수정 |
| #117 | Tools 화면 (Tool 위험도 표, MCP 서버 관리), Agent 편집 화면에 정책 위험도 표시 |
| #119 | 문서 (**merge 대기**) |

**설계 판단**
- 화면에서 등록하는 서버는 **Streamable HTTP만** 허용합니다. stdio는 Runtime 호스트에서 명령을 실행하므로, 관리자 계정 하나가 탈취되면 서버 전체가 위험해집니다. 그래서 설정 파일로만 등록하게 했습니다.
- Tool 위험도는 새 저장소를 만들지 않고 기존 정책으로 저장합니다. 판정, 캐시, Audit을 그대로 재사용합니다.
- annotations는 서버가 스스로 보고하는 값이라 신뢰할 수 없습니다. 그래서 추천만 하고, 적용은 관리자가 확인한 뒤에 합니다.
- 이름이 겹치면 설정 파일이 우선하고, 충돌은 화면에 표시합니다.

---

## 6. 품질 / 검증 현황

| 영역 | 테스트 수 (작업 전 → 후) |
|---|---|
| Spring | 127 → 179 |
| Runtime | 105 → 158 |
| Frontend | 26 → 57 |
| E2E 테스트 (pytest 기준) | 8 → 11 |

- 모든 PR에서 CI 4개 job(test, runtime, frontend, e2e)이 통과했습니다.
- 실제 의존성으로 검증한 것: 실제 stdio/HTTP MCP 서버, 인증이 필요한 MCP 서버, 실제 Ollama, 로컬 AgentGate.

---

## 7. 작업 중 문제와 대응

| 문제 | 대응 |
|---|---|
| #81의 NOT NULL 컬럼 때문에 기존 DB의 스키마 갱신 실패 (운영 RDS도 영향) | #87로 수정, 재현 테스트 추가 |
| 배포 설정이 LLM 주소를 Ollama로 고정 → DGX Spark 연결 불가 | #101로 수정 (`.env`의 `LLM_BASE_URL` 사용, Ollama는 선택) |
| **제 실수: 커밋하지 않기로 하신 문서 2개(`DEVELOPMENT_SUMMARY.md`, `후속_보완_방향.md`)가 #109 브랜치에 push됨** | 즉시 브랜치에서 제거 후 강제 push, main에는 반영된 적 없음. **공개 저장소라 PR #109 타임라인의 force-push 기록 링크로는 아직 볼 수 있음**(완전 삭제는 GitHub Support 요청 필요). 이후 파일을 하나씩 지정해 커밋하고, 매번 비공개 파일이 없는지 확인 |
| 이번 변경과 무관한 프론트 테스트 간헐 실패 | #115로 수정 |

---

## 8. 운영 배포 전 필요한 조치

1. **App EC2 `.env`에 `AGENTGATE_SECRET_KEY` 추가** (`openssl rand -base64 32`). 없으면 prod 기동이 실패합니다. 키를 잃어버리면 저장된 MCP 서버 헤더를 다시 입력해야 하니 SSM이나 Secrets Manager에 백업해 두세요.
2. DGX Spark를 쓸 경우 Runtime `.env`에 다음을 설정합니다.
   - `LLM_BASE_URL`, `LLM_MODEL`
   - `LLM_TOOL_CALLING`: vLLM을 `--enable-auto-tool-choice --tool-call-parser hermes`(Qwen 계열)로 띄웠다면 `native`
3. 기존 워크플로 중 Tool 거절 후에도 계속 진행해야 하는 노드가 있으면 `onDenied: CONTINUE`를 설정합니다.
4. Tools 화면에서 Tool 위험도를 지정하거나 추천값을 적용합니다. 지정하지 않으면 모든 Tool이 승인 대기로 갑니다.

---

## 9. 남은 과제 / 다음 단계

| 단계 | 내용 |
|---|---|
| P3 Multi-Agent | Supervisor / Worker / Reviewer, Agent 간 결과 전달, Agent별 권한 분리 (시작 시 구조 제안 예정) |
| P4 Memory | short-term + pgvector long-term |
| P5 Observability | 노드별 LLM 입출력·토큰·시간 전체 trace, OpenTelemetry / Langfuse |
| P6 운영 필수 | 사용자/역할(RBAC, 승인 권한 분리), 승인 알림(Slack/메일)·만료, **Flyway 마이그레이션**(#87 같은 문제의 근본 해결), HTTPS, 설치 패키지 |
| 기타 | SSE 다중 인스턴스(Redis Pub/Sub), Tool 수준 기본 라벨, HTTP Tool의 Registry 편입, Vite가 `::1`에만 바인딩되는 개발 편의 문제 |

---

## 10. 피드백 받고 싶은 부분

- Tool 거절 시 기본 중단(`STOPPED`) 정책이 실제 업무 흐름에 맞는지
- 새 Tool의 기본 권한("항상 승인")과 Tool 위험도 추천 규칙이 너무 보수적이거나 느슨하지 않은지
- 화면 구성: Agents / Tools 화면에서 불편한 점
- P3 이후 우선순위 (Multi-Agent보다 P6 운영 필수 항목을 먼저 할지)
