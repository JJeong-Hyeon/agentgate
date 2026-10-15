-- The identity provider's own id for a person (the OIDC "sub" claim). Unique, so one IdP identity is one user,
-- whatever user name the IdP shows for it later.
alter table users add column oidc_subject varchar(255);
create unique index ux_users_oidc_subject on users (oidc_subject);
