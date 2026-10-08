-- Hibernate's ddl-auto never updated CHECK constraints of enum columns, so databases it created
-- before a value was added reject that value (e.g. executions.status 'STOPPED').
-- Recreate every enum check with the current values. A future enum value needs a new migration.

alter table agents drop constraint if exists agents_max_risk_level_check;
alter table agents add constraint agents_max_risk_level_check
    check (max_risk_level in ('LOW', 'MEDIUM', 'HIGH', 'BLOCKED'));

alter table approval_requests drop constraint if exists approval_requests_risk_level_check;
alter table approval_requests add constraint approval_requests_risk_level_check
    check (risk_level in ('LOW', 'MEDIUM', 'HIGH', 'BLOCKED'));

alter table approval_requests drop constraint if exists approval_requests_status_check;
alter table approval_requests add constraint approval_requests_status_check
    check (status in ('PENDING', 'APPROVED', 'REJECTED'));

alter table audit_logs drop constraint if exists audit_logs_basis_check;
alter table audit_logs add constraint audit_logs_basis_check
    check (basis in ('POLICY', 'AGENT_RISK_CAP', 'TOOL_NOT_GRANTED', 'TOOL_BLOCKED',
                     'TOOL_REQUIRES_APPROVAL', 'APPROVAL_REQUESTED'));

alter table audit_logs drop constraint if exists audit_logs_risk_level_check;
alter table audit_logs add constraint audit_logs_risk_level_check
    check (risk_level in ('LOW', 'MEDIUM', 'HIGH', 'BLOCKED'));

alter table audit_logs drop constraint if exists audit_logs_status_check;
alter table audit_logs add constraint audit_logs_status_check
    check (status in ('ALLOWED', 'APPROVAL_REQUIRED', 'BLOCKED'));

alter table executions drop constraint if exists executions_status_check;
alter table executions add constraint executions_status_check
    check (status in ('RUNNING', 'WAITING_APPROVAL', 'COMPLETED', 'STOPPED', 'FAILED'));

alter table node_executions drop constraint if exists node_executions_status_check;
alter table node_executions add constraint node_executions_status_check
    check (status in ('RUNNING', 'WAITING', 'COMPLETED', 'FAILED'));

alter table policies drop constraint if exists policies_category_check;
alter table policies add constraint policies_category_check
    check (category in ('PRIVACY', 'SECURITY', 'APPROVAL_WORKFLOW'));

alter table policies drop constraint if exists policies_risk_level_check;
alter table policies add constraint policies_risk_level_check
    check (risk_level in ('LOW', 'MEDIUM', 'HIGH', 'BLOCKED'));
