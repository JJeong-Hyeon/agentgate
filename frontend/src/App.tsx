import { BrowserRouter, Navigate, NavLink, Outlet, Route, Routes } from "react-router-dom";
import { AgentEditorPage } from "./agents/AgentEditorPage";
import { AgentsPage } from "./agents/AgentsPage";
import { useAuth } from "./auth/AuthContext";
import { BuilderPage } from "./builder/BuilderPage";
import { ExecutionPage } from "./execution/ExecutionPage";
import { ApprovalsPage } from "./pages/ApprovalsPage";
import { ExecutionsPage } from "./pages/ExecutionsPage";
import { LoginPage } from "./pages/LoginPage";
import { WorkflowsPage } from "./pages/WorkflowsPage";

function Layout() {
  const { username, logout } = useAuth();
  return (
    <div className="app">
      <header className="topbar">
        <span className="brand">AgentGate</span>
        <nav>
          <NavLink to="/workflows">Workflows</NavLink>
          <NavLink to="/executions">Executions</NavLink>
          <NavLink to="/approvals">Approvals</NavLink>
          <NavLink to="/agents">Agents</NavLink>
        </nav>
        <span className="spacer" />
        <span className="muted">{username}</span>
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
          <Route path="*" element={<Navigate to="/workflows" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
