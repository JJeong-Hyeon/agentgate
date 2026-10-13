import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { AgentGateClient, UNAUTHORIZED_EVENT, type Credentials } from "../api/client";
import type { Me, Role } from "../api/types";

interface AuthState {
  /** The signed-in user's API client; null before sign-in (the login gate keys off this). */
  client: AgentGateClient | null;
  /** The same client regardless of sign-in: its requests carry the session cookie, nothing else. */
  api: AgentGateClient;
  username: string | null;
  /** Current user's profile and roles; null while it loads or before login. */
  me: Me | null;
  meLoading: boolean;
  hasRole: (...roles: Role[]) => boolean;
  login: (credentials: Credentials) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

/**
 * Who is signed in, by the server's session cookie: the browser never holds the password. On load (and
 * reload) the session, if any, is found by asking the server who this is.
 */
export function AuthProvider({
  children,
  fetchImpl,
}: {
  children: ReactNode;
  fetchImpl?: typeof fetch;
}) {
  const client = useMemo(() => new AgentGateClient(null, fetchImpl), [fetchImpl]);
  const [me, setMe] = useState<Me | null>(null);
  const [meLoading, setMeLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    client
      .getMe()
      .then((profile) => {
        if (!cancelled) setMe(profile);
      })
      .catch(() => undefined)
      .finally(() => {
        if (!cancelled) setMeLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [client]);

  // The session ended while the console was open.
  useEffect(() => {
    // Only this provider's own client: a request of a page that is already gone must not sign anyone out.
    const onUnauthorized = (event: Event) => {
      if ((event as CustomEvent).detail === client) setMe(null);
    };
    window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
  }, [client]);

  const logout = useCallback(() => {
    setMe(null);
    client.logout().catch(() => undefined);
  }, [client]);

  const login = useCallback(
    async ({ username, password }: Credentials) => {
      setMe(await client.login(username, password));
    },
    [client],
  );

  const hasRole = useCallback((...roles: Role[]) => roles.some((r) => me?.roles.includes(r)), [me]);

  const value = useMemo<AuthState>(
    () => ({ client: me ? client : null, api: client, username: me?.username ?? null, me, meLoading, hasRole, login, logout }),
    [client, me, meLoading, hasRole, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const state = useContext(AuthContext);
  if (!state) throw new Error("useAuth must be used inside AuthProvider");
  return state;
}

/** The API client for pages behind the login gate (App shows them only to a signed-in user). */
export function useClient(): AgentGateClient {
  return useAuth().api;
}
