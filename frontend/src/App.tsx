import type { ReactElement } from "react";
import { BrowserRouter, Navigate, NavLink, Outlet, Route, Routes } from "react-router-dom";
import { AgentEditorPage } from "./agents/AgentEditorPage";
import { AgentsPage } from "./agents/AgentsPage";
import type { Role } from "./api/types";
import { useAuth } from "./auth/AuthContext";
import { ToolsPage } from "./tools/ToolsPage";
import { UsersPage } from "./users/UsersPage";
import { BuilderPage } from "./builder/BuilderPage";
import { ExecutionPage } from "./execution/ExecutionPage";
import { AccountPage } from "./pages/AccountPage";
import { ApprovalsPage } from "./pages/ApprovalsPage";
import { ExecutionsPage } from "./pages/ExecutionsPage";
import { LoginPage } from "./pages/LoginPage";
import { WorkflowsPage } from "./pages/WorkflowsPage";

/** Hides a route from roles that cannot use it; renders nothing while roles are still loading. */
function RequireRole({ roles, children }: { roles: Role[]; children: ReactElement }) {
  const { me, meLoading, hasRole } = useAuth();
  if (meLoading || !me) return null;
  if (!hasRole(...roles)) return <Navigate to="/workflows" replace />;
  return children;
}

function Layout() {
  const { username, me, logout } = useAuth();
  return (
    <div className="app">
      <header className="topbar">
        <span className="brand">AgentGate</span>
        <nav>
          <NavLink to="/workflows">Workflows</NavLink>
          <NavLink to="/executions">Executions</NavLink>
          <NavLink to="/approvals">Approvals</NavLink>
          <NavLink to="/agents">Agents</NavLink>
          <NavLink to="/tools">Tools</NavLink>
          {me?.roles.includes("ADMIN") && <NavLink to="/users">사용자</NavLink>}
        </nav>
        <span className="spacer" />
        <NavLink className="muted" to="/account">
          {username}
        </NavLink>
        <button className="secondary" onClick={logout}>
          로그아웃
        </button>
      </header>
      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}

export function App() {
  const { client } = useAuth();
  if (!client) return <LoginPage />;
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<Layout />}>
          <Route path="/workflows" element={<WorkflowsPage />} />
          <Route path="/workflows/new" element={<BuilderPage />} />
          <Route path="/workflows/:workflowId/edit" element={<BuilderPage />} />
          <Route path="/executions" element={<ExecutionsPage />} />
          <Route path="/executions/:executionId" element={<ExecutionPage />} />
          <Route path="/approvals" element={<ApprovalsPage />} />
          <Route path="/agents" element={<AgentsPage />} />
          <Route path="/agents/:id" element={<AgentEditorPage />} />
          <Route path="/tools" element={<ToolsPage />} />
          <Route
            path="/users"
            element={
              <RequireRole roles={["ADMIN"]}>
                <UsersPage />
              </RequireRole>
            }
          />
          <Route path="/account" element={<AccountPage />} />
          <Route path="*" element={<Navigate to="/workflows" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
