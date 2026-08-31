import { Shield, Lock, Eye, FileCheck } from "lucide-react";

const securityFeatures = [
  {
    icon: Shield,
    title: "Workspace boundaries",
    description: "The connected repository, release boundary, changelog, citations, and delivery history stay inside the selected workspace.",
  },
  {
    icon: Lock,
    title: "Selected GitHub access",
    description: "Plot reads the one GitHub repository the workspace owner connects and does not broaden access on its own.",
  },
  {
    icon: Eye,
    title: "Source citations",
    description: "Important changelog statements stay linked to saved source evidence so you can inspect why the draft says what it says.",
  },
  {
    icon: FileCheck,
    title: "Human-controlled handoff",
    description: "You choose when to publish. Plot prepares the draft; you approve before it goes live.",
  },
];

const certifications = ["SOURCE-CITED", "SELECTED-REPO", "WORKSPACE-SCOPED", "HUMAN REVIEW", "NO AUTO-PUBLISH"];

export function SecuritySection() {
  return (
    <section id="security" className="relative py-24 lg:py-32 bg-foreground/[0.02] overflow-hidden">
      <div className="max-w-[1400px] mx-auto px-6 lg:px-12">
        <div className="grid lg:grid-cols-2 gap-16 lg:gap-24">
          {/* Left: Content */}
          <div className="landing-reveal">
            <span className="inline-flex items-center gap-3 text-sm font-mono text-muted-foreground mb-6">
              <span className="w-8 h-px bg-foreground/30" />
              Safety
            </span>
            <h2 className="text-4xl lg:text-6xl font-display tracking-tight mb-8">
              Citations beat
              <br />
              confident fiction.
            </h2>
            <p className="text-xl text-muted-foreground leading-relaxed mb-12">
              Plot should not invent a product story. It shows which saved
              sources support a changelog draft and leaves review and publish
              decisions with you outside Plot.
            </p>

            {/* Certifications */}
            <div className="flex flex-wrap gap-3">
              {certifications.map((cert) => (
                <span
                  key={cert}
                  className="px-4 py-2 border border-foreground/10 text-sm font-mono"
                >
                  {cert}
                </span>
              ))}
            </div>
          </div>

          {/* Right: Features */}
          <div className="grid gap-6">
            {securityFeatures.map((feature) => (
              <div
                key={feature.title}
                className="landing-reveal p-6 border border-foreground/10 hover:border-foreground/20 transition-colors duration-300 group"
              >
                <div className="flex items-start gap-4">
                  <div className="shrink-0 w-10 h-10 flex items-center justify-center border border-foreground/10 group-hover:bg-foreground group-hover:text-background transition-colors duration-300">
                    <feature.icon className="w-5 h-5" />
                  </div>
                  <div>
                    <h3 className="text-lg font-medium mb-1 group-hover:translate-x-1 transition-transform duration-300">
                      {feature.title}
                    </h3>
                    <p className="text-muted-foreground">{feature.description}</p>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}
