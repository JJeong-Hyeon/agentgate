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
): (url: string, init?: RequestInit) => Promise<Response> {
  return async (url, init) => {
    if (url === "/api/v1/me" && (!init?.method || init.method === "GET")) return Response.json(me);
    return handler(url, init);
  };
}
