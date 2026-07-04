# YC-Style Product Review

Status: SUPERSEDED

Last reviewed: 2026-07-03

## Verdict

This review passed the narrower manual GitHub-first workflow. It has been
superseded by the later product correction: Plot should be an autonomous update
agent that prepares source-backed, on-style update packs for human and agent
review.

## Review Loop

1. Initial review returned `PASS_WITH_CHANGES`.
   - ICP was too broad.
   - v0 still looked like a multi-input writing system.
   - README and data architecture over-emphasized uploads, paste, and voice.
2. Second review returned `PASS_WITH_CHANGES`.
   - v0 artifact set still leaked internal briefs and handoffs.
   - README principle still said every input becomes a block.
   - deferred connection providers were still mixed into the v0 enum.
   - a landing visual still implied automation.
3. Final review returned `PASS`.

## Superseding Product Correction

The current product direction keeps the review discipline from this loop but
changes the center of gravity:

- Plot is autonomous draft preparation, not a manual composer.
- Voice/style is a core quality layer, not deferred polish.
- Humans still approve publishing; autonomous publish remains deferred.
- External coding agents should be able to create or inspect the same AgentRuns,
  ContentPacks, Claims, and ClaimEvidence that humans review.

## Strongest Version

- Small AI/devtool/API teams shipping weekly.
- Select shipping window, first source adapter, and release cadence.
- Let the update agent prepare shipped-change selection and draft packs.
- Generate one source-backed, on-style update pack for human approval.
- Defer broad inputs and publishing automation.

## Remaining Product Risks

- GitHub may not contain enough customer-impact context without later
  integrations.
- The fixed five-artifact pack may still be too much for the first user session.
- Claim review must save time, not feel like extra process.

## Validation Assignment

Run the concierge test with 5 real recent GitHub release windows, deliver the
pack before users ask for it, and ask for next-release agent usage plus payment.
