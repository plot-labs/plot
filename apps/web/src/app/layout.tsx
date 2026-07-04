import type { Metadata } from "next";
import { Instrument_Sans, Instrument_Serif, JetBrains_Mono } from "next/font/google";
import "./globals.css";

const instrumentSans = Instrument_Sans({
  subsets: ["latin"],
  variable: "--font-instrument",
});

const instrumentSerif = Instrument_Serif({
  subsets: ["latin"],
  weight: "400",
  variable: "--font-instrument-serif",
});

const jetbrainsMono = JetBrains_Mono({
  subsets: ["latin"],
  variable: "--font-jetbrains",
});

export const metadata: Metadata = {
  metadataBase: new URL("https://useplot.xyz"),
  title: "Plot — Autonomous update packs from shipped work",
  description:
    "For AI and devtool teams shipping every week. Plot turns merged PRs, releases, and issues into source-backed, on-style changelogs, docs updates, customer updates, and launch drafts — with human approval before publish.",
  openGraph: {
    title: "Plot — Ship fast. Write less. Stay source-backed.",
    description:
      "Autonomous update packs from your repo. Docs, changelogs, and launch copy in your team's voice.",
    url: "https://useplot.xyz",
    siteName: "Plot",
    type: "website",
  },
  twitter: {
    card: "summary_large_image",
    title: "Plot — Autonomous update packs from shipped work",
    description:
      "Turn merged work into source-backed update packs your team approves.",
  },
  icons: {
    icon: "/plot-favicon.svg",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="en"
      className={`${instrumentSans.variable} ${instrumentSerif.variable} ${jetbrainsMono.variable} h-full antialiased`}
    >
      <body className="min-h-full">{children}</body>
    </html>
  );
}
