import type { UserSummary } from "../api/types";

export const ADMIN_ME: UserSummary = {
  id: 1,
  username: "admin",
  displayName: null,
  roles: ["ADMIN"],
  enabled: true,
  createdAt: "",
  updatedAt: "",
};

/** Wraps a fetch mock handler so GET /api/v1/me resolves (ADMIN by default) unless the handler answers it itself. */
export function withMe(
  handler: (url: string, init?: RequestInit) => Promise<Response> | Response,
  me: UserSummary = ADMIN_ME,
): typeof fetch {
  // The server's session: tests that start signed in mark it with sessionStorage "agentgate.credentials".
  let signedIn = false;
  return async (input, init) => {
    const url = String(input);
    const method = init?.method ?? "GET";
    if (url === "/api/v1/auth/login" && method === "POST") {
      signedIn = true;
      return Response.json(me);
    }
    if (url === "/api/v1/auth/logout" && method === "POST") {
      signedIn = false;
      return new Response(null, { status: 204 });
    }
    if (url === "/api/v1/me" && method === "GET") {
      if (!signedIn && sessionStorage.getItem("agentgate.credentials") === null) {
        return new Response("{}", { status: 401 });
      }
      return Response.json(me);
    }
    return handler(url, init);
  };
}
