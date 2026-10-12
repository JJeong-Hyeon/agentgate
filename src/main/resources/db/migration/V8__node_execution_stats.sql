-- What an agent step cost, for the dashboard: the model call behind a turn and how long a tool ran.
-- Filled from the runtime's agent traces as steps complete; null for other steps.
alter table node_executions add column if not exists llm_model varchar(200);
alter table node_executions add column if not exists llm_latency_ms bigint;
alter table node_executions add column if not exists input_tokens bigint;
alter table node_executions add column if not exists output_tokens bigint;
alter table node_executions add column if not exists tool_name varchar(255);
alter table node_executions add column if not exists tool_status varchar(32);
alter table node_executions add column if not exists tool_duration_ms bigint;

create index if not exists node_executions_finished_idx on node_executions (finished_at);
create index if not exists executions_created_idx on executions (created_at);
create index if not exists approval_requests_status_created_idx on approval_requests (status, created_at);
