import { useCallback, useState, type FormEvent } from "react";
import type { McpServer } from "../api/types";
import { useAuth, useClient } from "../auth/AuthContext";
import { useAsync } from "../useAsync";

const NAME_PATTERN = /^[A-Za-z0-9_-]{1,64}$/;
const HEADER_PATTERN = /^[A-Za-z0-9-]{1,100}$/;

interface HeaderRow {
  name: string;
  value: string;
}

/** Header rows to the API shape (rows without a value are left out), or the first problem. */
export function headersFrom(rows: HeaderRow[]): { headers: Record<string, string> } | { error: string } {
  const headers: Record<string, string> = {};
  for (const row of rows) {
    const name = row.name.trim();
    if (!row.value) continue;
    if (!HEADER_PATTERN.test(name)) return { error: `헤더 이름 '${name}'이(가) 올바르지 않습니다.` };
    if (name in headers) return { error: `헤더 '${name}'이(가) 두 번 있습니다.` };
    headers[name] = row.value;
  }
  return { headers };
}

/** MCP servers registered in AgentGate: added and changed here without touching the runtime. */
export function McpServersSection({ onChanged }: { onChanged: () => void }) {
  const client = useClient();
  const { hasRole } = useAuth();
  const canEdit = hasRole("ADMIN");
  const servers = useAsync(useCallback(() => client.listMcpServers(), [client]));
  const [editing, setEditing] = useState<McpServer | "new" | null>(null);

  function done() {
    setEditing(null);
    servers.reload();
    onChanged();
  }

  return (
    <section className="card stack">
      <div className="row">
        <h2>MCP 서버</h2>
        <span className="spacer" />
        {canEdit && <button onClick={() => setEditing("new")}>서버 등록</button>}
      </div>
      <p className="hint">
        여기서 등록한 Streamable HTTP 서버는 Runtime 재시작 없이 30초 안에 반영됩니다. 인증 헤더는 암호화해 저장하고 다시
        보여주지 않습니다. stdio 서버는 Runtime 설정 파일(<span className="mono">MCP_CONFIG_PATH</span>)로만 등록할 수
        있습니다.
      </p>
      {servers.error && <p className="error">{servers.error.message}</p>}
      {canEdit && editing === "new" && <ServerForm onDone={done} onCancel={() => setEditing(null)} />}
      {servers.data?.length === 0 && editing !== "new" && <p className="muted">등록된 서버가 없습니다.</p>}
      <ul className="server-list">
        {servers.data?.map((server) =>
          canEdit && editing !== "new" && editing?.id === server.id ? (
            <li key={server.id}>
              <ServerForm server={server} onDone={done} onCancel={() => setEditing(null)} />
            </li>
          ) : (
            <li key={server.id} className="row">
              <span>
                <strong className="mono">{server.name}</strong>{" "}
                {!server.enabled && <span className="badge risk-blocked">사용 안 함</span>}
                <div className="muted mono">{server.url}</div>
                {server.description && <div className="muted">{server.description}</div>}
                {server.headerNames.length > 0 && (
                  <div className="muted">인증 헤더: {server.headerNames.join(", ")}</div>
                )}
              </span>
              <span className="spacer" />
              {canEdit && (
                <button className="secondary" onClick={() => setEditing(server)}>
                  수정
                </button>
              )}
            </li>
          ),
        )}
      </ul>
    </section>
  );
}

function ServerForm({ server, onDone, onCancel }: { server?: McpServer; onDone: () => void; onCancel: () => void }) {
  const client = useClient();
  const [name, setName] = useState(server?.name ?? "");
  const [url, setUrl] = useState(server?.url ?? "");
  const [description, setDescription] = useState(server?.description ?? "");
  const [enabled, setEnabled] = useState(server?.enabled ?? true);
  // On edit, headers are only sent when the admin chooses to replace them.
  const [replaceHeaders, setReplaceHeaders] = useState(!server);
  const [rows, setRows] = useState<HeaderRow[]>([{ name: "Authorization", value: "" }]);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!NAME_PATTERN.test(name)) {
      setError("이름은 영문, 숫자, _, -만 쓸 수 있습니다 (최대 64자).");
      return;
    }
    if (!/^https?:\/\/\S+$/.test(url)) {
      setError("URL은 http:// 또는 https://로 시작해야 합니다.");
      return;
    }
    const parsed = replaceHeaders ? headersFrom(rows) : { headers: undefined };
    if ("error" in parsed) {
      setError(parsed.error);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const input = { name, url, description: description || null, enabled, headers: parsed.headers };
      if (server) await client.updateMcpServer(server.id, input);
      else await client.createMcpServer(input);
      onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!server) return;
    setBusy(true);
    try {
      await client.deleteMcpServer(server.id);
      onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  }

  return (
    <form className="stack server-form" onSubmit={save} aria-label={server ? `${server.name} 수정` : "서버 등록"}>
      <div className="grid-2">
        <label>
          이름
          <input value={name} disabled={!!server} onChange={(e) => setName(e.target.value)} placeholder="crm" />
        </label>
        <label>
          URL
          <input value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://crm.internal/mcp" />
        </label>
      </div>
      {server && <span className="hint">이름은 Agent 정의와 정책이 참조하므로 바꿀 수 없습니다.</span>}
      <label>
        설명
        <input value={description} onChange={(e) => setDescription(e.target.value)} />
      </label>
      <label className="inline">
        <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
        사용
      </label>
      {server && (
        <label className="inline">
          <input type="checkbox" checked={replaceHeaders} onChange={(e) => setReplaceHeaders(e.target.checked)} />
          인증 헤더 교체 (현재: {server.headerNames.length > 0 ? server.headerNames.join(", ") : "없음"})
        </label>
      )}
      {replaceHeaders && (
        <div className="stack">
          {rows.map((row, i) => (
            <div key={i} className="grid-2">
              <input
                aria-label={`헤더 ${i + 1} 이름`}
                value={row.name}
                placeholder="Authorization"
                onChange={(e) => setRows(rows.map((r, j) => (j === i ? { ...r, name: e.target.value } : r)))}
              />
              <input
                aria-label={`헤더 ${i + 1} 값`}
                type="password"
                autoComplete="off"
                value={row.value}
                placeholder="Bearer …"
                onChange={(e) => setRows(rows.map((r, j) => (j === i ? { ...r, value: e.target.value } : r)))}
              />
            </div>
          ))}
          <div>
            <button type="button" className="secondary" onClick={() => setRows([...rows, { name: "", value: "" }])}>
              헤더 추가
            </button>
          </div>
          <span className="hint">
            값이 빈 헤더는 저장하지 않습니다.{server && " 모두 비워 두고 저장하면 저장된 헤더를 지웁니다."}
          </span>
        </div>
      )}
      {error && <p className="error">{error}</p>}
      <div className="row">
        <button type="submit" disabled={busy}>
          {server ? "저장" : "등록"}
        </button>
        <button type="button" className="secondary" onClick={onCancel} disabled={busy}>
          취소
        </button>
        <span className="spacer" />
        {server &&
          (confirmDelete ? (
            <>
              <span className="error">이 서버의 Tool을 쓰는 Agent는 실행할 수 없게 됩니다.</span>
              <button type="button" className="danger" onClick={remove} disabled={busy}>
                삭제 확인
              </button>
            </>
          ) : (
            <button type="button" className="danger" onClick={() => setConfirmDelete(true)}>
              삭제
            </button>
          ))}
      </div>
    </form>
  );
}
