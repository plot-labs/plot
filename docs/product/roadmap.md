# Plot Roadmap

Status: working roadmap

Last updated: 2026-07-09

## Principle

This roadmap is ordered by product value and dependency, not by feature
accumulation. Build the source-cited update loop first, validate repeat use
next, then add monetization, automation depth, and expansion bets.

## Phase 0 — Product Readiness

Goal: align the public product surface and capture the first channel signal.

- Clean the landing page: remove retired claim-review language from the
  pricing section; align copy with human-controlled publishing.
- Add one waitlist question — "which post-shipping update is most painful?" —
  to rank first channels with data.

## Phase 1 — Value Loop End To End

Goal: `repo connect -> shipping window -> source-cited draft` works for real.

1. GitHub adapter minimum: one OAuth flow covering login and source
   connection, repository selection, date-range import, WritingBlock
   normalization.
2. Generation loop: GenerationRun, ModelInvocation, and two seeded system
   templates first — changelog and launch/social post. Adjust from waitlist
   answers.
3. Citation UX inside Phase 1, not after: hover a generated sentence, see the
   supporting PR. This is the first visible differentiator.
4. Replace the web mock boundary with real API calls, Sessions screen first.
5. Voice as rules plus a few samples. Measurable style signals come later.

Explicitly not in Phase 1: repository watches and scheduled refresh, the full
five-artifact pack, real-time co-editing, team permissions, automation
recipes.

## Phase 2 — Design Partner Validation

Goal: prove repeat weekly usage. Not revenue.

- Onboard free design-partner teams, devtool/API startups first.
- Dogfood as distribution: use Plot to produce Plot's own weekly updates and
  publish them. The product is its own demo.
- Let partner demand decide pack fan-out: add more artifact types only when
  "now make the customer update too" requests actually occur.
- If usage does not stick, discuss pivot (narrower channel set, different
  persona, or the audio bet) before spending more.

## Phase 3 — Monetization And Depth

Only after design-partner validation:

- Monetization for validated workspaces.
- Voice depth: measurable style signals (`STYLE_REPORT`), accepted-edit
  learning loop.
- RepositoryWatch automation: the pack is ready Monday morning — the habit
  mechanism.
- Async sharing: share links and comments. Still not real-time co-editing.

## Phase 4 — Expansion Bets

- Audio: weekly shipping recap in the team's voice. Open market gap, but only
  meaningful for users who already have the text habit.
- Additional sources (issue tracker, team chat) and MCP/API access so external
  agents can drive Plot.
- Upmarket experiment: DevRel orgs at larger devtool companies.

## Relationship To The v0 Definition

[Product Definition](product-definition.md) describes the full v0 target
shape. This roadmap sequences it:

- The five-artifact update pack remains the v0 promise; Phase 1 ships two
  artifact types first and expands on partner demand.
- Repository watches remain in v0 scope but land in Phase 3 as the habit
  mechanism, after manual imports prove the loop.
- Real-time collaborative editing is out of scope for v0 entirely; async
  share-and-comment covers team workflows until after PMF signals.
