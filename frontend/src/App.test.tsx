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
  it("shows an error for wrong credentials", async () => {
    renderApp(vi.fn(async () => new Response("{}", { status: 401 })));

    fireEvent.change(screen.getByLabelText("비밀번호"), { target: { value: "nope" } });
    fireEvent.click(screen.getByRole("button", { name: "로그인" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("올바르지 않습니다");
  });

  it("logs in and lists workflows", async () => {
    const fetchImpl = vi.fn(
      withMe(async (url) => {
        if (url === "/api/v1/workflows")
          return Response.json([{ id: 1, workflowId: "research", name: "Research", latestVersion: 2 }]);
        return new Response("{}", { status: 404 });
      }),
    );
    renderApp(fetchImpl);

    fireEvent.change(screen.getByLabelText("비밀번호"), { target: { value: "pw" } });
    fireEvent.click(screen.getByRole("button", { name: "로그인" }));

    expect(await screen.findByText("Research")).toBeInTheDocument();
    expect(screen.getByText("research · v2")).toBeInTheDocument();
  });
});
