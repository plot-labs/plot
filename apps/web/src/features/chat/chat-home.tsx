"use client";

import Link from "next/link";
import { useRef, useState } from "react";

import type { SourceReference } from "@plot/api-client";
import { ChatComposer } from "@/features/chat/chat-composer";
import {
  isNonRetryableRequestError,
  messageFor,
  pendingAgentRequestKey,
  selectReferences,
  toComposerReferences,
  validateSourceSelection,
  type PendingAgentRequest,
} from "@/features/chat/chat-workspace-utils";
import { plotApiClient } from "@/lib/api-client";

type ChatHomeProps = {
  references: SourceReference[];
  referencesLoading: boolean;
  referencesError: string;
};

export function ChatHome({ references, referencesLoading, referencesError }: ChatHomeProps) {
  const [startError, setStartError] = useState("");
  const [starting, setStarting] = useState(false);
  const pendingRequestRef = useRef<PendingAgentRequest | null>(null);

  async function submitHomeRequest(message: string, referenceIds: string[]) {
    const selected = selectReferences(references, referenceIds);
    const validationError = validateSourceSelection(references, selected, referencesError);
    if (validationError) {
      setStartError(validationError);
      return;
    }

    setStarting(true);
    setStartError("");
    const idempotencyKey = pendingAgentRequestKey(pendingRequestRef, message, selected.map((reference) => reference.id));
    try {
      const run = await plotApiClient.createChatAgentRun({
        instruction: message,
        writingBlockIds: selected.map((reference) => reference.id),
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
          What should Plot create?
        </h1>
        <ChatComposer
          key={references.map((reference) => reference.id).join(":") || "no-references"}
          variant="center"
          placeholder="Describe the update you need..."
          onSubmit={(message, ids) => void submitHomeRequest(message, ids)}
          references={toComposerReferences(references)}
          busy={starting || referencesLoading}
        />
        {referencesLoading ? <p className="mt-3 text-center text-xs text-black/45 dark:text-white/45">Loading sources…</p> : null}
        {!referencesLoading && !referencesError && references.length === 0 ? <SourceEmptyState /> : null}
        {referencesError ? <ErrorNotice message={referencesError} /> : null}
        {startError ? <ErrorNotice message={startError} /> : null}
      </div>
    </div>
  );
}

function SourceEmptyState() {
  return <p className="mt-4 text-center text-xs text-black/50 dark:text-white/50">Connect and import a source in <Link href="/settings/integrations" className="text-[#2563eb] hover:underline dark:text-[#93c5fd]">Integrations</Link> or <Link href="/sources" className="text-[#2563eb] hover:underline dark:text-[#93c5fd]">Sources</Link> before starting a chat.</p>;
}

function ErrorNotice({ message }: { message: string }) {
  return <div role="alert" className="mt-4 rounded-xl border border-rose-300/60 bg-rose-50 px-4 py-3 text-sm text-rose-900 dark:border-rose-400/25 dark:bg-rose-400/[0.08] dark:text-rose-200">{message}</div>;
}
