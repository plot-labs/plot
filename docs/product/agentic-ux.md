# Agentic UX Direction

Status: v0 UX direction

Last updated: 2026-07-04

## Thesis

Plot should feel closer to an agentic workbench and a style-native writing
workspace than to a conventional SaaS dashboard.

```txt
Agent workbench:
task prompt -> agent work -> inspectable artifacts

Style-native writing workspace:
writing intent -> agent interview -> styled drafts

Plot:
shipping intent -> agent work -> source-cited, on-style drafts
```

The visible product is not GitHub connection setup. The visible product is a
durable work session where a person or agent can ask for an update pack, inspect
what the agent did, and copy or edit the generated artifacts.

## Reference Patterns

### Agent-First Workbenches

Useful patterns:

- A task is a durable object, not a disposable chat response.
- Work happens asynchronously and can continue after the user leaves.
- The user inspects artifacts instead of reading every tool call.
- Parallel tasks need a manager view, not only a chat transcript.
- Trust comes from plans, progress, verification, and final reports.

Plot translation:

- Use `Task` for update-pack work.
- Use `TaskArtifact` for source maps, citation maps, style notes, draft packs,
  agent plans, and verification summaries.
- Use an embedded task manager inside `Sessions` for scheduled and
  agent-created update tasks. Promote it to a separate `Autonomous` surface
  only after parallel or recurring work needs its own manager.

References:

- Agent-first products emphasize manager views and artifacts such as task
  lists, plans, screenshots, recordings, checks, and final reports.
- Coding-agent products center around isolated task environments, command/test
  results, and inspectable handoff before merge.
- Newer agent interfaces shift from editor-first usage toward delegating tasks
  and checking in on multiple agents.

### Style-Native Writing Workspaces

Useful patterns:

- First screen asks what the user wants to write, not which integration to set
  up.
- Style is a first-class control, not a hidden setting.
- Style is shown as measurable signals: sentence length, opener type, line
  length, reading grade, AI tells, and channel fit.
- The agent asks clarifying questions before writing.
- Multiple drafts are presented for comparison and mixing.
- The workspace is agent-native: humans and agents can use the same style and
  task context.

Plot translation:

- First screen asks: "What shipped, and what update do you need?"
- Generation controls are `shipping window`, `voice`, `audience`, and
  `channels`.
- Show multiple update artifacts in one pack: changelog, docs update, customer
  update, launch or social draft.
- Keep source citations and style match next to the draft, not in a separate
  settings page.
- Use style reports to explain what changed between a generic draft and an
  on-style Plot draft.

References:

- Strong writing workspaces center on writing samples, channels, styles,
  multiple drafts, and agent-native workspace controls.

### Operational Work And Content Pipeline Tools

Useful patterns:

- Dense operational tools show work without turning every item into a large
  card: IDs, labels, status, assignee, activity, metadata, and agent output all
  fit in one work surface.
- Strong agentic work tools treat agents as part of product work: agents can
  receive work, update activity, produce output, and hand off artifacts.
- Strong content pipeline tools show one timeline of repository, issue tracker,
  and team-chat activity, then brand-voice draft generation, schedules, and a
  content dashboard.
- Content pipelines should store source repositories, timeframes, trigger
  events, and content type with the generated draft.

Plot translation:

- Use a `Sources` timeline for shipped work, but make source
  selection editable before generation.
- Use a dense task detail screen: center work content, activity timeline,
  and a right rail for source scope, voice, citations, and style guidance.
- Use a content dashboard for packs, but keep the primary unit as a
  durable `Task`, not only a generated content row.
- Keep schedules as `UpdateRecipe` objects with run history once recurring
  work is implemented.

References:

- Dense operational tools are the density and task-detail reference.
- Content pipeline tools are the shipped-work activity and draft-generation
  reference.

### Agentic Development Environments

Useful patterns:

- Agent work is organized around durable tasks/workspaces, not one-off chat.
- Parallel work needs a manager view with status and attention.
- Source support should be visible next to generated content through citations
  and source references, not modeled as a separate approval workflow.
- Work intake can come from existing systems, not only blank prompts.
- Scheduled agent work should eventually be visible as recipes/automations with
  run history.

Plot translation:

- Use `Task` as the stable unit of autonomous update work.
- Use typed `TaskArtifact` entries for source coverage, citations, style notes,
  channel completeness, and generation summaries.
- Use source/request intake to start tasks from shipped work, issues, releases,
  manual notes, and later support/customer sources.
- Add `UpdateRecipe` for recurring release/update cadence.
- Keep source adapters permissioned and workspace-scoped.
- Avoid copying code-specific surfaces such as terminals, worktrees, ports, and
  IDE handoff.

References:

- Agentic development environments center on parallel agent work, isolated
  task/workspace state, live execution, inspectable artifacts, and handoff.

## Product Surface

### Primary Navigation

```txt
Sessions      chat-like work sessions, recent update requests
Sources       shipped-work timeline, source selection, import history
Packs         draft update packs with citations
Voice         team voice, channel styles, samples, rules
Settings      workspace, members, permissions
```

`Sessions` is the default interactive surface. Running, blocked, and
ready tasks should appear inside it first. `Autonomous` can become a
separate nav item later. `Recipes` should stay out of primary navigation until
recurring scheduled work is implemented.

### Home / Session Start

The first screen should look closer to a writing session than GitHub settings:

```txt
What shipped, and what update do you need?

[ Prepare update pack for July 1-7                    ]

Source: Connected source / paste / manual notes
Voice: Product-led / DevRel / Founder / Custom
Channels: Changelog, Docs, Customer update, Launch post
Citations: Source-backed
```

The first-run setup can ask for a source adapter after the user expresses the
job to be done. Integration setup should feel like a required capability, not
the product's main promise.

### Session Layout

```txt
Left rail:
  session history, running tasks, active watches

Center:
  chat transcript, agent questions, task progress, activity

Right rail:
  source scope, voice, pack artifacts, citations, style guidance
```

The chat transcript explains intent and decisions. The right rail is where trust
is built.

### Embedded Task Manager

The v0 task manager lives inside `Sessions`. It is the agent-work status view:

```txt
Queued       agent has a task but has not started
Running      importing, selecting, drafting, checking
Blocked      needs missing context, source access, or human decision
Ready        pack is ready to inspect, edit, or copy
Failed       agent failed with an inspectable error summary
```

Each row should show:

- task title
- shipping window
- source coverage
- citation coverage
- style match
- pack artifacts
- last agent action
- owner

When a workspace has multiple concurrent or scheduled runs, this embedded view
can be promoted to an `Autonomous` tab without changing the data model.

### Content Surface

The content workspace should be three-pane:

```txt
Source timeline   Draft variants             Citations
---------------   --------------             ---------
PRs               Changelog                  Source links
Issues            Docs update                Caveats
Commits           Customer update            Style match
Releases          Launch/social              Copy/export
```

The user can accept, edit, regenerate, or remove unsupported language.
When the agent is uncertain, the middle pane can show multiple writing-style
directions with style and citation indicators for each.

## Task Lifecycle

```txt
Draft request
  -> Task created
  -> Agent plan artifact
  -> Source selection artifact
  -> Generation run
  -> Content pack artifact
  -> Citation/style artifacts
  -> Ready to inspect
  -> Human-controlled copy/export
```

`AgentRun` is execution. `Task` is the durable user-visible work item.
`WorkSession` is the conversational surface around one or more tasks.

## System Design Implications

- Add `work_sessions` for chat-like sessions.
- Add `session_messages` for transcript and agent questions.
- Add `tasks` for durable work visible in the session task area.
- Add `task_artifacts` for inspectable outputs: source map, citation map, style
  report, agent plan, verification summary, and content pack links.
- Add `source_selection_items` so the source timeline becomes a
  visible selection, not hidden pipeline state.
- Add typed artifacts for source coverage, citations, style notes, and
  generation summaries.
- Add `update_recipes` later for recurring release/update cadence, but keep
  recipe UI out of v0 primary navigation.
- Link `agent_runs` to `tasks` and `work_sessions`.
- Keep publishing human-controlled outside Plot in v0.
- Keep source adapters permissioned and workspace-scoped.
- Store enough style-report payload to show measurable style signals without
  building a broad style marketplace.

## Non-Goals

- Do not build a generic template marketplace in v0.
- Do not make GitHub setup the home screen.
- Do not hide agent work behind one final generated answer.
- Do not store a broad company knowledge graph.
- Do not add autonomous publishing before the source-cited generation loop is trusted.
