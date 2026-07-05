import { FileText, GitPullRequest, PanelRightClose } from "lucide-react";

import type { DraftDocument, ReferenceDocument } from "@/lib/api-client";

type SelectedSessionDocument =
  | { kind: "draft"; document: DraftDocument }
  | { kind: "reference"; document: ReferenceDocument };

type SessionSidePanelProps = {
  selectedDocument: SelectedSessionDocument | null;
  openDocuments: SelectedSessionDocument[];
  drafts: DraftDocument[];
  references: ReferenceDocument[];
  draftBodies: Record<string, string>;
  onDraftBodyChange: (draftId: string, body: string) => void;
  onSelectDocument: (documentId: string) => void;
  onClose: () => void;
};

export function SessionSidePanel({
  selectedDocument,
  openDocuments,
  drafts,
  references,
  draftBodies,
  onDraftBodyChange,
  onSelectDocument,
  onClose,
}: SessionSidePanelProps) {
  if (!selectedDocument) {
    return null;
  }

  return (
    <aside className="fixed bottom-0 right-0 top-0 z-50 flex w-full max-w-[460px] shrink-0 flex-col border-l border-black/10 bg-[#f8fafc] shadow-2xl shadow-black/20 dark:border-white/10 dark:bg-[#18181b] dark:shadow-black/50 xl:static xl:h-full xl:w-[460px] xl:max-w-none xl:shadow-none">
      <div className="flex h-14 items-center gap-1 border-b border-black/10 px-3 dark:border-white/10">
        <div className="flex min-w-0 flex-1 items-center gap-1" role="tablist" aria-label="Open session documents">
          {openDocuments.map((item) => {
            const active = item.document.id === selectedDocument.document.id;
            const Icon = item.kind === "draft" ? FileText : GitPullRequest;
            const label = item.kind === "draft" ? item.document.filename : item.document.label;
            const tabId = `session-document-tab-${item.document.id}`;
            const panelId = `session-document-panel-${item.document.id}`;

            return (
              <button
                key={item.document.id}
                id={tabId}
                type="button"
                role="tab"
                aria-selected={active}
                aria-controls={panelId}
                onClick={() => onSelectDocument(item.document.id)}
                className={`flex min-w-0 items-center gap-2 rounded-md px-2.5 py-1.5 text-xs transition ${
                  active
                    ? "bg-black/10 text-black dark:bg-white/10 dark:text-white"
                    : "text-black/55 hover:bg-black/5 dark:text-white/55 dark:hover:bg-white/10"
                }`}
              >
                <Icon className="size-3.5 shrink-0" />
                <span className="truncate">{label}</span>
              </button>
            );
          })}
        </div>
        <button
          type="button"
          onClick={onClose}
          className="ml-auto inline-flex size-9 items-center justify-center rounded-xl text-black/55 transition hover:bg-black/5 dark:text-white/55 dark:hover:bg-white/10"
          aria-label="Close document panel"
        >
          <PanelRightClose className="size-4" />
        </button>
      </div>

      <div
        id={`session-document-panel-${selectedDocument.document.id}`}
        role="tabpanel"
        aria-labelledby={`session-document-tab-${selectedDocument.document.id}`}
        className="min-h-0 flex-1 overflow-y-auto px-6 py-6"
      >
        {selectedDocument.kind === "draft" ? (
          <DraftView
            draft={selectedDocument.document}
            references={references}
            draftBody={draftBodies[selectedDocument.document.id] ?? selectedDocument.document.body}
            onDraftBodyChange={onDraftBodyChange}
          />
        ) : (
          <ReferenceView reference={selectedDocument.document} drafts={drafts} />
        )}
      </div>
    </aside>
  );
}

function DraftView({
  draft,
  references,
  draftBody,
  onDraftBodyChange,
}: {
  draft: DraftDocument;
  references: ReferenceDocument[];
  draftBody: string;
  onDraftBodyChange: (draftId: string, body: string) => void;
}) {
  const usedReferences = references.filter((reference) => draft.referenceIds.includes(reference.id));

  return (
    <article className="space-y-6">
      <div>
        <div className="text-xs text-black/45 dark:text-white/45">draft / {draft.filename}</div>
        <h1 className="mt-2 text-2xl font-semibold">{draft.title}</h1>
        <div className="mt-1 text-sm text-black/50 dark:text-white/50">{draft.status}</div>
      </div>

      <textarea
        className="min-h-[300px] w-full resize-none rounded-lg border border-black/10 bg-white p-4 text-sm leading-6 outline-none focus:border-black/30 dark:border-white/10 dark:bg-[#1f2024] dark:focus:border-white/30"
        value={draftBody}
        onChange={(event) => onDraftBodyChange(draft.id, event.target.value)}
        aria-label={`${draft.title} body`}
      />

      <section>
        <h2 className="text-sm font-semibold">References used</h2>
        <div className="mt-2 space-y-2">
          {usedReferences.map((reference) => (
            <div key={reference.id} className="rounded-md border border-black/10 p-3 text-sm dark:border-white/10">
              <div className="font-medium">{reference.label}</div>
              <div className="mt-1 text-black/55 dark:text-white/55">{reference.summary}</div>
            </div>
          ))}
        </div>
      </section>

      <section>
        <h2 className="text-sm font-semibold">Needs your call</h2>
        <ul className="mt-2 space-y-2 text-sm text-black/65 dark:text-white/65">
          {draft.needsYourCall.map((item) => (
            <li key={item} className="rounded-md bg-black/5 px-3 py-2 dark:bg-white/10">
              {item}
            </li>
          ))}
        </ul>
      </section>

      <div className="flex gap-2">
        <button className="rounded-md bg-black px-3 py-2 text-sm font-medium text-white dark:bg-white dark:text-black">
          Approve
        </button>
        <button className="rounded-md border border-black/10 px-3 py-2 text-sm font-medium dark:border-white/10">
          Ask for changes
        </button>
      </div>
    </article>
  );
}

function ReferenceView({
  reference,
  drafts,
}: {
  reference: ReferenceDocument;
  drafts: DraftDocument[];
}) {
  const usedInDrafts = drafts.filter((draft) => reference.usedInDraftIds.includes(draft.id));

  return (
    <article className="space-y-6">
      <div>
        <div className="text-xs text-black/45 dark:text-white/45">
          reference / {reference.sourceType.toLowerCase()}
        </div>
        <h1 className="mt-2 text-2xl font-semibold">{reference.label}</h1>
        <div className="mt-1 text-sm text-black/50 dark:text-white/50">
          {reference.title} · {reference.date}
        </div>
      </div>

      <section>
        <h2 className="text-sm font-semibold">Summary</h2>
        <p className="mt-2 text-sm leading-6 text-black/70 dark:text-white/70">{reference.summary}</p>
      </section>

      <section>
        <h2 className="text-sm font-semibold">Used in</h2>
        <div className="mt-2 space-y-2">
          {usedInDrafts.map((draft) => (
            <div key={draft.id} className="rounded-md border border-black/10 p-3 text-sm dark:border-white/10">
              {draft.title}
            </div>
          ))}
        </div>
      </section>

      <section>
        <h2 className="text-sm font-semibold">Notes</h2>
        <ul className="mt-2 space-y-2 text-sm text-black/65 dark:text-white/65">
          {reference.notes.map((note) => (
            <li key={note} className="rounded-md bg-black/5 px-3 py-2 dark:bg-white/10">
              {note}
            </li>
          ))}
        </ul>
      </section>
    </article>
  );
}
