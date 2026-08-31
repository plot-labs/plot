export const PUBLIC_CHANGELOG_ORIGIN = "https://www.useplot.xyz";

export function publicChangelogPath(workspaceSlug: string): string {
  return `/changelog/${workspaceSlug}`;
}

export function resolvePublicOrigin(): string {
  if (typeof window !== "undefined") {
    const { hostname, origin } = window.location;
    if (hostname === "localhost" || hostname === "127.0.0.1") {
      return origin;
    }
  }
  return PUBLIC_CHANGELOG_ORIGIN;
}

export function publicChangelogUrl(workspaceSlug: string): string {
  return `${resolvePublicOrigin()}${publicChangelogPath(workspaceSlug)}`;
}

export function publicChangelogEntryUrl(publicPath: string): string {
  const path = publicPath.startsWith("/") ? publicPath : `/${publicPath}`;
  return `${resolvePublicOrigin()}${path}`;
}
