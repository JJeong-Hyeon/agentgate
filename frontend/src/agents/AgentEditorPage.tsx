import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import type { McpServerTools, McpToolInfo, ToolPermission } from "../api/types";
import { useClient } from "../auth/AuthContext";
import { useAsync } from "../useAsync";
import { ApiKeySection } from "./ApiKeySection";
import {
  defaultPermission,
  fromForm,
  PERMISSION_LABELS,
  toForm,
  toolKey,
  type DefinitionForm,
  type ToolChoice,
} from "./definitionForm";

export function AgentEditorPage() {
  const id = Number(useParams().id);
  const client = useClient();
  const agent = useAsync(useCallback(() => client.getAgent(id), [client, id]));
  const versions = useAsync(useCallback(() => client.listAgentDefinitionVersions(id), [client, id]));
  const [refresh, setRefresh] = useState(false);
  const catalog = useAsync(useCallback(() => client.listTools(refresh), [client, refresh]));
  const [form, setForm] = useState<DefinitionForm | null>(null);
  const [loadedVersion, setLoadedVersion] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    client
      .getAgentDefinition(id)
      .then((latest) => {
        if (cancelled) return;
        setForm(toForm(latest?.definition));
        setLoadedVersion(latest?.version ?? null);
      })
      .catch((e: unknown) => !cancelled && setError(e instanceof Error ? e.message : String(e)));
    return () => {
      cancelled = true;
    };
  }, [client, id]);

  async function loadVersion(version: number) {
    setError(null);
    try {
      const found = await client.getAgentDefinitionVersion(id, version);
      setForm(toForm(found.definition));
      setLoadedVersion(version);
      setSaved(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  async function save() {
    if (!form) return;
    const result = fromForm(form);
    if ("error" in result) {
      setError(result.error);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const created = await client.saveAgentDefinition(id, result.definition);
      setSaved(created.version);
      setLoadedVersion(created.version);
      versions.reload();
      agent.reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  const update = (patch: Partial<DefinitionForm>) => {
    setForm((f) => (f ? { ...f, ...patch } : f));
    setSaved(null);
  };

  return (
    <div className="agent-editor">
      <section className="card">
        <div className="row">
          <h2>
            <Link to="/agents">Agents</Link> / {agent.data?.name ?? "…"}{" "}
            <span className="muted mono">{agent.data?.agentId}</span>
          </h2>
          <span className="spacer" />
          {loadedVersion && <span className="muted">v{loadedVersion} 기준 편집 중</span>}
          <button onClick={save} disabled={busy || !form}>
            새 버전 저장
          </button>
        </div>
        {agent.error && <p className="error">{agent.error.message}</p>}
        {error && <p className="error">{error}</p>}
        {saved && <p className="ok">v{saved} 저장됨</p>}
        {agent.data?.maxRiskLevel && (
          <p className="hint">
            위험도 상한 {agent.data.maxRiskLevel}: 이보다 위험한 행동은 권한과 관계없이 차단됩니다.
          </p>
        )}
      </section>

      {form && (
        <div className="agent-editor-body">
          <section className="card stack">
            <h3>정의</h3>
            <label>
              설명
              <input value={form.description} onChange={(e) => update({ description: e.target.value })} />
            </label>
            <label>
              시스템 프롬프트
              <textarea
                rows={6}
                value={form.systemPrompt}
                onChange={(e) => update({ systemPrompt: e.target.value })}
              />
            </label>
            <div className="grid-2">
              <label>
                모델 (비우면 Runtime 기본값)
                <input value={form.model} onChange={(e) => update({ model: e.target.value })} />
              </label>
              <label>
                temperature
                <input
                  type="number"
                  min={0}
                  max={2}
                  step={0.1}
                  value={form.temperature}
                  onChange={(e) => update({ temperature: e.target.value })}
                />
              </label>
              <label>
                Tool 호출 방식
                <select
                  value={form.toolCalling}
                  onChange={(e) => update({ toolCalling: e.target.value as DefinitionForm["toolCalling"] })}
                >
                  <option value="">Runtime 기본값</option>
                  <option value="NATIVE">Function calling (NATIVE)</option>
                  <option value="JSON">JSON 응답 (JSON)</option>
                </select>
              </label>
              <label>
                최대 단계 (LLM 턴)
                <input
                  type="number"
                  min={1}
                  max={50}
                  value={form.maxSteps}
                  onChange={(e) => update({ maxSteps: e.target.value })}
                />
              </label>
            </div>
            <div className="field">
              <label>
                출력 JSON Schema (선택)
                <textarea
                  className="mono"
                  rows={6}
                  value={form.outputSchema}
                  placeholder='{"type": "object", "properties": {...}, "required": [...]}'
                  onChange={(e) => update({ outputSchema: e.target.value })}
                />
              </label>
              <span className="hint">지정하면 최종 답변을 이 스키마에 맞는 JSON으로 받습니다.</span>
            </div>
          </section>

          <section className="card stack">
            <div className="row">
              <h3>Tool과 권한</h3>
              <span className="spacer" />
              <button className="secondary" onClick={() => (refresh ? catalog.reload() : setRefresh(true))}>
                목록 새로고침
              </button>
            </div>
            <p className="hint">
              선택한 Tool만 Agent에게 보입니다. 호출할 때마다 AgentGate가 <em>권한 → 정책 → 위험도</em> 순으로
              판정합니다. 선택하지 않은 Tool은 호출할 수 없습니다.
            </p>
            <ToolPicker
              catalog={catalog.data}
              catalogError={catalog.error?.message}
              loading={catalog.loading}
              chosen={form.tools}
              onChange={(tools) => update({ tools })}
            />
          </section>

          {agent.data && <ApiKeySection agent={agent.data} onReissued={agent.reload} />}

          <section className="card stack">
            <h3>버전</h3>
            {versions.data?.length === 0 && <p className="muted">저장된 버전이 없습니다.</p>}
            <ul className="versions">
              {versions.data?.map((v) => (
                <li key={v.version} className="row">
                  <span>
                    v{v.version} <span className="muted">{new Date(v.createdAt).toLocaleString()}</span>
                  </span>
                  <span className="spacer" />
                  <button
                    className="secondary"
                    disabled={v.version === loadedVersion}
                    onClick={() => loadVersion(v.version)}
                  >
                    불러오기
                  </button>
                </li>
              ))}
            </ul>
            <p className="hint">불러온 버전을 저장하면 새 버전으로 추가됩니다. 실행 기록에는 사용한 버전이 남습니다.</p>
          </section>
        </div>
      )}
    </div>
  );
}

interface ToolPickerProps {
  catalog: McpServerTools[] | null;
  catalogError?: string;
  loading: boolean;
  chosen: Record<string, ToolChoice>;
  onChange: (tools: Record<string, ToolChoice>) => void;
}

function ToolPicker({ catalog, catalogError, loading, chosen, onChange }: ToolPickerProps) {
  const known = new Set((catalog ?? []).flatMap((s) => s.tools.map((t) => toolKey(s.server, t.name))));
  const missing = Object.entries(chosen).filter(([key]) => !known.has(key));

  const toggle = (server: string, tool: McpToolInfo) => {
    const key = toolKey(server, tool.name);
    const next = { ...chosen };
    if (next[key]) delete next[key];
    else next[key] = { server, tool: tool.name, permission: defaultPermission(tool.annotations), labels: "" };
    onChange(next);
  };
  const patch = (key: string, change: Partial<ToolChoice>) => onChange({ ...chosen, [key]: { ...chosen[key], ...change } });
  const remove = (key: string) => {
    const next = { ...chosen };
    delete next[key];
    onChange(next);
  };

  return (
    <div className="tool-picker">
      {catalogError && <p className="error">Tool 목록을 불러오지 못했습니다: {catalogError}</p>}
      {loading && !catalog && <p className="muted">Tool 목록을 불러오는 중…</p>}
      {catalog?.length === 0 && <p className="muted">Runtime에 설정된 MCP 서버가 없습니다.</p>}
      {catalog?.map((server) => (
        <div key={server.server} className="tool-server">
          <h4>
            {server.server} <span className="muted">{server.transport}</span>
          </h4>
          {server.error && <p className="error">연결 실패: {server.error}</p>}
          <table className="tool-table">
            <tbody>
              {server.tools.map((tool) => {
                const key = toolKey(server.server, tool.name);
                const choice = chosen[key];
                return (
                  <tr key={key} className={choice ? "chosen" : undefined}>
                    <td>
                      <label className="inline">
                        <input type="checkbox" checked={!!choice} onChange={() => toggle(server.server, tool)} />
                        <span className="mono">{tool.name}</span>
                      </label>
                      {tool.description && <div className="muted">{tool.description}</div>}
                    </td>
                    <td>{choice && <PermissionFields toolName={tool.name} choice={choice} onChange={(c) => patch(key, c)} />}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      ))}
      {missing.length > 0 && (
        <div className="tool-server">
          <h4>현재 목록에 없는 Tool</h4>
          <p className="hint">서버가 연결되지 않았거나 Tool이 사라졌습니다. 이 상태로는 실행을 시작할 수 없습니다.</p>
          <table className="tool-table">
            <tbody>
              {missing.map(([key, choice]) => (
                <tr key={key} className="chosen">
                  <td>
                    <span className="mono">{key}</span>{" "}
                    <button className="secondary" onClick={() => remove(key)}>
                      빼기
                    </button>
                  </td>
                  <td>
                    <PermissionFields toolName={key} choice={choice} onChange={(c) => patch(key, c)} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function PermissionFields({
  toolName,
  choice,
  onChange,
}: {
  toolName: string;
  choice: ToolChoice;
  onChange: (change: Partial<ToolChoice>) => void;
}) {
  return (
    <div className="permission-fields">
      <select
        aria-label={`${toolName} 권한`}
        className={`permission-${choice.permission.toLowerCase()}`}
        value={choice.permission}
        onChange={(e) => onChange({ permission: e.target.value as ToolPermission })}
      >
        {(Object.keys(PERMISSION_LABELS) as ToolPermission[]).map((p) => (
          <option key={p} value={p}>
            {PERMISSION_LABELS[p]}
          </option>
        ))}
      </select>
      <input
        aria-label={`${toolName} 라벨`}
        placeholder="라벨 (예: PII, EXTERNAL)"
        value={choice.labels}
        onChange={(e) => onChange({ labels: e.target.value })}
      />
    </div>
  );
}
