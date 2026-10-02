"use client";
// AI Elements conversation registry snapshot 2026-10-01; scroll/content subset only.
import {useEffect, type ComponentProps} from "react";
import {StickToBottom, useStickToBottomContext} from "use-stick-to-bottom";
import {cn} from "@/lib/utils";
export type ConversationProps = ComponentProps<typeof StickToBottom>;

export const Conversation = ({ className, ...props }: ConversationProps) => (
  <StickToBottom
    className={cn("relative flex-1 overflow-y-hidden", className)}
    initial="instant"
    resize="instant"
    role="log"
    aria-live="polite"
    {...props}
  />
);

export type ConversationContentProps = ComponentProps<
  typeof StickToBottom.Content
>;

// Focus the library's actual scroll owner without adding another scroll container.
export const ConversationContent = ({className,...props}: ConversationContentProps) => {
  const {scrollRef}=useStickToBottomContext();
  useEffect(()=>{
    const element=scrollRef.current;
    if (!element) return;
    element.tabIndex=0;
    element.setAttribute("aria-label","Chat messages");
    element.style.scrollbarGutter="auto";
  },[scrollRef]);
  return <StickToBottom.Content className={cn("flex flex-col gap-4 px-3 py-2",className)} {...props}/>;
};
