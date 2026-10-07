import type { Edge } from "@xyflow/react";
import { useState } from "react";
import type { DslNode } from "../api/types";
import { CommitInput } from "./CommitInput";
import { expectedLabels, idProblem } from "./model";

const splitList = (text: string) =>
  text
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);

function parseCases(text: string): Record<string, string> {
  return Object.fromEntries(
    text
      .split("\n")
      .map((line) => line.split("="))
      .filter((parts) => parts.length >= 2 && parts[0].trim())
      .map(([label, ...value]) => [label.trim(), value.join("=").trim()]),
  );
}

interface NodeInspectorProps {
  node: DslNode;
  nodes: DslNode[];
  error?: string;
  onChange: (node: DslNode) => void;
  onRename: (to: string) => void;
  onDelete: () => void;
}

export function NodeInspector({ node, nodes, error, onChange, onRename, onDelete }: NodeInspectorProps) {
  const [idError, setIdError] = useState<string | null>(null);
  const config = node.config ?? {};
  const set = (key: string, value: unknown) => {
    const next = { ...config };
    if (value === undefined || value === "") delete next[key];
    else next[key] = value;
    onChange({ ...node, config: next });
  };
  const str = (key: string) => (config[key] as string | undefined) ?? "";
  const list = (key: string) => ((config[key] as string[] | undefined) ?? []).join(", ");
  const isLlm = ["LLM", "AGENT", "ROUTER", "REVIEWER"].includes(node.type);

  return (
    <div className="inspector-body">
      <div className="row">
        <h3>{node.type}</h3>
        <button className="danger" onClick={onDelete}>
          삭제
        </button>
      </div>
      {error && <p className="error">{error}</p>}
      <div className="field">
        <label>
          id
          <CommitInput
            value={node.id}
            onCommit={(id) => {
              const problem = idProblem(id, node.id, nodes);
              setIdError(problem);
              if (!problem) onRename(id);
            }}
          />
        </label>
        {idError && <span className="error">{idError}</span>}
        <span className="hint">프롬프트에서 {`{${node.id}}`}로 이 노드의 출력을 참조합니다.</span>
      </div>
      <label>
        표시 이름
        <input value={node.label ?? ""} onChange={(e) => onChange({ ...node, label: e.target.value || null })} />
      </label>

      {isLlm && (
        <>
          <label>
            시스템 프롬프트
            <textarea rows={3} value={str("system")} onChange={(e) => set("system", e.target.value)} />
          </label>
          <div className="field">
            <label>
              프롬프트
              <textarea rows={5} value={str("prompt")} onChange={(e) => set("prompt", e.target.value)} />
            </label>
            <span className="hint">{"{task}"}는 입력, {"{노드id}"}는 앞선 노드의 출력입니다.</span>
          </div>
          <label>
            모델 (비우면 기본값)
            <input value={str("model")} onChange={(e) => set("model", e.target.value)} />
          </label>
          <label>
            temperature
            <input
              type="number"
              min={0}
              max={2}
              step={0.1}
              value={(config.temperature as number | undefined) ?? ""}
              onChange={(e) => set("temperature", e.target.value === "" ? undefined : Number(e.target.value))}
            />
          </label>
        </>
      )}
      {node.type === "ROUTER" && (
        <label>
          경로 (쉼표로 구분)
          <CommitInput value={list("routes")} onCommit={(v) => set("routes", splitList(v))} />
        </label>
      )}
      {node.type === "REVIEWER" && (
        <label>
          최대 재시도
          <input
            type="number"
            min={0}
            max={10}
            value={(config.maxRevisions as number | undefined) ?? 1}
            onChange={(e) => set("maxRevisions", Number(e.target.value))}
          />
        </label>
      )}
      {node.type === "CONDITION" && (
        <>
          <label>
            상태 키
            <input value={str("key")} onChange={(e) => set("key", e.target.value)} />
          </label>
          <label>
            분기 (한 줄에 label=값)
            <CommitInput
              multiline
              rows={4}
              value={Object.entries((config.cases as Record<string, string>) ?? {})
                .map(([label, value]) => `${label}=${value}`)
                .join("\n")}
              onCommit={(v) => set("cases", parseCases(v))}
            />
          </label>
          <label>
            기본 분기 label
            <input value={str("default")} onChange={(e) => set("default", e.target.value)} />
          </label>
        </>
      )}
      {node.type === "HTTP_TOOL" && (
        <>
          <label>
            AgentGate 행동 이름
            <input value={str("action")} onChange={(e) => set("action", e.target.value)} />
          </label>
          <label>
            URL
            <input value={str("url")} onChange={(e) => set("url", e.target.value)} />
          </label>
          <label>
            메서드
            <select value={str("method") || "POST"} onChange={(e) => set("method", e.target.value)}>
              {["GET", "POST", "PUT", "PATCH", "DELETE"].map((m) => (
                <option key={m}>{m}</option>
              ))}
            </select>
          </label>
          <label>
            위험 라벨 (쉼표로 구분)
            <CommitInput value={list("labels")} onCommit={(v) => set("labels", splitList(v))} />
          </label>
          <label>
            전송할 상태 키 (쉼표로 구분)
            <CommitInput value={list("payloadKeys")} onCommit={(v) => set("payloadKeys", splitList(v))} />
          </label>
        </>
      )}
      {node.type === "APPROVAL" && (
        <>
          <label>
            안내 메시지
            <input value={str("message")} onChange={(e) => set("message", e.target.value)} />
          </label>
          <p className="hint">Runtime이 아직 APPROVAL 노드를 실행하지 못합니다.</p>
        </>
      )}
    </div>
  );
}

interface EdgeInspectorProps {
  edge: Edge;
  source: DslNode | undefined;
  onLabel: (label: string | null) => void;
  onDelete: () => void;
}

export function EdgeInspector({ edge, source, onLabel, onDelete }: EdgeInspectorProps) {
  const labels = source ? expectedLabels(source) : null;
  return (
    <div className="inspector-body">
      <div className="row">
        <h3>연결</h3>
        <button className="danger" onClick={onDelete}>
          삭제
        </button>
      </div>
      <p className="muted">
        {edge.source} → {edge.target}
      </p>
      {labels ? (
        <label>
          분기 label
          <select value={(edge.label as string) ?? ""} onChange={(e) => onLabel(e.target.value || null)}>
            <option value="">(선택)</option>
            {labels.map((label) => (
              <option key={label}>{label}</option>
            ))}
          </select>
        </label>
      ) : (
        <p className="hint">일반 노드의 연결에는 label이 없습니다. 여러 개 연결하면 병렬로 실행됩니다.</p>
      )}
    </div>
  );
}
