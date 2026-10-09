import { act, render, screen, waitFor } from "@testing-library/react";
import { AuthProvider, useAuth } from "./AuthContext";

function Probe() {
  const { me, meLoading, hasRole, login } = useAuth();
  return (
    <div>
      <span data-testid="loading">{String(meLoading)}</span>
      <span data-testid="roles">{me?.roles.join(",") ?? "none"}</span>
      <span data-testid="can-admin">{String(hasRole("ADMIN"))}</span>
      <button onClick={() => login({ username: "jane", password: "pw" })}>login</button>
    </div>
  );
}

const ME = { id: 2, username: "jane", displayName: "Jane", roles: ["EDITOR"], enabled: true, createdAt: "", updatedAt: "" };

describe("AuthProvider", () => {
  it("fetches roles on login", async () => {
    const fetchImpl = vi.fn(async (url: RequestInfo | URL) =>
      String(url) === "/api/v1/me" ? Response.json(ME) : new Response("{}", { status: 404 }),
    );
    render(
      <AuthProvider fetchImpl={fetchImpl as unknown as typeof fetch}>
        <Probe />
      </AuthProvider>,
    );

    expect(screen.getByTestId("roles")).toHaveTextContent("none");
    await act(() => screen.getByRole("button", { name: "login" }).click());

    await waitFor(() => expect(screen.getByTestId("roles")).toHaveTextContent("EDITOR"));
    expect(screen.getByTestId("can-admin")).toHaveTextContent("false");
  });

  it("re-fetches roles for credentials restored from storage, and logs out if they're no longer valid", async () => {
    sessionStorage.setItem("agentgate.credentials", JSON.stringify({ username: "jane", password: "pw" }));
    const fetchImpl = vi.fn(async () => new Response("{}", { status: 401 }));
    render(
      <AuthProvider fetchImpl={fetchImpl as unknown as typeof fetch}>
        <Probe />
      </AuthProvider>,
    );

    expect(screen.getByTestId("loading")).toHaveTextContent("true");
    await waitFor(() => expect(screen.getByTestId("loading")).toHaveTextContent("false"));
    expect(screen.getByTestId("roles")).toHaveTextContent("none");
  });
});
