# Web Product Shell Design

Date: 2026-07-04
Status: Approved design for implementation planning

## Goal

Build the first internal Plot product shell for the dev workspace. The shell
should introduce the application routes, navigation, dev data boundary, and a
real workflow placeholder for creating and reviewing generated writing from a
session.

This is not an authenticated production workspace. There is no auth gate in
scope. The app assumes a dev workspace with seeded sessions, drafts, references,
sources, packs, voice settings, and workspace settings.

## Scope

Implement an interactive frontend placeholder, not a backend integration.

In scope:

- `app/(app)` route group.
- Product routes at `/sessions`, `/sources`, `/packs`, `/voice`, and
  `/settings`.
- Product shell navigation for Sessions, Sources, Packs, Voice, and Settings.
- No authentication gate.
- `lib/api-client.ts` as the frontend data boundary.
- `lib/dev-context.ts` as seeded dev workspace data.
- Feature folders for `features/sessions`, `features/sources`, and
  `features/packs`.
- A rich `/sessions` workflow with local state.
- Supporting, non-empty workflow placeholders for Sources, Packs, Voice, and
  Settings.
- `just lint-web` and `just build-web` verification.

Out of scope:

- Real API calls.
- Real auth, workspace membership, or permissions.
- Persistence beyond local component state.
- Production source adapter setup.
- Publishing or external integrations.
- AI quality scores, coverage percentages, claim-risk scores, or similar
  internal evaluation language in the UI.

## Route Shape

The existing landing page at `/` stays unchanged. The internal product shell
uses top-level product routes:

```txt
/sessions
/sources
/packs
/voice
/settings
```

These routes live under `apps/web/src/app/(app)/`, so the route group keeps the
source tree organized without adding `(app)` to the URL.

## UX Direction

The product shell should use a desktop workbench structure: a session list on
the left, a durable work session in the center, and a toggleable document panel
on the right.

The visual treatment should remain Plot-specific. Use the existing Plot design
tokens and support light/dark mode styling, but keep all user-facing labels in
Plot's own product language.

The main screen is `/sessions`. It should feel like a place where the user asks
Plot to create a specific deliverable, then inspects generated drafts and the
references used.

Example session request:

```txt
Create a changelog for July 1-7.
```

Some requests produce one draft. Other requests may produce multiple drafts as a
saved update pack. The UI should not imply that every request always creates a
multi-artifact pack.

## Sessions Screen

`/sessions` is the highest-fidelity screen.

Layout:

```txt
┌───────────────┬──────────────────────────────────────────────┬──────────────────────────────┐
│ Sidebar       │ Session                                      │ Right panel                  │
│               │                                              │                              │
│ New session   │ User                                         │ document tabs                │
│ Search        │ "Create a changelog for July 1-7."           │ [Changelog.md] [PR #184]     │
│               │                                              │                              │
│ Sessions      │ Agent                                        │ selected document            │
│ > Changelog   │ "I drafted a changelog using the release     │ draft editor or reference    │
│   Launch post │  tag and six merged PRs."                    │ viewer                       │
│               │                                              │                              │
│ Sources       │             ┌──────────────────────────┐     │                              │
│ Packs         │             │ Drafts                   │     │                              │
│ Voice         │             │ • Changelog              │     │                              │
│ Settings      │             │ • Customer update        │     │                              │
│               │             │                          │     │                              │
│               │             │ References               │     │                              │
│               │             │ • PR #184 Auth copy      │     │                              │
│               │             │ • Release v0.4           │     │                              │
│               │             └──────────────────────────┘     │                              │
│               │                                              │                              │
│               │ Composer                                     │                              │
│               │ [Ask Plot to revise or create another draft] │                              │
└───────────────┴──────────────────────────────────────────────┴──────────────────────────────┘
```

The center session contains:

- Existing user and agent messages from the seeded session.
- A composer for adding a local user message.
- A demo agent reply after composer submit.
- A floating summary card that lists generated `Drafts` and used
  `References`.

The floating summary is intentionally small. It is not a metrics panel. It is a
quick list of what Plot generated and what it used.

Recommended labels:

```txt
Drafts
- Changelog
- Customer update

References
- PR #184 Auth copy
- Release v0.4
- Issue #77
```

Avoid labels such as:

- Artifacts
- Checks
- Coverage
- Claim risk
- Style score

## Right Panel Behavior

The right panel is a document viewer/editor, not a permanent review rail.

It can be open or closed. When open, it shows document-style tabs for generated
drafts and references, similar to a file/document panel.

Click behavior:

- Clicking a draft in the floating summary opens the right panel and selects
  that draft document.
- Clicking a reference in the floating summary opens the right panel and
  switches to a reference document view.
- Clicking another draft or reference changes the selected document and may add
  or activate a document tab.
- Closing the right panel keeps the floating summary visible in the session.

Draft document view:

```txt
Changelog.md

July 1-7 Changelog

[editable draft body]

References used
- PR #184 Auth copy
- Release v0.4

Needs your call
- Include beta wording in customer-facing copy?

[Approve] [Ask for changes]
```

Reference document view:

```txt
PR #184

Auth copy improvements
Merged Jul 3

Summary
Updated empty-state copy and clarified the login recovery flow.

Used in
- Changelog
- Customer update

Notes
- Keep beta wording internal.
```

The panel should make generated results easy to read and edit. It should not
turn the UI into a dashboard of AI evaluations.

## Supporting Pages

The supporting pages should be real workflow placeholders, but simpler than
`/sessions`.

### Sources

Purpose: show the reference inventory that Plot can use.

The page should include a list of source items such as PRs, releases, and
issues. Use customer-facing statuses:

- Used in draft
- Not used
- Needs context

Clicking a source item should show a detail preview. The page should not expose
internal adapter or normalization jargon.

### Packs

Purpose: show saved results from prior requests.

A pack is a saved result grouping from a request. It may contain one draft or
multiple drafts depending on what the user asked for. The page should show
saved result groups and their drafts. Selecting a draft should show a preview
with references and approval state.

### Voice

Purpose: show team writing guidance.

Use practical writing language:

- Preferred
- Avoid
- Examples
- Channel notes

Do not show style-match percentages or scoring.

### Settings

Purpose: show the dev workspace assumption.

Include workspace name, source connection placeholder, members placeholder, and
environment status. Do not add auth gates or real settings persistence.

## Data Boundary

`lib/dev-context.ts` owns seeded dev data:

- workspace
- user
- sessions
- messages
- drafts
- references
- source items
- saved packs
- voice guidance
- settings placeholders

`lib/api-client.ts` is the frontend boundary used by feature modules. It should
return the seeded dev context for now. Feature modules should import from
`api-client.ts`, not directly from `dev-context.ts`, so the data source can be
replaced later.

No network calls are needed for this implementation.

## Component Structure

```txt
apps/web/src/
  app/(app)/
    layout.tsx
    sessions/page.tsx
    sources/page.tsx
    packs/page.tsx
    voice/page.tsx
    settings/page.tsx

  components/layout/
    product-shell.tsx
    product-sidebar.tsx

  features/sessions/
    sessions-workspace.tsx
    session-thread.tsx
    session-floating-summary.tsx
    session-side-panel.tsx
    session-composer.tsx

  features/sources/
    sources-workspace.tsx

  features/packs/
    packs-workspace.tsx

  lib/
    api-client.ts
    dev-context.ts
```

The route pages should stay thin and delegate behavior to feature modules.

## Local State

`/sessions` owns local UI state:

```txt
selectedSessionId
selectedDocumentId
isRightPanelOpen
messages
drafts
references
```

Expected flow:

1. `/sessions` loads seeded session data.
2. The center thread renders user and agent messages.
3. The floating summary renders `Drafts` and `References`.
4. Clicking a draft opens the right panel with the draft document.
5. Clicking a reference opens the right panel with the reference document.
6. Submitting the composer appends a user message and a demo agent response.
7. The right panel can be closed and reopened.

The composer behavior should stay lightweight. It should demonstrate the shape
of the workflow without pretending a real agent exists.

## Visual Rules

- Support both light and dark styling.
- Use Plot's existing font setup and design tokens where possible.
- Keep the interface dense and workbench-like.
- Avoid oversized marketing sections inside the app shell.
- Avoid nested decorative cards.
- Keep the floating summary compact.
- Keep the right panel readable as a document viewer/editor.
- Use familiar icons from `lucide-react` for navigation and controls.
- Use stable dimensions for sidebars, floating summary, and right panel so
  interactions do not shift the layout.

## Verification

Automated commands:

```sh
just lint-web
just build-web
```

Manual checks:

- `/sessions` renders.
- `/sources` renders.
- `/packs` renders.
- `/voice` renders.
- `/settings` renders.
- Sidebar active state follows the current route.
- Floating Drafts click opens the right panel in draft view.
- Floating References click opens the right panel in reference view.
- Composer adds a local user message and demo agent response.
- Right panel can close and reopen.
