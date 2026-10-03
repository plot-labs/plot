"use client";

import { Check, Copy, ExternalLink, Globe, ShieldAlert, Undo2, X } from "lucide-react";
import { useState, type ReactNode } from "react";

import { PlotApiError, type Artifact, type ArtifactPublication, type PlotApiClient } from "@plot/api-client";
import { publicChangelogEntryUrl } from "@/lib/public-changelog-url";
import { useWorkspaceEntitlement } from "@/lib/use-workspace-entitlement";

type PublishWarning = { key: string; sentenceNumber: number; excerpt: string };

type PublishSuccess = {
  entryId: string;
  entrySlug: string;
  publicPath: string;
  publishedAt: string;
};

export function PublishDialog({
  pack,
  client,
  presentation = "button",
  onPackChange,
  hasUnsavedChanges = false,
}: {
  pack: Artifact;
  client: PlotApiClient;
  presentation?: "button" | "inline" | "menu";
  onPackChange?: (pack: Artifact) => void;
  /** Publishing uses the last saved revision, so unsaved editor changes block it. */
  hasUnsavedChanges?: boolean;
}) {
  const entitlement = useWorkspaceEntitlement();
  const canPublish = (entitlement?.capabilities.publish ?? true) && pack.contentType === "CHANGELOG";
  const canUnpublish = entitlement?.capabilities.unpublish ?? true;
  const [pending, setPending] = useState(false);
  const [confirmation, setConfirmation] = useState<{ warnings: PublishWarning[] } | null>(null);
  const [unpublishConfirm, setUnpublishConfirm] = useState(false);
  const [success, setSuccess] = useState<PublishSuccess | null>(null);
  const [withdrawn, setWithdrawn] = useState(false);
  const [livePublication, setLivePublication] = useState<ArtifactPublication | null>(pack.publication ?? null);
  const [message, setMessage] = useState("");
  const [copyState, setCopyState] = useState<"idle" | "copied">("idle");
  const [externalConfirmed, setExternalConfirmed] = useState(false);

  const [previousPublication, setPreviousPublication] = useState(pack.publication);
  if (previousPublication !== pack.publication) {
    setPreviousPublication(pack.publication);
    setLivePublication(pack.publication ?? null);
  }

  async function requestPublish(acknowledgeUnresolved: boolean, acknowledgedWarningKeys: string[] = []) {
    if (pending) return;
    setPending(true);
    setMessage("");
    try {
      const result = await client.publishArtifactVariant(pack.variant.id, {
        expectedRevisionNumber: pack.variant.revisionNumber,
        acknowledgeUnresolved,
        acknowledgedWarningKeys,
      });
      setConfirmation(null);
      setSuccess(result);
      setWithdrawn(false);
      setExternalConfirmed(false);
      setLivePublication(result);
      setCopyState("idle");
      onPackChange?.({ ...pack, publication: result });
    } catch (error) {
      if (error instanceof PlotApiError && error.code === "PUBLISH_CONFIRMATION_REQUIRED") {
        const warnings = Array.isArray(error.details?.warnings)
          ? error.details.warnings.filter(isPublishWarning)
          : [];
        setConfirmation({ warnings });
        setMessage("Explicit confirmation is required before publish.");
      } else {
        setMessage(error instanceof Error ? error.message : "The content could not be published.");
      }
    } finally {
      setPending(false);
    }
  }

  async function requestUnpublish() {
    if (pending) return;
    setPending(true);
    setMessage("");
    try {
      await client.unpublishArtifactVariant(pack.variant.id);
      setUnpublishConfirm(false);
      setSuccess(null);
      setWithdrawn(true);
      setLivePublication(null);
      onPackChange?.({ ...pack, publication: null });
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "The changelog could not be unpublished.");
    } finally {
      setPending(false);
    }
  }

  async function copyPublicUrl(publicPath: string) {
    await navigator.clipboard.writeText(publicChangelogEntryUrl(publicPath));
    setCopyState("copied");
    window.setTimeout(() => setCopyState("idle"), 2_000);
  }

  async function confirmExternalDelivery(entryId: string) {
    if (typeof client.recordProductDeliveryEvent !== "function") {
      setExternalConfirmed(true);
      return;
    }
    try {
      await client.recordProductDeliveryEvent(pack.variant.id, {
        kind: "EXTERNAL_DELIVERY_CONFIRMED",
        entryId,
        clientEventId: crypto.randomUUID(),
      });
      setExternalConfirmed(true);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Could not record external delivery.");
    }
  }

  const live = livePublication;
  const showUnpublish = Boolean(live) && canUnpublish;
  const showPublish = !live && canPublish;
  // The live entry is behind the latest saved revision; publishing again replaces it at the same URL.
  const showUpdate = Boolean(live) && canPublish && typeof live?.revisionNumber === "number" && pack.variant.revisionNumber > live.revisionNumber;
  const publishBlocked = pending || hasUnsavedChanges;
  const unsavedNote = hasUnsavedChanges && (showPublish || showUpdate) ? "Save your changes before publishing. Publish uses the last saved draft." : "";
  const visibleMessage = confirmation ? "" : message;
  if (!showPublish && !showUnpublish && !success && !withdrawn) return null;

  if (presentation === "menu") {
    return (
      <div role="none" className="relative border-t border-black/[0.06] pt-1 dark:border-white/10">
        {showUpdate ? (
          <button aria-busy={Boolean(pending)}
            type="button"
            role="menuitem"
            disabled={publishBlocked}
            onClick={() => void requestPublish(false)}
            className="glass-control flex h-8 w-full items-center gap-2 rounded-[4px] px-2.5 text-left"
          >
            <Globe aria-hidden="true" className="size-4" /> Update published changelog
          </button>
        ) : null}
        {showUnpublish ? (
          <button aria-busy={Boolean(pending)}
            type="button"
            role="menuitem"
            disabled={pending}
            onClick={() => setUnpublishConfirm(true)}
            className="glass-control glass-danger flex h-8 w-full items-center gap-2 rounded-[4px] px-2.5 text-left"
          >
            <Undo2 aria-hidden="true" className="size-4" /> Unpublish changelog
          </button>
        ) : showPublish ? (
          <button aria-busy={Boolean(pending)}
            type="button"
            role="menuitem"
            disabled={publishBlocked}
            onClick={() => void requestPublish(false)}
            className="glass-control flex h-8 w-full items-center gap-2 rounded-[4px] px-2.5 text-left"
          >
            <Globe aria-hidden="true" className="size-4" /> Publish changelog
          </button>
        ) : null}
        {showPublish ? <PublishScopeNote citations={publicCitationPreview(pack)} className="px-2.5 pb-1" /> : null}
        {unpublishConfirm ? (
          <UnpublishConfirmation
            pending={pending}
            onCancel={() => setUnpublishConfirm(false)}
            onConfirm={() => void requestUnpublish()}
          />
        ) : null}
        {confirmation ? (
          <PublishConfirmation
            confirmation={confirmation}
            pending={pending}
            pack={pack}
            onCancel={() => setConfirmation(null)}
            onConfirm={() => void requestPublish(true, confirmation.warnings.map((warning) => warning.key))}
          />
        ) : null}
        {success ? (
          <PublishSuccessPanel
            success={success}
            copyState={copyState}
            externalConfirmed={externalConfirmed}
            onCopy={() => void copyPublicUrl(success.publicPath)}
            onConfirmExternal={() => void confirmExternalDelivery(success.entryId)}
            onDismiss={() => setSuccess(null)}
          />
        ) : null}
        {unsavedNote ? <p className="px-2.5 py-1 text-xs text-black/58 dark:text-white/58">{unsavedNote}</p> : null}
        {withdrawn ? <p role="status" aria-live="polite" className="px-2.5 py-1 text-xs text-black/58 dark:text-white/58">Public changelog withdrawn. The internal snapshot is kept.</p> : null}
        {visibleMessage ? <p role="alert" className="px-2.5 py-1 text-xs text-rose-700 dark:text-rose-300">{visibleMessage}</p> : null}
      </div>
    );
  }


  return (
    <div className={presentation === "inline" ? "relative inline-flex flex-col items-end gap-2" : "relative inline-flex items-center gap-2"}>
      {unsavedNote ? (
        <span id="publish-unsaved-note" className="text-xs text-black/50 dark:text-white/50">
          Save changes to publish
        </span>
      ) : null}
      {showUpdate ? (
        <button aria-busy={Boolean(pending)}
          type="button"
          disabled={publishBlocked}
          onClick={() => void requestPublish(false)}
          aria-label="Update published changelog"
          aria-describedby={unsavedNote ? "publish-unsaved-note" : undefined}
          title="Replace the live changelog with the latest saved draft at the same URL."
          className="glass-button glass-primary min-w-[120px]"
        >
          <Globe aria-hidden="true" className="size-3.5 text-black/60 dark:text-white/60" />
          {pending ? "Updating…" : "Update"}
        </button>
      ) : null}
      {showUnpublish ? (
        <button aria-busy={Boolean(pending)}
          type="button"
          disabled={pending}
          onClick={() => setUnpublishConfirm(true)}
          aria-label="Unpublish changelog"
          className="glass-button glass-danger min-w-[132px]"
        >
          <Undo2 aria-hidden="true" className="size-3.5" />
          {pending ? "Unpublishing…" : "Unpublish"}
        </button>
      ) : showPublish ? (
        <button aria-busy={Boolean(pending)}
          type="button"
          disabled={publishBlocked}
          onClick={() => void requestPublish(false)}
          aria-label="Publish changelog"
          aria-describedby={unsavedNote ? "publish-unsaved-note" : undefined}
          title="Hosted publish includes the body and public citations only. Private source labels stay private. Plot does not remove secrets from the body."
          className="glass-button glass-primary min-w-[120px]"
        >
          <Globe aria-hidden="true" className="size-3.5 text-black/60 dark:text-white/60" />
          {pending ? "Publishing…" : "Publish"}
        </button>
      ) : null}

      {showPublish ? (
        <PublishScopeNote
          citations={publicCitationPreview(pack)}
          className={presentation === "inline"
            ? "max-w-[18rem] text-right text-[11px] leading-4 text-black/50 dark:text-white/50"
            : "sr-only"}
        />
      ) : null}

      {unpublishConfirm ? (
        <UnpublishConfirmation
          pending={pending}
          presentation={presentation}
          onCancel={() => setUnpublishConfirm(false)}
          onConfirm={() => void requestUnpublish()}
        />
      ) : null}

      {confirmation ? (
        <PublishConfirmation
          confirmation={confirmation}
          pending={pending}
          pack={pack}
          presentation={presentation}
          onCancel={() => setConfirmation(null)}
          onConfirm={() => void requestPublish(true, confirmation.warnings.map((warning) => warning.key))}
        />
      ) : null}

      {success ? (
        <PublishSuccessPanel
          success={success}
          copyState={copyState}
          presentation={presentation}
          externalConfirmed={externalConfirmed}
          onCopy={() => void copyPublicUrl(success.publicPath)}
          onConfirmExternal={() => void confirmExternalDelivery(success.entryId)}
          onDismiss={() => setSuccess(null)}
        />
      ) : null}

      {withdrawn ? (
        <PublishNotice presentation={presentation} tone="status" onDismiss={() => setWithdrawn(false)}>
          Public changelog withdrawn. The internal snapshot is kept.
        </PublishNotice>
      ) : null}

      {visibleMessage ? (
        <PublishNotice presentation={presentation} tone="error" onDismiss={() => setMessage("")}>
          {visibleMessage}
        </PublishNotice>
      ) : null}
    </div>
  );
}

/** Visible outcome text: anchored under the trigger for the compact button, inline otherwise. */
function PublishNotice({
  presentation,
  tone,
  onDismiss,
  children,
}: {
  presentation: "button" | "inline" | "menu";
  tone: "status" | "error";
  onDismiss: () => void;
  children: ReactNode;
}) {
  const toneClass = tone === "error" ? "text-rose-700 dark:text-rose-300" : "text-black/62 dark:text-white/62";
  if (presentation !== "button") {
    return (
      <span role={tone === "error" ? "alert" : "status"} className={`text-right text-xs ${toneClass}`}>
        {children}
      </span>
    );
  }
  return (
    <div
      role={tone === "error" ? "alert" : "status"}
      className="glass-layer absolute right-0 top-[calc(100%+12px)] z-50 flex w-[min(320px,calc(100vw-32px))] items-start gap-2 rounded-lg border border-black/10 p-3 text-xs leading-5 dark:border-white/10"
    >
      <p className={`min-w-0 flex-1 ${toneClass}`}>{children}</p>
      <button
        type="button"
        onClick={onDismiss}
        aria-label="Dismiss publish message"
        className="glass-button glass-icon inline-flex size-7 shrink-0 items-center justify-center"
      >
        <X aria-hidden="true" className="size-3.5" />
      </button>
    </div>
  );
}

function publicCitationPreview(pack: Artifact): Array<{ sourceLabel: string; originalUrl: string }> {
  const seen = new Set<string>();
  const citations: Array<{ sourceLabel: string; originalUrl: string }> = [];
  for (const sentence of pack.variant.sentences) {
    for (const citation of sentence.citations) {
      if (citation.provider !== "GITHUB" || !citation.originalUrl) continue;
      const key = `${citation.sourceLabel}\0${citation.originalUrl}`;
      if (seen.has(key)) continue;
      seen.add(key);
      citations.push({ sourceLabel: citation.sourceLabel, originalUrl: citation.originalUrl });
    }
  }
  return citations;
}

function PublishScopeNote({
  citations,
  className,
}: {
  citations: Array<{ sourceLabel: string; originalUrl: string }>;
  className?: string;
}) {
  return (
    <div className={className ?? "max-w-[18rem] text-right text-[11px] leading-4 text-black/50 dark:text-white/50"}>
      <p>
        Hosted publish shows the changelog body and public citations only. Private source labels stay out of the page. Plot does not remove secrets from the body.
      </p>
      {citations.length ? (
        <ul className="mt-1 space-y-0.5">
          {citations.slice(0, 4).map((citation) => (
            <li key={`${citation.sourceLabel}:${citation.originalUrl}`} className="truncate" title={citation.originalUrl}>
              {citation.sourceLabel}
            </li>
          ))}
          {citations.length > 4 ? <li>+{citations.length - 4} more</li> : null}
        </ul>
      ) : (
        <p className="mt-1">No public citations will appear on this page.</p>
      )}
    </div>
  );
}

function UnpublishConfirmation({
  pending,
  presentation,
  onCancel,
  onConfirm,
}: {
  pending: boolean;
  presentation?: "button" | "inline" | "menu";
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const anchored = presentation === "button";
  return (
    <div
      role="alertdialog"
      aria-labelledby="unpublish-warning-title"
      aria-describedby="unpublish-warning-description"
      className={`glass-layer ${anchored
        ? "absolute right-0 top-[calc(100%+12px)] z-50 w-[min(360px,calc(100vw-32px))] rounded-lg border border-black/10 p-3 dark:border-white/10"
        : "w-full rounded-lg border border-black/10 p-3 dark:border-white/10"}`}
    >
      <h3 id="unpublish-warning-title" className="text-sm font-semibold">Withdraw this public changelog?</h3>
      <p id="unpublish-warning-description" className="mt-1 text-xs leading-5 text-black/62 dark:text-white/62">
        The public page, list entry, and citations will stop resolving. The internal snapshot stays.
      </p>
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <button aria-busy={Boolean(pending)}
          type="button"
          disabled={pending}
          onClick={onConfirm}
          aria-label="Confirm unpublish"
          className="glass-button glass-danger inline-flex items-center"
        >
          Unpublish
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="glass-button inline-flex items-center border"
        >
          Cancel
        </button>
      </div>
    </div>
  );
}

function PublishConfirmation({
  confirmation,
  pending,
  pack,
  presentation,
  onCancel,
  onConfirm,
}: {
  confirmation: { warnings: PublishWarning[] };
  pending: boolean;
  pack: Artifact;
  presentation?: "button" | "inline" | "menu";
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const anchored = presentation === "button";
  return (
    <div
      role="alertdialog"
      aria-labelledby="publish-warning-title"
      aria-describedby="publish-warning-description"
      className={anchored
        ? "absolute right-0 top-[calc(100%+12px)] z-50 w-[min(360px,calc(100vw-32px))] rounded-lg border border-amber-300/70 bg-amber-50 p-3 shadow-[0_12px_32px_rgba(0,0,0,0.14)] dark:border-amber-400/25 dark:bg-[#2b2820]"
        : "w-full rounded-lg border border-amber-300/70 bg-amber-50 p-3 dark:border-amber-400/25 dark:bg-amber-400/[0.07]"}
    >
      <div className="flex items-start gap-2">
        <ShieldAlert className="mt-0.5 size-4 shrink-0 text-amber-700 dark:text-amber-300" />
        <div className="min-w-0 flex-1">
          <h3 id="publish-warning-title" className="text-sm font-semibold">Unresolved statements will be published</h3>
          <div id="publish-warning-description" className="mt-1 text-xs leading-5 text-black/62 dark:text-white/62">
            <p>Review affected statements before continuing.</p>
            {confirmation.warnings.length ? (
              <ul className="mt-2 space-y-1">
                {confirmation.warnings.map((warning) => (
                  <li key={warning.key}>
                    <button
                      type="button"
                      onClick={() => focusStatement(pack, warning.sentenceNumber)}
                      className="glass-button block max-w-full truncate text-left underline underline-offset-2"
                    >
                      Statement {warning.sentenceNumber} — “{warning.excerpt}”
                    </button>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="mt-1">Affected statement details are unavailable.</p>
            )}
          </div>
        </div>
        <button
          type="button"
          onClick={onCancel}
          className="glass-button glass-icon inline-flex size-9 shrink-0 items-center justify-center"
          aria-label="Cancel publish warning"
          title="Cancel publish warning"
        >
          <X aria-hidden="true" className="size-4" />
        </button>
      </div>
      <button aria-busy={Boolean(pending)}
        autoFocus
        type="button"
        disabled={pending}
        onClick={onConfirm}
        className="glass-button glass-primary mt-3 inline-flex items-center gap-2"
      >
        <Check aria-hidden="true" className="size-4" /> Confirm and publish
      </button>
    </div>
  );
}

function PublishSuccessPanel({
  success,
  copyState,
  presentation,
  externalConfirmed,
  onCopy,
  onConfirmExternal,
  onDismiss,
}: {
  success: PublishSuccess;
  copyState: "idle" | "copied";
  presentation?: "button" | "inline" | "menu";
  externalConfirmed: boolean;
  onCopy: () => void;
  onConfirmExternal: () => void;
  onDismiss: () => void;
}) {
  const publicUrl = publicChangelogEntryUrl(success.publicPath);
  const anchored = presentation === "button";
  return (
    <div
      role="status"
      aria-live="polite"
      className={`glass-layer ${anchored
        ? "absolute right-0 top-[calc(100%+12px)] z-50 w-[min(360px,calc(100vw-32px))] rounded-lg border border-black/10 p-3 dark:border-white/10"
        : "w-full rounded-lg border border-black/10 p-3 dark:border-white/10"}`}
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="text-sm font-semibold text-black/85 dark:text-white/88">Changelog published</p>
          <p className="mt-1 truncate text-xs text-black/58 dark:text-white/58" title={publicUrl}>{publicUrl}</p>
        </div>
        <button
          type="button"
          onClick={onDismiss}
          className="glass-button glass-icon inline-flex size-8 shrink-0 items-center justify-center"
          aria-label="Dismiss publish success"
        >
          <X aria-hidden="true" className="size-4" />
        </button>
      </div>
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <button
          type="button"
          onClick={onCopy}
          className="glass-button glass-primary inline-flex items-center gap-1.5 border"
        >
          <Copy aria-hidden="true" className="size-3.5" />
          {copyState === "copied" ? "Copied" : "Copy link"}
        </button>
        <a
          href={publicUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="glass-button glass-primary inline-flex items-center gap-1.5"
        >
          <ExternalLink aria-hidden="true" className="size-3.5" />
          View live
        </a>
        <button
          type="button"
          onClick={onConfirmExternal}
          disabled={externalConfirmed}
          className="glass-button inline-flex items-center gap-1.5 border"
        >
          {externalConfirmed ? "Marked as shared" : "I shared this outside Plot"}
        </button>
      </div>
    </div>
  );
}

function focusStatement(pack: Artifact, sentenceNumber: number) {
  const sentenceId = [...pack.variant.sentences].sort((a, b) => a.orderIndex - b.orderIndex)[sentenceNumber - 1]?.id;
  const sentence = sentenceId
    ? document.querySelector<HTMLElement>(`[data-statement-id="${sentenceId}"]`)
    : null;
  sentence?.scrollIntoView?.({ block: "center", behavior: "smooth" });
  sentence?.focus();
  if (sentence) {
    sentence.dataset.statementHighlight = "true";
    window.setTimeout(() => {
      if (sentence.isConnected) delete sentence.dataset.statementHighlight;
    }, 2_000);
  }
}

function isPublishWarning(value: unknown): value is PublishWarning {
  if (!value || typeof value !== "object") return false;
  const warning = value as Record<string, unknown>;
  return typeof warning.key === "string" && typeof warning.sentenceNumber === "number" && typeof warning.excerpt === "string";
}
