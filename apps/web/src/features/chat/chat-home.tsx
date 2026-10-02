"use client";

import { useRef, useState } from "react";

import type { ChatModel, ChatReasoningEffort } from "@plot/api-client";
import { ChatComposer } from "@/features/chat/chat-composer";
import {
  isNonRetryableRequestError,
  messageFor,
  pendingAgentRequestKey,
  type PendingAgentRequest,
} from "@/features/chat/chat-workspace-utils";
import { plotApiClient } from "@/lib/api-client";
import { useWorkspaceEntitlement } from "@/lib/use-workspace-entitlement";

export function ChatHome() {
  const [startError, setStartError] = useState("");
  const [starting, setStarting] = useState(false);
  const pendingRequestRef = useRef<PendingAgentRequest | null>(null);
  const entitlement = useWorkspaceEntitlement();
  const canGenerate = entitlement?.capabilities.generate ?? true;

  async function submitHomeRequest(
    message: string,
    skillIds: string[] = [],
    model: ChatModel = "auto",
    reasoningEffort: ChatReasoningEffort = "medium",
  ) {
    setStarting(true);
    setStartError("");
    const idempotencyKey = pendingAgentRequestKey(
      pendingRequestRef,
      message,
      [],
      JSON.stringify({ skillIds, model, reasoningEffort }),
    );
    try {
      const run = await plotApiClient.createChatAgentRun({
        instruction: message,
        writingBlockIds: [],
        skillIds,
        model,
        reasoningEffort,
      }, idempotencyKey);
      pendingRequestRef.current = null;
      window.location.assign(`/chat?chat=${encodeURIComponent(run.chatId)}&agent=${encodeURIComponent(run.id)}`);
    } catch (error) {
      if (isNonRetryableRequestError(error)) pendingRequestRef.current = null;
      setStartError(messageFor(error, "The request could not be started. Try again."));
      setStarting(false);
    }
  }

  return (
    <div className="flex min-h-dvh flex-col items-center justify-center bg-white px-4 pb-20 pt-8 dark:bg-[#111113]">
      <div className="w-full max-w-[660px]">
        <h1 className="mb-7 text-center text-[26px] font-semibold tracking-tight text-black/90 dark:text-white/92 sm:text-[28px]">
          What can Plot help with?
        </h1>
        <ChatComposer
          variant="center"
          placeholder="Ask a question or create content..."
          onSubmit={(message, skills, model, effort) => void submitHomeRequest(message, skills, model, effort)}
          busy={starting}
          canGenerate={canGenerate}
        />
        {startError ? <ErrorNotice message={startError} /> : null}
		{!canGenerate ? (
          <p className="mt-3 text-center text-xs text-black/50 dark:text-white/50">
				{entitlement?.accessMode === "complete_only"
					? "New AI responses are paused. Open an existing artifact to edit, export, or publish."
					: "This workspace cannot start new responses. You can still export existing artifacts."}
          </p>
        ) : null}
      </div>
    </div>
  );
}

function ErrorNotice({ message }: { message: string }) {
  return <div role="alert" className="mt-4 rounded-xl border border-rose-300/60 bg-rose-50 px-4 py-3 text-sm text-rose-900 dark:border-rose-400/25 dark:bg-rose-400/[0.08] dark:text-rose-200">{message}</div>;
}
