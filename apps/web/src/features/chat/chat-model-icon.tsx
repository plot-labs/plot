import Image from "next/image";

export type ChatModelProvider = "auto" | "anthropic" | "openai" | "google" | "deepseek" | "xai" | "qwen";

const MODEL_ICON_SOURCES: Record<Exclude<ChatModelProvider, "auto">, string> = {
  anthropic: "/icons/models/claude.svg",
  openai: "/icons/models/openai.svg",
  google: "/icons/models/gemini.svg",
  deepseek: "/icons/models/deepseek.svg",
  xai: "/icons/models/grok.svg",
  qwen: "/icons/models/qwen.svg",
};

function PlotAiIcon({ className }: { className?: string }) {
  return <Image src="/plot-icon.svg" alt="" width={16} height={16} className={`${className ?? ""} object-contain dark:invert`} />;
}

export function ChatModelIcon({ provider, className }: { provider: ChatModelProvider; className?: string }) {
  if (provider === "auto") return <PlotAiIcon className={className} />;

  return (
    <Image
      src={MODEL_ICON_SOURCES[provider]}
      alt=""
      width={16}
      height={16}
      className={`${className ?? ""} object-contain ${provider === "openai" || provider === "xai" ? "dark:invert" : ""}`}
    />
  );
}
