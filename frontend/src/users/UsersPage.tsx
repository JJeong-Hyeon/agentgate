import { useCallback, useState, type FormEvent } from "react";
import type { Role, UserSummary } from "../api/types";
import { useAuth, useClient } from "../auth/AuthContext";
import { useAsync } from "../useAsync";

const USERNAME_PATTERN = /^[A-Za-z0-9._@-]{3,64}$/;
const ALL_ROLES: Role[] = ["ADMIN", "EDITOR", "APPROVER", "VIEWER"];

/** Users and their roles (ADMIN only). Mirrors the backend's own invariants for the last active ADMIN. */
export function UsersPage() {
  const client = useClient();
  const users = useAsync(useCallback(() => client.listUsers(), [client]));
  const [editing, setEditing] = useState<UserSummary | "new" | null>(null);

  function done() {
    setEditing(null);
    users.reload();
  }

  const activeAdmins = (users.data ?? []).filter((u) => u.enabled && u.roles.includes("ADMIN"));
  const lastActiveAdminId = activeAdmins.length === 1 ? activeAdmins[0].id : null;

  return (
    <section className="card stack">
      <div className="row">
        <h2>사용자</h2>
        <span className="spacer" />
        <button onClick={() => setEditing("new")}>사용자 추가</button>
      </div>
      {users.error && <p className="error">{users.error.message}</p>}
      {editing === "new" && <UserForm onDone={done} onCancel={() => setEditing(null)} />}
      {users.data?.length === 0 && editing !== "new" && <p className="muted">등록된 사용자가 없습니다.</p>}
      <ul className="server-list">
        {users.data?.map((user) =>
          editing !== "new" && editing?.id === user.id ? (
            <li key={user.id}>
              <UserForm
                user={user}
                isLastAdmin={user.id === lastActiveAdminId}
                onDone={done}
                onCancel={() => setEditing(null)}
              />
            </li>
          ) : (
            <li key={user.id} className="row">
              <span>
                <strong className="mono">{user.username}</strong>{" "}
                {!user.enabled && <span className="badge risk-blocked">사용 안 함</span>}
                {user.displayName && <div className="muted">{user.displayName}</div>}
                <div className="muted">{user.roles.join(", ")}</div>
              </span>
              <span className="spacer" />
              <button className="secondary" onClick={() => setEditing(user)}>
                수정
              </button>
            </li>
          ),
        )}
      </ul>
    </section>
  );
}

function UserForm({
  user,
  isLastAdmin,
  onDone,
  onCancel,
}: {
  user?: UserSummary;
  isLastAdmin?: boolean;
  onDone: () => void;
  onCancel: () => void;
}) {
  const client = useClient();
  const { username: myUsername } = useAuth();
  const isSelf = !!user && user.username === myUsername;
  const [usernameValue, setUsernameValue] = useState(user?.username ?? "");
  const [displayName, setDisplayName] = useState(user?.displayName ?? "");
  const [password, setPassword] = useState("");
  const [roles, setRoles] = useState<Role[]>(user?.roles ?? ["VIEWER"]);
  const [enabled, setEnabled] = useState(user?.enabled ?? true);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function toggleRole(role: Role) {
    setRoles((rs) => (rs.includes(role) ? rs.filter((r) => r !== role) : [...rs, role]));
  }

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!USERNAME_PATTERN.test(usernameValue)) {
      setError("사용자 이름은 영문, 숫자, ._@-만 쓸 수 있습니다 (3~64자).");
      return;
    }
    if (password && password.length < 8) {
      setError("비밀번호는 8자 이상이어야 합니다.");
      return;
    }
    if (!user && !password) {
      setError("새 사용자는 비밀번호가 필요합니다.");
      return;
    }
    if (roles.length === 0) {
      setError("최소 하나의 역할이 필요합니다.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const input = {
        username: usernameValue,
        displayName: displayName || null,
        roles,
        enabled,
        ...(password ? { password } : {}),
      };
      if (user) await client.updateUser(user.id, input);
      else await client.createUser(input);
      onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!user) return;
    setBusy(true);
    try {
      await client.deleteUser(user.id);
      onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  }

  return (
    <form className="stack server-form" onSubmit={save} aria-label={user ? `${user.username} 수정` : "사용자 추가"}>
      <div className="grid-2">
        <label>
          사용자 이름
          <input
            value={usernameValue}
            disabled={!!user}
            onChange={(e) => setUsernameValue(e.target.value)}
            placeholder="jane"
          />
        </label>
        <label>
          표시 이름
          <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
        </label>
      </div>
      <label>
        {user ? "비밀번호 재설정 (비워두면 유지)" : "비밀번호"}
        <input
          type="password"
          autoComplete="new-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
      </label>
      <div className="row">
        {ALL_ROLES.map((role) => (
          <label key={role} className="inline">
            <input
              type="checkbox"
              checked={roles.includes(role)}
              disabled={isLastAdmin && role === "ADMIN"}
              onChange={() => toggleRole(role)}
            />
            {role}
          </label>
        ))}
      </div>
      <label className="inline">
        <input type="checkbox" checked={enabled} disabled={isLastAdmin} onChange={(e) => setEnabled(e.target.checked)} />
        사용
      </label>
      {isLastAdmin && <span className="hint">마지막 활성 ADMIN은 비활성화하거나 ADMIN 역할을 뗄 수 없습니다.</span>}
      {error && <p className="error">{error}</p>}
      <div className="row">
        <button type="submit" disabled={busy}>
          {user ? "저장" : "추가"}
        </button>
        <button type="button" className="secondary" onClick={onCancel} disabled={busy}>
          취소
        </button>
        <span className="spacer" />
        {user && isSelf && <span className="hint">자기 자신은 삭제할 수 없습니다.</span>}
        {user &&
          !isSelf &&
          (confirmDelete ? (
            <>
              <span className="error">이 사용자를 삭제합니다.</span>
              <button type="button" className="danger" onClick={remove} disabled={busy}>
                삭제 확인
              </button>
            </>
          ) : (
            <button type="button" className="danger" onClick={() => setConfirmDelete(true)}>
              삭제
            </button>
          ))}
      </div>
    </form>
  );
}
