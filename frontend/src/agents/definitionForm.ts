// Editing an agent definition: the form's text fields and their conversion to the API shape.
import type { AgentDefinition, AgentToolDefinition, ToolCallingMode, ToolPermission } from "../api/types";

export interface ToolChoice {
  server: string;
  tool: string;
  permission: ToolPermission;
  // comma separated
  labels: string;
}

export interface DefinitionForm {
  description: string;
  systemPrompt: string;
  model: string;
  temperature: string;
  toolCalling: "" | ToolCallingMode;
  maxSteps: string;
  // JSON text; empty → free-text answers
  outputSchema: string;
  // keyed by toolKey(server, tool), in the order they were chosen
  tools: Record<string, ToolChoice>;
  // agentId → permission for delegating to that agent
  delegates: Record<string, ToolPermission>;
}

export const toolKey = (server: string, tool: string) => `${server}/${tool}`;

export function emptyForm(): DefinitionForm {
  return {
    description: "",
    systemPrompt: "",
    model: "",
    temperature: "",
    toolCalling: "",
    maxSteps: "8",
    outputSchema: "",
    tools: {},
    delegates: {},
  };
}

export function toForm(definition: AgentDefinition | null | undefined): DefinitionForm {
  if (!definition) return emptyForm();
  const tools: Record<string, ToolChoice> = {};
  for (const t of definition.tools) {
    tools[toolKey(t.server, t.tool)] = {
      server: t.server,
      tool: t.tool,
      permission: t.permission,
      labels: (t.labels ?? []).join(", "),
    };
  }
  return {
    description: definition.description ?? "",
    systemPrompt: definition.systemPrompt,
    model: definition.model ?? "",
    temperature: definition.temperature == null ? "" : String(definition.temperature),
    toolCalling: definition.toolCalling ?? "",
    maxSteps: definition.maxSteps == null ? "8" : String(definition.maxSteps),
    outputSchema: definition.outputSchema ? JSON.stringify(definition.outputSchema, null, 2) : "",
    tools,
    delegates: Object.fromEntries((definition.delegates ?? []).map((d) => [d.agentId, d.permission])),
  };
}

const blankToNull = (s: string) => (s.trim() === "" ? null : s.trim());

/** The definition to save, or the first problem with the form. */
export function fromForm(form: DefinitionForm): { definition: AgentDefinition } | { error: string } {
  if (!form.systemPrompt.trim()) return { error: "시스템 프롬프트를 입력하세요." };

  let temperature: number | null = null;
  if (form.temperature.trim() !== "") {
    temperature = Number(form.temperature);
    if (Number.isNaN(temperature) || temperature < 0 || temperature > 2) {
      return { error: "temperature는 0 ~ 2 사이여야 합니다." };
    }
  }
  const maxSteps = Number(form.maxSteps);
  if (!Number.isInteger(maxSteps) || maxSteps < 1 || maxSteps > 50) {
    return { error: "최대 단계는 1 ~ 50 사이의 정수여야 합니다." };
  }
  let outputSchema: Record<string, unknown> | null = null;
  if (form.outputSchema.trim() !== "") {
    try {
      const parsed: unknown = JSON.parse(form.outputSchema);
      if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
        return { error: "출력 스키마는 JSON 객체여야 합니다." };
      }
      outputSchema = parsed as Record<string, unknown>;
    } catch {
      return { error: "출력 스키마가 올바른 JSON이 아닙니다." };
    }
  }
  const tools: AgentToolDefinition[] = Object.values(form.tools).map((t) => ({
    server: t.server,
    tool: t.tool,
    permission: t.permission,
    labels: t.labels
      .split(",")
      .map((l) => l.trim())
      .filter(Boolean),
  }));
  return {
    definition: {
      description: blankToNull(form.description),
      systemPrompt: form.systemPrompt,
      model: blankToNull(form.model),
      temperature,
      toolCalling: form.toolCalling || null,
      maxSteps,
      outputSchema,
      tools,
      delegates: Object.entries(form.delegates).map(([agentId, permission]) => ({ agentId, permission })),
    },
  };
}

/** Permission a newly chosen tool starts with: approval unless the server says it only reads. */
export function defaultPermission(annotations: Record<string, unknown> | null | undefined): ToolPermission {
  return annotations?.readOnlyHint === true ? "AUTO" : "APPROVAL";
}

export const PERMISSION_LABELS: Record<ToolPermission, string> = {
  AUTO: "자동 (정책에 따름)",
  APPROVAL: "항상 승인",
  BLOCKED: "차단",
};
