"use client";

import Link from "next/link";
import { ArrowLeft } from "lucide-react";
import { useEffect, useId, useRef, useState } from "react";

import type { ChatModel, ChatReasoningEffort, SourceReference } from "@plot/api-client";
import {
  WorkspaceErrorNotice,
  WorkspaceHeader,
  workspacePageClass,
  workspaceSectionClass,
} from "@/components/layout/workspace-page";
import { ChatComposer } from "@/features/chat/chat-composer";
import {
  chatHref,
  isNonRetryableRequestError,
  messageFor,
  pendingAgentRequestKey,
  type PendingAgentRequest,
} from "@/features/chat/chat-workspace-utils";
import { ContentSourcePicker } from "@/features/content-sources/content-source-picker";
import { plotApiClient } from "@/lib/api-client";
import { useWorkspaceEntitlement } from "@/lib/use-workspace-entitlement";
import { useWorkspaceChanged } from "@/lib/workspace-changed";

type SourceLoad =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; sources: SourceReference[] };

/**
 * Starts a new draft from Contents. The reader describes what to write, and
 * Plot looks for the relevant changes itself, the same way a request started
 * from Chat does. A reader who wants to steer the draft can open the change
 * list and choose which imported changes it is written from.
 */
export function CreateContentWorkspace() {
  const [pickerOpen, setPickerOpen] = useState(false);
  const [load, setLoad] = useState<SourceLoad>({ status: "idle" });
  const [reloadNonce, setReloadNonce] = useState(0);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [starting, setStarting] = useState(false);
  const [startError, setStartError] = useState("");
  const pendingRequestRef = useRef<PendingAgentRequest | null>(null);
  const pickerId = useId();
  const entitlement = useWorkspaceEntitlement();
  const canGenerate = entitlement?.capabilities.generate ?? true;

  useWorkspaceChanged(() => {
    setPickerOpen(false);
    setLoad({ status: "idle" });
    setSelectedIds([]);
    setStartError("");
    pendingRequestRef.current = null;
  });

  // The change list is only fetched once the reader asks for it: most drafts
  // start without it, and it carries every imported change.
  const shouldLoad = pickerOpen && load.status === "loading";
  useEffect(() => {
    if (!shouldLoad) return;
    const controller = new AbortController();
    plotApiClient.listSourceReferences({ signal: controller.signal })
      .then((sources) => {
        if (!controller.signal.aborted) setLoad({ status: "ready", sources });
      })
      .catch(() => {
        if (!controller.signal.aborted) setLoad({ status: "error" });
      });
    return () => controller.abort();
  }, [shouldLoad, reloadNonce]);

  function togglePicker() {
    if (!pickerOpen && load.status === "idle") setLoad({ status: "loading" });
    setPickerOpen((open) => !open);
  }

  async function startDraft(
    instruction: string,
    skillIds: string[],
    model: ChatModel,
    reasoningEffort: ChatReasoningEffort,
  ) {
    setStarting(true);
    setStartError("");
    const writingBlockIds = [...selectedIds];
    const idempotencyKey = pendingAgentRequestKey(
      pendingRequestRef,
      instruction,
      writingBlockIds,
      JSON.stringify({ skillIds, model, reasoningEffort }),
    );
    try {
      const run = await plotApiClient.createChatAgentRun({
        instruction,
        writingBlockIds,
        skillIds,
        model,
        reasoningEffort,
      }, idempotencyKey);
      pendingRequestRef.current = null;
      window.location.assign(chatHref(run.chatId, run.id));
    } catch (error) {
      if (isNonRetryableRequestError(error)) pendingRequestRef.current = null;
      setStartError(messageFor(error, "The draft could not be started. Try again."));
      setStarting(false);
    }
  }

  const hasSelection = selectedIds.length > 0;

  return (
    <div className={workspacePageClass}>
      <section className={`${workspaceSectionClass} px-6 pb-12`} aria-labelledby="create-content-heading">
        <WorkspaceHeader
          id="create-content-heading"
          title="Create content"
          description="Describe the update you need. Plot finds the changes to write from."
          variant="standalone"
          actions={
            <Link href="/contents" className="glass-button inline-flex items-center gap-1.5">
              <ArrowLeft className="size-3.5" aria-hidden="true" />
              Contents
            </Link>
          }
        />

        <h2 className="mt-9 text-[13px] font-medium text-black/70 dark:text-white/72">What should Plot write?</h2>
        <div className="mt-3">
          <ChatComposer
            variant="center"
            placeholder={hasSelection ? "For example: write a changelog entry for these changes" : "Describe the update you need..."}
            onSubmit={(message, skills, model, effort) => void startDraft(message, skills, model, effort)}
            busy={starting}
            canGenerate={canGenerate}
          />
        </div>
        {startError ? (
          <div role="alert" className="mt-4 rounded-xl border border-rose-300/60 bg-rose-50 px-4 py-3 text-sm text-rose-900 dark:border-rose-400/25 dark:bg-rose-400/[0.08] dark:text-rose-200">
            {startError}
          </div>
        ) : null}
        {!canGenerate ? (
          <p className="mt-3 text-center text-xs text-black/50 dark:text-white/50">
            {entitlement?.accessMode === "complete_only"
              ? "New AI responses are paused. Open existing content to edit, export, or publish."
              : "This workspace cannot start new responses. You can still export existing content."}
          </p>
        ) : null}

        <div className="glass-card mt-4 overflow-hidden rounded-[14px] border border-black/[0.09] dark:border-white/10">
          <div className="flex flex-wrap items-center justify-between gap-3 px-5 py-3.5">
            <div className="min-w-0 flex-1 basis-64">
              <h2 className="text-[13px] font-medium text-black/85 dark:text-white/88">Changes</h2>
              <p aria-live="polite" className="mt-0.5 text-[12px] leading-[18px] text-black/50 dark:text-white/52">
                {hasSelection
                  ? `Plot will write from the ${selectedIds.length === 1 ? "change" : `${selectedIds.length} changes`} you chose.`
                  : "Automatic. Plot finds the relevant pull requests and commits itself."}
              </p>
            </div>
            <button
              type="button"
              className="glass-button glass-primary shrink-0"
              aria-expanded={pickerOpen}
              aria-controls={pickerId}
              onClick={togglePicker}
            >
              {pickerOpen ? "Hide list" : hasSelection ? "Edit changes" : "Choose changes"}
            </button>
          </div>
          {pickerOpen ? (
            <div id={pickerId} className="border-t border-black/[0.07] dark:border-white/[0.08]">
              {load.status === "ready" && load.sources.length > 0 ? (
                <ContentSourcePicker
                  sources={load.sources}
                  selectedIds={selectedIds}
                  onSelectedIdsChange={setSelectedIds}
                  disabled={starting}
                />
              ) : load.status === "ready" ? (
                <p className="px-5 py-4 text-[12px] leading-5 text-black/58 dark:text-white/60">
                  No imported changes yet. Connect a repository in{" "}
                  <Link href="/settings/integrations" className="underline underline-offset-2">Connections</Link>
                  , or describe the update yourself.
                </p>
              ) : load.status === "error" ? (
                <div className="p-4">
                  <WorkspaceErrorNotice
                    message="Your changes could not be loaded. You can retry, or let Plot find them."
                    onRetry={() => { setLoad({ status: "loading" }); setReloadNonce((value) => value + 1); }}
                  />
                </div>
              ) : (
                <p role="status" className="px-5 py-4 text-[13px] text-black/45 dark:text-white/48">Loading your changes…</p>
              )}
            </div>
          ) : null}
        </div>
      </section>
    </div>
  );
}
