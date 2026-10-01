"use client";
// AI Elements inline-citation registry snapshot 2026-10-01; inline text subset.
// Existing click dialogs own navigation/focus; no hover-only/carousel dependencies.
import type {ComponentProps} from "react";
import {cn} from "@/lib/utils";
export type InlineCitationProps = ComponentProps<"span">;

export const InlineCitation = ({
  className,
  ...props
}: InlineCitationProps) => (
  <span
    className={cn("group inline items-center gap-1", className)}
    {...props}
  />
);

export type InlineCitationTextProps = ComponentProps<"span">;

export const InlineCitationText = ({
  className,
  ...props
}: InlineCitationTextProps) => (
  <span
    className={cn("transition-colors group-hover:bg-accent", className)}
    {...props}
  />
);

