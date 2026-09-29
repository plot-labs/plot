import Link from "next/link";
import type { ComponentProps } from "react";
import { Add01Icon } from "@hugeicons/core-free-icons";
import { HugeiconsIcon } from "@hugeicons/react";

const className = "inline-flex h-8.5 shrink-0 items-center gap-1.5 rounded-full px-3.5 text-[12px] font-semibold text-white transition-all duration-200 hover:opacity-90 active:scale-[0.98] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/20 disabled:cursor-not-allowed disabled:opacity-40";
const style = {
  background: "linear-gradient(to bottom, rgba(0, 0, 0, 0.78), rgba(0, 0, 0, 0.88))",
  backdropFilter: "saturate(200%) blur(40px)",
  WebkitBackdropFilter: "saturate(200%) blur(40px)",
  border: "1px solid rgba(255, 255, 255, 0.18)",
  boxShadow: "inset 0 1px 1px rgba(255, 255, 255, 0.25), inset 0 -1px 1px rgba(0, 0, 0, 0.1), 0 8px 24px rgba(0, 0, 0, 0.12), 0 2px 6px rgba(0, 0, 0, 0.08)",
  color: "#FFFFFF",
};

type Props =
  | ({ href: string } & Omit<ComponentProps<typeof Link>, "href" | "children" | "className" | "style">)
  | ({ href?: never } & Omit<ComponentProps<"button">, "children" | "className" | "style">);

export function WorkspaceCreateAction(props: Props) {
  const children = <><HugeiconsIcon icon={Add01Icon} size={15} color="currentColor" strokeWidth={1.5} aria-hidden="true" />Create</>;
  if (props.href !== undefined) {
    return <Link {...props} className={className} style={style}>{children}</Link>;
  }
  return <button type="button" {...props} className={className} style={style}>{children}</button>;
}
