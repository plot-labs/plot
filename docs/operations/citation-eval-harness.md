# Citation/Generation Quality Eval Harness

Plot's eval harness validates citation and generation quality for the artifact workflow models. The harness ensures that the reviewer model correctly identifies supported, unsupported, conflicting, and editorial sentences, and that the rewriter model preserves supported content while removing unsupported claims.

## Overview

The eval harness provides two testing modes:

1. **Deterministic (fixture-based)**: Runs in default CI against golden fixture outputs
2. **Live (model-based)**: Optional testing against real model outputs (requires AI credentials)

Both modes score outputs against the same corpus of evaluation cases with expected verdicts and evidence IDs.

## Corpus Format

Evaluation cases are stored in `apps/api/src/test/resources/evals/generation-citation-cases.json`.

### Case Structure

```json
{
  "version": 2,
  "cases": [
    {
      "id": "case-identifier",
      "tags": ["tag1", "tag2"],
      "instruction": "Optional instruction for the writer model",
      "evidence": [
        {
          "id": "10000000-0000-0000-0000-000000000001",
          "label": "PR #123",
          "title": "Evidence title",
          "body": "Evidence content (no https:// URLs allowed)"
        }
      ],
      "sentences": [
        {
          "id": "20000000-0000-0000-0000-000000000001",
          "body": "The sentence text to be reviewed",
          "expectedVerdict": "SUPPORTED|NOT_REQUIRED|NEEDS_SUPPORT|CONFLICT",
          "expectedEvidenceIds": ["evidence-uuid", ...],
          "rewriteTarget": false,
          "rewriteExpectedVerdict": null,
          "rewriteExpectedEvidenceIds": []
        }
      ]
    }
  ]
}
```

### Field Descriptions

- **id**: Unique kebab-case identifier for the case
- **tags**: Tags describing the test scenario (used for corpus validation)
- **instruction**: Optional instruction passed to the writer model
- **evidence**: List of evidence snapshots (synthetic UUIDs, no real repo names or secrets)
- **sentences**: List of sentences to be reviewed
  - **expectedVerdict**: The verdict the reviewer should assign
  - **expectedEvidenceIds**: Unordered set of evidence IDs that should support the verdict
  - **rewriteTarget**: If true, this sentence should be rewritten when it has NEEDS_SUPPORT verdict
  - **rewriteExpectedVerdict**: Expected verdict after rewrite and re-review
  - **rewriteExpectedEvidenceIds**: Expected evidence IDs after rewrite

### Corpus Constraints

- Evidence bodies must not contain `https://` URLs
- No secrets or real private repository names
- All UUIDs must be unique across the corpus
- Use synthetic UUIDs in the `10000000-0000-0000-0000-00000000xxxx` and `20000000-0000-0000-0000-00000000xxxx` ranges
- `rewriteTarget` must be true only when `expectedVerdict` is `NEEDS_SUPPORT`
- When `rewriteTarget` is true, `rewriteExpectedVerdict` must not be null

## Fixture Format

Golden fixture outputs are stored in `apps/api/src/test/resources/evals/generation-citation-cases-fixtures.json`.

### Fixture Structure

```json
{
  "version": 1,
  "fixtures": [
    {
      "caseId": "case-identifier",
      "reviewerOutput": {
        "reviews": [
          {
            "sentenceId": "20000000-0000-0000-0000-000000000001",
            "verdict": "SUPPORTED",
            "evidenceIds": ["10000000-0000-0000-0000-000000000001"],
            "reason": null,
            "modelSuppliedUrls": []
          }
        ],
        "documentConflicts": []
      },
      "rewriterOutput": null
    }
  ]
}
```

- **reviewerOutput**: The expected output from `ArtifactWorkflowModelGateway.review`
- **rewriterOutput**: The expected output from `ArtifactWorkflowModelGateway.rewrite` (null if no rewrite needed)

Fixtures are validated through `ModelOutputValidator.validateReview` and `.applyTargetedRewrite` before scoring.

## Adding a New Case

1. Add the case to `generation-citation-cases.json`:
   - Choose a unique kebab-case `id`
   - Add descriptive tags
   - Use synthetic UUIDs in the reserved ranges
   - No `https://` in evidence bodies
   - Set `expectedVerdict` and `expectedEvidenceIds` for each sentence

2. Add fixture data to `generation-citation-cases-fixtures.json`:
   - Match the `caseId` to your corpus case `id`
   - Provide golden `reviewerOutput` (from a known-good model run or manual construction)
   - If `rewriteTarget` is true, provide `rewriterOutput`

3. Update tags in `ArtifactWorkflowCitationEvalCorpusTest.kt`:
   - Add any new tags to the expected set in the corpus validation test

4. Run `just eval-citation` to verify the new case passes

## Running the Evals

### Deterministic Eval (default CI)

```bash
just eval-citation
```

This runs `ArtifactWorkflowCitationEvalTest` which:
- Loads corpus and fixtures
- Validates fixture outputs through `ModelOutputValidator`
- Scores each sentence verdict and evidence IDs against expected values
- Applies rewrites when `rewriteTarget` is true and re-validates
- Fails on any mismatch with a detailed per-case report

This test runs as part of `just test-api` and in CI.

### Live Eval (opt-in, requires credentials)

```bash
just eval-citation-live
```

Or manually:

```bash
cd apps/api
./gradlew liveEval
```

This runs `ArtifactWorkflowCitationLiveEvalTest` which:
- Calls real `ArtifactWorkflowModelGateway.review` and `.rewrite` against corpus cases
- Validates and scores outputs the same way as deterministic eval
- Prints a summary of passed/failed cases
- Does not log prompt/completion bodies or evidence contents

**Prerequisites:**
- AI credentials configured (same as used by the API)
- The `liveEval` Gradle task automatically sets `PLOT_EVAL_LIVE=true`

**Not enabled in default `test` task or PR CI** (tagged with `@Tag("live-eval")` and excluded from default test suite).

## What Pass/Fail Means

### Pass Criteria

A case passes when:
- Initial review verdict matches `expectedVerdict` for every sentence
- Initial review evidence IDs match `expectedEvidenceIds` (unordered set comparison)
- For `rewriteTarget` sentences: after rewrite and re-review, verdict matches `rewriteExpectedVerdict` and evidence IDs match `rewriteExpectedEvidenceIds`
- No validation errors from `ModelOutputValidator`

### Failure Scenarios

A case fails when:
- Verdict mismatch (e.g., model says SUPPORTED but expected NEEDS_SUPPORT)
- Evidence ID mismatch (wrong evidence cited, or wrong subset)
- Rewrite omits a sentence that should remain
- Rewritten sentence still has wrong verdict/evidence after re-review
- `ModelOutputValidator` rejects the output as invalid

## Limitations and Future Work

### Out of Scope for v0

- Automating QA fail-deploy gates based on eval results
- Langfuse dashboards or observability integrations
- Rewriting production prompts based on eval failures
- Real private repository data or production smoke tests

### Eval Hygiene

- Never commit secrets or real private repo names to the corpus
- Keep evidence bodies synthetic and concise
- Use deterministic UUIDs for reproducibility
- Avoid prompt injection attempts in evidence (unless testing prompt safety)
- Do not test features that violate Plot's trust loop (exact range, inspectable citations, human publish)

## Related Files

- Corpus: `apps/api/src/test/resources/evals/generation-citation-cases.json`
- Fixtures: `apps/api/src/test/resources/evals/generation-citation-cases-fixtures.json`
- Deterministic test: `apps/api/src/test/kotlin/com/plot/api/ai/provider/ArtifactWorkflowCitationEvalTest.kt`
- Live test: `apps/api/src/test/kotlin/com/plot/api/ai/provider/ArtifactWorkflowCitationLiveEvalTest.kt`
- Corpus validation: `apps/api/src/test/kotlin/com/plot/api/ai/provider/ArtifactWorkflowCitationEvalCorpusTest.kt`
- Validator: `apps/api/src/main/kotlin/com/plot/api/artifact/workflow/ModelOutputValidator.kt`
- Gateway: `apps/api/src/main/kotlin/com/plot/api/ai/provider/ArtifactWorkflowModelGateway.kt`
