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
  metadataBase: new URL("https://www.useplot.xyz"),
  title: "Plot — Cited changelogs from shipped work",
  description:
    "Plot turns release evidence into a source-cited changelog your team can review and publish to a hosted page. Changelog is the first customer content output.",
  openGraph: {
    title: "Plot — Ship fast. Write less. Stay source-backed.",
    description:
      "Release evidence to cited changelog to human review to publish. Changelog is where Plot starts.",
    url: "https://www.useplot.xyz",
    siteName: "Plot",
    type: "website",
    images: [
      {
        url: "/og-image.png",
        width: 1200,
        height: 630,
        alt: "Plot turns shipped work into cited changelogs ready for review and publish.",
      },
    ],
  },
  twitter: {
    card: "summary_large_image",
    title: "Plot — Cited changelogs from shipped work",
    description:
      "Release evidence to cited changelog to human review to publish.",
    images: ["/og-image.png"],
  },
  icons: {
    icon:
      process.env.VERCEL_ENV === "production" ? "/plot-favicon.svg" : "/plot-logo-favicon-non-prod.png",
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
      data-theme="light"
      className={`${instrumentSans.variable} ${instrumentSerif.variable} ${jetbrainsMono.variable} h-full antialiased`}
    >
      <body className="min-h-full">{children}</body>
    </html>
  );
}
