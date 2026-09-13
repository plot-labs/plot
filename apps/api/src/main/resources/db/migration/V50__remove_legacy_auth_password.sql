-- WorkOS Magic Auth owns passwordless authentication.
-- auth_account remains for product GitHub OAuth credentials.
alter table auth_account
    drop column if exists password;
