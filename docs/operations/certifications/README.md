# Generation certification reports

This directory contains only generated, redacted decision records. Raw manifests, provider responses, prompts, completions, source labels/titles/URLs/bodies, snapshot excerpts, browser captures, database extracts, and authentication state are never committed here.

## Filename and revision

Use `YYYY-MM-DD-<40-hex-source-revision>.md`. A report certifies exactly the immutable source revision named inside it. A later non-report code/configuration change invalidates the campaign and requires a new report on a new revision. The report may be committed only in a report-only follow-up revision.

## Versioned schema

`generation-certification-report-v1` contains these fixed sections:

1. Source revision, opaque campaign/environment/source aliases, campaign manifest hash, corpus hash, combined profile hash, and selected model-execution ID.
2. Deterministic, selected-model browser/audit, all-candidate evidence, process-restart, cleanup, and computed-eligibility outcomes.
3. One model section per required candidate with model-execution manifest, model profile, route policy, requested model, exact served model, observed upstream, and complete attempt lineage.
4. Every retained top-level attempt per model. Each ordinal has at least one valid result; model-inconclusive attempts and fresh full attempts created for browser-infrastructure replacement remain visible with their independent lineages. Every case and aggregate retains cold/warm marker, native token classes, actual micro-USD cost, latency, rewrite/model-call counts, citation/grounding/conflict/NOT_REQUIRED basis-point metrics, and typed defect codes.
5. Each browser/persisted-audit reconciliation with the exact model execution, attempt, scenario, ordinal, outcome, and typed codes.
6. Process-level restart checkpoint artifact, source-snapshot-set hash, and exactly-once model/citation/intervention/content-pack/export counts.
7. Campaign/revision-bound cleanup outcome, observation/attestation time, operator alias, codes, machine-observed listener and artifact state, credential/state-secret attestation, and database disposition. Restricted retention adds only an opaque owner alias and UTC expiry.
8. Opaque operator alias, UTC timestamp, numeric 1–5 rubric fields, hedging enum, requested decision, computed eligibility, and final GO/NO-GO.

Unknown fields, generic maps, free-form notes, and source/provider-derived narratives are rejected.

## Privacy allow-list

Allowed values are:

- fixed schema labels and Markdown structure;
- opaque `campaign-*`, `model-execution-*`, `attempt-*`, `env-*`, `source-*`, `operator-*`, and `owner-*` aliases;
- the committed 40-hex source revision;
- sealed SHA-256 campaign/model/profile/route/corpus/source-snapshot-set hashes explicitly present in the typed input;
- fixed model IDs and the allow-listed upstream slug;
- UTC timestamps, booleans, non-negative counts, bounded basis points, cost, and latency;
- typed outcomes, cleanup/reconciliation/restart codes, model/preflight failure codes, and the enumerated operator rubric.

The validator rejects URLs, repository/PR titles or names, source text, snapshot/prompt/completion content, private identifiers, emails, credentials, private keys, raw provider/request IDs, reversible unapproved hashes, Playwright traces/screenshots/video/storage state, transient artifact paths, HTML diagnostics, and free-form operator/source notes. `outputContentHash` remains transient audit data and is never rendered.

## Decision rules

- Model coverage requires at least one non-inconclusive result at each ordinal 1–3. Every model-inconclusive result must terminate in a same-ordinal model replacement lineage; additional passing full-model attempts may exist only because a browser-infrastructure replacement needed a new composite attempt.
- Browser/audit eligibility independently requires three valid passing ordinals for the selected model. Browser observations remain pending until offline reconciliation passes.
- Nano is selected when eligible. 4o Mini can be selected only when Nano is ineligible and 4o Mini is eligible.
- Deterministic and process-level restart gates must pass.
- Automation cannot convert an operator `NO_GO` into GO or override a hard failure.
- A final requested GO is rejected until cleanup proves zero listeners, credential/state-secret disposition, raw/browser artifact deletion, and database destruction or approved restricted retention.

Run the immutable-evidence assembler once for the draft before cleanup and seal its redacted typed input into the safe report snapshot. Raw API/browser roots are then purged. After a passing cleanup envelope, the final renderer verifies the snapshot hash and joins only the snapshot, cleanup, and final operator decision. The final operator timestamp must not precede cleanup. Both generated Markdown outputs pass the redaction validator; the final file is written with create-new semantics and the safe root is purged afterward.
