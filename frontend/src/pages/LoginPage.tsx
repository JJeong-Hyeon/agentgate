import { useState, type FormEvent } from "react";
import { ApiError } from "../api/client";
import { useAuth } from "../auth/AuthContext";
import { Logo } from "../components/Logo";

function loginError(e: unknown): string {
  if (e instanceof ApiError && e.status === 401) return "아이디 또는 비밀번호가 올바르지 않습니다.";
  if (e instanceof ApiError && e.status === 429)
    return "로그인 실패가 반복되어 잠시 막혔습니다. 잠시 후 다시 시도하세요.";
  return "AgentGate에 연결할 수 없습니다.";
}

export function LoginPage() {
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
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        <button type="submit" disabled={submitting || !password}>
          {submitting ? "확인 중…" : "로그인"}
        </button>
      </form>
    </main>
  );
}
