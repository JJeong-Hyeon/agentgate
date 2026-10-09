import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { AuthProvider } from "../auth/AuthContext";
import { withMe } from "../testUtils/me";
import { UsersPage } from "./UsersPage";

const ADMIN = {
  id: 1,
  username: "admin",
  displayName: "Admin",
  roles: ["ADMIN"],
  enabled: true,
  createdAt: "",
  updatedAt: "",
};
const VIEWER = {
  id: 2,
  username: "jane",
  displayName: "Jane",
  roles: ["VIEWER"],
  enabled: true,
  createdAt: "",
  updatedAt: "",
};

function renderPage(users = [ADMIN, VIEWER]) {
  sessionStorage.setItem("agentgate.credentials", JSON.stringify({ username: "admin", password: "pw" }));
  const fetchImpl = vi.fn(
    withMe(async (url, init) => {
      const method = init?.method ?? "GET";
      if (url === "/api/v1/users" && method === "POST") return Response.json({ ...VIEWER, id: 9 });
      if (url === "/api/v1/users/2" && method === "PUT") return Response.json({ ...VIEWER, roles: ["EDITOR"] });
      if (url === "/api/v1/users/2" && method === "DELETE") return new Response(null, { status: 204 });
      if (url === "/api/v1/users") return Response.json(users);
      return new Response("{}", { status: 404 });
    }),
  );
  render(
    <AuthProvider fetchImpl={fetchImpl as unknown as typeof fetch}>
      <MemoryRouter>
        <UsersPage />
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

describe("UsersPage", () => {
  it("lists users with their roles", async () => {
    renderPage();

    expect(await screen.findByText("jane")).toBeInTheDocument();
    expect(screen.getByText("VIEWER")).toBeInTheDocument();
    expect(screen.getByText("ADMIN")).toBeInTheDocument();
  });

  it("creates a user", async () => {
    const fetchImpl = renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "사용자 추가" }));
    const form = screen.getByRole("form", { name: "사용자 추가" });
    fireEvent.change(within(form).getByLabelText("사용자 이름"), { target: { value: "bob" } });
    fireEvent.change(within(form).getByLabelText("비밀번호"), { target: { value: "longenough" } });
    fireEvent.click(within(form).getByLabelText("EDITOR"));
    fireEvent.click(within(form).getByRole("button", { name: "추가" }));

    await waitFor(() => expect(calls(fetchImpl, "POST")).toHaveLength(1));
    expect(calls(fetchImpl, "POST")[0].body).toEqual({
      username: "bob",
      displayName: null,
      roles: ["VIEWER", "EDITOR"],
      enabled: true,
      password: "longenough",
    });
  });

  it("rejects a password under 8 characters", async () => {
    renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "사용자 추가" }));
    const form = screen.getByRole("form", { name: "사용자 추가" });
    fireEvent.change(within(form).getByLabelText("사용자 이름"), { target: { value: "bob" } });
    fireEvent.change(within(form).getByLabelText("비밀번호"), { target: { value: "short" } });
    fireEvent.click(within(form).getByRole("button", { name: "추가" }));

    expect(screen.getByText("비밀번호는 8자 이상이어야 합니다.")).toBeInTheDocument();
  });

  it("edits a user's roles", async () => {
    const fetchImpl = renderPage();

    const rows = await screen.findAllByRole("button", { name: "수정" });
    fireEvent.click(rows[1]);
    const form = screen.getByRole("form", { name: "jane 수정" });
    expect(within(form).getByLabelText("사용자 이름")).toBeDisabled();
    fireEvent.click(within(form).getByLabelText("EDITOR"));
    fireEvent.click(within(form).getByRole("button", { name: "저장" }));

    await waitFor(() => expect(calls(fetchImpl, "PUT")).toHaveLength(1));
    expect(calls(fetchImpl, "PUT")[0].body.roles).toEqual(["VIEWER", "EDITOR"]);
    expect(calls(fetchImpl, "PUT")[0].body).not.toHaveProperty("password");
  });

  it("deletes a user only after confirming, but never the signed-in user", async () => {
    const fetchImpl = renderPage();

    const rows = await screen.findAllByRole("button", { name: "수정" });
    fireEvent.click(rows[1]);
    const janeForm = screen.getByRole("form", { name: "jane 수정" });
    fireEvent.click(within(janeForm).getByRole("button", { name: "삭제" }));
    expect(calls(fetchImpl, "DELETE")).toHaveLength(0);
    fireEvent.click(within(janeForm).getByRole("button", { name: "삭제 확인" }));

    await waitFor(() => expect(calls(fetchImpl, "DELETE")).toHaveLength(1));
    expect(calls(fetchImpl, "DELETE")[0].url).toBe("/api/v1/users/2");

    fireEvent.click(rows[0]);
    const adminForm = screen.getByRole("form", { name: "admin 수정" });
    expect(within(adminForm).queryByRole("button", { name: "삭제" })).not.toBeInTheDocument();
    expect(within(adminForm).getByText("자기 자신은 삭제할 수 없습니다.")).toBeInTheDocument();
  });

  it("protects the last active admin from losing ADMIN or being disabled", async () => {
    renderPage();

    fireEvent.click((await screen.findAllByRole("button", { name: "수정" }))[0]);
    const form = screen.getByRole("form", { name: "admin 수정" });
    expect(within(form).getByLabelText("ADMIN")).toBeDisabled();
    expect(within(form).getByLabelText("사용")).toBeDisabled();
    expect(within(form).getByText("마지막 활성 ADMIN은 비활성화하거나 ADMIN 역할을 뗄 수 없습니다.")).toBeInTheDocument();
  });
});
