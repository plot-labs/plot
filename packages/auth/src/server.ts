import { betterAuth } from "better-auth";
import { jwt } from "better-auth/plugins";
import { Pool } from "pg";

import { assertAllowedEmail, normalizeEmail, parseAllowedEmails } from "./policy";

function createAuth() {
  const isProduction = process.env.NODE_ENV === "production";
  const allowedEmails = parseAllowedEmails(process.env.AUTH_ALLOWED_EMAILS);
  if (isProduction && allowedEmails.size === 0) {
    throw new Error("AUTH_ALLOWED_EMAILS must contain at least one address in production");
  }

  const databaseUrl = process.env.BETTER_AUTH_DATABASE_URL ?? process.env.DATABASE_URL ??
    (isProduction ? "" : "postgres://postgres:postgres@127.0.0.1:5432/plot");
  if (!databaseUrl) {
    throw new Error("BETTER_AUTH_DATABASE_URL (or DATABASE_URL) is required");
  }

  const authSecret = process.env.BETTER_AUTH_SECRET ?? (isProduction ? "" : "plot-local-development-secret-change-me-32");
  if (authSecret.length < 32) {
    throw new Error("BETTER_AUTH_SECRET must be at least 32 characters");
  }

  const pool = new Pool({ connectionString: databaseUrl });

/**
 * Better Auth owns browser sessions and OAuth. Plot's Flyway V5 migration owns
 * the auth_* tables; the CLI is used only to inspect/verify generated SQL.
 */
  return betterAuth({
  appName: "Plot",
  baseURL: process.env.BETTER_AUTH_URL ?? process.env.NEXT_PUBLIC_APP_URL ?? "http://localhost:3000",
  secret: authSecret,
  database: pool,
  trustedOrigins: [
    process.env.BETTER_AUTH_URL ?? "http://localhost:3000",
    "https://useplot.xyz",
    "https://app.useplot.xyz",
  ],
  advanced: {
    useSecureCookies: isProduction,
  },
  session: {
    modelName: "auth_session",
    fields: {
      expiresAt: "expires_at",
      createdAt: "created_at",
      updatedAt: "updated_at",
      ipAddress: "ip_address",
      userAgent: "user_agent",
      userId: "user_id",
    },
    storeSessionInDatabase: true,
    cookieCache: { enabled: false },
  },
  user: {
    modelName: "auth_user",
    fields: {
      emailVerified: "email_verified",
      createdAt: "created_at",
      updatedAt: "updated_at",
    },
    changeEmail: { enabled: false },
    deleteUser: { enabled: false },
  },
  account: {
    modelName: "auth_account",
    fields: {
      accountId: "account_id",
      providerId: "provider_id",
      userId: "user_id",
      accessToken: "access_token",
      refreshToken: "refresh_token",
      idToken: "id_token",
      accessTokenExpiresAt: "access_token_expires_at",
      refreshTokenExpiresAt: "refresh_token_expires_at",
      createdAt: "created_at",
      updatedAt: "updated_at",
    },
    accountLinking: { enabled: false },
  },
  verification: {
    modelName: "auth_verification",
    fields: {
      expiresAt: "expires_at",
      createdAt: "created_at",
      updatedAt: "updated_at",
    },
  },
  socialProviders: {
    github: {
      clientId: process.env.GITHUB_OAUTH_CLIENT_ID ?? "",
      clientSecret: process.env.GITHUB_OAUTH_CLIENT_SECRET ?? "",
      scope: ["read:user", "user:email"],
      mapProfileToUser: (profile) => {
        const email = normalizeEmail(String(profile.email ?? ""));
        assertAllowedEmail(email, allowedEmails, { production: isProduction });
        return {
          name: String(profile.name ?? profile.login ?? email),
          email,
          image: typeof profile.avatar_url === "string" ? profile.avatar_url : undefined,
        };
      },
    },
  },
  plugins: [
    jwt({
      jwks: {
        keyPairConfig: { alg: "ES256" },
      },
      // Plugin schema is explicitly mapped to the Flyway-owned table.
      schema: {
        jwks: {
          modelName: "auth_jwks",
          fields: {
            publicKey: "public_key",
            privateKey: "private_key",
            createdAt: "created_at",
            expiresAt: "expires_at",
          },
        },
      },
      jwt: {
        issuer: process.env.PLOT_AUTH_JWT_ISSUER ?? "https://app.useplot.xyz",
        audience: process.env.PLOT_AUTH_JWT_AUDIENCE ?? "plot-api",
        expirationTime: "15m",
        getSubject: ({ user }) => user.id,
        definePayload: ({ user }) => ({ email: user.email, name: user.name }),
      },
    }),
  ],
  });
}

type BetterAuthInstance = ReturnType<typeof createAuth>;

/**
 * Importing a Next route during `next build` must not require production
 * credentials. The first runtime use constructs and validates Better Auth, so
 * request paths still fail closed when its allowlist, database URL, or secret
 * is absent or invalid.
 */
export const auth = new Proxy({} as BetterAuthInstance, {
  get(_target, property, receiver) {
    return Reflect.get(createAuth(), property, receiver);
  },
});

export type AuthSession = typeof auth.$Infer.Session;
export type AuthUser = AuthSession["user"];
