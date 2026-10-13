-- How long an agent's model call waited for a free slot under LLM_MAX_CONCURRENCY, separate from
-- llm_latency_ms (the call itself). Null for steps before this column existed or without a queue.
alter table node_executions add column if not exists llm_queue_wait_ms bigint;
