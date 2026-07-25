import type { Metadata } from "next";

import { LegalPage } from "@/components/legal/legal-page";

export const metadata: Metadata = {
  title: "Privacy Policy — Plot",
  description: "How Plot collects, uses, and protects personal information.",
};

const sections = [
  {
    title: "Information we collect",
    items: [
      "Account information received through GitHub, including your name, email address, avatar, and GitHub account identifier.",
      "Authentication and security information, such as session records, IP address, browser information, and sign-in timestamps.",
      "Workspace content and source data you choose to connect to Plot, including repository, release, issue, and writing-style information.",
      "Waitlist and support information you submit, such as your email address, role, product interests, and messages.",
    ],
  },
  {
    title: "How we use information",
    items: [
      "Provide, secure, and maintain Plot and authenticate approved accounts.",
      "Prepare source-backed update drafts and operate connected workspace features.",
      "Respond to support requests and communicate about early access or service changes.",
      "Diagnose problems, prevent misuse, and improve service reliability.",
    ],
  },
  {
    title: "Service providers",
    paragraphs: [
      "We may share information with providers that help us operate Plot, such as GitHub for authentication and connected sources, Resend for waitlist email, and our hosting and database providers. They receive only the information needed to perform services for us.",
    ],
  },
  {
    title: "Retention and security",
    paragraphs: [
      "We retain information for as long as needed to provide Plot, meet security and operational requirements, or resolve disputes. We use reasonable administrative and technical safeguards, but no online service can guarantee absolute security.",
    ],
  },
  {
    title: "Your choices",
    paragraphs: [
      "You may ask to access, correct, or delete personal information associated with Plot, subject to legal and security requirements. You may also ask us to remove you from the waitlist by email.",
    ],
  },
  {
    title: "Contact",
    paragraphs: [
      "For privacy questions or requests, email hello@useplot.xyz. We may update this policy as Plot changes and will revise the date shown above when we do.",
    ],
  },
];

export default function PrivacyPage() {
  return (
    <LegalPage
      eyebrow="Legal"
      title="Privacy Policy"
      updatedAt="July 25, 2026"
      introduction="This policy explains what information Plot collects, why we use it, and the choices available to you when you use our website and service."
      sections={sections}
    />
  );
}
