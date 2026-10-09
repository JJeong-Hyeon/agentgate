import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { AgentGateClient, type Credentials } from "../api/client";
import type { Me, Role } from "../api/types";

const STORAGE_KEY = "agentgate.credentials";

interface AuthState {
  client: AgentGateClient | null;
  username: string | null;
  /** Current user's profile and roles; null while it loads or before login. */
  me: Me | null;
  meLoading: boolean;
  hasRole: (...roles: Role[]) => boolean;
  login: (credentials: Credentials) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

// Credentials live only for this browser tab's session; storage may be unavailable (private mode).
function loadStored(): Credentials | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as Credentials) : null;
  } catch {
    return null;
  }
}

function store(credentials: Credentials | null): void {
  try {
    if (credentials) sessionStorage.setItem(STORAGE_KEY, JSON.stringify(credentials));
    else sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    // Login still works for this page load.
  }
}

export function AuthProvider({
  children,
  fetchImpl,
}: {
  children: ReactNode;
  fetchImpl?: typeof fetch;
}) {
  const [credentials, setCredentials] = useState<Credentials | null>(loadStored);
  const [me, setMe] = useState<Me | null>(null);
  // Starts true when credentials were restored from storage, so the nav doesn't flash role-gated links.
  const [meLoading, setMeLoading] = useState(credentials !== null);

  const logout = useCallback(() => {
    store(null);
    setCredentials(null);
    setMe(null);
  }, []);

  const login = useCallback(
    async (candidate: Credentials) => {
      // GET /me both verifies the credentials and returns this user's roles.
      const profile = await new AgentGateClient(candidate, fetchImpl).getMe();
      store(candidate);
      setCredentials(candidate);
      setMe(profile);
    },
    [fetchImpl],
  );

  // Credentials restored from sessionStorage (page reload) still need their roles fetched.
  useEffect(() => {
    if (!credentials || me) return;
    let cancelled = false;
    setMeLoading(true);
    new AgentGateClient(credentials, fetchImpl)
      .getMe()
      .then((profile) => {
        if (!cancelled) setMe(profile);
      })
      .catch(() => {
        if (!cancelled) logout();
      })
      .finally(() => {
        if (!cancelled) setMeLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [credentials, me, fetchImpl, logout]);

  const hasRole = useCallback((...roles: Role[]) => roles.some((r) => me?.roles.includes(r)), [me]);

  const value = useMemo<AuthState>(
    () => ({
      client: credentials ? new AgentGateClient(credentials, fetchImpl) : null,
      username: credentials?.username ?? null,
      me,
      meLoading,
      hasRole,
      login,
      logout,
    }),
    [credentials, fetchImpl, me, meLoading, hasRole, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const state = useContext(AuthContext);
  if (!state) throw new Error("useAuth must be used inside AuthProvider");
  return state;
}

/** The API client of the logged-in admin; only for pages behind the login gate. */
export function useClient(): AgentGateClient {
  const { client } = useAuth();
  if (!client) throw new Error("Not logged in");
  return client;
}
