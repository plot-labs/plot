const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export const WAITLIST_ROLES = [
  { value: "", label: "Role (optional)" },
  { value: "founder", label: "Founder" },
  { value: "engineering", label: "Engineering" },
  { value: "product", label: "Product" },
  { value: "devrel", label: "DevRel" },
  { value: "other", label: "Other" },
] as const;

export type WaitlistRole = (typeof WAITLIST_ROLES)[number]["value"];

export type WaitlistPayload = {
  email: string;
  role?: WaitlistRole;
  company?: string;
  website?: string;
};

export function parseWaitlistPayload(body: unknown): WaitlistPayload | null {
  if (!body || typeof body !== "object") return null;

  const record = body as Record<string, unknown>;
  const email = typeof record.email === "string" ? record.email.trim().toLowerCase() : "";
  const role = typeof record.role === "string" ? record.role.trim() : "";
  const company = typeof record.company === "string" ? record.company.trim() : "";
  const website = typeof record.website === "string" ? record.website.trim() : "";

  if (!EMAIL_PATTERN.test(email) || email.length > 254) return null;

  const validRole = WAITLIST_ROLES.some((item) => item.value === role && role !== "");
  if (role && !validRole) return null;

  return {
    email,
    role: validRole ? (role as WaitlistRole) : undefined,
    company: company || undefined,
    website: website || undefined,
  };
}

export function roleLabel(role?: WaitlistRole) {
  return WAITLIST_ROLES.find((item) => item.value === role)?.label;
}