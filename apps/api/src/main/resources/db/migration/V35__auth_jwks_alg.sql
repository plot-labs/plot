-- Kotlin-owned auth contract: ES256 algorithm column for auth_jwks rows.
alter table auth_jwks add column alg text;

update auth_jwks set alg = 'ES256' where alg is null;

alter table auth_jwks alter column alg set not null;
