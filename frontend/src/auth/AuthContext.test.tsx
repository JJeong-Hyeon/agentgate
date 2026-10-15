import { act, render, screen, waitFor } from "@testing-library/react";
import { AuthProvider, useAuth } from "./AuthContext";

function Probe() {
  const { me, meLoading, hasRole, login, logout } = useAuth();
  return (
    <div>
      <span data-testid="loading">{String(meLoading)}</span>
      <span data-testid="roles">{me?.roles.join(",") ?? "none"}</span>
      <span data-testid="can-admin">{String(hasRole("ADMIN"))}</span>
      <button onClick={() => login({ username: "jane", password: "pw" })}>login</button>
      <button onClick={logout}>logout</button>
    </div>
  );
}

const ME = { id: 2, username: "jane", displayName: "Jane", roles: ["EDITOR"], enabled: true, createdAt: "", updatedAt: "" };

describe("AuthProvider", () => {
  it("signs in with the server's session and takes the roles from the answer", async () => {
    const fetchImpl = vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
      if (String(url) === "/api/v1/auth/login" && init?.method === "POST") return Response.json(ME);
      return new Response("{}", { status: 401 }); // no session yet
    });
    render(
      <AuthProvider fetchImpl={fetchImpl as unknown as typeof fetch}>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("loading")).toHaveTextContent("false"));
    expect(screen.getByTestId("roles")).toHaveTextContent("none");

    await act(() => screen.getByRole("button", { name: "login" }).click());

    await waitFor(() => expect(screen.getByTestId("roles")).toHaveTextContent("EDITOR"));
    expect(screen.getByTestId("can-admin")).toHaveTextContent("false");
    // The password goes to the login call only; it is not kept anywhere in the browser.
    const login = fetchImpl.mock.calls.find(([url]) => String(url) === "/api/v1/auth/login")!;
    expect(JSON.parse(String(login[1]!.body))).toEqual({ username: "jane", password: "pw" });
    expect(JSON.stringify(sessionStorage)).not.toContain("pw");
  });

  it("finds an existing session when the page loads, and stays signed out without one", async () => {
    const withSession = vi.fn(async () => Response.json(ME));
    const { unmount } = render(
      <AuthProvider fetchImpl={withSession as unknown as typeof fetch}>
        <Probe />
      </AuthProvider>,
    );
    expect(screen.getByTestId("loading")).toHaveTextContent("true");
    await waitFor(() => expect(screen.getByTestId("roles")).toHaveTextContent("EDITOR"));
    unmount();

    const expired = vi.fn(async () => new Response("{}", { status: 401 }));
    render(
      <AuthProvider fetchImpl={expired as unknown as typeof fetch}>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("loading")).toHaveTextContent("false"));
    expect(screen.getByTestId("roles")).toHaveTextContent("none");
  });

  it("follows the identity provider's logout address after an SSO sign-out", async () => {
    const fetchImpl = vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
      if (String(url) === "/api/v1/auth/logout" && init?.method === "POST")
        return Response.json({ logoutUrl: "https://idp.example/logout?id_token_hint=x" });
      return Response.json(ME);
    });
    const assign = vi.fn();
    const original = window.location;
    Object.defineProperty(window, "location", { configurable: true, value: { ...original, assign } });
    try {
      render(
        <AuthProvider fetchImpl={fetchImpl as unknown as typeof fetch}>
          <Probe />
        </AuthProvider>,
      );
      await waitFor(() => expect(screen.getByTestId("loading")).toHaveTextContent("false"));

      act(() => screen.getByText("logout").click());

      await waitFor(() => expect(assign).toHaveBeenCalledWith("https://idp.example/logout?id_token_hint=x"));
    } finally {
      Object.defineProperty(window, "location", { configurable: true, value: original });
    }
  });
});
