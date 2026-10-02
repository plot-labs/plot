"use client";
// AI Elements sources registry snapshot 2026-10-01; retain Plot's always-visible list.
// Source URLs pass Plot's existing trust-boundary validation; URL-less items are text.
import type {ComponentProps} from "react";
import {cn} from "@/lib/utils";
import {isSafeHttpUrl} from "@/lib/safe-url";
export const Sources = ({className,...props}:ComponentProps<"div">) => <div className={cn("not-prose text-xs",className)} {...props}/>;
export function Source({href,title,children,className,...props}:ComponentProps<"a">) {
 const classes=cn("inline-flex h-5 max-w-[15em] items-center gap-1 overflow-hidden rounded-[8px] border border-[light-dark(#05365919,#f2f4f619)] px-2 text-[12px] leading-[1.6667] text-[light-dark(#4e606f,#aaafb5)] no-underline",className);
 const body=children??<span className="min-w-0 truncate">{title}</span>;
 return isSafeHttpUrl(href)?<a {...props} href={href} title={title} target="_blank" rel="noopener noreferrer" className={classes}>{body}</a>:<span aria-label={props["aria-label"]} title={title} className={classes}>{body}</span>;
}
