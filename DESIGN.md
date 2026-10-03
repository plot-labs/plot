---
version: alpha
name: Plot
description: Plot web UI reference with adopted transparent glass button and card specifications
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
  button-primary:
    backgroundColor: transparent
    textColor: "{colors.foreground}"
    typography: "{typography.body-xs}"
    fontWeight: 600
    rounded: "{rounded.full}"
    height: 34px
    padding: 0 14px
    iconSize: 15px
    iconGap: 6px
    borderLight: "1px solid rgba(0, 0, 0, 0.12)"
    borderDark: "1px solid rgba(255, 255, 255, 0.18)"
  button-secondary:
    backgroundColor: transparent
    textColor: "{colors.foreground}"
    fontWeight: 500
    rounded: "{rounded.full}"
    height: 34px
    padding: 0 14px
    border: 1px solid transparent
  button-icon:
    backgroundColor: transparent
    rounded: "{rounded.full}"
    height: 36px
    width: 36px
    border: 1px solid transparent
  button-destructive:
    backgroundLight: "rgba(255, 255, 255, 0.65)"
    backgroundDark: "rgba(255, 255, 255, 0.08)"
    textLight: "#b91c1c"
    textDark: "#fca5a5"
    rounded: "{rounded.full}"
  button-hover:
    backgroundLight: "rgba(0, 0, 0, 0.03)"
    backgroundDark: "rgba(255, 255, 255, 0.06)"
    highlightLight: "inset 0 1px 0 rgba(255, 255, 255, 0.7)"
    highlightDark: "inset 0 1px 0 rgba(255, 255, 255, 0.12)"
  button-pressed:
    backgroundLight: "rgba(0, 0, 0, 0.06)"
    backgroundDark: "rgba(255, 255, 255, 0.1)"
    scale: 0.98
  button-focus:
    ringWidth: 2px
    ringOffset: 2px
  button-disabled:
    opacity: 0.4
  card:
    backgroundColor: transparent
    borderLight: "1px solid rgba(0, 0, 0, 0.08)"
    borderDark: "1px solid rgba(255, 255, 255, 0.12)"
    rounded: 14px
    boxShadow: none
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
    backgroundColor: transparent
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

This document records Plot's adopted design and the existing web UI reference. The Buttons and Surfaces sections, and their button/card tokens, specify the approved transparent glass direction. These specifications are not a claim that the current UI already implements them. Other sections describe the existing implementation unless stated otherwise.

The reference implementation is `apps/web/src`. Update implementation and documentation together when applying an adopted specification; retain existing dimensions and behavior outside that scope.

## Visual direction

Plot combines neutral workspace backgrounds, editorial serif headings, compact sans-serif controls, and rounded surfaces. Preserve an Apple Liquid Glass inspired appearance through visible underlying surfaces, subtle edge highlights, and restrained depth.

Buttons and ordinary cards have transparent backgrounds by default. Primary actions use persistent thin borders and semibold labels; other actions reveal their surface on interaction. Use blur and shadows for floating layers over actual content. Create follows the same primary action specification as New chat, Save draft, Copy, Export, and Continue.

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
- Adopted button colors: transparent surfaces with dark text in light mode and light text in dark mode. Primary action emphasis comes from borders and weight.
- Adopted destructive actions: a light translucent surface with red text and icons; use a faint white surface with lighter red text in dark mode.
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

Shared implementation: `apps/web/src/components/ui/glass.css`. Most actions share transparent backgrounds and a rounded pill shape. Use the primary treatment for Create, New chat, Save draft, Copy, Export, and Continue. Use the secondary treatment for Cancel and other supporting actions. Labels describe the action; an Add icon belongs only to creation actions.

| Variant | Treatment | Typical use |
| --- | --- | --- |
| Primary | Transparent, persistent thin border, 12px semibold label, 34px height, 14px horizontal padding | Create, Save draft, Continue |
| Secondary | Transparent, no visible resting border, 12px medium label; same size as primary | Cancel, supporting actions |
| Icon | Transparent, no visible resting border, 36px circle, accessible action label | Close, refresh, more |
| Destructive | Light translucent surface, red text and icons; primary dimensions | Delete, disconnect |
| Copy split control | Transparent, one persistent outer border; a divider separates Copy and export menu | Document Copy |

Use 15px icons with a 6px label gap when an icon helps explain the action. Existing compact or form-specific sizes can remain where space requires them; surface and state rules remain shared. Reserve transparent border space so interactions do not shift layout. Tabs, selected list rows, and formatting controls retain their selection semantics rather than adopting action-button styling. Onboarding checklist rows and profile/menu rows retain left alignment and their existing spacing; only standalone action buttons center their contents.

### Button states

| State | Required treatment and behavior |
| --- | --- |
| Default | Transparent surface; only primary actions have a visible resting border. No filled black gradient or outer shadow. |
| Hover | Enabled controls reveal a faint translucent surface and subtle top edge highlight. Secondary/icon borders may become visible. No hover enlargement. |
| Pressed | Slightly stronger surface, scale 0.98; preserve layout size. Destructive actions keep their light surface and red label. |
| Keyboard focus | Visible 2px outer ring with 2px offset, in a contrasting theme-aware color. Keep the ring visible during hover, press, and loading when focused. |
| Disabled | Opacity 0.4, unavailable to activation, no hover or pressed effects. Preserve an understandable label; explain unavailable actions nearby when needed. |
| Loading | Preserve the normal surface and readable opacity; replace the icon with a spinner and use progress text such as Saving…. Preserve width, prevent duplicate activation, and expose aria-busy. Retain existing focus and announce progress accessibly. |
| Success | Return to the normal enabled appearance. When useful, briefly show a check and completion label without changing button width; announce completion. |
| Failure | Restore the action for retry where possible. Show an accessible error and recovery guidance nearby; red button styling remains reserved for destructive actions. |

Disabled suppresses interaction effects. Loading blocks repeat activation without looking unavailable. Focus is additive and must not be hidden by another state. Destructive colors persist across hover and press; do not turn destructive controls into solid red buttons.

Transition colors, opacity, borders, and highlights over 150ms; pressed transforms over 100ms. With reduced motion, omit scale animation and spinner rotation while retaining progress text. Native buttons use disabled when unavailable; links remain links for navigation, and aria-disabled links must also suppress activation. Icon-only controls always have accessible names.

## Inputs and focus states

Shared workspace search fields use a 40px-high white container with a 9px radius and a 13px input. Focus adds a subtle black border/ring. Dark mode uses a low-opacity white surface and lighter text.

Focus styling follows the current control family:

- Workspace search: neutral `focus-within` ring.
- Adopted button focus: theme-aware 2px `focus-visible` ring with 2px offset, as specified above.
- Editor formatting and selected export/review controls: amber focus ring.
- Document title: underline on keyboard focus.

Onboarding fields have their own rounded, translucent treatment. A blanket prohibition on inset shadows or layered borders does not describe the current implementation.

## Surfaces, blur, and elevation

Cards and floating layers share the surface classes in `glass.css`. Keep workspace/page and document backgrounds readable. Transparent cards reveal that background rather than adding another opaque fill.

| Surface | Adopted treatment |
| --- | --- |
| Ordinary cards and Contents list container | Transparent background, thin theme-aware border, existing radius, no outer shadow |
| Interactive cards and list rows | Transparent at rest; faint hover/pressed surface only when clickable. Selected state remains identifiable with text/icon or border as well as tint. |
| Primary action buttons, including Create | Transparent background, persistent thin border; edge highlight on interaction |
| Getting started sidebar card | Transparent surface with restrained edges; remove decorative filled gradients and persistent heavy shadows |
| Popovers, menus, dialogs, and floating cards | Light translucent neutral surface, subtle edge highlight, backdrop blur over content, restrained outer shadow |
| Sources drawer and Chat header | Translucency/blur when layered over content; use a quiet surface otherwise |
| Overlay backdrop | Dimming and light blur where needed to distinguish the active layer |

Floating layers use a 96% neutral surface with backdrop blur so underlying text does not compete with menu items. Use blur where there is an underlying surface to see through. Ordinary inline buttons and cards do not need a backdrop filter. When backdrop filtering is unavailable or transparency is reduced, floating layers use an opaque theme surface that preserves readability. Decorative transparency must not reduce text or control contrast; adjust the translucent layer as needed.

Dangerous actions are the deliberate filled-surface exception: light translucent backgrounds with red labels, as specified under Buttons. Status and citation accents retain their semantic styling.

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

- Inspect the relevant component and rendered screen when recording implemented behavior. Mark approved design specifications separately until their implementation is verified.
- Record deliberate variants with their scope; do not turn one screen's style into a universal rule.
- Update this document when approved UI changes alter dimensions, typography, surfaces, or interaction states.
- If a new request is to synchronize documentation, change the documentation rather than restyling the implementation to fit older prose.
- Shared references: `app/globals.css`, `components/layout/workspace-page.tsx`, `components/layout/sidebar-onboarding.tsx`, `features/artifacts/artifacts-workspace.tsx`, `features/routines/routines-workspace.tsx`, and `features/citations/tiptap-citation-extension.tsx`.

### Shared workspace list controls

- Automation and Contents use `WorkspaceCreateAction` for their header Create control. It preserves native link or button behavior with the same icon and label. It uses the shared transparent primary treatment.
- Automation and Contents item titles use 14px medium text. Routine metadata and compact row actions use 12px text.
- Connections provider card titles use 16px semibold text.
- Automation, Contents, and Connections use `WorkspaceEmptyState`: 13px medium title, 12px description with 20px line height, 4px title-to-description gap, and 40px vertical padding. An optional icon sits 12px above the title.

- Automation and Contents share `WorkspaceErrorNotice`: 12px message text, a 28px icon-only retry button with an accessible label, and a wrapping layout for narrow widths. Contents retries loading without a full page refresh.
- Routine row actions wrap when space is limited. List loading indicators expose a status role while retaining each page's spinner or skeleton presentation.


## AI UI components

AI surfaces use locally owned AI Elements subsets in `apps/web/src/components/ai-elements` and shadcn primitives in `components/ui`. Keep the existing Inter/Playfair fonts, product colors, typography scale and center/dock composition. Adapt selected registry components to existing callbacks; generation, polling and document persistence remain in the current hooks.

- Composer: PromptInput with controlled draft, native form submission and IME guard; ModelSelector uses Command inside Popover, with reasoning controls outside Command.
- Chat: Message, Conversation with one focusable scroll owner, static Tool status and Sources. Safe HTTP links use the existing URL validator.
- Document: inline citation badges remain inert inside the NodeView trigger; the dialog returns focus and retains citation serialization.
- Workspace: shadcn v4 resizable panels start at 50:50. Desktop preserves document pixels with 420px minimum per pane when space permits and 1200px document maximum; mobile retains the mounted editor in a full workspace overlay.

Global Tailwind tokens own the existing 8/10/12/14/17/20/24/29/35/42px text scale and small/medium/large shadows. Component installation must preserve these tokens and avoid importing a second global theme.
