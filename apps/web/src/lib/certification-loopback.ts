const loopbackHost = /^(?:127\.0\.0\.1|localhost)(?::([0-9]{1,5}))?$/i;
const loopbackIpv6Host = /^\[::1\](?::([0-9]{1,5}))?$/i;

export function isCertificationLoopbackAuthority(host: string | null): boolean {
  if (!host) return false;
  const match = host.match(loopbackHost) ?? host.match(loopbackIpv6Host);
  if (!match) return false;
  const port = match[1];
  return port === undefined || (Number.isInteger(Number(port)) && Number(port) >= 1 && Number(port) <= 65_535);
}

export function isCertificationLoopbackRequest(input: { url?: string; headers: Headers }): boolean {
  if (process.env.PLOT_CERTIFICATION_LOOPBACK_GUARD !== "true") return false;
  const authority = input.headers.get("host") ?? (input.url ? new URL(input.url).host : null);
  const forwardedHost = input.headers.get("x-forwarded-host");
  return isCertificationLoopbackAuthority(authority) &&
    (forwardedHost === null || forwardedHost.split(",").every((value) => isCertificationLoopbackAuthority(value.trim())));
}
