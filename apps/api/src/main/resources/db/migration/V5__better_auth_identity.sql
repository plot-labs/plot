-- Better Auth is intentionally kept in Plot's database, but its tables are
-- owned by Flyway so application startup never runs an auth CLI migration.
create table auth_user (
  id text primary key,
  name text not null,
  email text not null unique,
  email_verified boolean not null default false,
  image text,
  created_at timestamptz not null,
  updated_at timestamptz not null
);

create table auth_session (
  id text primary key,
  expires_at timestamptz not null,
  token text not null unique,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  ip_address text,
  user_agent text,
  user_id text not null references auth_user(id) on delete cascade
);

create index auth_session_user_id_idx on auth_session(user_id);

create table auth_account (
  id text primary key,
  account_id text not null,
  provider_id text not null,
  user_id text not null references auth_user(id) on delete cascade,
  access_token text,
  refresh_token text,
  id_token text,
  access_token_expires_at timestamptz,
  refresh_token_expires_at timestamptz,
  scope text,
  password text,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (provider_id, account_id)
);

create index auth_account_user_id_idx on auth_account(user_id);

create table auth_verification (
  id text primary key,
  identifier text not null,
  value text not null,
  expires_at timestamptz not null,
  created_at timestamptz,
  updated_at timestamptz
);

create table auth_jwks (
  id text primary key,
  public_key text not null,
  private_key text not null,
  created_at timestamptz not null,
  expires_at timestamptz
);

alter table users add column auth_issuer text;
alter table users add column auth_subject text;

create unique index users_auth_identity_idx
  on users(auth_issuer, auth_subject)
  where auth_issuer is not null and auth_subject is not null;
