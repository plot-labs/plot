import { withAuth } from "@workos-inc/authkit-nextjs";

/** AuthKit's default cookie name; kept as a shared naming constant for the proxy. */
export const SESSION_COOKIE = "wos-session";

export type PlotAuthSession = {
  user: {
    id: string;
    email: string;
    name: string | null;
    image: string | null;
  };
  accessToken: string;
  organizationId?: string;
} | null;

/**
 * Reads the AuthKit session that the WorkOS proxy attached to this request.
 * The sealed browser cookie is intentionally never forwarded to Kotlin and
 * the API receives only the short-lived WorkOS access token.
 */
export async function fetchPlotAuthSession(): Promise<PlotAuthSession> {
  try {
    const session = await withAuth();
    if (!session.user || !session.accessToken) return null;
    return {
      user: {
        id: session.user.id,
        email: session.user.email,
        name: session.user.name,
        image: session.user.profilePictureUrl,
      },
      accessToken: session.accessToken,
      ...(session.organizationId ? { organizationId: session.organizationId } : {}),
    };
  } catch {
    return null;
  }
}
