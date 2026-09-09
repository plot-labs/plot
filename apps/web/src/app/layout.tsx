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
  title: "Plot — Make every release land",
  description:
    "Plot identifies the shipped changes customers need to know, prepares a customer-ready changelog, and keeps your team in control of publication.",
  openGraph: {
    title: "Plot — Make every release land with customers",
    description:
      "Turn shipped work into customer updates people notice and understand.",
    url: "https://www.useplot.xyz",
    siteName: "Plot",
    type: "website",
    images: [
      {
        url: "/og-image.png",
        width: 1200,
        height: 630,
        alt: "Plot turns shipped work into customer-ready changelogs.",
      },
    ],
  },
  twitter: {
    card: "summary_large_image",
    title: "Plot — Make every release land",
    description:
      "Turn shipped work into customer updates people notice and understand.",
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
