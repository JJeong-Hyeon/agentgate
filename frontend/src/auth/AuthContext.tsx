import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import { AgentGateClient, type Credentials } from "../api/client";

const STORAGE_KEY = "agentgate.credentials";

interface AuthState {
  client: AgentGateClient | null;
  username: string | null;
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

  const login = useCallback(
    async (candidate: Credentials) => {
      // Any admin-only call verifies the credentials.
      await new AgentGateClient(candidate, fetchImpl).listWorkflows();
      store(candidate);
      setCredentials(candidate);
    },
    [fetchImpl],
  );

  const logout = useCallback(() => {
    store(null);
    setCredentials(null);
  }, []);

  const value = useMemo<AuthState>(
    () => ({
      client: credentials ? new AgentGateClient(credentials, fetchImpl) : null,
      username: credentials?.username ?? null,
      login,
      logout,
    }),
    [credentials, fetchImpl, login, logout],
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
