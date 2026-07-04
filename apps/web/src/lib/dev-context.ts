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
