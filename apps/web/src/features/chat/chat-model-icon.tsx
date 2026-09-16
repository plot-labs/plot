import Image from "next/image";

export type ChatModelProvider = "auto" | "anthropic" | "openai";

function PlotAiIcon({ className }: { className?: string }) {
  return <Image src="/plot-icon.svg" alt="" width={16} height={16} className={`${className ?? ""} object-contain dark:invert`} />;
}

export function ChatModelIcon({ provider, className }: { provider: ChatModelProvider; className?: string }) {
  if (provider === "auto") return <PlotAiIcon className={className} />;

  return (
    <Image
      src={provider === "openai" ? "/icons/models/openai.svg" : "/icons/models/claude.svg"}
      alt=""
      width={16}
      height={16}
      className={`${className ?? ""} object-contain ${provider === "openai" ? "dark:invert" : ""}`}
    />
  );
}
