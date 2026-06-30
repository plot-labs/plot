import { Navigation } from "@/components/landing/navigation";
import { HeroSection } from "@/components/landing/hero-section";
import { FeaturesSection } from "@/components/landing/features-section";
import { StyleSection } from "@/components/landing/style-section";
import { HowItWorksSection } from "@/components/landing/how-it-works-section";
import { SecuritySection } from "@/components/landing/security-section";
import { PricingSection } from "@/components/landing/pricing-section";
import { CtaSection } from "@/components/landing/cta-section";
import { FooterSection } from "@/components/landing/footer-section";

export default function Home() {
  return (
    <main className="relative min-h-screen overflow-x-hidden noise-overlay">
      <Navigation />
      <HeroSection />
      <FeaturesSection />
      <StyleSection />
      <HowItWorksSection />
      <SecuritySection />
      <PricingSection />
      <CtaSection />
      <FooterSection />
    </main>
  );
}
