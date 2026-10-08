import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { AuthProvider } from "../auth/AuthContext";
import { BuilderPage } from "../builder/BuilderPage";
import { AgentEditorPage } from "./AgentEditorPage";
import { AgentsPage } from "./AgentsPage";

type Handler = (url: string, init?: RequestInit) => Response;

function renderAt(path: string, handler: Handler) {
  sessionStorage.setItem("agentgate.credentials", JSON.stringify({ username: "admin", password: "pw" }));
  const fetchImpl = vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => handler(String(url), init));
  render(
    <AuthProvider fetchImpl={fetchImpl as unknown as typeof fetch}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/agents" element={<AgentsPage />} />
          <Route path="/agents/:id" element={<AgentEditorPage />} />
          <Route path="/workflows/new" element={<BuilderPage />} />
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  );
  return fetchImpl;
}

const sent = (fetchImpl: ReturnType<typeof vi.fn>, method: string) =>
  fetchImpl.mock.calls
    .filter(([, init]) => (init as RequestInit | undefined)?.method === method)
    .map(([target, init]) => ({ url: String(target), body: JSON.parse((init as RequestInit).body as string) }));

const AGENT = {
  id: 7,
  agentId: "note-agent",
  name: "Note Agent",
  description: "Keeps notes",
  maxRiskLevel: null,
  latestDefinitionVersion: 2,
  apiKeyIssuedAt: "2026-10-08T00:00:00Z",
  createdAt: "2026-10-08T00:00:00Z",
};
const DEFINITION = {
  agentId: "note-agent",
  version: 2,
  createdAt: "2026-10-08T00:00:00Z",
  definition: {
    systemPrompt: "Be careful.",
    tools: [{ server: "notes", tool: "save_note", permission: "APPROVAL", labels: [] }],
    maxSteps: 8,
  },
};
const CATALOG = [
  {
    server: "notes",
    transport: "url",
    error: null,
    tools: [
      { name: "save_note", title: null, description: "Save a note.", inputSchema: {}, annotations: null },
      {
        name: "list_notes",
        title: null,
        description: "List notes.",
        inputSchema: {},
        annotations: { readOnlyHint: true },
      },
    ],
  },
  { server: "files", transport: "stdio", error: "connection refused", tools: [] },
];

describe("AgentsPage", () => {
  it("registers an agent and shows its API key once", async () => {
    const fetchImpl = renderAt("/agents", (_url, init) => {
      if (init?.method === "POST") {
        return Response.json({ id: 8, agentId: "mail-agent", name: "Mail", apiKey: "secret-key", createdAt: "" });
      }
      return Response.json([AGENT]);
    });

    expect(await screen.findByText("Note Agent")).toBeInTheDocument();
    expect(screen.getByText(/note-agent · v2/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Agent id"), { target: { value: "mail-agent" } });
    fireEvent.change(screen.getByLabelText("이름"), { target: { value: "Mail" } });
    fireEvent.click(screen.getByRole("button", { name: "등록" }));

    expect(await screen.findByText("secret-key")).toBeInTheDocument();
    expect(sent(fetchImpl, "POST")[0].body).toEqual({ agentId: "mail-agent", name: "Mail" });
  });

  it("rejects an invalid agent id before calling the API", async () => {
    const fetchImpl = renderAt("/agents", () => Response.json([]));

    fireEvent.change(await screen.findByLabelText("Agent id"), { target: { value: "Bad Id" } });
    fireEvent.click(screen.getByRole("button", { name: "등록" }));

    expect(await screen.findByText(/소문자, 숫자, -만/)).toBeInTheDocument();
    expect(sent(fetchImpl, "POST")).toHaveLength(0);
  });
});

describe("AgentEditorPage", () => {
  function editorHandler(url: string, init?: RequestInit): Response {
    if (url === "/api/v1/agents/7/api-key" && init?.method === "POST") {
      return Response.json({ id: 7, agentId: "note-agent", apiKey: "new-secret", issuedAt: "2026-10-08T01:00:00Z" });
    }
    if (init?.method === "PUT") return Response.json({ agentId: "note-agent", version: 3, createdAt: "" });
    if (url === "/api/v1/agents/7") return Response.json(AGENT);
    if (url === "/api/v1/agents/7/definition") return Response.json(DEFINITION);
    if (url === "/api/v1/agents/7/definition/versions") {
      return Response.json([
        { agentId: "note-agent", version: 2, createdAt: "2026-10-08T00:00:00Z" },
        { agentId: "note-agent", version: 1, createdAt: "2026-10-07T00:00:00Z" },
      ]);
    }
    if (url === "/api/v1/agents/7/definition/versions/1") {
      return Response.json({ ...DEFINITION, version: 1, definition: { ...DEFINITION.definition, systemPrompt: "Old." } });
    }
    if (url.startsWith("/api/v1/tools")) return Response.json(CATALOG);
    return new Response(null, { status: 404 });
  }

  it("edits the definition and tool permissions, then saves a new version", async () => {
    const fetchImpl = renderAt("/agents/7", editorHandler);

    expect(await screen.findByDisplayValue("Be careful.")).toBeInTheDocument();
    expect(await screen.findByText("연결 실패: connection refused")).toBeInTheDocument();
    // A read-only tool starts as automatic.
    fireEvent.click(screen.getByRole("checkbox", { name: "list_notes" }));
    expect(screen.getByLabelText("list_notes 권한")).toHaveValue("AUTO");
    fireEvent.change(screen.getByLabelText("save_note 권한"), { target: { value: "BLOCKED" } });
    fireEvent.change(screen.getByLabelText("save_note 라벨"), { target: { value: "PII" } });
    fireEvent.click(screen.getByRole("button", { name: "새 버전 저장" }));

    expect(await screen.findByText("v3 저장됨")).toBeInTheDocument();
    const [put] = sent(fetchImpl, "PUT");
    expect(put.url).toBe("/api/v1/agents/7/definition");
    expect(put.body.systemPrompt).toBe("Be careful.");
    expect(put.body.tools).toEqual([
      { server: "notes", tool: "save_note", permission: "BLOCKED", labels: ["PII"] },
      { server: "notes", tool: "list_notes", permission: "AUTO", labels: [] },
    ]);
  });

  it("loads an earlier version into the form", async () => {
    renderAt("/agents/7", editorHandler);

    await screen.findByDisplayValue("Be careful.");
    const versions = screen.getByText("버전", { selector: "h3" }).closest("section") as HTMLElement;
    fireEvent.click(within(versions).getAllByRole("button", { name: "불러오기" })[1]);

    expect(await screen.findByDisplayValue("Old.")).toBeInTheDocument();
    expect(screen.getByText("v1 기준 편집 중")).toBeInTheDocument();
  });

  it("reissues the API key after a second confirmation and shows it once", async () => {
    const fetchImpl = renderAt("/agents/7", editorHandler);

    fireEvent.click(await screen.findByRole("button", { name: "API Key 재발급" }));
    expect(sent(fetchImpl, "POST")).toHaveLength(0);
    expect(screen.getByText("기존 키는 즉시 사용할 수 없게 됩니다.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "재발급 확인" }));

    expect(await screen.findByText("new-secret")).toBeInTheDocument();
    expect(fetchImpl.mock.calls.some(([u, i]) => String(u) === "/api/v1/agents/7/api-key" && (i as RequestInit)?.method === "POST")).toBe(true);
  });

  it("does not save an invalid form", async () => {
    const fetchImpl = renderAt("/agents/7", editorHandler);

    fireEvent.change(await screen.findByDisplayValue("Be careful."), { target: { value: "" } });
    fireEvent.click(screen.getByRole("button", { name: "새 버전 저장" }));

    expect(await screen.findByText("시스템 프롬프트를 입력하세요.")).toBeInTheDocument();
    expect(sent(fetchImpl, "PUT")).toHaveLength(0);
  });
});

describe("Builder agent node", () => {
  it("runs a registered agent pinned to a version", async () => {
    const fetchImpl = renderAt("/workflows/new", (url, init) => {
      if (init?.method === "POST") return Response.json({ workflowId: "notes", version: 1 });
      if (url === "/api/v1/agents") return Response.json([AGENT, { ...AGENT, id: 9, agentId: "empty", latestDefinitionVersion: 0 }]);
      if (url.startsWith("/api/v1/agents/7/definition/versions/")) return Response.json(DEFINITION);
      return Response.json({});
    });

    fireEvent.click(await screen.findByRole("button", { name: "Agent" }));
    fireEvent.click(screen.getByRole("radio", { name: "등록된 Agent (Tool 사용)" }));
    expect(await screen.findByRole("option", { name: /empty.*정의 없음/ })).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Agent"), { target: { value: "note-agent" } });
    expect(screen.queryByLabelText("시스템 프롬프트")).not.toBeInTheDocument();
    expect(await screen.findByText("notes/save_note")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("정의 버전"), { target: { value: "1" } });
    fireEvent.change(screen.getByLabelText("워크플로 id"), { target: { value: "notes" } });
    fireEvent.click(screen.getByRole("button", { name: "만들기" }));

    await waitFor(() => expect(sent(fetchImpl, "POST")).toHaveLength(1));
    const agentNode = sent(fetchImpl, "POST")[0].body.dsl.nodes.find((n: { type: string }) => n.type === "AGENT");
    expect(agentNode.config).toEqual({ prompt: "{task}", agentId: "note-agent", agentVersion: 1 });
  });
});
