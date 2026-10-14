import { fireEvent, render, screen } from "@testing-library/react";
import { App } from "./App";
import { AuthProvider } from "./auth/AuthContext";
import { withMe } from "./testUtils/me";

function renderApp(fetchImpl: typeof fetch) {
  sessionStorage.clear();
  return render(
    <AuthProvider fetchImpl={fetchImpl}>
      <App />
    </AuthProvider>,
  );
}

describe("App", () => {
  // The router keeps the browser location between tests.
  beforeEach(() => window.history.pushState({}, "", "/"));

  it("offers SSO, and hides the password form when the server turns it off", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        Response.json({ localLoginEnabled: false, ssoEnabled: true, ssoLabel: "Keycloak" }),
      ),
    );
    try {
      renderApp(vi.fn(async () => new Response("{}", { status: 401 })));

      const link = await screen.findByRole("link", { name: "Keycloak로 로그인" });
      expect(link).toHaveAttribute("href", "/oauth2/authorization/oidc");
      expect(screen.queryByLabelText("비밀번호")).not.toBeInTheDocument();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("shows an error for wrong credentials", async () => {
    renderApp(vi.fn(async () => new Response("{}", { status: 401 })));

    fireEvent.change(await screen.findByLabelText("비밀번호"), { target: { value: "nope" } });
    fireEvent.click(screen.getByRole("button", { name: "로그인" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("올바르지 않습니다");
  });

  it("tells the user when sign-in is blocked after repeated failures", async () => {
    renderApp(
      vi.fn(async () => Response.json({ status: 429, code: "TOO_MANY_LOGIN_ATTEMPTS" }, { status: 429 })),
    );

    fireEvent.change(await screen.findByLabelText("비밀번호"), { target: { value: "pw" } });
    fireEvent.click(screen.getByRole("button", { name: "로그인" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("잠시 막혔습니다");
  });

  it("goes back to the sign-in page when the session ends", async () => {
    let expired = false;
    const fetchImpl = vi.fn(
      withMe(async (url) => {
        if (url === "/api/v1/workflows") {
          if (expired) return new Response("{}", { status: 401 });
          return Response.json([{ id: 1, workflowId: "research", name: "Research", latestVersion: 2 }]);
        }
        return new Response("{}", { status: 404 });
      }),
    );
    sessionStorage.setItem("agentgate.credentials", "signed-in");
    render(
      <AuthProvider fetchImpl={fetchImpl}>
        <App />
      </AuthProvider>,
    );
    fireEvent.click(await screen.findByRole("link", { name: "Workflows" }));
    expect(await screen.findByText("research · v2")).toBeInTheDocument();

    // The server forgot the session (timeout, restart): the next request is a 401.
    expired = true;
    fireEvent.click(screen.getByRole("link", { name: "Executions" }));
    fireEvent.click(screen.getByRole("link", { name: "Workflows" }));

    expect(await screen.findByLabelText("비밀번호")).toBeInTheDocument();
  });

  it("logs in to the dashboard and moves to workflows", async () => {
    const fetchImpl = vi.fn(
      withMe(async (url) => {
        if (url === "/api/v1/workflows")
          return Response.json([{ id: 1, workflowId: "research", name: "Research", latestVersion: 2 }]);
        return new Response("{}", { status: 404 });
      }),
    );
    renderApp(fetchImpl);

    fireEvent.change(await screen.findByLabelText("비밀번호"), { target: { value: "pw" } });
    fireEvent.click(screen.getByRole("button", { name: "로그인" }));

    expect(await screen.findByRole("heading", { name: "대시보드" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("link", { name: "Workflows" }));

    expect(await screen.findByText("Research")).toBeInTheDocument();
    expect(screen.getByText("research · v2")).toBeInTheDocument();
  });
});
