import { Resend } from "resend";
import { NextResponse } from "next/server";

import { parseWaitlistPayload, roleLabel } from "@/lib/waitlist";

const resend = process.env.RESEND_API_KEY ? new Resend(process.env.RESEND_API_KEY) : null;

export async function POST(request: Request) {
  if (!resend || !process.env.RESEND_API_KEY) {
    return NextResponse.json(
      { error: "Waitlist is not configured yet." },
      { status: 503 },
    );
  }

  let body: unknown;

  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ error: "Invalid request body." }, { status: 400 });
  }

  const payload = parseWaitlistPayload(body);

  if (!payload) {
    return NextResponse.json({ error: "Enter a valid email address." }, { status: 400 });
  }

  if (payload.website) {
    return NextResponse.json({ ok: true });
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
        "Plot prepares source-backed, on-style update packs from shipped work — docs, release notes, customer updates, and launch drafts — with human approval before anything goes out.",
        "",
        "We'll reach out as early access opens.",
      ].join("\n"),
    });
  }

  return NextResponse.json({ ok: true, id: data?.id });
}