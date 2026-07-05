# Plot Product Definition

Status: v0 product definition

Last updated: 2026-07-04

## One Sentence

Plot is an autonomous update agent that turns shipped work into source-cited,
on-style updates: docs updates, changelogs, release notes, customer updates,
and launch or social drafts.

## Product Thesis

Coding agents make product shipping faster. The surrounding update work does
not keep up, and generic writing tools do not know what shipped, what source
supports it, or how the team should say it.

Teams merge PRs, close issues, make tradeoffs, answer customers, and ship small
improvements every day. The shipped work is real, but the updates around it
arrive late or not at all:

- docs stay behind the implementation
- changelogs miss meaningful changes
- customer-facing updates require repeated context gathering
- internal teams ask what changed
- launch posts and social updates lag behind shipping
- factual statements in generated content are hard to trust

Plot does not try to become a company knowledge base. It is an autonomous
post-shipping update agent: watch configured shipped-work sources, prepare the
needed update pack, preserve team voice and channel style, and keep important
generated content tied to source material through citations.

## Status Quo / Why It Fails

The first user usually:

1. Opens merged PRs, commits, issues, release notes, and shipped-work source
   views.
2. Asks engineers what actually matters, what changed, and what should not be
   promised publicly.
3. Writes one version for docs or changelog, then rewrites it for customers,
   internal teams, launch posts, and social channels.
4. Manually checks whether generated statements are supported by source
   material.
5. Repeats the same context-gathering loop next week.

Generic chat over pasted PR text is not enough because source selection,
source-checking, release-window boundaries, channel style, and multi-artifact
rewriting still live in the user's head. Source-native release notes alone are not
enough because teams also need docs deltas, customer language, and launch/social
drafts that sound like the team.

The failure mode is not weak prose. The failure mode is stale docs, missed
changes, inaccurate statements, repeated context gathering, and one human
becoming the bottleneck after every ship cycle.

## Not A Knowledge Layer

Plot should not position itself as:

- a company brain
- an internal wiki
- a general knowledge management system
- a universal RAG/search product
- a place to store all company context forever

Plot uses source material only to produce source-cited update artifacts. The
product center is the autonomous update-generation and citation loop, not
knowledge storage.

## Inspired By

### Tyquill

Tyquill collected source material and generated drafts. Plot keeps the useful
part of that loop, but narrows the domain.

```txt
Tyquill: save sources -> generate articles
Plot: shipped work -> source-cited update pack
```

Plot is not a general newsletter or article workspace. It is for product teams
whose shipped changes need to become clear updates.

### External Product Patterns

The direct references should stay as private research inputs, not
product-facing positioning. The reusable patterns are:

- shipped work can become changelogs, launch posts, and social updates without
  starting from a blank page
- autonomous drafting must stay separate from autonomous publishing
- shipped activity should be visible in one timeline before content generation
- AI output should sound like the writer or team, not like a generic model
- style should be visible through measurable signals, not hidden in a prompt
- humans and agents should work inside the same durable work object
- source citations should stay attached to generated content, not hidden in a
  chat transcript

Voice/style is not the only category, but it is a core quality bar:
source-backed updates should also be on-style for the channel and team.

Plot should borrow the activity-timeline clarity, content metadata, schedules,
brand-voice setup, dense task detail, style metrics, draft comparison, and
inspectable agent-work shape. Plot should differentiate through durable work
sessions, editable source selection, source citations, style guidance, and
human-controlled publishing outside Plot.

### Agentic Work Environments

Newer agentic work environments converge on the same product shape: durable
work sessions, autonomous task execution, and inspectable artifacts. Plot
should use that shape for product updates.

```txt
Agent workbench: task -> agent work -> inspectable artifacts
Writing workspace: intent -> agent interview -> styled drafts
Plot: shipping intent -> agent work -> source-cited, on-style drafts
```

Plot should have a chat-like session surface with an embedded task manager for
running, blocked, and ready work. A separate `Autonomous` surface can be
promoted later when scheduled or parallel agent work becomes common.
`AgentRun` is execution detail; `Task` and `WorkSession` are the objects the
user should understand.

### Capture-To-Creation Tools

Capture-to-creation tools turn scattered material into finished artifacts. Plot
narrows that idea to shipped product work and the update artifacts teams
repeatedly need after shipping.

## First Persona

Plot starts with one first persona:

```txt
The founder, DevRel lead, product lead, or senior engineer at a seed to Series A
AI/devtool/API startup with 3-30 engineers, shipping every week from connected
repository, issue, release, and docs workflows, who personally turns merged work
into docs, changelogs, customer updates, and launch/social drafts.
```

They already trust their shipped-work systems as the record of what changed, but
they do not trust raw AI writing to explain those changes without source
citations.

Expansion personas can come later: product marketing, customer success,
solutions engineering, and larger release teams.

## v0 Wedge

The v0 wedge is:

```txt
work session
  -> shipping window and release cadence
  -> connected shipped-work sources
  -> autonomous task
  -> autonomous import or refresh
  -> shipped-change selection
  -> source-backed, on-style update pack
  -> source citations and style guidance
  -> human-controlled publishing outside Plot
```

The user should not need to paste individual PR links or remember to start from
a blank composer every week. They should choose the shipping window, inspect the
agent-prepared changes, and edit or copy an update pack. The first implementation can
start with a repository-backed adapter, but the product should read as a
connected-source update workflow rather than a single integration.

This is autonomous draft preparation, not autonomous publishing.

## Narrowest Sellable Promise

For the first version, Plot should make one promise:

```txt
After choosing a shipping window and source scope, Plot prepares a
source-backed, on-style update pack in minutes, with important generated
statements tied back to source material and every variant ready to inspect,
edit, or copy.
```

The first pack should cover a small fixed set of artifacts:

- release brief
- changelog or release notes
- docs update draft
- customer update
- launch or social draft

This is intentionally smaller than a publishing automation platform. The update
agent drafts and cites the pack; a human still edits, copies, and publishes
outside Plot.

## Core Workflow

1. A workspace member starts a work session with a request like "prepare this
   week's update pack."
2. Plot asks for or infers the shipping window, source scope, voice, audience,
   and channels.
3. Plot creates a durable task visible from the session's task area.
4. Plot's update agent imports PRs, commits, releases, issues, and useful
   metadata into source blocks.
5. Plot proposes which shipped changes matter for this update and records that
   source selection as an inspectable artifact.
6. Plot creates an update pack with channel-specific, on-style variants.
7. Plot attaches source citations to generated content where possible.
8. Plot records source maps, citation maps, style notes, and draft packs as task
   artifacts.
9. A person or external coding agent can inspect the same pack, citations, and
   style guidance.
10. A human edits, copies, or publishes variants outside Plot.
11. Accepted edits can improve future templates and voice/style guidance.

## Product Surface

The v0 UI should have three visible work surfaces, plus supporting settings:

- `Sessions`: chat-like work sessions for starting and steering update work,
  including running and ready tasks.
- `Sources`: a shipped-work timeline where the user can inspect and
  edit source selection.
- `Packs`: draft update packs with source metadata, citations, and channel
  variants.

Supporting surfaces:

- `Voice`: team voice, channel styles, samples, and style reports.
- `Settings`: workspace, members, permissions, source adapters, and billing.

The `Autonomous` manager should start embedded inside `Sessions` and can become
an independent nav item after multiple concurrent or scheduled tasks exist.
`AutomationRecipe` should remain a model concept in v0, not a primary UI
surface.
When `Autonomous` gets its own screen, it can expose task and template subviews
without renaming the underlying domain objects.

Session start should ask "What shipped, and what update do you need?" rather
than leading with integration setup. The source adapter appears as a capability
inside the session, not as the product's main promise.

The content workspace should be a three-pane workspace:

```txt
Source timeline  ->  Draft variants  ->  Citations and style guidance
```

The task detail view should use a dense right rail for source window, channels,
voice profile, source citations, style fit, and generation state.

## Update Pack

An update pack is the user-facing bundle generated from one set of shipped
changes. The v0 pack should contain a fixed first set:

- release brief
- changelog or release notes
- docs update draft
- customer update
- launch or social draft

The same source set can produce multiple variants, but the content workspace
should keep source citations, caveats, voice/style guidance, and draft output
together.

## Core Objects

- `WorkSession`: chat-like workspace where humans and agents discuss one update
  request and inspect resulting tasks and artifacts.
- `SessionMessage`: one human, agent, or system message inside a work session.
- `Task`: a durable user-visible unit of update work, shown in the session's
  running or ready task area.
- `TaskArtifact`: an inspectable output from a task, such as an agent plan,
  source map, content pack, citation map, style note, or verification summary.
- `SourceSelectionItem`: an included, excluded, or needs-context source item for
  a task.
- `AutomationRecipe`: a recurring or scheduled automation configuration,
  initially used as model shape rather than primary UI.
- `AutomationRun`: one execution record for an automation recipe.
- `Connection`: an authenticated source connection. v0 can implement one
  repository-backed adapter first.
- `SourceRepository`: a configured repository source from the first adapter.
- `RepositoryImport`: one bounded import for a selected release window, started
  by a user, repository watch, or agent run.
- `RepositoryWatch`: configured repository monitoring or scheduled refresh for
  autonomous update-pack preparation.
- `WritingBlock`: normalized source material, such as a PR, commit group,
  release, or issue.
- `ContentTemplate`: a reusable output recipe for changelogs, docs updates,
  customer updates, launch posts, and similar artifacts.
- `VoiceProfile`: team and channel style guidance from accepted examples and
  explicit rules.
- `AgentRun`: one autonomous or user-requested update-agent attempt.
- `GenerationRun`: one backend-managed model generation request.
- `ContentPack`: the generated update pack.
- `ContentVariant`: one editable draft inside the pack.
- `Citation`: the link between generated content and one or more source blocks.

## v0 Scope

v0 should include:

- source setup for the first shipped-work adapter
- repository selection for public and private repositories through that adapter
- repository watch or scheduled refresh for autonomous draft preparation
- user-triggered repository import as a fallback/manual override
- WritingBlock normalization for PRs, commit groups, releases, and issues
- a fixed first update pack: release brief, changelog or release notes, docs
  update draft, customer update, and launch or social draft
- one default VoiceProfile with accepted examples, channel style rules, and
  explicit do/don't guidance
- chat-like WorkSession UX for starting and steering update work
- session task area backed by durable Task state
- source timeline for imported shipped work
- task detail with activity and right-side metadata
- draft comparison and style signals for generated variants
- TaskArtifact records for source maps, citation maps, style notes, agent plans,
  verification summaries, and content pack links
- SourceSelectionItem records for inspectable source inclusion/exclusion
- AutomationRecipe and AutomationRun model shape, even if scheduled execution
  and automation UI are deferred
- lightweight AgentRun state for import, draft, citation mapping, and task state
- direct model-provider API call from the backend
- content packs and editable variants
- citation/source-reference support for generated content
- edit and copy/export states

## Pre-PMF Risks

The current product direction is only worth building if these assumptions prove
true:

- The target user feels real recurring pain after shipping, not only mild
  annoyance.
- The first connected shipped-work sources have enough signal for a useful
  update pack without requiring team chat, support, and every docs integration on
  day one.
- Autonomous draft preparation is welcomed as leverage, not treated as another
  noisy inbox.
- Voice/style quality matters enough that users prefer Plot over raw release
  notes or generic chat output.
- Users trust a shipped-work activity timeline more when they can edit source
  selection and see citations next to generated content.
- Source-backed citations are meaningfully more trusted than a generic chat
  prompt over copied PR text.
- Users want one source-cited pack that fans out into multiple artifacts, rather
  than a single changelog generator.
- The workflow saves enough time to become weekly habit before background
  automation exists.

## Deferred

Defer:

- issue tracker and team chat integrations
- manual paste/upload input
- rich prior-writing import beyond a small accepted-example set
- scheduled publishing
- direct publish to docs, social, or changelog systems
- autonomous publish without human control
- full planning, cycles, roadmaps, and initiatives
- broad public template marketplace
- complex multi-agent workflows beyond the update-agent loop
- full span-level source annotation
- company-wide knowledge base or search
- complex analytics and enterprise audit

## Positioning

Primary positioning:

```txt
Ship fast. Write less. Stay source-backed and on-style.
```

Alternate:

```txt
Plot turns shipped work into autonomous update packs in your team's voice.
```

Messaging guardrail:

```txt
"Ship fast" and "write less" are the outer promise. The product mechanism is
autonomous draft preparation, source-backed citations, team voice/style, and
human-controlled publishing.
```

Avoid leading with:

- knowledge layer
- company brain
- generic AI writer
- voice-only writing assistant
- autonomous publisher
- always-on publishing without human control

Those phrases either over-broaden the product or pull Plot toward categories
that are not the intended v0.

## Success Metrics

v0 should prove:

- time from repository import to source-cited update pack
- time from shipped change to agent-prepared draft pack
- percentage of generated variants with attached source citations
- edit distance between generated and accepted variants
- style rewrite distance between generated and accepted variants
- repeat weekly usage by the same workspace
- number of output artifacts generated from one shipped-change set
- user trust in source-backed citations

## Why This Gets More Important

Generic AI writing will commoditize. That makes Plot's defensibility less about
prose quality alone and more about workflow fit: permissioned source import,
release-window selection, autonomous draft preparation, inspectable change
selection, source-backed citations, accepted edits, channel templates, and team
voice/style memory.

If coding agents keep increasing shipping velocity, the number of small shipped
changes rises faster than the number of people willing to update docs,
changelogs, customers, and launch channels. Plot becomes more important when
shipping gets faster because it sits after shipping, where human trust and
source citations still matter.

## Strategic Boundary

Plot can grow into publishing automation after the source-cited generation loop
works. The sequence is:

```txt
v0: first source adapter, autonomous draft packs, voice/style, and human-controlled handoff
v1: lightweight integrations, richer draft inbox, and external agent access
v2: publishing handoff, schedules, and controlled publishing policies
```

The moat is not that Plot writes better prose. Models will keep improving. The
moat is the workflow memory around shipped changes, source citations, channel
templates, accepted edits, and team voice/style.
