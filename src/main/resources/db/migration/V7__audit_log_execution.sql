-- Links each governance decision to the run it was made in, for the execution trace.
alter table audit_logs add column if not exists execution_id varchar(64);

create index if not exists audit_logs_execution_idx on audit_logs (execution_id);
create index if not exists audit_logs_agent_created_idx on audit_logs (agent_id, created_at desc);
create index if not exists audit_logs_created_idx on audit_logs (created_at desc);
