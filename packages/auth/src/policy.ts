export function normalizeEmail(value: string): string {
  return value.trim().toLowerCase();
}

export function parseAllowedEmails(value: string | undefined): Set<string> {
  return new Set(
    (value ?? "")
      .split(/[\s,]+/)
      .map(normalizeEmail)
      .filter(Boolean),
  );
}

export function isAllowedEmail(email: string, allowedEmails: Set<string>): boolean {
  return allowedEmails.has(normalizeEmail(email));
}

export function assertAllowedEmail(email: string, allowedEmails: Set<string>, options: { production?: boolean } = {}): void {
  if (options.production && allowedEmails.size === 0) {
    throw new Error("AUTH_ALLOWED_EMAILS must contain at least one address in production");
  }
  if (!isAllowedEmail(email, allowedEmails)) {
    throw new Error("Access denied");
  }
}

export function isUuid(value: string | null | undefined): boolean {
  return Boolean(value && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value));
}
