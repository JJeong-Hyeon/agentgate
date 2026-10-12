alter table approval_requests add column if not exists approver_notified_at timestamp(6) with time zone;

-- Requests made before notifications existed are not announced when a webhook is first configured.
update approval_requests set approver_notified_at = coalesce(created_at, now()) where approver_notified_at is null;
