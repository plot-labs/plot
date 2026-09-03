# Plot Design Specification

This is the constitution the team agreed to, written as a spec Product Engineer can implement and QA can fail a deploy against.

**Product:** [useplot.xyz](https://useplot.xyz)  
**Tagline:** Ship fast. Write less. Source-cited changelogs from shipped work.  
**Principle:** Human review, never auto-publish.

---

## Purpose and Non-Goals

### Purpose
Plot generates source-cited changelog drafts from shipped GitHub releases and lets operators review, edit, and publish them to a hosted public changelog or export as Markdown.

### Non-Goals
- Automatic publication without human review
- Multi-repository release aggregation before single-repo loop is proven
- Speculative or incomplete feature previews in production UI
- Citation-free claims in any published surface

---

## The Seven Laws

### 1. No claim in the UI without an inspectable citation; otherwise it stays draft.
**Implication:** Every sentence in a changelog artifact must be backed by captured source evidence or marked as user-edited. Uncited AI-generated claims cannot reach the publish step. The UI surfaces citations through chips, popovers, and sources panels.

**Enforced in:**
- `SourcesPopover` (artifact editor)
- `PublicCitationChip` (public changelog)
- `PublishDialog` confirmation flow

### 2. Human is the last mile. Drafts may be automated; publish is never automatic. No "looks done" states that skip review.
**Implication:** The publish action requires explicit user confirmation. Draft generation happens on release webhooks; publication requires a button press. Status labels distinguish `draft`, `needs review`, `cited`, and `published` states. No state may silently become published.

**Enforced in:**
- `PublishDialog` requires manual trigger
- Artifact states: `DRAFT`, `NEEDS_REVIEW`, `READY`, `PUBLISHED`
- No auto-publish on routine completion

### 3. Range must be exact (base/head, release boundary, in/out). Fuzzy range cannot ship.
**Implication:** GitHub release automation captures exact commit SHAs for base and head. Releases with ambiguous boundaries stay in `NEEDS_RANGE` status until resolution. The UI displays tag names, release boundaries, and range verification status.

**Enforced in:**
- `GitHubReleaseRequestPersistence` (API)
- `RoutineReleaseActivity` displays range status
- Release monitoring checks exact boundaries

### 4. One workspace, one release source until the loop is proven. Do not sprawl repos/surfaces/variants early.
**Implication:** Private beta focuses on a single GitHub repository per workspace. Routines workspace and integrations settings configure one primary source. Multi-repo aggregation is deferred.

**Enforced in:**
- Onboarding flow configures one repository
- Routines create one `ON_GITHUB_RELEASE` automation
- No multi-repo picker in current product

### 5. Coming next must be labeled as coming next. Do not present planned features as live.
**Implication:** Landing page features section includes a "Coming next" card (04) that explicitly labels future capabilities. Marketing copy must not claim unshipped features as available.

**Enforced in:**
- `FeaturesSection` card 04: "Coming next"
- Feature labels: "blocks", "signals", "style", "pack"

### 6. One primary action per screen. Workspace is an operator review tool; public changelog is customer-readable prose. Same facts, different register.
**Implication:** Each workspace screen has a single primary CTA. Artifacts workspace focuses on editing and publishing. Public changelog focuses on reading published entries with citations. Layout, density, and language differ; factual content remains consistent.

**Enforced in:**
- Artifacts: primary action is "Publish" or "Save draft"
- Chat: primary action is send message
- Routines: primary action is "Create routine"
- Public changelog: no actions, only navigation and citation inspection

### 7. Copy prefers evidence over confidence. Marketing must use the same facts as the product. No inflated metrics.
**Implication:** Marketing landing page uses factual descriptions of what Plot does: "resolves the exact range", "prepares a changelog draft with saved citations", "you approve before anything goes live." No unsubstantiated claims or fake metrics.

**Enforced in:**
- `HeroSection`: "From shipped release to cited changelog you can publish."
- `FeaturesSection`: factual descriptions with no superlatives
- `HowItWorksSection`: process-based explanation

---

## Product Surfaces

### Internal / Operator Surfaces

#### Landing (`/`)
- **Purpose:** Marketing and waitlist capture
- **Primary action:** Join waitlist
- **Laws enforced:** 5 (coming next labeled), 7 (factual copy)
- **Layout:** `Navigation` → `HeroSection` → `FeaturesSection` → `StyleSection` → `HowItWorksSection` → `SecuritySection` → `CtaSection` → `FooterSection`

#### Sign-in (`/sign-in`)
- **Purpose:** Authentication entry
- **Primary action:** Sign in with GitHub
- **Layout:** Centered auth card with `AnimatedDitherArtwork`

#### Onboarding (`/onboarding`)
- **Purpose:** First-run setup: GitHub connection → repository selection → release routine creation → first run
- **Primary action:** Step-specific (Connect GitHub / Create routine / Run routine)
- **Laws enforced:** 4 (one workspace, one release source)
- **States:** Step 1 (connect), Step 2 (select repo), Step 3 (create routine)
- **Component:** `OnboardingFlow`

#### Home (`/home`)
- **Purpose:** Activity dashboard (current implementation redirects to Chat)
- **Primary action:** View recent activity
- **Layout:** TBD (currently redirects)

#### Chat (`/chat`)
- **Purpose:** Interactive, source-grounded AgentRun work
- **Primary action:** Send message
- **Laws enforced:** 1 (citations in responses), 6 (one primary action)
- **Layout:** `ChatHome` (empty state) or `ChatActiveWorkspace` (active thread)
- **Components:** `ChatComposer`, `ChatThread`, `ChatSourceCitations`
- **Query params:** `?chat=<id>`, `?agent=<id>`, `?artifact=<id>`

#### Routines (`/routines`)
- **Purpose:** Scheduled or explicitly started AgentRun work
- **Primary action:** Create routine
- **Laws enforced:** 3 (exact range), 4 (one source), 6 (one primary action)
- **Layout:** Routines list + create panel + activity detail
- **Components:** `RoutinesWorkspace`, `RoutineTriggerPicker`, `SourceRepositoryPicker`, `RoutineReleaseActivity`
- **States:** `ACTIVE`, `PAUSED`, `DISABLED`
- **Cadences:** `ON_GITHUB_RELEASE`, `DAILY`, `WEEKLY`, `MONTHLY`, `MANUAL`

#### Artifacts (`/artifacts`)
- **Purpose:** Editable, revisioned, source-cited documents and Markdown export
- **Primary action:** Publish or Save draft
- **Laws enforced:** 1 (citations required), 2 (human review), 6 (one primary action)
- **Layout:** Artifacts list or canvas workspace
- **Components:** `ArtifactsWorkspace`, `ArtifactCanvasWorkspace`, `ArtifactEditorChrome`, `TiptapDraftEditor`, `CitedDraftEditor`, `SourcesPopover`, `PublishDialog`, `ExportDialog`
- **States:** `DRAFT`, `NEEDS_REVIEW`, `READY`, `PUBLISHED`
- **Save states:** `saved`, `saving`, `dirty`, `error`

#### Integrations (`/settings/integrations`)
- **Purpose:** GitHub connections, repository scopes, writing block configuration
- **Primary action:** Connect/disconnect GitHub installation
- **Laws enforced:** 4 (one workspace, one source)
- **Layout:** `IntegrationsWorkspace`, `IntegrationsNavigation`
- **Components:** GitHub connection cards, repository list, monitoring status

#### Settings (`/settings/general`, `/settings/account`)
- **Purpose:** Workspace and account configuration
- **Primary action:** Update settings
- **Layout:** `WorkspaceGeneral`, `AccountSettings`
- **Navigation:** `SidebarNavigation` in settings mode

### Public / Customer Surfaces

#### Public Changelog List (`/:workspaceSlug/changelog`)
- **Purpose:** Customer-readable list of published changelog entries
- **Primary action:** Navigate to entry
- **Laws enforced:** 1 (citations visible), 6 (reading register), 7 (factual content)
- **Layout:** `PublicChangelogLayout` → `PublicChangelogList`
- **No editing, no operator controls**

#### Public Changelog Entry (`/:workspaceSlug/changelog/:entrySlug`)
- **Purpose:** Customer-readable individual changelog entry with citations
- **Primary action:** Inspect citations
- **Laws enforced:** 1 (citations inspectable), 6 (reading register), 7 (factual content)
- **Layout:** `PublicChangelogLayout` → `PublicChangelogEntryView`
- **Components:** `PublicCitationChip`, Markdown rendering with citation chips, sources section
- **No editing, no operator controls**

---

## Voice & Copy

### Workspace (Operator Register)
- **Audience:** Product team, release managers, operators
- **Tone:** Direct, technical, action-oriented
- **Density:** High information density, compact controls
- **Examples:**
  - "Save draft" / "Publish changelog"
  - "Routine paused" / "Needs review"
  - "Sources · 3" / "Statement 2 — 'excerpt'"

### Public Changelog (Customer Register)
- **Audience:** End users, customers reading updates
- **Tone:** Clear, professional, customer-focused
- **Density:** Readable prose, generous spacing
- **Examples:**
  - Entry titles: descriptive release names
  - Body: Markdown paragraphs with inline citation chips `[1, 2]`
  - Sources section: numbered list with provider labels

### Landing (Marketing Register)
- **Audience:** Prospective users evaluating Plot
- **Tone:** Factual, clear, confident without superlatives
- **Examples:**
  - "Ship fast. Write less."
  - "From shipped release to cited changelog you can publish."
  - "Plot never auto-publishes — you approve before anything goes live."

### Korean Language Support
- `README.ko.md` provides Korean translations
- UI components support Korean text (fonts: Instrument Sans, Instrument Serif, JetBrains Mono)
- Product surfaces are English-first; localization is future work

---

## Visual System

### Color Palette (Light Mode)
Plot uses a neutral, near-monochrome palette with minimal color accents.

**Base Colors:**
- `--background`: `oklch(0.985 0 0)` — off-white background
- `--foreground`: `oklch(0.145 0 0)` — near-black text
- `--card`: `oklch(1 0 0)` — white cards
- `--muted`: `oklch(0.94 0 0)` — muted background
- `--muted-foreground`: `oklch(0.45 0 0)` — muted text
- `--border`: `oklch(0.88 0 0)` — subtle borders

**Accents:**
- `--destructive`: `oklch(0.577 0.245 27.325)` — red for errors/warnings
- `--ring`: `oklch(0.145 0 0)` — focus rings (black)
- Amber highlights for focus states and warnings

**Sidebar:**
- `--sidebar`: `oklch(0.985 0 0)` — matches background
- `--sidebar-primary`: `oklch(0.205 0 0)` — dark active state
- `--sidebar-accent`: `oklch(0.97 0 0)` — hover state

**Dark Mode:** Defined in globals.css with same structure

### Typography

**Families:**
- `--font-sans`: Instrument Sans (primary UI)
- `--font-display`: Instrument Serif (headlines, titles)
- `--font-mono`: JetBrains Mono (code, metadata, labels)

**Scale:**
- Display (landing): `text-6xl` (3.75rem), `text-8xl` (6rem), up to `text-[8.75rem]`
- Headings: `text-3xl` to `text-5xl`
- Body: `text-base` (1rem), `text-sm` (0.875rem), `text-xs` (0.75rem)
- Mono labels: `text-[10px]`, `text-[11px]`

**Tracking:**
- Display: `tracking-tight` (0)
- Headings: `tracking-tight` or `tracking-[-0.02em]`
- Mono uppercase labels: `tracking-[0.08em]` or `tracking-[0.12em]`

### Spacing & Layout

**Radius:**
- `--radius`: `0.25rem` (4px base)
- Buttons: `rounded-lg` (4px)
- Cards: `rounded-xl` (12px), `rounded-2xl` (16px)
- Chips: `rounded-full`

**Padding:**
- Dense controls: `px-2.5 py-1.5`, `px-3 py-2`
- Buttons: `px-3` (small), `px-8` (large landing CTAs)
- Cards: `p-3`, `p-4`
- Sections: `py-24 lg:py-32`

**Shadows:**
- Cards: `shadow-sm`, `shadow-[0_12px_32px_rgba(0,0,0,0.14)]`
- Popovers: `shadow-[0_24px_80px_rgba(0,0,0,0.2)]`
- Buttons: `shadow-[inset_0_1px_1px_rgba(255,255,255,0.25),...]`

**Grid & Constraints:**
- Max-width: `max-w-[1400px]` (landing sections)
- Sidebar: `280px` expanded, `68px` collapsed
- Content: `px-6 lg:px-12` horizontal padding
- Grid: Landing features use `lg:grid-cols-2`, How It Works uses `lg:grid-cols-[0.88fr_1.12fr]`

### Iconography

**Library:** Hugeicons (free) + Lucide
- `MessageMultiple01Icon` — Chat
- `ZapIcon` — Routines
- `Shapes01Icon` — Artifacts
- `Settings02Icon` — Settings
- `PlugSocketIcon` — Integrations
- `GithubIcon` — GitHub provider
- `Globe` — Publish action
- `ExternalLink` — Citations, sources
- `Save` — Save draft
- `ShieldAlert` — Warnings
- `Check` — Confirmation

**Size convention:** `size-4` (16px), `size-3.5` (14px), `size-8` (32px for feature cards)

### Logo & Brand

**Logo file:** `/apps/web/public/plot-icon.svg`
- Black abstract mark with organic curves
- 520×520 SVG
- Two main strokes forming continuous flow
- Used in README, landing page, favicon

**Favicon:** `plot-favicon.svg` (production), `plot-logo-favicon-non-prod.png` (dev)

**OG image:** `og-image.png`

---

## Component Rules

### Citation Chips

**Workspace Citations (Artifact Editor):**
- Component: `SourcesPopover`
- Trigger: `<button>` with "Sources · {count}"
- Style: `rounded-lg border border-black/10 bg-white px-3 text-sm font-semibold`
- Popover: `Citation` component from `@astryxdesign/core`, numbered list
- Behavior: Click to open popover, displays all unique sources with external links

**Public Citations:**
- Component: `PublicCitationChip`
- Display: Inline `[1, 2]` chips in red accent
- Style: `rounded-full border border-[#ef3f2c]/25 bg-[#fff4f1] px-1.5 font-mono text-[11px] text-[#c73728]`
- Behavior: Hover/focus to reveal sources popover with provider labels
- Sources section: Numbered grid at bottom of entry

### Review/Publish States

**Artifact States:**
- `DRAFT` — Initial AI-generated state, may have uncited claims
- `NEEDS_REVIEW` — Draft ready for human inspection
- `READY` — Reviewed and approved, ready to publish
- `PUBLISHED` — Live on public changelog

**Visual Indicators:**
- Draft badge: `rounded-full bg-foreground/5 px-2 py-0.5 font-mono text-[8px] uppercase text-muted-foreground`
- Save state label: `artifactSaveStateLabel()` — "Saved", "Saving…", "Unsaved changes", "Save needs attention"
- Publish warnings: Amber alert dialog with `<ShieldAlert>` icon, statement focus links

**State Transitions:**
- Draft → Publish (requires confirmation if unresolved statements exist)
- Publish confirmation: `PublishConfirmation` dialog with warning list
- Publish success: `PublishSuccessPanel` with public URL and actions

### Release Range Display

**Components:** `RoutineReleaseActivity`

**Range States:**
- `NEEDS_RANGE` — First observed tag, no base commit yet
- Exact range: Display base/head SHAs, tag names, release boundary
- Monitoring status: `ACTIVE`, `DISABLED`, `QUEUED`, `ANALYZING`, `COMPLETED`, `FAILED`

**Visual Treatment:**
- Tag chip: `rounded-full border border-black/10 px-2 py-0.5 font-mono text-xs uppercase`
- Status badges: Same chip style with state-specific text
- Error states: Display error codes and last attempt timestamp

### Empty/Error/Loading States

**Empty States:**
- Message: Descriptive text explaining what will appear here
- Action: Primary CTA to create first item
- Example (Artifacts): "No artifacts yet. Create your first artifact from Chat or Routines."

**Loading States:**
- Spinner: `<LoaderCircle className="animate-spin" />`
- Text: "Loading…" with ellipsis
- No skeleton content that implies structure before data arrives

**Error States:**
- Message: Clear error description, never generic "Something went wrong"
- Retry action if applicable
- No fake success states when errors occur

**Never fake completeness:**
- Do not show placeholder content
- Do not hide controls that should be visible
- Do not imply published state when still draft

---

## Screen Inventory

### Landing (`/`)
**Layout:** Single-page scroll with fixed navigation
- **Navigation:** Logo, "Join waitlist" CTA (sticky)
- **Hero:** Display headline, subheading, two CTAs ("Join waitlist", "See how it works"), background terminal visual
- **Features:** 2×2 grid, four capability cards (blocks, signals, style, pack), card 04 labeled "Coming next"
- **Style:** Brand promise visualization
- **How It Works:** Three-step process (Connect → Release → Review+publish), sticky visual
- **Security:** Trust signals
- **CTA:** Final waitlist form
- **Footer:** Legal links, branding
- **Primary action:** Join waitlist
- **Forbidden patterns:** Unlabeled future features, auto-publish claims, inflated metrics

### Sign-in (`/sign-in`)
**Layout:** Centered card on animated dither background
- **Card:** Sign-in form or OAuth flow
- **Primary action:** Sign in
- **Forbidden patterns:** Social proof without evidence, fake user counts

### Onboarding (`/onboarding`)
**Layout:** Step indicator + centered content
- **Step 1:** GitHub connection card, "Connect GitHub" button
- **Step 2:** Repository picker (search + list), "Create routine" form
- **Step 3:** First run trigger, "Run routine" button, polling status
- **Primary action:** Step-specific CTA
- **Forbidden patterns:** Auto-creating routines without user confirmation, skipping step verification

### Home (`/home`)
**Layout:** TBD (currently redirects to `/chat`)
- **Future:** Activity feed, workspace stats, recent artifacts
- **Primary action:** TBD
- **Forbidden patterns:** Fake activity, auto-generated content without citations

### Chat (`/chat`)
**Layout:** `ProductShell` with sidebar + main content
- **Sidebar:** Product navigation (Chat, Routines, Artifacts), recent chat history
- **Empty state (`ChatHome`):** Welcome message, source references panel, "Start a new chat" composer
- **Active state (`ChatActiveWorkspace`):** Thread messages, composer, artifact/agent inspector panel
- **Components:** `ChatComposer` (textarea + send button), `ChatThread` (message list), `ChatSourceCitations` (inline citations with links)
- **Primary action:** Send message
- **Forbidden patterns:** AI responses without citations, auto-sending messages, hiding source links

### Routines (`/routines`)
**Layout:** `ProductShell` with sidebar + main content
- **List:** Routine cards with name, source, cadence, status, last run timestamp
- **Create panel:** Name input, repository picker, instruction textarea, cadence picker, "Create routine" button
- **Detail panel (expanded):** Routine config, `RoutineReleaseActivity` (release history, range status), manual run trigger
- **Primary action:** Create routine
- **Forbidden patterns:** Auto-enabling routines, unclear range boundaries, hiding release errors

### Artifacts (`/artifacts`)
**Layout:** `ProductShell` with sidebar + main content or full-canvas editor
- **List view:** Artifact cards with title, status, last updated timestamp
- **Canvas view (`ArtifactCanvasWorkspace`):** Full-screen editor with chrome
- **Editor chrome (`ArtifactEditorChrome`):** Save draft button, save state label, publish/export actions
- **Editor:** `TiptapDraftEditor` (rich text) or `CitedDraftEditor` (with citation tracking)
- **Panels:** `SourcesPopover`, `ArtifactHistoryPanel`, `PublishDialog`, `ExportDialog`
- **Primary action:** Publish (when ready) or Save draft (when editing)
- **Forbidden patterns:** Auto-publish, publish without confirmation, hiding uncited claims, unclear save state

### Integrations (`/settings/integrations`)
**Layout:** `ProductShell` in settings mode + main content
- **Sidebar:** Settings navigation (Account, General, Integrations)
- **Content:** GitHub connection cards, repository monitoring status, "Connect GitHub" CTA
- **Primary action:** Connect/disconnect GitHub
- **Forbidden patterns:** Auto-connecting repositories, unclear monitoring status, hidden access errors

### Settings General/Account
**Layout:** `ProductShell` in settings mode + form
- **General:** Workspace name, slug, logo, member list
- **Account:** User profile, email, authentication
- **Primary action:** Save changes
- **Forbidden patterns:** Auto-saving critical changes, unclear validation errors

### Public Changelog List (`/:workspaceSlug/changelog`)
**Layout:** Standalone public layout (no sidebar, no workspace chrome)
- **Header:** Workspace name, logo, "All updates" heading
- **List:** Entry cards with title, tag name, published date, excerpt
- **Card:** Link to full entry
- **Primary action:** Navigate to entry
- **Forbidden patterns:** Operator controls, edit actions, unpublished drafts, citation-free claims

### Public Changelog Entry (`/:workspaceSlug/changelog/:entrySlug`)
**Layout:** Standalone public layout
- **Header:** Back link ("← All updates"), published date, tag chip, title
- **Body:** Markdown prose with inline `PublicCitationChip` references
- **Sources section:** Numbered grid of citation links (provider, label, URL)
- **Primary action:** Inspect citations
- **Forbidden patterns:** Operator controls, edit actions, uncited claims, broken source links

---

## Accessibility & Density

### Operator Tool (Workspace)
- **Density:** High information density, compact controls
- **Target:** Professional users spending extended time in the app
- **Min heights:** Buttons `min-h-8` (32px), Inputs `min-h-10` (40px)
- **Text size:** `text-xs` to `text-sm` for controls, `text-base` for content
- **Keyboard nav:** Full keyboard support, focus rings on all interactive elements
- **Screen reader:** ARIA labels, roles, live regions for status updates

### Public Reading (Changelog)
- **Density:** Generous spacing, readable prose
- **Target:** General audience scanning updates
- **Text size:** `text-[17px]` body, `text-5xl` to `text-6xl` headings
- **Line height:** `leading-8` for body content
- **Contrast:** High contrast for readability (WCAG AA minimum)
- **Focus indicators:** Clear focus rings on citation chips and links

### Focus States
- **Default:** `focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-{color}`
- **Amber accent:** `focus-visible:ring-amber-400` (workspace primary actions)
- **Black accent:** `focus-visible:ring-black/15` (secondary workspace actions)
- **Red accent:** `focus-visible:ring-[#ef3f2c]/30` (public citation chips)

### Motion & Animation
- **Respect prefers-reduced-motion:** `@media (prefers-reduced-motion: no-preference)`
- **Landing reveals:** `landing-reveal` animation (fade-in + translate-y)
- **Loading spinners:** `animate-spin` on `<LoaderCircle>`
- **Flow animations:** Animated dots on feature visuals (SVG `<animateMotion>`)
- **Transitions:** `transition` utility for hover/focus states

---

## How to Use This File

### For Product Engineers
- **Before implementing a feature:** Check which laws apply to your surface
- **Component naming:** Use the exact component names listed here (greppable)
- **State transitions:** Follow the state machine in "Review/Publish States"
- **Copy & labels:** Match the voice register for your surface (operator vs customer vs marketing)

### For QA
- **Fail a deploy if:**
  - Publish happens without explicit user confirmation (Law 2)
  - Uncited claims appear in published changelog (Law 1)
  - Range boundaries are fuzzy or missing (Law 3)
  - Future features are presented as live (Law 5)
  - Empty/error states fake completeness
- **Verify:**
  - All citation chips link to inspectable sources
  - All primary actions are clearly labeled and singular per screen
  - All states match the documented state machine

### For Design/Product
- **When adding a feature:**
  - Identify which law(s) apply
  - Determine the primary action for the screen
  - Choose the voice register (operator/customer/marketing)
  - Update DESIGN.md with new screen/component/state
- **When changing UI:**
  - PRs that change UI must update DESIGN.md
  - Document new components, states, or patterns
  - Ensure copy matches the voice register

### For Marketing
- **Copy must match product reality:**
  - Use the same factual language as the product
  - Do not claim unshipped features
  - Label future work as "Coming next"
  - Reference this file for accurate product descriptions

---

## Related Documentation

- [System Architecture Overview](docs/architecture/system-overview.md) — request boundaries, ownership model, persistence conventions
- [GitHub Release Automation](docs/operations/github-release-automation.md) — webhook flow, range detection
- [Private Repository Certification](docs/operations/private-repository-production-certification.md) — security and access controls
- [README](README.md) — repository overview, development setup, verification commands
- [README.ko.md](README.ko.md) — Korean translation of repository overview

---

**This file is the source of truth for UI behavior, states, and patterns. When in doubt, refer here. When the product changes, update this file first.**
