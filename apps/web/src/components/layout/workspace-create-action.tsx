import Link from "next/link";
import type { ComponentProps } from "react";
import { Add01Icon } from "@hugeicons/core-free-icons";
import { HugeiconsIcon } from "@hugeicons/react";

const className = "glass-button glass-primary shrink-0";

type Props =
  | ({ href: string } & Omit<ComponentProps<typeof Link>, "href" | "children" | "className" | "style">)
  | ({ href?: never } & Omit<ComponentProps<"button">, "children" | "className" | "style">);

export function WorkspaceCreateAction(props: Props) {
  const children = <><HugeiconsIcon icon={Add01Icon} size={15} color="currentColor" strokeWidth={1.5} aria-hidden="true" />Create</>;
  if (props.href !== undefined) {
    return <Link {...props} className={className}>{children}</Link>;
  }
  return <button type="button" {...props} className={className}>{children}</button>;
}
