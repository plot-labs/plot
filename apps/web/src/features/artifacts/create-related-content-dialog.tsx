"use client";

import { Check, GitPullRequest, Loader2, Megaphone, ScrollText, X } from "lucide-react";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";

import { PlotApiError, type Artifact, type ContentType, type PlotApiClient } from "@plot/api-client";

type CreateRelatedContentDialogProps = {
  pack: Artifact;
  client: PlotApiClient;
  open: boolean;
  onClose: () => void;
};

export function CreateRelatedContentDialog(props: CreateRelatedContentDialogProps) {
  if (!props.open) return null;
  return <CreateRelatedContentDialogContent key={`${props.pack.id}:${props.pack.contentType}`} {...props} />;
}

function CreateRelatedContentDialogContent({
  pack,
  client,
  open,
  onClose,
}: CreateRelatedContentDialogProps) {
  const router = useRouter();
  const defaultTargetType: ContentType =
    pack.contentType === "CHANGELOG" ? "LAUNCH_ANNOUNCEMENT" : "CHANGELOG";
  const [targetType, setTargetType] = useState<ContentType>(defaultTargetType);
  const [instruction, setInstruction] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const dialogRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        onClose();
      }
    }

    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [open, onClose]);

  if (!open) return null;

  async function handleReplicate(e: React.FormEvent) {
    e.preventDefault();
    if (pending) return;
    setPending(true);
    setError(null);

    const idempotencyKey = `replicate-${pack.id}-${targetType}-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
    try {
      const run = await client.replicateArtifact(
        pack.id,
        {
          contentType: targetType,
          instruction: instruction.trim() || undefined,
        },
        idempotencyKey,
      );
      onClose();
      router.push(`/chat?chat=${run.chatId}&agent=${run.id}`);
    } catch (err) {
      setPending(false);
      if (err instanceof PlotApiError) {
        if (err.code === "SOURCE_NOT_READY") {
          setError("Connected source repositories or permissions are no longer active.");
        } else {
          setError(err.message || "Failed to create related content.");
        }
      } else {
        setError("An unexpected error occurred. Please try again.");
      }
    }
  }

  const sourceCount = pack.variant.sources.length;

  return (
    <div
      className="fixed inset-0 z-[100] flex items-center justify-center bg-black/25 p-4 backdrop-blur-xs transition-opacity dark:bg-black/50"
      onMouseDown={onClose}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="replicate-dialog-title"
        className="relative w-full max-w-[460px] overflow-hidden rounded-[16px] border border-black/10 bg-white p-6 text-black/85 shadow-[0_16px_48px_rgba(0,0,0,0.12)] dark:border-white/10 dark:bg-[#1f1f23] dark:text-white/88"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="flex items-start justify-between gap-4">
          <div className="space-y-1">
            <h2
              id="replicate-dialog-title"
              className="font-display text-[22px] font-normal leading-tight text-black/90 dark:text-white/92"
            >
              Create related content
            </h2>
            <p className="text-xs leading-relaxed text-black/50 dark:text-white/50">
              Generate another format using the verified evidence from this artifact.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="rounded-lg p-1.5 text-black/40 transition hover:bg-black/5 hover:text-black/70 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-black/15 dark:text-white/40 dark:hover:bg-white/10 dark:hover:text-white/70"
          >
            <X className="size-4" />
          </button>
        </header>

        <form onSubmit={handleReplicate} className="mt-5 space-y-4">
          <div className="flex items-center justify-between rounded-lg border border-black/[0.08] bg-black/[0.02] px-3.5 py-2.5 text-xs dark:border-white/[0.08] dark:bg-white/[0.02]">
            <div className="flex items-center gap-2 text-black/60 dark:text-white/60">
              <GitPullRequest className="size-3.5 text-black/45 dark:text-white/45" />
              <span className="font-medium text-black/75 dark:text-white/75">Source evidence</span>
            </div>
            <span className="text-black/50 dark:text-white/50">
              {sourceCount} {sourceCount === 1 ? "source" : "sources"} attached
            </span>
          </div>

          <div className="space-y-2">
            <label className="block text-xs font-medium text-black/70 dark:text-white/70">
              Target format
            </label>
            <div className="grid grid-cols-2 gap-3">
              <button
                type="button"
                aria-pressed={targetType === "LAUNCH_ANNOUNCEMENT"}
                onClick={() => setTargetType("LAUNCH_ANNOUNCEMENT")}
                className={`group relative flex cursor-pointer flex-col gap-2.5 rounded-xl border p-3.5 text-left transition ${
                  targetType === "LAUNCH_ANNOUNCEMENT"
                    ? "border-black/40 bg-black/[0.025] shadow-[0_1px_3px_rgba(0,0,0,0.04)] dark:border-white/40 dark:bg-white/[0.04]"
                    : "border-black/10 hover:border-black/25 hover:bg-black/[0.01] dark:border-white/10 dark:hover:border-white/25 dark:hover:bg-white/[0.01]"
                }`}
              >
                <div className="flex items-start justify-between">
                  <Megaphone className="size-4 text-black/70 dark:text-white/70" />
                  <div
                    className={`flex size-4 shrink-0 items-center justify-center rounded-sm border transition ${
                      targetType === "LAUNCH_ANNOUNCEMENT"
                        ? "border-black bg-black text-white dark:border-white dark:bg-white dark:text-black"
                        : "border-black/20 group-hover:border-black/40 dark:border-white/20 dark:group-hover:border-white/40"
                    }`}
                  >
                    {targetType === "LAUNCH_ANNOUNCEMENT" && (
                      <Check className="size-3 stroke-[2.5]" />
                    )}
                  </div>
                </div>
                <div className="space-y-0.5">
                  <p className="text-xs font-semibold text-black/88 dark:text-white/88">
                    Launch announcement
                  </p>
                  <p className="text-[11px] leading-relaxed text-black/50 dark:text-white/50">
                    Customer-facing release note and public summary
                  </p>
                </div>
              </button>

              <button
                type="button"
                aria-pressed={targetType === "CHANGELOG"}
                onClick={() => setTargetType("CHANGELOG")}
                className={`group relative flex cursor-pointer flex-col gap-2.5 rounded-xl border p-3.5 text-left transition ${
                  targetType === "CHANGELOG"
                    ? "border-black/40 bg-black/[0.025] shadow-[0_1px_3px_rgba(0,0,0,0.04)] dark:border-white/40 dark:bg-white/[0.04]"
                    : "border-black/10 hover:border-black/25 hover:bg-black/[0.01] dark:border-white/10 dark:hover:border-white/25 dark:hover:bg-white/[0.01]"
                }`}
              >
                <div className="flex items-start justify-between">
                  <ScrollText className="size-4 text-black/70 dark:text-white/70" />
                  <div
                    className={`flex size-4 shrink-0 items-center justify-center rounded-sm border transition ${
                      targetType === "CHANGELOG"
                        ? "border-black bg-black text-white dark:border-white dark:bg-white dark:text-black"
                        : "border-black/20 group-hover:border-black/40 dark:border-white/20 dark:group-hover:border-white/40"
                    }`}
                  >
                    {targetType === "CHANGELOG" && (
                      <Check className="size-3 stroke-[2.5]" />
                    )}
                  </div>
                </div>
                <div className="space-y-0.5">
                  <p className="text-xs font-semibold text-black/88 dark:text-white/88">
                    Changelog
                  </p>
                  <p className="text-[11px] leading-relaxed text-black/50 dark:text-white/50">
                    Technical, release-focused record of changes
                  </p>
                </div>
              </button>
            </div>
          </div>

          <div className="space-y-1.5">
            <label
              htmlFor="replicate-instruction"
              className="block text-xs font-medium text-black/70 dark:text-white/70"
            >
              Additional instructions (optional)
            </label>
            <textarea
              id="replicate-instruction"
              value={instruction}
              onChange={(e) => setInstruction(e.target.value)}
              placeholder="e.g. Highlight developer usability improvements and keep it concise."
              rows={3}
              maxLength={2000}
              className="w-full resize-y rounded-lg border border-black/12 bg-white px-3 py-2 text-xs text-black/85 placeholder:text-black/35 outline-none transition focus:border-black/35 focus:ring-1 focus:ring-black/10 dark:border-white/12 dark:bg-white/[0.04] dark:text-white/85 dark:placeholder:text-white/35 dark:focus:border-white/35 dark:focus:ring-white/10"
            />
          </div>

          {error ? (
            <div className="rounded-lg border border-rose-500/20 bg-rose-500/5 p-3 text-xs text-rose-600 dark:border-rose-400/20 dark:bg-rose-400/10 dark:text-rose-400">
              {error}
            </div>
          ) : null}

          <div className="-mx-6 -mb-6 mt-6 flex items-center justify-between border-t border-black/[0.08] bg-black/[0.015] px-6 py-3.5 dark:border-white/[0.08] dark:bg-white/[0.02]">
            <p className="text-[11px] text-black/45 dark:text-white/45">
              Consumes 1 generation credit
            </p>
            <button
              type="submit"
              disabled={pending}
              className="inline-flex h-9 items-center justify-center gap-2 rounded-lg bg-black px-4 text-xs font-medium text-white shadow-sm transition hover:bg-black/85 disabled:cursor-not-allowed disabled:opacity-40 dark:bg-white dark:text-black dark:hover:bg-white/90"
            >
              {pending ? (
                <>
                  <Loader2 className="size-3.5 animate-spin" />
                  <span>Creating…</span>
                </>
              ) : (
                <span>Generate content</span>
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
