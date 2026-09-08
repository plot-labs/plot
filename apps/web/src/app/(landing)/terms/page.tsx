import type { Metadata } from "next";

import { LegalPage } from "@/components/legal/legal-page";

export const metadata: Metadata = {
  title: "Terms of Service — Plot",
  description: "Terms that apply when using the Plot service.",
};

const sections = [
  {
    title: "Using Plot",
    paragraphs: [
      "You may use Plot only if you can legally enter into these terms and follow applicable laws. Early access may be limited to approved accounts. You are responsible for activity under your account and for keeping access credentials secure.",
    ],
  },
  {
    title: "Connected sources",
    paragraphs: [
      "You may connect repositories and other sources only when you have permission to access and process their content. Your use of third-party services, including GitHub, remains subject to their terms and policies.",
    ],
  },
  {
    title: "Drafts and publishing",
    paragraphs: [
      "Plot prepares drafts and citations from connected sources. Generated output may be incomplete or inaccurate. You are responsible for reviewing, editing, approving, and publishing any output, and for ensuring it does not disclose confidential information or violate another party's rights.",
    ],
  },
  {
    title: "Acceptable use",
    items: [
      "Do not misuse Plot, interfere with the service, bypass access controls, or attempt unauthorized access.",
      "Do not upload or process content you do not have the right to use.",
      "Do not use Plot to violate law, infringe rights, distribute malware, or harm others.",
    ],
  },
  {
    title: "Ownership",
    paragraphs: [
      "You retain ownership of content you provide. You give Plot the limited permission needed to host, process, and transform that content to operate the service. Plot and its software, design, and branding remain owned by Plot and its licensors.",
    ],
  },
  {
    title: "Service changes",
    paragraphs: [
      "Plot may change, suspend, or discontinue features, particularly during early access. We may restrict access when necessary to protect the service, comply with law, or respond to misuse.",
    ],
  },
  {
    title: "Disclaimers and liability",
    paragraphs: [
      "Plot is provided on an “as is” and “as available” basis to the extent permitted by law. We do not guarantee uninterrupted service or error-free output. To the extent permitted by law, Plot is not liable for indirect, incidental, special, consequential, or punitive damages arising from use of the service.",
    ],
  },
  {
    title: "Contact",
    paragraphs: [
      "Questions about these terms may be sent to hello@useplot.xyz. We may update these terms as the service changes and will revise the date shown above.",
    ],
  },
];

export default function TermsPage() {
  return (
    <LegalPage
      eyebrow="Legal"
      title="Terms of Service"
      updatedAt="July 25, 2026"
      introduction="These terms govern access to Plot's website and early-access service. By using Plot, you agree to them."
      sections={sections}
    />
  );
}
