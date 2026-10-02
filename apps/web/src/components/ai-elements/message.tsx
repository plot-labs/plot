"use client";
// AI Elements message registry snapshot 2026-10-01; text/metadata subset, Plot bubble styles.
import type {ComponentProps, HTMLAttributes} from "react";
import {cn} from "@/lib/utils";
export type MessageProps = HTMLAttributes<HTMLDivElement> & {
  from: "user" | "assistant" | "system";
};

export const Message = ({ className, from, ...props }: MessageProps) => (
  <div
    data-message-from={from}
    aria-label={`Message from ${from}`}
    className={cn(
      "group flex w-full max-w-full flex-col",
      from === "user" ? "is-user items-end" : "is-assistant items-start",
      className
    )}
    {...props}
  />
);

export type MessageContentProps = HTMLAttributes<HTMLDivElement>;

export const MessageContent = ({
  children,
  className,
  ...props
}: MessageContentProps) => (
  <div
    className={cn(
      "flex w-fit min-w-0 max-w-full flex-col break-words rounded-[12px] px-4 text-[14px] leading-[1.4286] [font-family:-apple-system,BlinkMacSystemFont,Segoe_UI,Roboto,Helvetica,Arial,sans-serif]",
      "group-[.is-user]:bg-[light-dark(rgba(5,54,89,0.1),rgba(223,226,229,0.2))] group-[.is-user]:py-3 group-[.is-user]:text-[light-dark(#0a1317,#dfe2e5)]",
      className
    )}
    {...props}
  >
    {children}
  </div>
);

export type MessageActionsProps = ComponentProps<"div">;

export const MessageActions = ({
  className,
  children,
  ...props
}: MessageActionsProps) => (
  <div className={cn("flex items-center gap-1", className)} {...props}>
    {children}
  </div>
);

