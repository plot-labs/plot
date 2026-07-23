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

export const WAITLIST_PAIN_CHANNELS = [
  { value: "docs", label: "Docs" },
  { value: "changelog", label: "Changelog / release notes" },
  { value: "customer_updates", label: "Customer updates" },
  { value: "launch_social", label: "Launch / social" },
  { value: "other", label: "Other" },
] as const;

export type WaitlistPainChannel = (typeof WAITLIST_PAIN_CHANNELS)[number]["value"];

export type WaitlistPayload = {
  email: string;
  role?: WaitlistRole;
  painChannel: WaitlistPainChannel;
  company?: string;
  website?: string;
};

export function parseWaitlistPayload(body: unknown): WaitlistPayload | null {
  if (!body || typeof body !== "object") return null;

  const record = body as Record<string, unknown>;
  const email = typeof record.email === "string" ? record.email.trim().toLowerCase() : "";
  const role = typeof record.role === "string" ? record.role.trim() : "";
  const painChannel =
    typeof record.painChannel === "string" ? record.painChannel.trim() : "";
  const company = typeof record.company === "string" ? record.company.trim() : "";
  const website = typeof record.website === "string" ? record.website.trim() : "";

  if (!EMAIL_PATTERN.test(email) || email.length > 254) return null;

  const validRole = WAITLIST_ROLES.some((item) => item.value === role && role !== "");
  if (role && !validRole) return null;

  const validPain = WAITLIST_PAIN_CHANNELS.some((item) => item.value === painChannel);
  if (!validPain) return null;

  return {
    email,
    role: validRole ? (role as WaitlistRole) : undefined,
    painChannel: painChannel as WaitlistPainChannel,
    company: company || undefined,
    website: website || undefined,
  };
}

export function roleLabel(role?: WaitlistRole) {
  return WAITLIST_ROLES.find((item) => item.value === role)?.label;
}
