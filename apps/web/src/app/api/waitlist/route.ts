import { Resend } from "resend";
import { NextResponse } from "next/server";

import { createFixedWindowLimiter } from "@/lib/rate-limit";
import { parseWaitlistPayload, roleLabel } from "@/lib/waitlist";

const resend = process.env.RESEND_API_KEY ? new Resend(process.env.RESEND_API_KEY) : null;

// Every submission creates a third-party contact and a confirmation email,
// so unthrottled posts turn into mail-bombing and Resend quota burn. Limit
// per source IP and per target email (spread-out bombing of one address).
const perIpLimiter = createFixedWindowLimiter(60 * 60 * 1000, 20);
const perEmailLimiter = createFixedWindowLimiter(60 * 60 * 1000, 3);

function clientIp(request: Request): string {
  const forwarded = request.headers.get("x-forwarded-for");
  return forwarded?.split(",")[0]?.trim() || request.headers.get("x-real-ip") || "unknown";
}

export async function POST(request: Request) {
  if (!resend || !process.env.RESEND_API_KEY) {
    return NextResponse.json(
      { error: "Waitlist is not configured yet." },
      { status: 503 },
    );
  }

  const ip = clientIp(request);
  if (perIpLimiter.check(ip)) {
    return NextResponse.json({ error: "Too many requests. Try again later." }, { status: 429 });
  }

  let body: unknown;

  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ error: "Invalid request body." }, { status: 400 });
  }

  const payload = parseWaitlistPayload(body);

  if (!payload) {
    return NextResponse.json(
      { error: "Enter a valid email and choose the most painful update channel." },
      { status: 400 },
    );
  }

  if (payload.website) {
    return NextResponse.json({ ok: true });
  }

  if (perEmailLimiter.check(payload.email.toLowerCase())) {
    return NextResponse.json({ error: "Too many requests. Try again later." }, { status: 429 });
  }

  const role = roleLabel(payload.role);
  const segmentId = process.env.RESEND_WAITLIST_SEGMENT_ID;

  const { data, error } = await resend.contacts.create({
    email: payload.email,
    firstName: role,
    unsubscribed: false,
    ...(segmentId ? { segments: [{ id: segmentId }] } : {}),
  });

  if (error) {
    const message = error.message.toLowerCase();

    if (message.includes("already") || message.includes("exists")) {
      return NextResponse.json({ ok: true, duplicate: true });
    }

    return NextResponse.json({ error: "Could not join the waitlist." }, { status: 502 });
  }

  const fromEmail = process.env.RESEND_FROM_EMAIL;

  if (fromEmail) {
    await resend.emails.send({
      from: fromEmail,
      to: payload.email,
      subject: "You're on the Plot waitlist",
      text: [
        "Thanks for joining the Plot waitlist.",
        "",
        "Plot prepares source-backed, on-style update packs from shipped work — docs, release notes, customer updates, and launch drafts — so you can edit, copy, and publish outside Plot.",
        "",
        "We'll reach out as early access opens.",
      ].join("\n"),
    });
  }

  return NextResponse.json({ ok: true, id: data?.id });
}
