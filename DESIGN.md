---
version: alpha
name: Plot
description: Current Plot web UI reference; document implemented component variants
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
    fontFamily: Playfair Display
    fontSize: 96px
    fontWeight: 400
    lineHeight: 0.9
    letterSpacing: -0.03em
  display-md:
    fontFamily: Playfair Display
    fontSize: 72px
    fontWeight: 400
    lineHeight: 0.9
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Playfair Display
    fontSize: 48px
    fontWeight: 400
    lineHeight: 1.1
    letterSpacing: -0.02em
  headline-md:
    fontFamily: Playfair Display
    fontSize: 32px
    fontWeight: 400
    lineHeight: 1.2
    letterSpacing: -0.02em
  body-lg:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: 400
    lineHeight: 1.6
  body-md:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: 400
    lineHeight: 1.5
  body-sm:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: 400
    lineHeight: 1.5
  body-xs:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: 400
    lineHeight: 1.5
  label-md:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: 600
    lineHeight: 1
    letterSpacing: 0.08em
  label-sm:
    fontFamily: Inter
    fontSize: 11px
    fontWeight: 600
    lineHeight: 1
    letterSpacing: 0.08em
  label-xs:
    fontFamily: Inter
    fontSize: 10px
    fontWeight: 600
    lineHeight: 1
    letterSpacing: 0.12em
rounded:
  sm: 0rem
  md: 0.125rem
  DEFAULT: 0.25rem
  lg: 0.25rem
  xl: 0.5rem
  "2xl": 1rem
  full: 9999px
spacing:
  unit: 4px
  dense: 8px
  base: 16px
  comfortable: 24px
  spacious: 32px
components:
  button-document-primary:
    backgroundColor: "#000000"
    textColor: "#ffffff"
    typography: "{typography.body-xs}"
    fontWeight: 500
    rounded: "{rounded.lg}"
    minHeight: 32px
    padding: 0 12px
  button-document-primary-hover:
    backgroundColor: "rgba(0, 0, 0, 0.8)"
  button-create:
    background: "linear-gradient(to bottom, rgba(0, 0, 0, 0.78), rgba(0, 0, 0, 0.88))"
    textColor: "#ffffff"
    typography: "{typography.body-xs}"
    fontWeight: 600
    rounded: "{rounded.full}"
    height: 34px
    padding: 0 14px
    iconSize: 15px
    iconGap: 6px
    border: "1px solid rgba(255, 255, 255, 0.18)"
    backdropFilter: "saturate(200%) blur(40px)"
    boxShadow: "inset 0 1px 1px rgba(255, 255, 255, 0.25), inset 0 -1px 1px rgba(0, 0, 0, 0.1), 0 8px 24px rgba(0, 0, 0, 0.12), 0 2px 6px rgba(0, 0, 0, 0.08)"
  button-create-hover:
    opacity: 0.9
  button-create-active:
    scale: 0.98
  button-workspace-primary:
    backgroundColor: "#252a30"
    textColor: "#ffffff"
    typography: "{typography.body-xs}"
    fontWeight: 600
    rounded: "{rounded.full}"
    height: 32px
    padding: 0 14px
  button-workspace-text:
    backgroundColor: transparent
    fontSize: 12px
    fontWeight: 500
    rounded: 7px
    height: 32px
    padding: 0 10px
  button-workspace-icon:
    backgroundColor: transparent
    rounded: 9px
    height: 36px
    width: 36px
  citation-chip:
    backgroundColor: "{colors.citation-red-bg}"
    textColor: "{colors.citation-red}"
    typography: "{typography.label-sm}"
    rounded: "{rounded.full}"
    padding: 0 6px
    height: 24px
  citation-chip-hover:
    backgroundColor: "#ffeae5"
  workspace-search:
    backgroundColor: "#ffffff"
    fontSize: 13px
    rounded: 9px
    height: 40px
    padding: 0 12px
  contents-table:
    backgroundColor: "#ffffff"
    rounded: 14px
    rowMinHeight: 76px
  content-status:
    fontSize: 12px
    rounded: 5px
    padding: 2px 8px
    border: "1px solid rgba(0, 0, 0, 0.1)"
  sidebar-nav-item:
    backgroundColor: transparent
    textColor: "rgba(0, 0, 0, 0.65)"
    fontSize: 13px
    fontWeight: 500
    rounded: 8px
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


## Purpose and source of truth

This document describes the implemented Plot web UI. Update it when an adopted UI changes. A difference from an older design rule is a documentation update candidate; it is not, by itself, a reason to restyle working screens.

The reference implementation is `apps/web/src`. The tables below distinguish existing component variants rather than imposing one radius, font size, or surface treatment on every screen. The token block records selected shared values; explicit component styles also form part of the current design.

## Visual direction

Plot combines neutral workspace backgrounds, editorial serif headings, compact sans-serif controls, and rounded surfaces. Most list content uses light borders and restrained fills. Create buttons, onboarding surfaces, and citation popovers also use gradients, translucency, blur, or shadows where specified below.

Keep these treatments tied to their existing components. When adding a similar action or surface, reuse the nearest implemented variant and update this document if a new variant is deliberately introduced.

## Typography

Inter supplies body copy, navigation, metadata, button labels, and form controls. Playfair Display supplies both narrative headings and workspace page headings. Font weights include regular 400, medium 500, and semibold 600.

| Use | Current treatment | Reference |
| --- | --- | --- |
| Automation, Contents, Connections, and shared workspace page heading | Playfair Display, 32px, regular | `components/layout/workspace-page.tsx` |
| Shared workspace page description | Inter, 13px, 20px line height, 8px below heading | `components/layout/workspace-page.tsx` |
| Create routine panel heading | Playfair Display, 28px, regular | `features/routines/routines-workspace.tsx` |
| Document title | Playfair Display, 30px, 38px line height | `features/artifacts/artifact-document-surface.tsx` |
| Sources drawer heading | Playfair Display, 24px, 32px line height | `features/artifacts/artifact-canvas-workspace.tsx` |
| Sidebar navigation | Inter, 13px, medium | `components/layout/sidebar-navigation.tsx` |
| Workspace search input | Inter, 13px | `components/layout/workspace-page.tsx` |
| Contents row title | Inter, 14px, medium, up to two lines | `features/artifacts/artifacts-workspace.tsx` |
| Onboarding headings | Playfair Display; size varies by step and viewport | `features/onboarding/onboarding-flow.tsx` |

Do not replace workspace serif headings with sans-serif solely to match an older documentation rule.

## Colors and themes

Global semantic colors are defined in `app/globals.css`. The UI also uses component-specific colors and a second set of surface/ink/line tokens used by the Beautiful UI components.

- Workspace lists: `#f7f8fa` in light mode, `#18191d` in dark mode.
- Content editor canvas: `#eef0f3`, with a white document surface; dark canvas `#18181b`, document `#202024`.
- Chat header: `#fbfbf8` at 85% opacity; dark `#16171a` at 85% opacity.
- Standard workspace primary action: `#252a30` with white text; dark mode reverses to a white fill with dark text.
- Header Create action: near-black translucent gradient with white text in both themes.
- Public citation accent: `#ef3f2c` on `#fff4f1`. Workspace citations have their own rendering and popover treatment.
- Warnings, errors, connection states, and review states use the colors defined by their owning components; accents are not restricted to citation red and amber.

Dark variants are component-specific. Do not infer dark colors by simply inverting the light palette.

## Layout and dimensions

| Surface | Current dimensions |
| --- | --- |
| Sidebar | 252px expanded, 72px collapsed |
| Shared workspace list section | Maximum width 760px |
| Contents list | Shared workspace maximum width 760px, 24px horizontal padding, matching Automation and Connections |
| Content document canvas | Maximum width 1080px, side borders, open vertical layout |
| Sources drawer | Maximum width 420px |
| Onboarding modal | Maximum width 600px, viewport-constrained scrolling |

Contents shares the workspace width and horizontal padding used by Automation and Connections. Its list has Name, Status, and Updated columns, no All/Draft/Published tabs, and no Type column. The header Create action links to Chat. At smaller widths, rows stack the title above status and update time.

The content editor uses side borders and content-driven height. Do not add a surrounding rectangle that visually suggests a fixed editing height.

Spacing commonly follows 4px increments, with deliberate compact values such as 6px icon gaps, 14px button padding, and 34px Create-button height.

## Radius tokens and explicit radii

The current CSS token mapping uses a 4px base radius:

| Token | Resolved value |
| --- | --- |
| `rounded-sm` | 0px |
| `rounded-md` | 2px |
| `rounded-lg` | 4px |
| `rounded-xl` | 8px |
| `rounded-2xl` | 16px |
| `rounded-full` | Pill/circle |

Components also use explicit radii. Search and workspace icon controls use 9px, sidebar rows use 8px, the Contents table uses 14px, and the onboarding modal uses 22px. These are implemented variants, not violations of the base token scale.

## Buttons

| Variant | Current treatment | Typical use |
| --- | --- | --- |
| Header Create | 34px high, full radius, 12px semibold label, 14px horizontal padding, 15px Add icon, 6px gap | Automation and Contents headers |
| Create routine submit | 36px high, full radius, 13px semibold label, 16px horizontal padding; same gradient and depth treatment as header Create | Routine creation form |
| Workspace primary | 32px high, full radius, 12px semibold label, 14px horizontal padding, solid fill | Home New chat |
| Document primary | Minimum 32px high, 4px radius, 12px medium label, 12px horizontal padding, black fill | Save draft and document confirmation actions |
| Workspace text | 32px high, 7px radius, 12px medium label, 10px horizontal padding | Compact secondary actions |
| Workspace icon | 36px square, 9px radius | Refresh and similar list controls |
| Document Copy split control | 32px high, 8px outer radius, bordered translucent white fill, subtle shadow | Copy and export menu |
| Onboarding actions | Full radius; height and label size vary by step | Install on GitHub, Continue |

The two Create variants use the gradient, border, backdrop filter, and shadow values recorded in `button-create`. Both use hover opacity 0.9 and active scale 0.98. The header label is **Create**; the form submission label is **Create routine** or **Creating…**. Disabled controls use reduced opacity and block interaction.

Prefer an existing variant when adding a button. Keep label, icon, spacing, and state treatment consistent within that variant. A link styled as a button remains a link when it navigates to another page.

## Inputs and focus states

Shared workspace search fields use a 40px-high white container with a 9px radius and a 13px input. Focus adds a subtle black border/ring. Dark mode uses a low-opacity white surface and lighter text.

Focus styling follows the current control family:

- Workspace search: neutral `focus-within` ring.
- Create and most compact workspace actions: neutral 2px `focus-visible` ring.
- Editor formatting and selected export/review controls: amber focus ring.
- Document title: underline on keyboard focus.

Onboarding fields have their own rounded, translucent treatment. A blanket prohibition on inset shadows or layered borders does not describe the current implementation.

## Surfaces, blur, and elevation

| Surface | Current treatment |
| --- | --- |
| Contents table | White fill, 14px radius, subtle border and row separators, lightly tinted header/footer |
| Header Create and Create routine submit | Near-black gradient, translucent light border, inset highlights, soft outer shadow, backdrop filter |
| Getting started sidebar card | Translucent white layers, gradient border wrapper, inset/outer shadows, backdrop blur, decorative soft highlights |
| Onboarding modal | 22px radius, translucent surface, backdrop blur, layered shadow; dimmed and lightly blurred page overlay |
| Workspace switcher modal overlay | Dimmed background with 2px blur |
| Citation popover | Translucent white surface, 8px radius, backdrop blur, soft shadow; dark translucent variant |
| Chat header | Translucent fill with backdrop blur |
| Copy menu and Sources drawer | Bordered surfaces with overlay shadows |

Blur and depth are part of these implemented components. They are not restricted to the header Create button. Ordinary list rows stay visually quiet so titles and actions remain easy to scan.

## Status badges and citations

Status labels vary by context:

- Contents Draft/Published: 12px text in normal capitalization, 5px radius, thin border, 2px vertical and 8px horizontal padding.
- Integration status: filled pill badge, 12px medium text.
- Compact tags: small uppercase pill labels where the owning component uses that treatment.
- Routine enabled state: a small dot and On/Paused text.

Do not force all of these into one uppercase pill badge without an explicit UI decision.

Public content and the editor use different citation presentations. The editor's citation opens a compact popover of up to 300px, constrained to viewport width, with source navigation and an external source link. Its title and URL must not overflow. Opening a citation suppresses the editor formatting tooltip.

Sources drawer rows use the shared filled GitHub mark for GitHub sources without a decorative square background. Source titles, URLs, and outbound actions remain readable and clickable.

## Loading, empty, and error states

The Contents list currently uses three animated skeleton rows while fetching. Automation uses a spinner with loading text. Both patterns describe pending data and should resolve to the actual data, an explicit empty state, or an error message.

Do not describe skeleton loading as forbidden. Preserve retry and disabled states where the UI depends on unavailable sources or configuration.

## Maintaining this reference

- Inspect the relevant component and the rendered screen before updating a rule.
- Record deliberate variants with their scope; do not turn one screen's style into a universal rule.
- Update this document when approved UI changes alter dimensions, typography, surfaces, or interaction states.
- If a new request is to synchronize documentation, change the documentation rather than restyling the implementation to fit older prose.
- Shared references: `app/globals.css`, `components/layout/workspace-page.tsx`, `components/layout/sidebar-onboarding.tsx`, `features/artifacts/artifacts-workspace.tsx`, `features/routines/routines-workspace.tsx`, and `features/citations/tiptap-citation-extension.tsx`.

### Shared workspace list controls

- Automation and Contents use `WorkspaceCreateAction` for their header Create control. It preserves native link or button behavior with the same icon, label, and glossy pill styling.
- Automation and Contents item titles use 14px medium text. Routine metadata and compact row actions use 12px text.
- Connections provider card titles use 16px semibold text.
- Automation, Contents, and Connections use `WorkspaceEmptyState`: 13px medium title, 12px description with 20px line height, 4px title-to-description gap, and 40px vertical padding. An optional icon sits 12px above the title.

- Automation and Contents share `WorkspaceErrorNotice`: 12px message text, a 28px icon-only retry button with an accessible label, and a wrapping layout for narrow widths. Contents retries loading without a full page refresh.
- Routine row actions wrap when space is limited. List loading indicators expose a status role while retaining each page's spinner or skeleton presentation.
