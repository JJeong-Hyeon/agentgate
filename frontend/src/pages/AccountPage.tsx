import { useState, type FormEvent } from "react";
import { useAuth, useClient } from "../auth/AuthContext";

export function AccountPage() {
  const client = useClient();
  const { username, me } = useAuth();
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);

  async function save(event: FormEvent) {
    event.preventDefault();
    if (newPassword.length < 8) {
      setError("새 비밀번호는 8자 이상이어야 합니다.");
      return;
    }
    setBusy(true);
    setError(null);
    setDone(false);
    try {
      await client.changeMyPassword(currentPassword, newPassword);
      setCurrentPassword("");
      setNewPassword("");
      setDone(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card stack">
      <h2>계정</h2>
      <p className="muted">
        {username}
        {me?.roles.length ? ` · ${me.roles.join(", ")}` : ""}
      </p>
      <form className="stack" onSubmit={save} aria-label="비밀번호 변경">
        <label>
          현재 비밀번호
          <input
            type="password"
            autoComplete="current-password"
            value={currentPassword}
            onChange={(e) => setCurrentPassword(e.target.value)}
          />
        </label>
        <label>
          새 비밀번호
          <input
            type="password"
            autoComplete="new-password"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
          />
        </label>
        {error && <p className="error">{error}</p>}
        {done && <p className="notice" role="status">비밀번호를 변경했습니다.</p>}
        <div>
          <button type="submit" disabled={busy || !currentPassword || !newPassword}>
            변경
          </button>
        </div>
      </form>
    </section>
  );
}
