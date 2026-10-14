-- Where a user signs in: with the password stored here (LOCAL) or through the company IdP (OIDC).
alter table users add column auth_source varchar(16) not null default 'LOCAL';
