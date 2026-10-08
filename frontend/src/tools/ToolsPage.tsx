import { useCallback, useState } from "react";
import { useClient } from "../auth/AuthContext";
import { useAsync } from "../useAsync";
import { McpServersSection } from "./McpServersSection";
import { ToolRisksSection } from "./ToolRisksSection";

export function ToolsPage() {
  const client = useClient();
  const [refresh, setRefresh] = useState(0);
  const risks = useAsync(useCallback(() => client.listToolRisks(refresh > 0), [client, refresh]));
  const relist = () => setRefresh((n) => n + 1);

  return (
    <div className="agent-editor">
      <ToolRisksSection risks={risks} onRefresh={relist} />
      <McpServersSection onChanged={relist} />
    </div>
  );
}
