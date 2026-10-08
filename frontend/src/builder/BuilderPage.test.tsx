import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { AuthProvider } from "../auth/AuthContext";
import { BuilderPage } from "./BuilderPage";

const DSL = {
  workflowId: "echo",
  version: 1,
  nodes: [
    { id: "start", type: "START", position: { x: 0, y: 0 } },
    { id: "answer", type: "LLM", position: { x: 240, y: 0 }, config: { prompt: "{task}" } },
    { id: "end", type: "END", position: { x: 480, y: 0 } },
  ],
  edges: [
    { source: "start", target: "answer" },
    { source: "answer", target: "end" },
  ],
};

type Handler = (url: string, init?: RequestInit) => Response;

function renderBuilder(path: string, handler: Handler) {
  sessionStorage.setItem("agentgate.credentials", JSON.stringify({ username: "admin", password: "pw" }));
  const fetchImpl = vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => handler(String(url), init));
  render(
    <AuthProvider fetchImpl={fetchImpl as unknown as typeof fetch}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/workflows/new" element={<BuilderPage />} />
          <Route path="/workflows/:workflowId/edit" element={<BuilderPage />} />
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  );
  return fetchImpl;
}

const sentBodies = (fetchImpl: ReturnType<typeof vi.fn>) =>
  fetchImpl.mock.calls
    .filter(([, init]) => (init as RequestInit | undefined)?.method === "POST")
    .map(([target, init]) => ({ url: String(target), body: JSON.parse((init as RequestInit).body as string) }));

describe("BuilderPage", () => {
  it("loads a workflow and saves a new version with node positions", async () => {
    const fetchImpl = renderBuilder("/workflows/echo/edit", (_url, init) =>
      init?.method === "POST"
        ? Response.json({ workflowId: "echo", version: 2, dsl: DSL })
        : Response.json({ workflowId: "echo", name: "Echo", latestVersion: 1, dsl: DSL }),
    );

    expect(await screen.findByText("answer")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "새 버전 저장" }));

    expect(await screen.findByText("v2 저장됨")).toBeInTheDocument();
    const [post] = sentBodies(fetchImpl);
    expect(post.url).toBe("/api/v1/workflows/echo/versions");
    expect(post.body.dsl.name).toBe("Echo");
    expect(post.body.dsl.nodes[1]).toMatchObject({ id: "answer", position: { x: 240, y: 0 } });
    expect(post.body.dsl.edges).toEqual(DSL.edges);
  });

  it("adds nodes from the palette and edits them in the inspector", async () => {
    const fetchImpl = renderBuilder("/workflows/new", () => Response.json({}));

    fireEvent.click(await screen.findByRole("button", { name: "HTTP Tool" }));
    const inspector = screen.getByText("HTTP_TOOL", { selector: "h3" }).closest(".inspector-body") as HTMLElement;
    fireEvent.change(within(inspector).getByLabelText("URL"), { target: { value: "https://hook/report" } });
    fireEvent.change(screen.getByLabelText("워크플로 id"), { target: { value: "hooky" } });
    fireEvent.click(screen.getByRole("button", { name: "만들기" }));

    await waitFor(() => expect(sentBodies(fetchImpl)).toHaveLength(1));
    const [post] = sentBodies(fetchImpl);
    expect(post.url).toBe("/api/v1/workflows");
    expect(post.body.workflowId).toBe("hooky");
    expect(post.body.dsl.nodes.map((n: { id: string }) => n.id)).toEqual(["start", "end", "http_tool_1"]);
    expect(post.body.dsl.nodes[2].config.url).toBe("https://hook/report");
  });

  it("lets a tool node continue when its call is denied", async () => {
    const fetchImpl = renderBuilder("/workflows/new", () => Response.json({}));

    fireEvent.click(await screen.findByRole("button", { name: "MCP Tool" }));
    const toggle = screen.getByRole("checkbox", { name: "거절·차단돼도 다음 단계로 계속" });
    expect(toggle).not.toBeChecked();
    fireEvent.click(toggle);
    fireEvent.change(screen.getByLabelText("워크플로 id"), { target: { value: "lenient" } });
    fireEvent.click(screen.getByRole("button", { name: "만들기" }));

    await waitFor(() => expect(sentBodies(fetchImpl)).toHaveLength(1));
    const tool = sentBodies(fetchImpl)[0].body.dsl.nodes.find((n: { type: string }) => n.type === "MCP_TOOL");
    expect(tool.config.onDenied).toBe("CONTINUE");
  });

  it("rejects an invalid workflow id before calling the API", async () => {
    const fetchImpl = renderBuilder("/workflows/new", () => Response.json({}));

    fireEvent.change(await screen.findByLabelText("워크플로 id"), { target: { value: "Bad Id" } });
    fireEvent.click(screen.getByRole("button", { name: "만들기" }));

    expect(await screen.findByText(/소문자, 숫자/)).toBeInTheDocument();
    expect(sentBodies(fetchImpl)).toHaveLength(0);
  });

  it("shows validation errors and highlights the nodes", async () => {
    renderBuilder("/workflows/echo/edit", (_url, init) =>
      init?.method === "POST"
        ? Response.json(
            {
              status: 422,
              code: "INVALID_WORKFLOW",
              message: "Workflow DSL is invalid",
              errors: [{ path: "config.prompt", message: "Unknown variable '{ghost}'", nodeId: "answer" }],
            },
            { status: 422 },
          )
        : Response.json({ workflowId: "echo", name: "Echo", latestVersion: 1, dsl: DSL }),
    );

    await screen.findByText("answer");
    fireEvent.click(screen.getByRole("button", { name: "새 버전 저장" }));

    expect(await screen.findByText("검증 오류 1건")).toBeInTheDocument();
    expect(screen.getByText(/Unknown variable/)).toBeInTheDocument();
    expect(document.querySelector(".dsl-node.has-error")).toHaveTextContent("answer");
  });
});
