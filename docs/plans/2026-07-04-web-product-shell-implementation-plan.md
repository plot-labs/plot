# Web Product Shell Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the dev-only Plot product shell with Sessions, Sources, Packs, Voice, and Settings routes, centered on an interactive session workspace with generated drafts, references, a floating summary, and a toggleable right document panel.

**Architecture:** Keep `/` as the existing landing page and add an `(app)` route group for product routes. Use `lib/dev-context.ts` for seeded dev data and `lib/api-client.ts` as the only data boundary consumed by features. Put the durable session workflow in `features/sessions`, with smaller supporting workspaces for Sources and Packs plus route-level Voice and Settings pages.

**Tech Stack:** Next.js App Router, React 19, TypeScript strict mode, Tailwind CSS v4 classes, existing `Button` component, `lucide-react` icons, `just lint-web`, and `just build-web`.

---

## Scope Check

This plan implements one coherent frontend slice. It does not introduce auth, real API calls, persistence, publishing, or source adapter setup. The current repository has no web unit-test runner, so the test loop is lint/build plus manual interaction checks.

## File Structure

Create:

- `apps/web/src/app/(app)/layout.tsx` wraps internal product routes in the product shell.
- `apps/web/src/app/(app)/sessions/page.tsx` renders the Sessions workspace.
- `apps/web/src/app/(app)/sources/page.tsx` renders the Sources workspace.
- `apps/web/src/app/(app)/packs/page.tsx` renders the Packs workspace.
- `apps/web/src/app/(app)/voice/page.tsx` renders the Voice supporting surface.
- `apps/web/src/app/(app)/settings/page.tsx` renders the Settings supporting surface.
- `apps/web/src/components/layout/product-shell.tsx` owns product chrome, right padding, and light/dark visual toggle state.
- `apps/web/src/components/layout/product-sidebar.tsx` owns route-aware navigation and session shortcuts.
- `apps/web/src/features/sessions/sessions-workspace.tsx` owns session UI state and composes the session feature.
- `apps/web/src/features/sessions/session-thread.tsx` renders user/agent messages and the floating summary.
- `apps/web/src/features/sessions/session-floating-summary.tsx` renders clickable Drafts and References.
- `apps/web/src/features/sessions/session-side-panel.tsx` renders draft editor and reference document views.
- `apps/web/src/features/sessions/session-composer.tsx` appends local user messages and demo agent replies.
- `apps/web/src/features/sources/sources-workspace.tsx` renders the reference inventory supporting page.
- `apps/web/src/features/packs/packs-workspace.tsx` renders saved result groups.
- `apps/web/src/lib/dev-context.ts` owns seeded dev data and shared UI data types.
- `apps/web/src/lib/api-client.ts` exposes typed getters over the dev context.

Modify:

- No existing landing files need changes.
- `apps/web/src/app/globals.css` is not required for the first pass because the product shell can use local Tailwind light/dark classes.

## Implementation Tasks

### Task 1: Create Seeded Dev Data And API Boundary

**Files:**

- Create: `apps/web/src/lib/dev-context.ts`
- Create: `apps/web/src/lib/api-client.ts`

- [ ] **Step 1: Create `dev-context.ts` with shared types and seeded data**

Create `apps/web/src/lib/dev-context.ts` with this content:

```ts
export type SessionRole = "user" | "agent";
export type DocumentKind = "draft" | "reference";
export type DraftStatus = "Draft ready" | "In progress" | "Needs your call" | "Approved";
export type SourceStatus = "Used in draft" | "Not used" | "Needs context";

export type Workspace = {
  id: string;
  name: string;
  environment: string;
  connectionLabel: string;
};

export type SessionMessage = {
  id: string;
  role: SessionRole;
  author: string;
  timestamp: string;
  content: string;
};

export type WorkSession = {
  id: string;
  title: string;
  subtitle: string;
  project: string;
  updatedAt: string;
  status: string;
  messages: SessionMessage[];
  draftIds: string[];
  referenceIds: string[];
};

export type DraftDocument = {
  id: string;
  kind: "draft";
  title: string;
  filename: string;
  status: DraftStatus;
  body: string;
  referenceIds: string[];
  needsYourCall: string[];
  updatedAt: string;
};

export type ReferenceDocument = {
  id: string;
  kind: "reference";
  title: string;
  label: string;
  sourceType: string;
  date: string;
  status: SourceStatus;
  summary: string;
  usedInDraftIds: string[];
  notes: string[];
};

export type SavedPack = {
  id: string;
  title: string;
  request: string;
  status: string;
  updatedAt: string;
  draftIds: string[];
};

export type VoiceGuidance = {
  preferred: string[];
  avoid: string[];
  examples: string[];
  channelNotes: string[];
};

export type DevContext = {
  workspace: Workspace;
  sessions: WorkSession[];
  drafts: DraftDocument[];
  references: ReferenceDocument[];
  packs: SavedPack[];
  voice: VoiceGuidance;
  members: string[];
};

export const devContext: DevContext = {
  workspace: {
    id: "workspace-dev",
    name: "Plot Dev Workspace",
    environment: "Local dev",
    connectionLabel: "GitHub demo source",
  },
  sessions: [
    {
      id: "session-changelog-july",
      title: "July changelog",
      subtitle: "Create a changelog for July 1-7",
      project: "plot",
      updatedAt: "18m ago",
      status: "Draft ready",
      draftIds: ["draft-changelog", "draft-customer-update"],
      referenceIds: ["ref-pr-184", "ref-release-v04", "ref-issue-77"],
      messages: [
        {
          id: "msg-1",
          role: "user",
          author: "You",
          timestamp: "09:18",
          content: "Create a changelog for July 1-7. Keep customer-facing wording conservative.",
        },
        {
          id: "msg-2",
          role: "agent",
          author: "Plot",
          timestamp: "09:19",
          content:
            "I drafted a changelog using the release tag, six merged PRs, and one issue that needs a product wording decision.",
        },
      ],
    },
    {
      id: "session-launch-copy",
      title: "Launch copy",
      subtitle: "Turn the release notes into a short launch post",
      project: "plot",
      updatedAt: "2h ago",
      status: "In progress",
      draftIds: ["draft-launch-post"],
      referenceIds: ["ref-release-v04", "ref-pr-179"],
      messages: [
        {
          id: "msg-3",
          role: "user",
          author: "You",
          timestamp: "07:42",
          content: "Write a short launch post from the current release notes.",
        },
      ],
    },
  ],
  drafts: [
    {
      id: "draft-changelog",
      kind: "draft",
      title: "July 1-7 Changelog",
      filename: "Changelog.md",
      status: "Draft ready",
      updatedAt: "09:24",
      referenceIds: ["ref-pr-184", "ref-release-v04", "ref-issue-77"],
      needsYourCall: ["Should the beta wording stay internal or appear in the public changelog?"],
      body:
        "## July 1-7\n\n- Improved the login recovery copy so users understand the next step after a failed sign-in.\n- Added clearer empty-state messaging for new workspaces.\n- Updated the release notes flow so shipped changes can be reviewed before publishing.\n\nOne customer-facing line still needs a wording decision before approval.",
    },
    {
      id: "draft-customer-update",
      kind: "draft",
      title: "Customer update",
      filename: "Customer-update.md",
      status: "Needs your call",
      updatedAt: "09:26",
      referenceIds: ["ref-pr-184", "ref-issue-77"],
      needsYourCall: ["Can this mention automatic update preparation, or should it say assisted drafting?"],
      body:
        "We improved the update drafting flow this week so release notes are easier to review before they go out. The biggest change is clearer guidance around what changed and which parts still need a product decision.",
    },
    {
      id: "draft-launch-post",
      kind: "draft",
      title: "Launch post",
      filename: "Launch-post.md",
      status: "In progress",
      updatedAt: "07:55",
      referenceIds: ["ref-release-v04", "ref-pr-179"],
      needsYourCall: ["Pick founder voice or product voice before final copy."],
      body:
        "Shipping faster only helps if your updates keep up. This release makes the review step clearer before any customer-facing copy leaves the workspace.",
    },
  ],
  references: [
    {
      id: "ref-pr-184",
      kind: "reference",
      title: "Auth copy improvements",
      label: "PR #184",
      sourceType: "Pull request",
      date: "Jul 3",
      status: "Used in draft",
      usedInDraftIds: ["draft-changelog", "draft-customer-update"],
      summary: "Updated empty-state copy and clarified the login recovery flow after failed sign-in attempts.",
      notes: ["Keep the beta wording internal until product confirms the public phrasing."],
    },
    {
      id: "ref-release-v04",
      kind: "reference",
      title: "Release v0.4",
      label: "Release v0.4",
      sourceType: "Release",
      date: "Jul 7",
      status: "Used in draft",
      usedInDraftIds: ["draft-changelog", "draft-launch-post"],
      summary: "Release tag containing workspace copy improvements and review-flow refinements.",
      notes: ["Best source for the changelog introduction."],
    },
    {
      id: "ref-issue-77",
      kind: "reference",
      title: "Customer wording decision",
      label: "Issue #77",
      sourceType: "Issue",
      date: "Jul 4",
      status: "Needs context",
      usedInDraftIds: ["draft-changelog", "draft-customer-update"],
      summary: "Open product wording decision for how strongly to describe automated update preparation.",
      notes: ["Needs a human decision before customer copy is approved."],
    },
    {
      id: "ref-pr-179",
      kind: "reference",
      title: "Review flow cleanup",
      label: "PR #179",
      sourceType: "Pull request",
      date: "Jul 1",
      status: "Not used",
      usedInDraftIds: ["draft-launch-post"],
      summary: "Small cleanup to the review flow and copy around saved results.",
      notes: ["Useful for internal notes, less useful for customer copy."],
    },
  ],
  packs: [
    {
      id: "pack-july-changelog",
      title: "July changelog request",
      request: "Create a changelog for July 1-7",
      status: "Draft ready",
      updatedAt: "09:26",
      draftIds: ["draft-changelog", "draft-customer-update"],
    },
    {
      id: "pack-launch-copy",
      title: "Launch copy request",
      request: "Turn release notes into a short launch post",
      status: "In progress",
      updatedAt: "07:55",
      draftIds: ["draft-launch-post"],
    },
  ],
  voice: {
    preferred: [
      "Lead with what changed for the user.",
      "Use plain product language before technical details.",
      "Keep claims conservative until the reference is clear.",
    ],
    avoid: [
      "Do not describe unfinished work as launched.",
      "Do not use vague automation claims without a source.",
      "Do not make the product sound like a generic writing tool.",
    ],
    examples: [
      "Use: Review shipped changes before customer copy goes out.",
      "Avoid: AI instantly creates perfect launch content.",
    ],
    channelNotes: [
      "Changelog: direct, scannable, release-window first.",
      "Customer update: explain the practical benefit in one short paragraph.",
      "Launch post: stronger point of view, still source-backed.",
    ],
  },
  members: ["Mina", "Alex", "You"],
};
```

- [ ] **Step 2: Create `api-client.ts` as the only feature data boundary**

Create `apps/web/src/lib/api-client.ts` with this content:

```ts
import {
  devContext,
  type DraftDocument,
  type ReferenceDocument,
  type SessionMessage,
} from "@/lib/dev-context";

export type SelectedDocument =
  | { kind: "draft"; document: DraftDocument }
  | { kind: "reference"; document: ReferenceDocument };

export function getProductShellData() {
  return {
    workspace: devContext.workspace,
    sessions: devContext.sessions,
  };
}

export function getSessionsWorkspace() {
  return {
    workspace: devContext.workspace,
    sessions: devContext.sessions,
    drafts: devContext.drafts,
    references: devContext.references,
  };
}

export function getSourcesWorkspace() {
  return {
    references: devContext.references,
    drafts: devContext.drafts,
  };
}

export function getPacksWorkspace() {
  return {
    packs: devContext.packs,
    drafts: devContext.drafts,
    references: devContext.references,
  };
}

export function getVoiceWorkspace() {
  return devContext.voice;
}

export function getSettingsWorkspace() {
  return {
    workspace: devContext.workspace,
    members: devContext.members,
  };
}

export function getSelectedDocument(documentId: string): SelectedDocument | null {
  const draft = devContext.drafts.find((item) => item.id === documentId);

  if (draft) {
    return { kind: "draft", document: draft };
  }

  const reference = devContext.references.find((item) => item.id === documentId);

  if (reference) {
    return { kind: "reference", document: reference };
  }

  return null;
}

export function createDemoAgentReply(message: string, count: number): SessionMessage {
  const asksForAnotherDraft =
    message.toLowerCase().includes("customer") ||
    message.toLowerCase().includes("launch") ||
    message.toLowerCase().includes("draft");

  return {
    id: `agent-reply-${count}`,
    role: "agent",
    author: "Plot",
    timestamp: "Now",
    content: asksForAnotherDraft
      ? "I can add another draft to this work. Open an existing draft on the right, or ask for a specific format."
      : "I noted that. The current draft and references are still available from the summary card.",
  };
}
```

- [ ] **Step 3: Run lint for the new data files**

Run:

```bash
just lint-web
```

Expected:

```txt
pnpm --filter @plot/web lint
```

The command should complete without ESLint errors.

- [ ] **Step 4: Commit Task 1**

Run:

```bash
git add apps/web/src/lib/dev-context.ts apps/web/src/lib/api-client.ts
git commit -m "feat(web): add dev product data boundary"
```

### Task 2: Add Product Shell And App Routes

**Files:**

- Create: `apps/web/src/components/layout/product-sidebar.tsx`
- Create: `apps/web/src/components/layout/product-shell.tsx`
- Create: `apps/web/src/app/(app)/layout.tsx`

- [ ] **Step 1: Create the route-aware product sidebar**

Create `apps/web/src/components/layout/product-sidebar.tsx` with this content:

```tsx
"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  FileText,
  FolderOpen,
  MessageSquareText,
  Mic2,
  PackageOpen,
  Plus,
  Search,
  Settings,
} from "lucide-react";

import { getProductShellData } from "@/lib/api-client";
import { cn } from "@/lib/utils";

const navItems = [
  { href: "/sessions", label: "Sessions", icon: MessageSquareText },
  { href: "/sources", label: "Sources", icon: FolderOpen },
  { href: "/packs", label: "Packs", icon: PackageOpen },
  { href: "/voice", label: "Voice", icon: Mic2 },
  { href: "/settings", label: "Settings", icon: Settings },
];

export function ProductSidebar() {
  const pathname = usePathname();
  const { sessions } = getProductShellData();

  return (
    <aside className="flex h-full w-[280px] shrink-0 flex-col border-r border-black/10 bg-[#ede8df] text-[#1c1a17] dark:border-white/10 dark:bg-[#2b2b2d] dark:text-[#f4f1ea]">
      <div className="flex items-center gap-2 border-b border-black/10 px-4 py-4 dark:border-white/10">
        <div className="flex size-8 items-center justify-center rounded-md bg-[#1c1a17] text-sm font-semibold text-[#f8f5ef] dark:bg-[#f4f1ea] dark:text-[#19191a]">
          P
        </div>
        <div>
          <div className="text-sm font-semibold">Plot</div>
          <div className="text-xs text-black/50 dark:text-white/50">Dev workspace</div>
        </div>
      </div>

      <div className="space-y-1 px-3 py-3">
        <button className="flex w-full items-center gap-2 rounded-md px-3 py-2 text-left text-sm text-black/70 transition hover:bg-black/5 dark:text-white/70 dark:hover:bg-white/10">
          <Plus className="size-4" />
          New session
        </button>
        <button className="flex w-full items-center gap-2 rounded-md px-3 py-2 text-left text-sm text-black/70 transition hover:bg-black/5 dark:text-white/70 dark:hover:bg-white/10">
          <Search className="size-4" />
          Search
        </button>
      </div>

      <nav className="space-y-1 px-3 pb-3">
        {navItems.map((item) => {
          const Icon = item.icon;
          const active = pathname === item.href;

          return (
            <Link
              key={item.href}
              href={item.href}
              className={cn(
                "flex items-center gap-2 rounded-md px-3 py-2 text-sm transition",
                active
                  ? "bg-white text-[#171511] shadow-sm dark:bg-white/10 dark:text-white"
                  : "text-black/65 hover:bg-black/5 dark:text-white/65 dark:hover:bg-white/10",
              )}
            >
              <Icon className="size-4" />
              {item.label}
            </Link>
          );
        })}
      </nav>

      <div className="px-3 pt-2 text-[11px] font-medium uppercase text-black/40 dark:text-white/40">
        Recent sessions
      </div>

      <div className="min-h-0 flex-1 space-y-1 overflow-y-auto px-3 py-2">
        {sessions.map((session) => (
          <Link
            key={session.id}
            href="/sessions"
            className="group block rounded-md px-3 py-2 text-sm transition hover:bg-black/5 dark:hover:bg-white/10"
          >
            <div className="flex items-center gap-2">
              <FileText className="size-3.5 text-black/45 dark:text-white/45" />
              <span className="truncate text-black/75 dark:text-white/75">{session.title}</span>
              <span className="ml-auto text-xs text-black/40 dark:text-white/40">{session.updatedAt}</span>
            </div>
            <div className="mt-1 truncate pl-5 text-xs text-black/45 dark:text-white/45">
              {session.subtitle}
            </div>
          </Link>
        ))}
      </div>
    </aside>
  );
}
```

- [ ] **Step 2: Create the product shell with light/dark local state**

Create `apps/web/src/components/layout/product-shell.tsx` with this content:

```tsx
"use client";

import { Moon, PanelRight, Sun } from "lucide-react";
import type { ReactNode } from "react";
import { useState } from "react";

import { ProductSidebar } from "@/components/layout/product-sidebar";

export function ProductShell({ children }: { children: ReactNode }) {
  const [darkMode, setDarkMode] = useState(false);

  return (
    <div className={darkMode ? "dark" : undefined}>
      <div className="flex min-h-screen bg-[#f8f5ef] text-[#171511] dark:bg-[#141414] dark:text-[#f4f1ea]">
        <ProductSidebar />

        <div className="flex min-w-0 flex-1 flex-col">
          <header className="flex h-12 items-center justify-between border-b border-black/10 bg-[#fbfaf6]/90 px-4 backdrop-blur dark:border-white/10 dark:bg-[#181818]/90">
            <div className="text-sm font-medium">Plot workspace</div>
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => setDarkMode((value) => !value)}
                className="inline-flex size-8 items-center justify-center rounded-md text-black/60 transition hover:bg-black/5 dark:text-white/70 dark:hover:bg-white/10"
                aria-label="Toggle light and dark view"
              >
                {darkMode ? <Sun className="size-4" /> : <Moon className="size-4" />}
              </button>
              <div className="inline-flex items-center gap-2 rounded-md border border-black/10 px-2.5 py-1.5 text-xs text-black/55 dark:border-white/10 dark:text-white/55">
                <PanelRight className="size-3.5" />
                Dev
              </div>
            </div>
          </header>

          <main className="min-h-0 flex-1 overflow-hidden">{children}</main>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 3: Create the route group layout**

Create `apps/web/src/app/(app)/layout.tsx` with this content:

```tsx
import type { ReactNode } from "react";

import { ProductShell } from "@/components/layout/product-shell";

export default function AppLayout({ children }: { children: ReactNode }) {
  return <ProductShell>{children}</ProductShell>;
}
```

- [ ] **Step 4: Run lint for shell files**

Run:

```bash
just lint-web
```

Expected:

```txt
pnpm --filter @plot/web lint
```

The command should complete without ESLint errors.

- [ ] **Step 5: Commit Task 2**

Run:

```bash
git add apps/web/src/components/layout/product-sidebar.tsx apps/web/src/components/layout/product-shell.tsx apps/web/src/app/'(app)'/layout.tsx
git commit -m "feat(web): add product shell layout"
```

### Task 3: Build The Sessions Workspace

**Files:**

- Create: `apps/web/src/app/(app)/sessions/page.tsx`
- Create: `apps/web/src/features/sessions/sessions-workspace.tsx`
- Create: `apps/web/src/features/sessions/session-thread.tsx`
- Create: `apps/web/src/features/sessions/session-floating-summary.tsx`
- Create: `apps/web/src/features/sessions/session-side-panel.tsx`
- Create: `apps/web/src/features/sessions/session-composer.tsx`

- [ ] **Step 1: Create the Sessions route page**

Create `apps/web/src/app/(app)/sessions/page.tsx` with this content:

```tsx
import { SessionsWorkspace } from "@/features/sessions/sessions-workspace";

export default function SessionsPage() {
  return <SessionsWorkspace />;
}
```

- [ ] **Step 2: Create the floating summary component**

Create `apps/web/src/features/sessions/session-floating-summary.tsx` with this content:

```tsx
import { FileText, GitPullRequest } from "lucide-react";

import type { DraftDocument, ReferenceDocument } from "@/lib/dev-context";

type SessionFloatingSummaryProps = {
  drafts: DraftDocument[];
  references: ReferenceDocument[];
  onSelectDocument: (documentId: string) => void;
};

export function SessionFloatingSummary({
  drafts,
  references,
  onSelectDocument,
}: SessionFloatingSummaryProps) {
  return (
    <div className="absolute right-5 top-5 w-[260px] rounded-xl border border-black/10 bg-white/95 p-3 text-sm shadow-xl shadow-black/10 backdrop-blur dark:border-white/10 dark:bg-[#242424]/95 dark:shadow-black/30">
      <div className="text-xs font-semibold uppercase text-black/45 dark:text-white/45">Drafts</div>
      <div className="mt-2 space-y-1">
        {drafts.map((draft) => (
          <button
            key={draft.id}
            type="button"
            onClick={() => onSelectDocument(draft.id)}
            className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-black/75 transition hover:bg-black/5 dark:text-white/75 dark:hover:bg-white/10"
          >
            <FileText className="size-3.5" />
            <span className="truncate">{draft.title}</span>
          </button>
        ))}
      </div>

      <div className="mt-4 border-t border-black/10 pt-3 dark:border-white/10">
        <div className="text-xs font-semibold uppercase text-black/45 dark:text-white/45">References</div>
        <div className="mt-2 space-y-1">
          {references.map((reference) => (
            <button
              key={reference.id}
              type="button"
              onClick={() => onSelectDocument(reference.id)}
              className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-black/75 transition hover:bg-black/5 dark:text-white/75 dark:hover:bg-white/10"
            >
              <GitPullRequest className="size-3.5" />
              <span className="truncate">{reference.label}</span>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 3: Create the session thread component**

Create `apps/web/src/features/sessions/session-thread.tsx` with this content:

```tsx
import type { DraftDocument, ReferenceDocument, SessionMessage } from "@/lib/dev-context";
import { SessionFloatingSummary } from "@/features/sessions/session-floating-summary";

type SessionThreadProps = {
  messages: SessionMessage[];
  drafts: DraftDocument[];
  references: ReferenceDocument[];
  onSelectDocument: (documentId: string) => void;
};

export function SessionThread({
  messages,
  drafts,
  references,
  onSelectDocument,
}: SessionThreadProps) {
  return (
    <section className="relative min-h-0 flex-1 overflow-y-auto px-8 py-8">
      <div className="mx-auto max-w-3xl space-y-6 pb-40">
        {messages.map((message) => (
          <article key={message.id} className={message.role === "agent" ? "pl-8" : "pr-8"}>
            <div className="mb-2 flex items-center gap-2 text-xs text-black/45 dark:text-white/45">
              <span className="font-medium text-black/65 dark:text-white/65">{message.author}</span>
              <span>{message.timestamp}</span>
            </div>
            <div className="rounded-xl border border-black/10 bg-white px-4 py-3 text-sm leading-6 text-black/80 shadow-sm dark:border-white/10 dark:bg-[#222] dark:text-white/80">
              {message.content}
            </div>
          </article>
        ))}
      </div>

      <SessionFloatingSummary
        drafts={drafts}
        references={references}
        onSelectDocument={onSelectDocument}
      />
    </section>
  );
}
```

- [ ] **Step 4: Create the right document panel**

Create `apps/web/src/features/sessions/session-side-panel.tsx` with this content:

```tsx
import { FileText, GitPullRequest, PanelRightClose } from "lucide-react";

import type { DraftDocument, ReferenceDocument } from "@/lib/dev-context";

type SelectedSessionDocument =
  | { kind: "draft"; document: DraftDocument }
  | { kind: "reference"; document: ReferenceDocument };

type SessionSidePanelProps = {
  selectedDocument: SelectedSessionDocument | null;
  openDocuments: SelectedSessionDocument[];
  drafts: DraftDocument[];
  references: ReferenceDocument[];
  onSelectDocument: (documentId: string) => void;
  onClose: () => void;
};

export function SessionSidePanel({
  selectedDocument,
  openDocuments,
  drafts,
  references,
  onSelectDocument,
  onClose,
}: SessionSidePanelProps) {
  if (!selectedDocument) {
    return null;
  }

  return (
    <aside className="flex h-full w-[460px] shrink-0 flex-col border-l border-black/10 bg-[#fbfaf6] dark:border-white/10 dark:bg-[#181818]">
      <div className="flex h-12 items-center gap-1 border-b border-black/10 px-3 dark:border-white/10">
        {openDocuments.map((item) => {
          const active = item.document.id === selectedDocument.document.id;
          const Icon = item.kind === "draft" ? FileText : GitPullRequest;
          const label = item.kind === "draft" ? item.document.filename : item.document.label;

          return (
            <button
              key={item.document.id}
              type="button"
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
        <button
          type="button"
          onClick={onClose}
          className="ml-auto inline-flex size-8 items-center justify-center rounded-md text-black/55 transition hover:bg-black/5 dark:text-white/55 dark:hover:bg-white/10"
          aria-label="Close document panel"
        >
          <PanelRightClose className="size-4" />
        </button>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto px-6 py-6">
        {selectedDocument.kind === "draft" ? (
          <DraftView draft={selectedDocument.document} references={references} />
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
}: {
  draft: DraftDocument;
  references: ReferenceDocument[];
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
        className="min-h-[300px] w-full resize-none rounded-lg border border-black/10 bg-white p-4 text-sm leading-6 outline-none focus:border-black/30 dark:border-white/10 dark:bg-[#202020] dark:focus:border-white/30"
        defaultValue={draft.body}
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
```

- [ ] **Step 5: Create the composer component**

Create `apps/web/src/features/sessions/session-composer.tsx` with this content:

```tsx
"use client";

import { Send } from "lucide-react";
import { useState } from "react";

type SessionComposerProps = {
  onSubmit: (message: string) => void;
};

export function SessionComposer({ onSubmit }: SessionComposerProps) {
  const [message, setMessage] = useState("");

  return (
    <form
      className="border-t border-black/10 bg-[#f8f5ef] px-8 py-4 dark:border-white/10 dark:bg-[#141414]"
      onSubmit={(event) => {
        event.preventDefault();
        const trimmed = message.trim();

        if (!trimmed) {
          return;
        }

        onSubmit(trimmed);
        setMessage("");
      }}
    >
      <div className="mx-auto flex max-w-3xl items-end gap-2 rounded-xl border border-black/10 bg-white p-2 shadow-lg shadow-black/5 dark:border-white/10 dark:bg-[#242424]">
        <textarea
          value={message}
          onChange={(event) => setMessage(event.target.value)}
          className="min-h-12 flex-1 resize-none bg-transparent px-2 py-2 text-sm outline-none placeholder:text-black/35 dark:placeholder:text-white/35"
          placeholder="Ask Plot to revise, use another reference, or create another draft..."
          aria-label="Session message"
        />
        <button
          type="submit"
          className="inline-flex size-10 items-center justify-center rounded-lg bg-black text-white transition hover:bg-black/85 dark:bg-white dark:text-black dark:hover:bg-white/85"
          aria-label="Send message"
        >
          <Send className="size-4" />
        </button>
      </div>
    </form>
  );
}
```

- [ ] **Step 6: Create the session workspace state owner**

Create `apps/web/src/features/sessions/sessions-workspace.tsx` with this content:

```tsx
"use client";

import { PanelRightOpen } from "lucide-react";
import { useMemo, useState } from "react";

import {
  createDemoAgentReply,
  getSelectedDocument,
  getSessionsWorkspace,
  type SelectedDocument,
} from "@/lib/api-client";
import type { SessionMessage } from "@/lib/dev-context";
import { SessionComposer } from "@/features/sessions/session-composer";
import { SessionSidePanel } from "@/features/sessions/session-side-panel";
import { SessionThread } from "@/features/sessions/session-thread";

export function SessionsWorkspace() {
  const data = getSessionsWorkspace();
  const activeSession = data.sessions[0];
  const [messages, setMessages] = useState<SessionMessage[]>(activeSession.messages);
  const [selectedDocumentId, setSelectedDocumentId] = useState(activeSession.draftIds[0]);
  const [rightPanelOpen, setRightPanelOpen] = useState(true);
  const [openDocumentIds, setOpenDocumentIds] = useState<string[]>([
    activeSession.draftIds[0],
    activeSession.referenceIds[0],
  ]);

  const sessionDrafts = data.drafts.filter((draft) => activeSession.draftIds.includes(draft.id));
  const sessionReferences = data.references.filter((reference) =>
    activeSession.referenceIds.includes(reference.id),
  );

  const selectedDocument = getSelectedDocument(selectedDocumentId);

  const openDocuments = useMemo(() => {
    return openDocumentIds
      .map((documentId) => getSelectedDocument(documentId))
      .filter((item): item is SelectedDocument => Boolean(item));
  }, [openDocumentIds]);

  function selectDocument(documentId: string) {
    setSelectedDocumentId(documentId);
    setRightPanelOpen(true);
    setOpenDocumentIds((current) =>
      current.includes(documentId) ? current : [...current.slice(-2), documentId],
    );
  }

  function submitMessage(message: string) {
    const userMessage: SessionMessage = {
      id: `user-message-${messages.length + 1}`,
      role: "user",
      author: "You",
      timestamp: "Now",
      content: message,
    };
    const agentReply = createDemoAgentReply(message, messages.length + 2);

    setMessages((current) => [...current, userMessage, agentReply]);
  }

  return (
    <div className="flex h-[calc(100vh-3rem)] min-h-0">
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="border-b border-black/10 bg-[#fbfaf6] px-8 py-4 dark:border-white/10 dark:bg-[#181818]">
          <div className="text-xs font-medium uppercase text-black/45 dark:text-white/45">Session</div>
          <div className="mt-1 flex items-center justify-between gap-4">
            <div>
              <h1 className="text-2xl font-semibold">{activeSession.title}</h1>
              <p className="mt-1 text-sm text-black/55 dark:text-white/55">{activeSession.subtitle}</p>
            </div>
            {!rightPanelOpen && (
              <button
                type="button"
                onClick={() => setRightPanelOpen(true)}
                className="inline-flex items-center gap-2 rounded-md border border-black/10 px-3 py-2 text-sm dark:border-white/10"
              >
                <PanelRightOpen className="size-4" />
                Open document
              </button>
            )}
          </div>
        </header>

        <SessionThread
          messages={messages}
          drafts={sessionDrafts}
          references={sessionReferences}
          onSelectDocument={selectDocument}
        />
        <SessionComposer onSubmit={submitMessage} />
      </div>

      {rightPanelOpen && (
        <SessionSidePanel
          selectedDocument={selectedDocument}
          openDocuments={openDocuments}
          drafts={data.drafts}
          references={data.references}
          onSelectDocument={selectDocument}
          onClose={() => setRightPanelOpen(false)}
        />
      )}
    </div>
  );
}
```

- [ ] **Step 7: Run lint and build after Sessions**

Run:

```bash
just lint-web
just build-web
```

Expected:

```txt
pnpm --filter @plot/web lint
pnpm --filter @plot/web build
```

Both commands should complete successfully. The build should include `/sessions`.

- [ ] **Step 8: Commit Task 3**

Run:

```bash
git add apps/web/src/app/'(app)'/sessions/page.tsx apps/web/src/features/sessions
git commit -m "feat(web): add sessions workspace"
```

### Task 4: Add Sources And Packs Supporting Workspaces

**Files:**

- Create: `apps/web/src/app/(app)/sources/page.tsx`
- Create: `apps/web/src/app/(app)/packs/page.tsx`
- Create: `apps/web/src/features/sources/sources-workspace.tsx`
- Create: `apps/web/src/features/packs/packs-workspace.tsx`

- [ ] **Step 1: Create Sources route page**

Create `apps/web/src/app/(app)/sources/page.tsx` with this content:

```tsx
import { SourcesWorkspace } from "@/features/sources/sources-workspace";

export default function SourcesPage() {
  return <SourcesWorkspace />;
}
```

- [ ] **Step 2: Create Sources workspace**

Create `apps/web/src/features/sources/sources-workspace.tsx` with this content:

```tsx
"use client";

import { useState } from "react";

import { getSourcesWorkspace } from "@/lib/api-client";

export function SourcesWorkspace() {
  const { references, drafts } = getSourcesWorkspace();
  const [selectedReferenceId, setSelectedReferenceId] = useState(references[0]?.id);
  const selectedReference = references.find((reference) => reference.id === selectedReferenceId);

  return (
    <div className="grid h-[calc(100vh-3rem)] grid-cols-[minmax(360px,420px)_1fr] overflow-hidden">
      <section className="border-r border-black/10 bg-[#f8f5ef] p-6 dark:border-white/10 dark:bg-[#141414]">
        <h1 className="text-2xl font-semibold">Sources</h1>
        <p className="mt-1 text-sm text-black/55 dark:text-white/55">
          References Plot can use when drafting updates.
        </p>

        <div className="mt-6 space-y-2">
          {references.map((reference) => (
            <button
              key={reference.id}
              type="button"
              onClick={() => setSelectedReferenceId(reference.id)}
              className={`w-full rounded-lg border p-4 text-left transition ${
                reference.id === selectedReferenceId
                  ? "border-black/20 bg-white dark:border-white/20 dark:bg-white/10"
                  : "border-black/10 bg-white/60 hover:bg-white dark:border-white/10 dark:bg-white/5 dark:hover:bg-white/10"
              }`}
            >
              <div className="flex items-center justify-between gap-3">
                <div className="font-medium">{reference.label}</div>
                <div className="rounded-full bg-black/5 px-2 py-1 text-xs text-black/55 dark:bg-white/10 dark:text-white/55">
                  {reference.status}
                </div>
              </div>
              <div className="mt-1 text-sm text-black/55 dark:text-white/55">{reference.title}</div>
            </button>
          ))}
        </div>
      </section>

      <section className="overflow-y-auto bg-[#fbfaf6] p-8 dark:bg-[#181818]">
        {selectedReference && (
          <article className="mx-auto max-w-3xl space-y-6">
            <div>
              <div className="text-xs uppercase text-black/45 dark:text-white/45">
                {selectedReference.sourceType}
              </div>
              <h2 className="mt-2 text-3xl font-semibold">{selectedReference.title}</h2>
              <p className="mt-1 text-sm text-black/55 dark:text-white/55">
                {selectedReference.label} · {selectedReference.date}
              </p>
            </div>

            <p className="text-sm leading-6 text-black/70 dark:text-white/70">
              {selectedReference.summary}
            </p>

            <section>
              <h3 className="text-sm font-semibold">Used in drafts</h3>
              <div className="mt-3 grid gap-2">
                {drafts
                  .filter((draft) => selectedReference.usedInDraftIds.includes(draft.id))
                  .map((draft) => (
                    <div key={draft.id} className="rounded-lg border border-black/10 bg-white p-3 text-sm dark:border-white/10 dark:bg-white/5">
                      {draft.title}
                    </div>
                  ))}
              </div>
            </section>

            <section>
              <h3 className="text-sm font-semibold">Notes</h3>
              <ul className="mt-3 space-y-2 text-sm text-black/65 dark:text-white/65">
                {selectedReference.notes.map((note) => (
                  <li key={note} className="rounded-lg bg-black/5 px-3 py-2 dark:bg-white/10">
                    {note}
                  </li>
                ))}
              </ul>
            </section>
          </article>
        )}
      </section>
    </div>
  );
}
```

- [ ] **Step 3: Create Packs route page**

Create `apps/web/src/app/(app)/packs/page.tsx` with this content:

```tsx
import { PacksWorkspace } from "@/features/packs/packs-workspace";

export default function PacksPage() {
  return <PacksWorkspace />;
}
```

- [ ] **Step 4: Create Packs workspace**

Create `apps/web/src/features/packs/packs-workspace.tsx` with this content:

```tsx
"use client";

import { useState } from "react";

import { getPacksWorkspace } from "@/lib/api-client";

export function PacksWorkspace() {
  const { packs, drafts, references } = getPacksWorkspace();
  const [selectedPackId, setSelectedPackId] = useState(packs[0]?.id);
  const selectedPack = packs.find((pack) => pack.id === selectedPackId);
  const packDrafts = drafts.filter((draft) => selectedPack?.draftIds.includes(draft.id));
  const selectedDraft = packDrafts[0];
  const usedReferences = references.filter((reference) => selectedDraft?.referenceIds.includes(reference.id));

  return (
    <div className="grid h-[calc(100vh-3rem)] grid-cols-[360px_1fr] overflow-hidden">
      <section className="border-r border-black/10 bg-[#f8f5ef] p-6 dark:border-white/10 dark:bg-[#141414]">
        <h1 className="text-2xl font-semibold">Packs</h1>
        <p className="mt-1 text-sm text-black/55 dark:text-white/55">
          Saved results from prior requests.
        </p>

        <div className="mt-6 space-y-2">
          {packs.map((pack) => (
            <button
              key={pack.id}
              type="button"
              onClick={() => setSelectedPackId(pack.id)}
              className={`w-full rounded-lg border p-4 text-left transition ${
                pack.id === selectedPackId
                  ? "border-black/20 bg-white dark:border-white/20 dark:bg-white/10"
                  : "border-black/10 bg-white/60 hover:bg-white dark:border-white/10 dark:bg-white/5 dark:hover:bg-white/10"
              }`}
            >
              <div className="font-medium">{pack.title}</div>
              <div className="mt-1 text-sm text-black/55 dark:text-white/55">{pack.request}</div>
              <div className="mt-3 flex items-center justify-between text-xs text-black/45 dark:text-white/45">
                <span>{pack.status}</span>
                <span>{pack.updatedAt}</span>
              </div>
            </button>
          ))}
        </div>
      </section>

      <section className="overflow-y-auto bg-[#fbfaf6] p-8 dark:bg-[#181818]">
        {selectedPack && selectedDraft && (
          <article className="mx-auto max-w-4xl space-y-6">
            <div>
              <div className="text-xs uppercase text-black/45 dark:text-white/45">Saved result</div>
              <h2 className="mt-2 text-3xl font-semibold">{selectedPack.title}</h2>
              <p className="mt-1 text-sm text-black/55 dark:text-white/55">{selectedPack.request}</p>
            </div>

            <div className="grid gap-3 md:grid-cols-2">
              {packDrafts.map((draft) => (
                <div key={draft.id} className="rounded-lg border border-black/10 bg-white p-4 dark:border-white/10 dark:bg-white/5">
                  <div className="font-medium">{draft.title}</div>
                  <div className="mt-1 text-sm text-black/55 dark:text-white/55">{draft.status}</div>
                </div>
              ))}
            </div>

            <section className="rounded-xl border border-black/10 bg-white p-5 dark:border-white/10 dark:bg-white/5">
              <div className="text-sm font-semibold">{selectedDraft.filename}</div>
              <div className="mt-4 whitespace-pre-line text-sm leading-6 text-black/75 dark:text-white/75">
                {selectedDraft.body}
              </div>
            </section>

            <section>
              <h3 className="text-sm font-semibold">References</h3>
              <div className="mt-3 grid gap-2">
                {usedReferences.map((reference) => (
                  <div key={reference.id} className="rounded-lg border border-black/10 bg-white p-3 text-sm dark:border-white/10 dark:bg-white/5">
                    <div className="font-medium">{reference.label}</div>
                    <div className="mt-1 text-black/55 dark:text-white/55">{reference.summary}</div>
                  </div>
                ))}
              </div>
            </section>
          </article>
        )}
      </section>
    </div>
  );
}
```

- [ ] **Step 5: Run lint and build after Sources/Packs**

Run:

```bash
just lint-web
just build-web
```

Expected:

```txt
pnpm --filter @plot/web lint
pnpm --filter @plot/web build
```

Both commands should complete successfully. The build should include `/sources` and `/packs`.

- [ ] **Step 6: Commit Task 4**

Run:

```bash
git add apps/web/src/app/'(app)'/sources/page.tsx apps/web/src/app/'(app)'/packs/page.tsx apps/web/src/features/sources apps/web/src/features/packs
git commit -m "feat(web): add sources and packs workspaces"
```

### Task 5: Add Voice And Settings Supporting Pages

**Files:**

- Create: `apps/web/src/app/(app)/voice/page.tsx`
- Create: `apps/web/src/app/(app)/settings/page.tsx`

- [ ] **Step 1: Create Voice page**

Create `apps/web/src/app/(app)/voice/page.tsx` with this content:

```tsx
import { getVoiceWorkspace } from "@/lib/api-client";

export default function VoicePage() {
  const voice = getVoiceWorkspace();

  return (
    <div className="h-[calc(100vh-3rem)] overflow-y-auto bg-[#fbfaf6] p-8 dark:bg-[#181818]">
      <div className="mx-auto max-w-5xl">
        <div>
          <div className="text-xs font-medium uppercase text-black/45 dark:text-white/45">Voice</div>
          <h1 className="mt-2 text-3xl font-semibold">Writing guidance</h1>
          <p className="mt-2 max-w-2xl text-sm leading-6 text-black/60 dark:text-white/60">
            Practical guidance for drafts created in this workspace.
          </p>
        </div>

        <div className="mt-8 grid gap-4 md:grid-cols-2">
          <GuidanceSection title="Preferred" items={voice.preferred} />
          <GuidanceSection title="Avoid" items={voice.avoid} />
          <GuidanceSection title="Examples" items={voice.examples} />
          <GuidanceSection title="Channel notes" items={voice.channelNotes} />
        </div>
      </div>
    </div>
  );
}

function GuidanceSection({ title, items }: { title: string; items: string[] }) {
  return (
    <section className="rounded-xl border border-black/10 bg-white p-5 dark:border-white/10 dark:bg-white/5">
      <h2 className="text-sm font-semibold">{title}</h2>
      <ul className="mt-4 space-y-3 text-sm leading-6 text-black/65 dark:text-white/65">
        {items.map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
    </section>
  );
}
```

- [ ] **Step 2: Create Settings page**

Create `apps/web/src/app/(app)/settings/page.tsx` with this content:

```tsx
import { getSettingsWorkspace } from "@/lib/api-client";

export default function SettingsPage() {
  const { workspace, members } = getSettingsWorkspace();

  return (
    <div className="h-[calc(100vh-3rem)] overflow-y-auto bg-[#fbfaf6] p-8 dark:bg-[#181818]">
      <div className="mx-auto max-w-4xl">
        <div>
          <div className="text-xs font-medium uppercase text-black/45 dark:text-white/45">Settings</div>
          <h1 className="mt-2 text-3xl font-semibold">Workspace settings</h1>
          <p className="mt-2 text-sm text-black/60 dark:text-white/60">
            Dev workspace configuration for the product shell.
          </p>
        </div>

        <div className="mt-8 space-y-4">
          <section className="rounded-xl border border-black/10 bg-white p-5 dark:border-white/10 dark:bg-white/5">
            <h2 className="text-sm font-semibold">Workspace</h2>
            <dl className="mt-4 grid gap-3 text-sm">
              <div className="flex justify-between gap-4">
                <dt className="text-black/50 dark:text-white/50">Name</dt>
                <dd>{workspace.name}</dd>
              </div>
              <div className="flex justify-between gap-4">
                <dt className="text-black/50 dark:text-white/50">Environment</dt>
                <dd>{workspace.environment}</dd>
              </div>
              <div className="flex justify-between gap-4">
                <dt className="text-black/50 dark:text-white/50">Source connection</dt>
                <dd>{workspace.connectionLabel}</dd>
              </div>
            </dl>
          </section>

          <section className="rounded-xl border border-black/10 bg-white p-5 dark:border-white/10 dark:bg-white/5">
            <h2 className="text-sm font-semibold">Members</h2>
            <div className="mt-4 flex flex-wrap gap-2">
              {members.map((member) => (
                <span key={member} className="rounded-full bg-black/5 px-3 py-1.5 text-sm dark:bg-white/10">
                  {member}
                </span>
              ))}
            </div>
          </section>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 3: Run lint and build after Voice/Settings**

Run:

```bash
just lint-web
just build-web
```

Expected:

```txt
pnpm --filter @plot/web lint
pnpm --filter @plot/web build
```

Both commands should complete successfully. The build should include `/voice` and `/settings`.

- [ ] **Step 4: Commit Task 5**

Run:

```bash
git add apps/web/src/app/'(app)'/voice/page.tsx apps/web/src/app/'(app)'/settings/page.tsx
git commit -m "feat(web): add voice and settings pages"
```

### Task 6: Final Verification And Product Pass

**Files:**

- Modify only files created in Tasks 1-5 if lint, build, or manual checks reveal a concrete issue.

- [ ] **Step 1: Run full requested verification**

Run:

```bash
just lint-web
just build-web
```

Expected:

```txt
pnpm --filter @plot/web lint
pnpm --filter @plot/web build
```

Both commands should complete successfully.

- [ ] **Step 2: Start the local web app for manual checks**

Run:

```bash
just dev-web
```

Expected:

```txt
pnpm --filter @plot/web dev
```

The command should print a local URL. Open that URL in a browser.

- [ ] **Step 3: Manually check the product routes**

Visit these paths:

```txt
/sessions
/sources
/packs
/voice
/settings
```

Expected:

- `/sessions` shows the session list shell, central session thread, floating Drafts/References summary, composer, and right document panel.
- `/sources` shows source items and a selected source detail.
- `/packs` shows saved result groups and a selected draft preview.
- `/voice` shows Preferred, Avoid, Examples, and Channel notes.
- `/settings` shows workspace, environment, source connection, and members.

- [ ] **Step 4: Manually check the required interactions**

On `/sessions`, perform these checks:

```txt
Click Drafts -> Changelog
Click References -> PR #184
Close the right panel
Click Drafts -> Customer update
Type "Make the customer update shorter" in the composer
Submit the composer
Toggle light/dark view from the shell header
```

Expected:

- Clicking `Changelog` opens the right panel in draft view.
- Clicking `PR #184` changes the right panel to reference view.
- Closing the right panel leaves the floating summary visible.
- Clicking `Customer update` reopens the right panel in draft view.
- Submitting the composer appends a user message and a demo agent response.
- Light/dark toggle changes the product shell colors without affecting route navigation.

- [ ] **Step 5: Stop the dev server**

Use `Ctrl-C` in the terminal running `just dev-web`.

Expected:

```txt
^C
```

The dev server stops.

- [ ] **Step 6: Confirm git status**

Run:

```bash
git status --short
```

Expected after Task 6 fixes are committed:

```txt
```

No output means the worktree is clean.

- [ ] **Step 7: Commit final fixes if any files changed during verification**

If `git status --short` printed files changed during Task 6, run:

```bash
git add apps/web/src
git commit -m "fix(web): polish product shell verification"
```

Expected:

```txt
[feat-web-product-shell <hash>] fix(web): polish product shell verification
```

If `git status --short` printed no files, do not create an empty commit.

## Self-Review Notes

- Spec coverage: Tasks 1-5 cover route group, product nav, no auth gate, data boundary, dev context, sessions, sources, packs, voice, settings, local interactions, and customer-facing labels. Task 6 covers lint, build, and manual interaction checks.
- Terminology check: The plan uses `Drafts`, `References`, `Needs your call`, `Approve`, and `Ask for changes`. It does not use internal quality-score language for UI copy.
- Data consistency: `draftIds`, `referenceIds`, `usedInDraftIds`, and `selectedDocumentId` all use the same string ids defined in `dev-context.ts`.
- Scope check: The plan stays inside the frontend shell and does not add backend calls, auth, persistence, publishing, or external integrations.
