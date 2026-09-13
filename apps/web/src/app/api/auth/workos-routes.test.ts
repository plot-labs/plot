import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => {
  const userManagement = {
    authenticateWithCode: vi.fn(),
    authenticateWithMagicAuth: vi.fn(),
    createMagicAuth: vi.fn(),
    getAuthorizationUrl: vi.fn(),
  };
  const cookieStore = {
    delete: vi.fn(),
    get: vi.fn(),
    getAll: vi.fn(() => []),
    set: vi.fn(),
  };
  return {
    cookieStore,
    getWorkOS: vi.fn(() => ({ userManagement })),
    saveSession: vi.fn(),
    userManagement,
  };
});

vi.mock("@workos-inc/authkit-nextjs", () => ({
  getWorkOS: mocks.getWorkOS,
  saveSession: mocks.saveSession,
}));

vi.mock("next/headers", () => ({
  cookies: vi.fn(async () => mocks.cookieStore),
}));

import { NextRequest } from "next/server";

import { GET as getSocialCallback } from "../../auth/callback/route";
import { POST as postMagicAuthResend } from "./magic-auth/resend/route";
import { POST as postMagicAuthVerify } from "./magic-auth/verify/route";
import { GET as getSignIn, POST as postSignIn } from "./sign-in/route";
import { GET as getSignUp, POST as postSignUp } from "./sign-up/route";

const authResponse = {
  accessToken: "access-token",
  refreshToken: "refresh-token",
  user: {
    email: "member@example.com",
    emailVerified: true,
    id: "user-1",
    object: "user" as const,
  },
};

describe("WorkOS auth routes", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    process.env.WORKOS_CLIENT_ID = "client_test";
    delete process.env.PLOT_APP_ORIGIN;
    mocks.cookieStore.get.mockReturnValue(undefined);
    mocks.userManagement.getAuthorizationUrl.mockImplementation(async ({ state }: { state: string }) =>
      `https://github.com/login/oauth/authorize?state=${encodeURIComponent(state)}`);
    mocks.userManagement.authenticateWithCode.mockResolvedValue(authResponse);
    mocks.userManagement.authenticateWithMagicAuth.mockResolvedValue(authResponse);
    mocks.userManagement.createMagicAuth.mockResolvedValue({ id: "magic-auth-1" });
    mocks.saveSession.mockResolvedValue(undefined);
  });

  it("redirects GitHub sign-in directly to the provider and constrains the return path", async () => {
    const response = await getSignIn(new Request(
      "http://plot.test/api/auth/sign-in?returnTo=https%3A%2F%2Fevil.test%2Fsteal",
    ));

    expect(response.status).toBe(307);
    expect(new URL(response.headers.get("location") ?? "").origin).toBe("https://github.com");
    expect(mocks.userManagement.getAuthorizationUrl).toHaveBeenCalledWith({
      clientId: "client_test",
      provider: "GitHubOAuth",
      redirectUri: "http://plot.test/auth/callback",
      state: expect.any(String),
    });
    expect(mocks.cookieStore.set).toHaveBeenCalledWith(expect.objectContaining({
      httpOnly: true,
      maxAge: 600,
      value: expect.any(String),
    }));
  });

  it("redirects GitHub signup through the same Plot-owned callback", async () => {
    const response = await getSignUp(new Request(
      "http://plot.test/api/auth/sign-up?returnTo=%2Fauth%2Fcomplete",
    ));

    expect(response.status).toBe(307);
    expect(response.headers.get("location")).toContain("https://github.com/login/oauth/authorize");
    expect(mocks.userManagement.getAuthorizationUrl).toHaveBeenCalledWith(expect.objectContaining({
      provider: "GitHubOAuth",
      redirectUri: "http://plot.test/auth/callback",
    }));
  });

  it("validates the OAuth state before exchanging the GitHub code and saves the WorkOS session", async () => {
    const startResponse = await getSignIn(new Request("http://plot.test/api/auth/sign-in"));
    const authorizationUrl = new URL(startResponse.headers.get("location") ?? "");
    const cookieState = mocks.cookieStore.set.mock.calls.at(-1)?.[0];
    mocks.cookieStore.get.mockReturnValue({ value: cookieState?.value });

    const callbackRequest = new NextRequest(
      `http://plot.test/auth/callback?code=github-code&state=${encodeURIComponent(authorizationUrl.searchParams.get("state") ?? "")}`,
    );
    const response = await getSocialCallback(callbackRequest);

    expect(response.status).toBe(307);
    expect(response.headers.get("location")).toBe("http://plot.test/auth/complete");
    expect(mocks.userManagement.authenticateWithCode).toHaveBeenCalledWith({
      clientId: "client_test",
      code: "github-code",
    });
    expect(mocks.saveSession).toHaveBeenCalledWith(authResponse, callbackRequest.url);
    expect(mocks.cookieStore.delete).toHaveBeenCalledWith("plot.workos.social_state");
  });

  it("rejects a mismatched OAuth state without contacting WorkOS", async () => {
    await getSignIn(new Request("http://plot.test/api/auth/sign-in"));
    const cookieState = mocks.cookieStore.set.mock.calls.at(-1)?.[0];
    mocks.cookieStore.get.mockReturnValue({ value: cookieState?.value });

    const response = await getSocialCallback(new NextRequest(
      "http://plot.test/auth/callback?code=github-code&state=wrong-state",
    ));

    expect(response.status).toBe(307);
    expect(response.headers.get("location")).toBe("http://plot.test/sign-in?error=callback_failed");
    expect(mocks.userManagement.authenticateWithCode).not.toHaveBeenCalled();
  });

  it("starts passwordless sign-in with a WorkOS Magic Auth code", async () => {
    const request = new Request("http://plot.test/api/auth/sign-in", {
      method: "POST",
      headers: { "user-agent": "vitest", "x-forwarded-for": "203.0.113.10" },
      body: JSON.stringify({ email: " Member@Example.com " }),
    });

    const response = await postSignIn(request);

    expect(response.status).toBe(202);
    await expect(response.json()).resolves.toEqual({
      email: "member@example.com",
      status: "verification_required",
    });
    expect(mocks.userManagement.createMagicAuth).toHaveBeenCalledWith({
      email: "member@example.com",
      ipAddress: "203.0.113.10",
      userAgent: "vitest",
    });
    expect(mocks.cookieStore.set).toHaveBeenCalledWith(expect.objectContaining({
      name: "plot.workos.pending_magic_auth",
      httpOnly: true,
      maxAge: 600,
    }));
  });

  it("starts passwordless signup through the same Magic Auth flow", async () => {
    const response = await postSignUp(new Request("http://plot.test/api/auth/sign-up", {
      method: "POST",
      body: JSON.stringify({ email: "new@example.com" }),
    }));

    expect(response.status).toBe(202);
    await expect(response.json()).resolves.toEqual({
      email: "new@example.com",
      status: "verification_required",
    });
    expect(mocks.userManagement.createMagicAuth).toHaveBeenCalledWith({ email: "new@example.com" });
  });

  it("rejects cross-origin Magic Auth requests before contacting WorkOS", async () => {
    const response = await postSignIn(new Request("http://plot.test/api/auth/sign-in", {
      method: "POST",
      headers: { origin: "https://attacker.test" },
      body: JSON.stringify({ email: "member@example.com" }),
    }));

    expect(response.status).toBe(403);
    await expect(response.json()).resolves.toEqual({
      error: "CSRF_ORIGIN_REJECTED",
      message: "Request origin is not allowed",
    });
    expect(mocks.userManagement.createMagicAuth).not.toHaveBeenCalled();
  });

  it("authenticates with the pending Magic Auth code and saves the session", async () => {
    await postSignIn(new Request("http://plot.test/api/auth/sign-in", {
      method: "POST",
      body: JSON.stringify({ email: "member@example.com" }),
    }));
    const magicCookie = mocks.cookieStore.set.mock.calls.at(-1)?.[0];
    mocks.cookieStore.get.mockReturnValue({ value: magicCookie?.value });

    const request = new Request("http://plot.test/api/auth/magic-auth/verify", {
      method: "POST",
      headers: { "user-agent": "vitest", "x-forwarded-for": "203.0.113.10" },
      body: JSON.stringify({ code: "123456" }),
    });
    const response = await postMagicAuthVerify(request);

    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual({ status: "authenticated" });
    expect(mocks.userManagement.authenticateWithMagicAuth).toHaveBeenCalledWith({
      clientId: "client_test",
      code: "123456",
      email: "member@example.com",
      ipAddress: "203.0.113.10",
      userAgent: "vitest",
    });
    expect(mocks.saveSession).toHaveBeenCalledWith(authResponse, request.url);
    expect(mocks.cookieStore.delete).toHaveBeenCalledWith("plot.workos.pending_magic_auth");
  });

  it("resends a Magic Auth code using the server-side pending email", async () => {
    await postSignIn(new Request("http://plot.test/api/auth/sign-in", {
      method: "POST",
      body: JSON.stringify({ email: "member@example.com" }),
    }));
    const magicCookie = mocks.cookieStore.set.mock.calls.at(-1)?.[0];
    mocks.cookieStore.get.mockReturnValue({ value: magicCookie?.value });

    const response = await postMagicAuthResend(new Request("http://plot.test/api/auth/magic-auth/resend", {
      method: "POST",
    }));

    expect(response.status).toBe(202);
    await expect(response.json()).resolves.toEqual({ status: "sent" });
    expect(mocks.userManagement.createMagicAuth).toHaveBeenLastCalledWith({ email: "member@example.com" });
  });
});
