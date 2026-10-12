import { useCallback, useState } from "react";
import { Link } from "react-router-dom";
import type { AgentMemory, ToolPermission } from "../api/types";
import { useAuth, useClient } from "../auth/AuthContext";
import { useAsync } from "../useAsync";
import { PERMISSION_LABELS, type MemoryForm } from "./definitionForm";

interface MemorySettingsProps {
  memory: MemoryForm;
  onChange: (memory: MemoryForm) => void;
}

/** What the agent may do with its long-term memory: part of the definition, saved with it. */
export function MemorySettings({ memory, onChange }: MemorySettingsProps) {
  const patch = (change: Partial<MemoryForm>) => onChange({ ...memory, ...change });
  return (
    <section className="card stack">
      <h3>장기 기억</h3>
      <p className="hint">
        실행이 끝나도 남는 Agent의 기억입니다. 검색은 <span className="mono">memory_search</span>(행동{" "}
        <span className="mono">MEMORY:SEARCH</span>), 저장은 <span className="mono">memory_save</span>(행동{" "}
        <span className="mono">MEMORY:SAVE</span>) Tool로 하며 다른 Tool처럼 권한·정책·승인을 거칩니다. 정책이 없으면
        기본 허용입니다.
      </p>
      <div className="grid-2">
        <label>
          기억 검색
          <PermissionSelect label="기억 검색 권한" value={memory.read} onChange={(read) => patch({ read })} />
        </label>
        <label>
          기억 저장
          <PermissionSelect label="기억 저장 권한" value={memory.write} onChange={(write) => patch({ write })} />
        </label>
        <label>
          자동 회상 (실행 시작 시 불러올 기억 수, 0이면 끔)
          <input
            type="number"
            min={0}
            max={10}
            value={memory.recall}
            onChange={(e) => patch({ recall: e.target.value })}
          />
        </label>
      </div>
      <span className="hint">자동 회상은 작업 내용으로 관련 기억을 찾아 시스템 프롬프트에 넣습니다. 검색 권한이 '자동'이어야 합니다.</span>
    </section>
  );
}

function PermissionSelect({
  label,
  value,
  onChange,
}: {
  label: string;
  value: ToolPermission;
  onChange: (value: ToolPermission) => void;
}) {
  return (
    <select
      aria-label={label}
      className={`permission-${value.toLowerCase()}`}
      value={value}
      onChange={(e) => onChange(e.target.value as ToolPermission)}
    >
      {(Object.keys(PERMISSION_LABELS) as ToolPermission[]).map((p) => (
        <option key={p} value={p}>
          {p === "AUTO" ? "자동 (기본 허용)" : p === "BLOCKED" ? "사용 안 함" : PERMISSION_LABELS[p]}
        </option>
      ))}
    </select>
  );
}

const MATCH_TEXT: Record<string, string> = { SEMANTIC: "의미", KEYWORD: "키워드" };

/** What the agent remembers: browse and search (any role), add and forget (ADMIN). */
export function MemoryBrowser({ agentId }: { agentId: string }) {
  const client = useClient();
  const { hasRole } = useAuth();
  const canChange = hasRole("ADMIN");
  const [query, setQuery] = useState("");
  const [searched, setSearched] = useState("");
  const memories = useAsync(useCallback(() => client.listAgentMemories(agentId, searched), [client, agentId, searched]));
  const [draft, setDraft] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function change(action: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await action();
      memories.reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  const add = () =>
    change(async () => {
      const result = await client.addAgentMemory(agentId, draft.trim());
      setDraft("");
      if (result.duplicate) setNotice(`이미 있는 기억입니다 (#${result.memory.id}). 새로 추가하지 않았습니다.`);
      else if (result.similar.length > 0)
        setNotice(`비슷한 기억이 있습니다: ${result.similar.map((m) => `#${m.id}`).join(", ")}. 필요 없으면 삭제하세요.`);
    });
  const forget = (memory: AgentMemory) => change(() => client.deleteAgentMemory(agentId, memory.id));

  return (
    <section className="card stack">
      <h3>기억 내용</h3>
      <form
        className="row"
        onSubmit={(e) => {
          e.preventDefault();
          setSearched(query);
        }}
      >
        <input
          aria-label="기억 검색어"
          placeholder="검색어 (비우면 최신순)"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <button type="submit" className="secondary">
          검색
        </button>
      </form>
      {canChange && (
        <div className="row">
          <input
            aria-label="새 기억"
            placeholder="Agent가 기억할 내용 (예: VIP 고객 문의는 당일 회신)"
            maxLength={4000}
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
          />
          <button disabled={busy || !draft.trim()} onClick={add}>
            추가
          </button>
        </div>
      )}
      {error && <p className="error">{error}</p>}
      {notice && <p className="muted">{notice}</p>}
      {memories.error && <p className="error">{memories.error.message}</p>}
      {memories.data?.length === 0 && (
        <p className="muted">{searched ? "검색 결과가 없습니다." : "저장된 기억이 없습니다."}</p>
      )}
      <ul className="memories">
        {memories.data?.map((memory) => (
          <li key={memory.id} className="memory">
            <p>
              <span className="muted mono">#{memory.id}</span> {memory.content}
            </p>
            <div className="row muted">
              <span>
                {memory.source === "USER" ? `${memory.createdBy ?? "?"} 추가` : "Agent 저장"}
                {memory.executionId && (
                  <>
                    {" · "}
                    <Link to={`/executions/${memory.executionId}`}>실행 {memory.executionId.slice(0, 8)}</Link>
                  </>
                )}
                {" · "}
                {new Date(memory.createdAt).toLocaleString()}
                {memory.matchedBy && ` · ${MATCH_TEXT[memory.matchedBy]} 일치`}
              </span>
              <span className="spacer" />
              {canChange && (
                <button className="secondary" disabled={busy} onClick={() => forget(memory)}>
                  삭제
                </button>
              )}
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
