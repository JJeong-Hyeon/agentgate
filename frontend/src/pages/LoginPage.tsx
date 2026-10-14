import { useEffect, useState, type FormEvent } from "react";
import { ApiError } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { Logo } from "../components/Logo";

function loginError(e: unknown): string {
  if (e instanceof ApiError && e.status === 401) return "아이디 또는 비밀번호가 올바르지 않습니다.";
  if (e instanceof ApiError && e.status === 429)
    return "로그인 실패가 반복되어 잠시 막혔습니다. 잠시 후 다시 시도하세요.";
  return "AgentGate에 연결할 수 없습니다.";
}

type AuthConfig = { localLoginEnabled: boolean; ssoEnabled: boolean; ssoLabel: string | null };

/** How this server lets people sign in; until it answers (or if it cannot), just the password form. */
function useAuthConfig(): AuthConfig {
  const [config, setConfig] = useState<AuthConfig>({ localLoginEnabled: true, ssoEnabled: false, ssoLabel: null });
  useEffect(() => {
    let cancelled = false;
    fetch("/api/v1/auth/config")
      .then((response) => (response.ok ? response.json() : null))
      .then((body) => {
        if (!cancelled && body && typeof body.localLoginEnabled === "boolean") setConfig(body);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, []);
  return config;
}

export function LoginPage() {
  const authConfig = useAuthConfig();
  const ssoFailed = new URLSearchParams(window.location.search).has("sso_error");
  const { login } = useAuth();
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await login({ username, password });
    } catch (e) {
      setError(loginError(e));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="login">
      <form className="card login-card" onSubmit={submit}>
        <h1>
          <Logo size={28} />
          AgentGate
        </h1>
        {authConfig.localLoginEnabled && (
          <>
            <label>
              아이디
              <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" />
            </label>
            <label>
              비밀번호
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
              />
            </label>
          </>
        )}
        {(error || ssoFailed) && (
          <p className="error" role="alert">
            {error ?? "SSO 로그인에 실패했습니다. 계정이 없거나 허용된 그룹에 속하지 않을 수 있습니다. 관리자에게 문의하세요."}
          </p>
        )}
        {authConfig.localLoginEnabled && (
          <button type="submit" disabled={submitting || !password}>
            {submitting ? "확인 중…" : "로그인"}
          </button>
        )}
        {authConfig.ssoEnabled && (
          <a className="button" href="/oauth2/authorization/oidc">
            {authConfig.ssoLabel ?? "SSO"}로 로그인
          </a>
        )}
      </form>
    </main>
  );
}
