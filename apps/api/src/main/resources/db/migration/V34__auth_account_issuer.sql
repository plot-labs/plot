-- Better Auth 1.7 resolves OAuth accounts by (issuer, account_id) instead of
-- (provider_id, account_id). Plot's auth tables are Flyway-owned, so add the
-- column and backfill existing GitHub rows with the provider-id namespace that
-- better-auth uses for social providers (local:oauth:<providerId>).
alter table auth_account
  add column issuer text;

update auth_account
set issuer = 'local:oauth:' || provider_id
where issuer is null;

alter table auth_account
  alter column issuer set not null;

create unique index auth_account_issuer_account_id_uidx
  on auth_account (issuer, account_id);
