---
version: alpha
name: Plot
description: Source-cited content system with editorial paper aesthetic
colors:
  background: oklch(0.985 0 0)
  foreground: oklch(0.145 0 0)
  primary: oklch(0.145 0 0)
  primary-foreground: oklch(0.985 0 0)
  secondary: oklch(0.96 0 0)
  secondary-foreground: oklch(0.145 0 0)
  muted: oklch(0.94 0 0)
  muted-foreground: oklch(0.45 0 0)
  accent: oklch(0.92 0 0)
  accent-foreground: oklch(0.145 0 0)
  destructive: oklch(0.577 0.245 27.325)
  border: oklch(0.88 0 0)
  citation-red: "#ef3f2c"
  citation-red-bg: "#fff4f1"
  focus-amber: "#f59e0b"
typography:
  display-lg:
    fontFamily: Instrument Serif
    fontSize: 96px
    fontWeight: 400
    lineHeight: 0.9
    letterSpacing: -0.03em
  display-md:
    fontFamily: Instrument Serif
    fontSize: 72px
    fontWeight: 400
    lineHeight: 0.9
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Instrument Serif
    fontSize: 48px
    fontWeight: 400
    lineHeight: 1.1
    letterSpacing: -0.02em
  headline-md:
    fontFamily: Instrument Serif
    fontSize: 32px
    fontWeight: 400
    lineHeight: 1.2
    letterSpacing: -0.02em
  body-lg:
    fontFamily: Instrument Sans
    fontSize: 18px
    fontWeight: 400
    lineHeight: 1.6
  body-md:
    fontFamily: Instrument Sans
    fontSize: 16px
    fontWeight: 400
    lineHeight: 1.5
  body-sm:
    fontFamily: Instrument Sans
    fontSize: 14px
    fontWeight: 400
    lineHeight: 1.5
  body-xs:
    fontFamily: Instrument Sans
    fontSize: 12px
    fontWeight: 400
    lineHeight: 1.5
  label-md:
    fontFamily: JetBrains Mono
    fontSize: 12px
    fontWeight: 600
    lineHeight: 1
    letterSpacing: 0.08em
  label-sm:
    fontFamily: JetBrains Mono
    fontSize: 11px
    fontWeight: 600
    lineHeight: 1
    letterSpacing: 0.08em
  label-xs:
    fontFamily: JetBrains Mono
    fontSize: 10px
    fontWeight: 600
    lineHeight: 1
    letterSpacing: 0.12em
rounded:
  sm: 0.125rem
  md: 0.1875rem
  DEFAULT: 0.25rem
  lg: 0.25rem
  xl: 0.375rem
  "2xl": 1rem
  full: 9999px
spacing:
  unit: 4px
  dense: 8px
  base: 16px
  comfortable: 24px
  spacious: 32px
components:
  button-primary:
    backgroundColor: "{colors.foreground}"
    textColor: "{colors.primary-foreground}"
    typography: "{typography.body-xs}"
    rounded: "{rounded.lg}"
    height: 32px
    padding: 0 12px
  button-primary-hover:
    backgroundColor: "rgba(18, 18, 18, 0.8)"
  button-secondary:
    backgroundColor: "rgba(0, 0, 0, 0.04)"
    textColor: "{colors.foreground}"
    typography: "{typography.body-xs}"
    rounded: "{rounded.lg}"
    height: 32px
    padding: 0 12px
  button-secondary-hover:
    backgroundColor: "rgba(0, 0, 0, 0.08)"
  button-ghost:
    backgroundColor: transparent
    textColor: "{colors.foreground}"
    typography: "{typography.body-xs}"
    rounded: "{rounded.lg}"
    height: 32px
    padding: 0 12px
  button-ghost-hover:
    backgroundColor: "rgba(0, 0, 0, 0.04)"
  citation-chip:
    backgroundColor: "{colors.citation-red-bg}"
    textColor: "{colors.citation-red}"
    typography: "{typography.label-sm}"
    rounded: "{rounded.full}"
    padding: 0 6px
    height: 24px
  citation-chip-hover:
    backgroundColor: "#ffeae5"
  input:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.foreground}"
    typography: "{typography.body-md}"
    rounded: "{rounded.lg}"
    height: 40px
    padding: 0 12px
  card:
    backgroundColor: "#ffffff"
    textColor: "{colors.foreground}"
    rounded: "{rounded.xl}"
    padding: 16px
  card-dense:
    backgroundColor: "{colors.secondary}"
    textColor: "{colors.foreground}"
    rounded: "{rounded.lg}"
    padding: 12px
  sidebar-nav-item:
    backgroundColor: transparent
    textColor: "rgba(0, 0, 0, 0.65)"
    typography: "{typography.body-sm}"
    rounded: "{rounded.lg}"
    height: 32px
    padding: 0 10px
  sidebar-nav-item-active:
    backgroundColor: "rgba(255, 255, 255, 0.75)"
    textColor: "#18181b"
  sidebar-nav-item-hover:
    backgroundColor: "rgba(0, 0, 0, 0.04)"
  tag:
    backgroundColor: "rgba(0, 0, 0, 0.05)"
    textColor: "{colors.muted-foreground}"
    typography: "{typography.label-xs}"
    rounded: "{rounded.full}"
    padding: 0 8px
    height: 20px
---

## Overview

Plot's visual identity is rooted in editorial paper aesthetics: near-monochrome ink on off-white stock, monospace labels for metadata, and serif headlines for narrative weight. The system evokes evidence review and journalistic rigor — not glossy product marketing or glassmorphic depth.

The design communicates **inspection and approval**: operators review source-cited drafts in a workspace with dense controls and technical clarity; customers read published content in a generous, readable register. Both surfaces share the same factual foundation (exact ranges, inspectable citations) but present it in registers suited to their audience.

The palette stays neutral except for two functional accents: **citation red** (`#ef3f2c`) marks inspectable sources in public-facing content, and **amber** (`#f59e0b`) highlights focus states and warnings in the workspace. This restraint keeps attention on content structure and evidence links, not decorative color.

Fonts reinforce the editorial metaphor:
- **Instrument Serif** for display headlines (journalistic authority)
- **Instrument Sans** for body text (contemporary readability)
- **JetBrains Mono** for all metadata, labels, and technical annotations (precision, not decoration)

The result is a workspace that feels like a CMS for factual content: grid backgrounds, minimal shadows, pill-shaped primary actions, and monospace uppercase labels. The published changelog reads like editorial output — generous line height, citation footnotes, numbered sources — not a SaaS dashboard.

## Colors

The color system is deliberately constrained. Near-black ink (`oklch(0.145 0 0)`) on off-white paper (`oklch(0.985 0 0)`) provides maximum readability. Grays step through subtle neutral tones for borders (`oklch(0.88 0 0)`), muted backgrounds (`oklch(0.94 0 0)`), and secondary text (`oklch(0.45 0 0)`).

Two functional accents break the monochrome:

- **Citation Red (`#ef3f2c`)**: A bold, high-contrast red used exclusively for citation chips in published content. The color signals "this claim has an inspectable source." Background tint: `#fff4f1`.
- **Focus Amber (`#f59e0b`)**: Amber rings and highlights mark focus states, warnings, and statements under review in the workspace. This warm accent stands apart from the cool red of citations.

The destructive color (`oklch(0.577 0.245 27.325)`) is a muted earthy red, distinct from citation red, reserved for error states.

All colors use OKLCH notation to preserve perceptual uniformity. The system avoids gradients, glassmorphism, or layered transparency — the paper metaphor is flat and direct.

## Typography

Typography follows a three-tier strategy:

1. **Instrument Serif** — Display and headline levels (`display-lg` at 96px, `headline-lg` at 48px). Reserved for landing page headlines, public changelog entry titles, and any narrative framing that benefits from editorial weight. Tracking is negative (`-0.02em` to `-0.03em`) to increase density.

2. **Instrument Sans** — Body text at all scales (`body-lg` 18px, `body-md` 16px, `body-sm` 14px, `body-xs` 12px). Used for prose, descriptions, button labels, and all operator workspace UI. Line heights are generous (1.5 to 1.6) for long-form readability.

3. **JetBrains Mono** — Metadata labels in three sizes (`label-md` 12px, `label-sm` 11px, `label-xs` 10px). Always uppercase with wide letter spacing (`0.08em` to `0.12em`). Used for timestamps, status badges, technical annotations, source counts, and any label that benefits from monospace precision.

Headlines and display text are set in regular weight (400); the typeface's serifs and generous spacing provide sufficient contrast. Body text and labels use regular (400) and semibold (600) weights only — no extremes.

## Layout

Plot uses a **dense operator layout** for workspace surfaces and a **readable prose layout** for public changelog. Both share a common spacing scale but apply it differently:

- **Workspace**: Compact controls (`dense: 8px`, `base: 16px`), minimal padding, tight card grids. Operators scan large lists of routines, artifacts, and sources; density improves efficiency.
- **Public changelog**: Generous spacing (`comfortable: 24px`, `spacious: 32px`), large line heights, wide margins. Readers consume narrative prose and citation-linked claims.

The spacing unit is `4px`. All spacing tokens are multiples of this unit to maintain vertical rhythm.

**Grid backgrounds**: Landing page and some workspace sections use a faint 1px line grid (typically 28px or 32px squares) to reinforce the editorial/paper aesthetic. Opacity is low (`0.03` to `0.05`) so the grid never competes with content.

**Max widths**: Landing sections constrain to `1400px`. Public changelog prose constrains to readable widths (approximately 65 characters per line). Workspace surfaces use fluid layouts with sidebar (280px expanded, 68px collapsed).

**Sidebar density**: Collapsed sidebar shows only icons; expanded sidebar shows labels. Recent chat history and routine lists scroll within the sidebar chrome, using the dense spacing scale.

## Elevation & Depth

Plot avoids layered depth and glassmorphism. Elevation is minimal and functional:

- **No shadows on most UI**: Buttons, inputs, and cards rely on border contrast and background fill, not drop shadows.
- **Subtle card shadows**: Published changelog cards and elevated modals use soft shadows (`shadow-sm` or custom `0 12px 32px rgba(0,0,0,0.14)`) to separate content from the background without implying stacked glass layers.
- **Focus rings**: All interactive elements use a 2px ring at focus (`focus-visible:ring-2`). Workspace primary actions use amber rings; public citation chips use red-tinted rings.
- **Popovers and dialogs**: Float above the page with crisp borders and minimal shadows. No blur, no transparency layers.

The editorial metaphor is **flat paper with ink**, not physical depth or translucent surfaces.

## Shapes

The design system uses **subtle rounding** on most interactive elements, never sharp corners or extreme pill shapes except for specific components:

- **Buttons and inputs**: `rounded-lg` (0.25rem / 4px)
- **Cards**: `rounded-xl` (0.375rem / 6px)
- **Tags and status badges**: `rounded-full` (pill shape) for compact, scannable metadata
- **Citation chips**: `rounded-full` to visually distinguish them from surrounding prose

Borders are consistent (`border: oklch(0.88 0 0)`) and typically 1px. No gradient borders, no glow effects.

The **Plot logo** is an abstract organic mark with continuous curves, rendered in solid black. It appears in the sidebar, landing navigation, and public changelog header — always flat, never animated or layered.

## Components

### Buttons

**Primary button**: Black background (`{colors.foreground}`), white text, rounded (`{rounded.lg}`), 32px height. Used sparingly — one primary action per screen. Hover reduces opacity slightly.

**Secondary button**: Light gray background (`rgba(0,0,0,0.04)`), black text. Used for alternate actions like "Cancel" or "Export."

**Ghost button**: Transparent background, black text. Hover adds a subtle gray tint. Used for tertiary actions and icon-only controls.

All buttons use `{typography.body-xs}` (12px Instrument Sans) for labels. Icon-only buttons are 32px square.

### Citation Chips

The **citation chip** is a signature component. In public changelog prose, it appears as `[1, 2]` inline with the text:

- Background: `#fff4f1` (warm cream tint)
- Text: `#ef3f2c` (citation red)
- Font: JetBrains Mono, 11px semibold
- Shape: `rounded-full`
- Height: 24px
- Padding: 0 6px

On hover/focus, the background deepens to `#ffeae5` and a popover reveals the source titles, providers, and external links. Citation chips never appear in workspace UI — only in published customer-facing content.

### Inputs

Text inputs and textareas use:

- Background: `{colors.accent}` (light gray)
- Text: `{colors.foreground}` (near-black)
- Typography: `{typography.body-md}` (16px Instrument Sans)
- Rounded: `{rounded.lg}`
- Height: 40px (single-line inputs)
- Padding: 0 12px

Focus state adds an amber ring (`focus-visible:ring-amber-400`). Inputs never have inner shadows or layered borders.

### Cards

**Standard card**: White background (`#ffffff`), `rounded-xl`, 16px padding. Used for artifact previews, routine detail panels, settings sections.

**Dense card**: Light gray background (`{colors.secondary}`), `rounded-lg`, 12px padding. Used for compact lists and sidebar elements.

No card uses drop shadows unless it floats as a modal or popover.

### Tags and Status Badges

Small pill-shaped labels (`rounded-full`, 20px height) with monospace uppercase text:

- Background: `rgba(0,0,0,0.05)`
- Text: `{colors.muted-foreground}`
- Font: JetBrains Mono 10px semibold, uppercase, `0.12em` letter spacing

Used for release tag names, routine statuses, artifact states, and provider labels in citation sources.

### Sidebar Navigation

**Inactive nav items**: Transparent background, gray text (`rgba(0,0,0,0.65)`), 32px height, `rounded-lg`.

**Active nav item**: White background with subtle shadow (`rgba(255,255,255,0.75)`), near-black text (`#18181b`).

**Hover state**: Light gray background (`rgba(0,0,0,0.04)`).

Icons are 14px to 16px, from Hugeicons (free) and Lucide. All navigation uses Instrument Sans 13px medium.

## Do's and Don'ts

### Do

- **Use one solid primary action per screen.** A single black button makes the intended action obvious. Secondary actions can be ghost or outline buttons.

- **Reserve serif for headlines and published narrative.** Instrument Serif signals "this is the story" or "this is the published output." Use Instrument Sans for all operator UI.

- **Keep monospace uppercase for metadata only.** JetBrains Mono with wide letter spacing (`0.08em` or more) is the visual signature of technical labels, timestamps, and status badges. Never use it for body prose.

- **Mark citations with the red chip in public content.** The `[1, 2]` notation in citation red is the visual proof of "this claim has sources." Workspace UI shows sources differently (popovers, lists) but never with the red chip.

- **Use amber for workspace focus and warnings.** Amber rings and highlights are the operator's visual cue: "pay attention here." Keep amber out of published changelog.

- **Let paper texture emerge from grid backgrounds and flat cards.** The editorial aesthetic comes from subtle line grids (low opacity), flat white cards on off-white backgrounds, and minimal shadows — not from skeuomorphic paper textures or noise overlays.

### Don't

- **Don't mix serif into operator chrome.** Workspace navigation, buttons, inputs, and control labels are always Instrument Sans. Serif is reserved for content headlines and published output.

- **Don't use citation red for errors or warnings.** Citation red (`#ef3f2c`) means "inspectable source" in published content. Errors use the separate destructive color; workspace warnings use amber.

- **Don't fake completeness with skeleton loaders or empty states that look published.** If data isn't loaded, say "Loading…" with a spinner. If a list is empty, say "No items yet" with a clear action. Never show gray placeholder boxes that imply structure before data arrives.

- **Don't add glassmorphism, gradients, or layered transparency.** Plot's aesthetic is **flat ink on paper**, not frosted glass or stacked translucent surfaces. Cards are opaque; shadows are minimal; no blur effects.

- **Don't create multiple primary CTAs on one screen.** If two actions compete for attention (e.g., "Save draft" and "Publish"), make one primary (black button) and one secondary (gray or ghost). Users scan for the single black pill.

- **Don't use extreme border radius or sharp corners.** Buttons and cards use subtle rounding (`4px` to `6px`). Tags and citation chips are fully rounded (`rounded-full`). Never use `0px` (sharp) or `16px+` (overly soft) radius on standard UI.

- **Don't hide the source link in citations.** Every citation chip must open a popover or link to the external source. No decorative citation chips that lack the evidence backing.
