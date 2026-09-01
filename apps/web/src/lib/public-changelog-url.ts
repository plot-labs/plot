export const PUBLIC_CHANGELOG_ORIGIN = "https://www.useplot.xyz";

const PUBLIC_CHANGELOG_PATH = /^\/[^/]+\/changelog(?:\/|$)/;

export function isPublicChangelogPath(pathname: string): boolean {
  return PUBLIC_CHANGELOG_PATH.test(pathname);
}

export function publicChangelogPath(workspaceSlug: string): string {
  return `/${workspaceSlug}/changelog`;
}

export function publicChangelogEntryPath(workspaceSlug: string, entrySlug: string): string {
  return `/${workspaceSlug}/changelog/${entrySlug}`;
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
