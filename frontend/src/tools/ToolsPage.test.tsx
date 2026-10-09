import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { AuthProvider } from "../auth/AuthContext";
import { withMe } from "../testUtils/me";
import { headersFrom } from "./McpServersSection";
import { ToolsPage } from "./ToolsPage";

const RISKS = [
  {
    server: "crm",
    source: "agentgate",
    transport: "url",
    error: null,
    tools: [
      {
        name: "lookup",
        title: null,
        description: "Look up a customer.",
        action: "MCP:crm:lookup",
        riskLevel: null,
        policyId: null,
        effectiveRiskLevel: "HIGH",
        suggestedRiskLevel: "LOW",
      },
      {
        name: "delete_customer",
        title: null,
        description: null,
        action: "MCP:crm:delete_customer",
        riskLevel: "BLOCKED",
        policyId: 4,
        effectiveRiskLevel: "BLOCKED",
        suggestedRiskLevel: "HIGH",
      },
    ],
  },
  { server: "files", source: "runtime", transport: "stdio", error: "connection refused", tools: [] },
];
const SERVERS = [
  {
    id: 3,
    name: "crm",
    url: "https://crm.internal/mcp",
    description: "CRM",
    enabled: true,
    headerNames: ["Authorization"],
    createdAt: "",
    updatedAt: "",
  },
];

function renderPage() {
  sessionStorage.setItem("agentgate.credentials", JSON.stringify({ username: "admin", password: "pw" }));
  const fetchImpl = vi.fn(
    withMe(async (url, init) => {
      const method = init?.method ?? "GET";
      if (url.startsWith("/api/v1/tool-risks/apply-suggestions")) return Response.json({ applied: 1 });
      if (url.startsWith("/api/v1/tool-risks") && method === "DELETE") return new Response(null, { status: 204 });
      if (url.startsWith("/api/v1/tool-risks") && method === "PUT") return Response.json({});
      if (url.startsWith("/api/v1/tool-risks")) return Response.json(RISKS);
      if (url === "/api/v1/mcp-servers" && method === "POST") return Response.json({ ...SERVERS[0], id: 9 });
      if (url === "/api/v1/mcp-servers/3" && method === "PUT") return Response.json(SERVERS[0]);
      if (url === "/api/v1/mcp-servers/3" && method === "DELETE") return new Response(null, { status: 204 });
      if (url === "/api/v1/mcp-servers") return Response.json(SERVERS);
      return new Response("{}", { status: 404 });
    }),
  );
  render(
    <AuthProvider fetchImpl={fetchImpl as unknown as typeof fetch}>
      <MemoryRouter>
        <ToolsPage />
      </MemoryRouter>
    </AuthProvider>,
  );
  return fetchImpl;
}

const calls = (fetchImpl: ReturnType<typeof vi.fn>, method: string) =>
  fetchImpl.mock.calls
    .filter(([, init]) => (init as RequestInit | undefined)?.method === method)
    .map(([url, init]) => ({
      url: String(url),
      body: (init as RequestInit).body ? JSON.parse((init as RequestInit).body as string) : null,
    }));

describe("ToolsPage", () => {
  it("shows each tool's risk, where its server is registered, and server errors", async () => {
    renderPage();

    expect(await screen.findByText("lookup")).toBeInTheDocument();
    expect(screen.getByLabelText("lookup 위험도")).toHaveValue("");
    expect(screen.getByLabelText("delete_customer 위험도")).toHaveValue("BLOCKED");
    expect(screen.getByText("AgentGate 등록 · url")).toBeInTheDocument();
    expect(screen.getByText("Runtime 설정 파일 · stdio")).toBeInTheDocument();
    expect(screen.getByText("connection refused")).toBeInTheDocument();
  });

  it("sets and clears a tool's risk", async () => {
    const fetchImpl = renderPage();

    fireEvent.change(await screen.findByLabelText("lookup 위험도"), { target: { value: "LOW" } });
    await waitFor(() => expect(calls(fetchImpl, "PUT")).toHaveLength(1));
    expect(calls(fetchImpl, "PUT")[0].body).toEqual({ server: "crm", tool: "lookup", riskLevel: "LOW" });

    fireEvent.change(screen.getByLabelText("delete_customer 위험도"), { target: { value: "" } });
    await waitFor(() => expect(calls(fetchImpl, "DELETE")).toHaveLength(1));
    expect(calls(fetchImpl, "DELETE")[0].url).toBe("/api/v1/tool-risks?server=crm&tool=delete_customer");
  });

  it("applies suggestions only after a second confirmation", async () => {
    const fetchImpl = renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "추천값 일괄 적용" }));
    expect(screen.getByText("위험도가 없는 Tool 1개에 추천값을 적용합니다.")).toBeInTheDocument();
    expect(calls(fetchImpl, "POST")).toHaveLength(0);
    fireEvent.click(screen.getByRole("button", { name: "적용 확인" }));

    expect(await screen.findByText("1개 Tool에 추천 위험도를 적용했습니다.")).toBeInTheDocument();
  });

  it("registers a server with secret headers", async () => {
    const fetchImpl = renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "서버 등록" }));
    const form = screen.getByRole("form", { name: "서버 등록" });
    fireEvent.change(within(form).getByLabelText("이름"), { target: { value: "billing" } });
    fireEvent.change(within(form).getByLabelText("URL"), { target: { value: "https://billing/mcp" } });
    const secret = within(form).getByLabelText("헤더 1 값");
    expect(secret).toHaveAttribute("type", "password");
    fireEvent.change(secret, { target: { value: "Bearer abc" } });
    fireEvent.click(within(form).getByRole("button", { name: "등록" }));

    await waitFor(() => expect(calls(fetchImpl, "POST")).toHaveLength(1));
    expect(calls(fetchImpl, "POST")[0].body).toEqual({
      name: "billing",
      url: "https://billing/mcp",
      description: null,
      enabled: true,
      headers: { Authorization: "Bearer abc" },
    });
  });

  it("edits a server without touching its headers unless asked", async () => {
    const fetchImpl = renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "수정" }));
    const form = screen.getByRole("form", { name: "crm 수정" });
    expect(within(form).getByLabelText("이름")).toBeDisabled();
    expect(within(form).queryByLabelText("헤더 1 값")).not.toBeInTheDocument();
    fireEvent.change(within(form).getByLabelText("URL"), { target: { value: "https://crm2/mcp" } });
    fireEvent.click(within(form).getByRole("button", { name: "저장" }));

    await waitFor(() => expect(calls(fetchImpl, "PUT")).toHaveLength(1));
    const [put] = calls(fetchImpl, "PUT");
    expect(put.url).toBe("/api/v1/mcp-servers/3");
    expect(put.body.url).toBe("https://crm2/mcp");
    expect(put.body).not.toHaveProperty("headers");
  });

  it("deletes a server only after confirming", async () => {
    const fetchImpl = renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "수정" }));
    fireEvent.click(screen.getByRole("button", { name: "삭제" }));
    expect(calls(fetchImpl, "DELETE")).toHaveLength(0);
    fireEvent.click(screen.getByRole("button", { name: "삭제 확인" }));

    await waitFor(() => expect(calls(fetchImpl, "DELETE")).toHaveLength(1));
    expect(calls(fetchImpl, "DELETE")[0].url).toBe("/api/v1/mcp-servers/3");
  });
});

describe("headersFrom", () => {
  it("leaves out rows without a value and rejects bad or repeated names", () => {
    expect(headersFrom([{ name: "Authorization", value: "" }])).toEqual({ headers: {} });
    expect(headersFrom([{ name: "X-Key", value: "1" }])).toEqual({ headers: { "X-Key": "1" } });
    expect(headersFrom([{ name: "Bad Name", value: "1" }])).toHaveProperty("error");
    expect(
      headersFrom([
        { name: "X-Key", value: "1" },
        { name: "X-Key", value: "2" },
      ]),
    ).toHaveProperty("error");
  });
});
