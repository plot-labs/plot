const SESSION_COOKIE = "plot.session";

function plotApiBaseUrl(): string {
  return (process.env.PLOT_API_BASE_URL ?? "http://127.0.0.1:8080").replace(/\/$/, "");
}

export type PlotAuthSession = {
  user?: {
    id?: string;
    email?: string | null;
    name?: string | null;
    image?: string | null;
  };
} | null;

export async function fetchPlotAuthSession(cookieHeader: string | null): Promise<PlotAuthSession> {
  if (!cookieHeader) return null;
  try {
    const response = await fetch(`${plotApiBaseUrl()}/api/auth/session`, {
      headers: { cookie: cookieHeader, accept: "application/json" },
      cache: "no-store",
    });
    if (!response.ok) return null;
    return await response.json() as PlotAuthSession;
  } catch {
    return null;
  }
}

export async function fetchPlotAuthToken(cookieHeader: string | null): Promise<string | null> {
  if (!cookieHeader) return null;
  try {
    const response = await fetch(`${plotApiBaseUrl()}/api/auth/token`, {
      headers: { cookie: cookieHeader, accept: "application/json" },
      cache: "no-store",
    });
    if (!response.ok) return null;
    const payload = await response.json() as { token?: string };
    return payload.token ?? null;
  } catch {
    return null;
  }
}

export { SESSION_COOKIE };
