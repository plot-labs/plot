"use client";
// AI Elements tool registry snapshot 2026-10-01; static single-call header subset.
// Plot has no expandable tool parameters/results; omit trigger, badge and code block.
import type {ComponentProps} from "react";
import {Check, LoaderCircle, X} from "lucide-react";
import {Collapsible} from "@/components/ui/collapsible";
import {cn} from "@/lib/utils";
export const Tool = ({className,...props}:ComponentProps<typeof Collapsible>) => <Collapsible className={cn("mt-2",className)} {...props}/>;
export type ToolState = "input-streaming" | "input-available" | "output-available" | "output-error";
export function ToolHeader({title,state,errorText}:{title:string;state:ToolState;errorText?:string}) {
 const pending=state==="input-streaming"||state==="input-available";
 return <div data-tool-state={state} className="flex min-h-6 items-center gap-1.5 py-0.5 text-[12px] leading-[20px] text-[light-dark(#4e606f,#aaafb5)]">
  <span title={errorText} className={cn("relative flex size-4 shrink-0 items-center justify-center rounded-full",pending?"text-[light-dark(#4e606f,#aaafb5)]":state==="output-error"?"text-[#ef4444]":"text-[#0d8626]")}>
   {pending?<LoaderCircle className="size-4 animate-spin"/>:<><span className="absolute inset-0 rounded-full bg-current opacity-15"/>{state==="output-error"?<X className="relative size-3"/>:<Check className="relative size-3"/>}</>}
  </span><span className="[font-family:SF_Mono,Monaco,Consolas,monospace] font-medium">{title}</span>
  <span className="sr-only">{state==="input-streaming"?"Pending":state==="input-available"?"Running":state==="output-error"?"Error":"Completed"}{errorText?`: ${errorText}`:""}</span>
 </div>;
}
