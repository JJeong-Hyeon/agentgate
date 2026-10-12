-- A memory an agent replaced with an updated one stays for the record but is no longer recalled or searched.
alter table agent_memories add column if not exists superseded_by bigint;
alter table agent_memories add column if not exists superseded_at timestamp(6) with time zone;

create index if not exists agent_memories_active_idx on agent_memories (agent_id, created_at desc) where superseded_by is null;
