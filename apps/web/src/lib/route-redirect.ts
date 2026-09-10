export type RouteSearchParams = Record<string, string | string[] | undefined>;

export type LegacyRouteProps = {
  searchParams: Promise<RouteSearchParams>;
};

export function buildRedirectPath(pathname: string, searchParams: RouteSearchParams) {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(searchParams)) {
    if (typeof value === "string") {
      query.set(key, value);
    } else {
      value?.forEach((item) => query.append(key, item));
    }
  }
  const serialized = query.toString();
  return serialized ? `${pathname}?${serialized}` : pathname;
}
