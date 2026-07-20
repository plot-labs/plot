import { expect, test, type Locator } from "@playwright/test";

import {
  BrowserCertificationError,
  browserFailureOutcome,
  loadBrowserCertificationConfig,
  writeBrowserObservation,
  type BrowserObservationCode,
} from "./support/certification-manifest";

const certification = loadBrowserCertificationConfig();
const instruction = "Create a concise changelog from the selected Writing Blocks.";

test("observes the production generation contract", async ({ context, page }) => {
  const startedAt = Date.now();
  let generationRequestCount = 0;
  let citationCount = 0;
  let exportEventCount = 0;
  let externalRequestObserved = false;
  let productContractStarted = false;
  const codes = new Set<BrowserObservationCode>();

  try {
    await context.route("**/*", async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      if (url.protocol !== "http:" && url.protocol !== "https:") {
        await route.continue();
        return;
      }
      if (url.origin !== certification.baseUrl) {
        externalRequestObserved = true;
        await route.abort("blockedbyclient");
        return;
      }
      if (url.pathname === "/api/plot/generations" && request.method() === "POST") {
        generationRequestCount += 1;
        validateGenerationRequest(request.postDataJSON());
        requireObservation(
          /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(
            request.headers()["idempotency-key"] ?? "",
          ),
          "BROWSER_IDEMPOTENCY_HEADER_REJECTED",
        );
        await route.continue({
          headers: { ...request.headers(), "idempotency-key": certification.idempotencyKey },
        });
        return;
      }
      await route.continue();
    });

    await page.goto("/sessions?session=session-changelog-july", { waitUntil: "domcontentloaded" });
    productContractStarted = true;
    const referencesButton = page.getByRole("button", {
      name: `References · ${certification.writingBlockIds.length}`,
      exact: true,
    });
    await expect(referencesButton).toBeVisible();
    await referencesButton.click();
    const importedReferences = page.getByRole("checkbox");
    await expect(importedReferences).toHaveCount(certification.writingBlockIds.length);
    for (let index = 0; index < certification.writingBlockIds.length; index += 1) {
      await expect(importedReferences.nth(index)).toBeEnabled();
      await expect(importedReferences.nth(index)).toBeChecked();
    }
    codes.add("REAL_GITHUB_BLOCKS_OBSERVED");

    await page.getByRole("textbox", { name: "Session message" }).fill(instruction);
    await page.getByRole("button", { name: "Send message" }).click();
    await expect.poll(() => generationRequestCount).toBe(1);
    await page.waitForURL((url) => isGenerationRunUrl(url), { timeout: 30_000 });

    const terminalStatus = page.getByRole("status", { name: /Generation status: (Ready|Needs review)/ });
    await expect(terminalStatus).toBeVisible({ timeout: 120_000 });
    await expect(page.getByRole("status", { name: "Generation status: Needs your call" })).toHaveCount(0);
    await page.reload({ waitUntil: "domcontentloaded" });
    await expect(terminalStatus).toBeVisible({ timeout: 30_000 });

    const draft = page.getByRole("region", { name: "Cited draft" });
    await expect(draft).toBeVisible();
    const citedSentences = await findCitedSentences(draft);
    requireObservation(citedSentences.length >= 2, "BROWSER_CITATION_COUNT_REJECTED");
    citationCount = await countInlineCitations(citedSentences);

    const preservedCitations = await collectPreservedCitations(citedSentences.slice(1));
    requireObservation(preservedCitations.length > 0, "BROWSER_CITATION_COUNT_REJECTED");
    codes.add("CITATION_POPOVER_OBSERVED");

    requireObservation(await hasEvidenceFreeSentence(draft), "BROWSER_EVIDENCE_FREE_SENTENCE_MISSING");
    codes.add("EVIDENCE_FREE_SENTENCE_OBSERVED");

    const editedSentence = citedSentences[0]!;
    const editedBody = "This operator-edited sentence must be revalidated before publication.";
    await editedSentence.getByRole("button", { name: /^Edit sentence/ }).click();
    await editedSentence.getByRole("textbox", { name: /^Sentence \d+ text$/ }).fill(editedBody);
    await editedSentence.getByRole("button", { name: /^Save sentence/ }).click();
    await expect(editedSentence).toContainText(editedBody);
    await expect(editedSentence.getByRole("button", { expanded: false })).toHaveCount(0);
    await page.reload({ waitUntil: "domcontentloaded" });
    const restoredEditedSentence = page.getByRole("listitem").filter({ hasText: editedBody });
    await expect(restoredEditedSentence).toBeVisible({ timeout: 30_000 });
    await expect(restoredEditedSentence.getByRole("button", { expanded: false })).toHaveCount(0);
    codes.add("STALE_EDIT_OBSERVED");

    await page.getByRole("button", { name: "Copy changelog" }).click();
    exportEventCount += 1;
    const warning = page.getByRole("alertdialog", { name: "Unresolved sentences will be exported" });
    await expect(warning).toBeVisible();
    await expect(restoredEditedSentence).toContainText(editedBody);
    await warning.getByRole("button", { name: "Confirm and copy" }).click();
    exportEventCount += 1;
    await expect(page.getByRole("status", { name: "Changelog copied." })).toBeVisible();
    codes.add("EXPORT_CONFIRMATION_OBSERVED");

    const markdown = await page.evaluate(() => navigator.clipboard.readText());
    validateMarkdown(markdown, preservedCitations);
    requireObservation(!externalRequestObserved, "BROWSER_EXTERNAL_REQUEST_REJECTED");
    codes.add("MARKDOWN_SAFETY_OBSERVED");
    codes.add("BROWSER_CONTRACT_OBSERVED");
    codes.add("PENDING_AUDIT_RECONCILIATION");

    writeBrowserObservation(certification, {
      outcome: "INCONCLUSIVE",
      metrics: {
        latencyMs: Date.now() - startedAt,
        citationCount,
        reviewNeededSentenceCount: 1,
        unresolvedConflictCount: 0,
        exportEventCount,
      },
      codes: [...codes],
    });
	} catch (error) {
		const outcome = browserFailureOutcome({ error, productContractStarted, externalRequestObserved });
		const failureCodes: BrowserObservationCode[] = outcome === "HARD_GATE_FAIL"
			? ["BROWSER_CONTRACT_FAILED", ...(generationRequestCount > 0 ? ["PENDING_AUDIT_RECONCILIATION" as const] : [])]
			: ["BROWSER_INFRASTRUCTURE_INCONCLUSIVE"];
		try {
      writeBrowserObservation(certification, {
        outcome,
        metrics: {
          latencyMs: Date.now() - startedAt,
          citationCount,
          exportEventCount,
        },
				codes: failureCodes,
      });
    } catch {
      // The immutable writer is fail-closed; never replace an existing observation.
    }
    throw new BrowserCertificationError("BROWSER_CONTRACT_FAILED");
  }
});

function validateGenerationRequest(input: unknown): void {
  if (!input || typeof input !== "object" || Array.isArray(input)) {
    throw new BrowserCertificationError("BROWSER_GENERATION_REQUEST_REJECTED");
  }
  const request = input as Record<string, unknown>;
  const keys = Object.keys(request).sort();
  requireObservation(
    JSON.stringify(keys) === JSON.stringify(["instruction", "sourceScopeId", "writingBlockIds"]),
    "BROWSER_GENERATION_REQUEST_REJECTED",
  );
  requireObservation(request.instruction === instruction, "BROWSER_GENERATION_INSTRUCTION_REJECTED");
  requireObservation(
    Array.isArray(request.writingBlockIds) &&
      JSON.stringify([...request.writingBlockIds].sort()) === JSON.stringify([...certification.writingBlockIds].sort()),
    "BROWSER_WRITING_BLOCK_ID_MISMATCH",
  );
}

function isGenerationRunUrl(url: URL): boolean {
  const runId = url.searchParams.get("generation");
  return url.origin === certification.baseUrl &&
    url.pathname === "/sessions" &&
    /^[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(runId ?? "");
}

async function findCitedSentences(draft: Locator): Promise<Locator[]> {
  const cited: Locator[] = [];
  const sentences = draft.getByRole("listitem");
  const sentenceCount = await sentences.count();
  for (let index = 0; index < sentenceCount; index += 1) {
    const sentence = sentences.nth(index);
    if (await sentence.getByRole("button", { expanded: false }).count() > 0) cited.push(sentence);
  }
  return cited;
}

async function countInlineCitations(sentences: readonly Locator[]): Promise<number> {
  let count = 0;
  for (const sentence of sentences) count += await sentence.getByRole("button", { expanded: false }).count();
  return count;
}

type PreservedCitation = Readonly<{ sourceLabel: string; originalUrl: string; snapshotExcerpt: string }>;

async function collectPreservedCitations(sentences: readonly Locator[]): Promise<PreservedCitation[]> {
  const citations: PreservedCitation[] = [];
  for (const sentence of sentences) {
    const buttons = sentence.getByRole("button", { expanded: false });
    const count = await buttons.count();
    for (let index = 0; index < count; index += 1) {
      const citation = buttons.nth(index);
      const label = (await citation.textContent())?.trim() ?? "";
      requireObservation(label.startsWith("GitHub · "), "BROWSER_CITATION_LABEL_REJECTED");
      const sourceLabel = label.slice("GitHub · ".length);
      requireObservation(sourceLabel.length > 0, "BROWSER_CITATION_LABEL_REJECTED");
      await citation.click();
      const evidencePopover = sentence.getByRole("region").first();
      await expect(evidencePopover).toBeVisible();
      const snapshotExcerpt = (await evidencePopover.getByRole("paragraph").first().textContent())?.trim() ?? "";
      requireObservation(snapshotExcerpt.length > 0, "BROWSER_SNAPSHOT_REJECTED");
      const originalLink = evidencePopover.getByRole("link", { name: "Open original" });
      await expect(originalLink).toBeVisible();
      const originalUrl = await originalLink.getAttribute("href");
      requireObservation(isPermittedGitHubUrl(originalUrl), "BROWSER_ORIGINAL_LINK_REJECTED");
      citations.push({ sourceLabel, originalUrl, snapshotExcerpt });
      await evidencePopover.getByRole("button", { name: "Close evidence" }).click();
    }
  }
  return citations;
}

async function hasEvidenceFreeSentence(draft: Locator): Promise<boolean> {
  const sentences = draft.getByRole("listitem");
  const sentenceCount = await sentences.count();
  for (let index = 0; index < sentenceCount; index += 1) {
    const sentence = sentences.nth(index);
    if (await sentence.getByRole("button", { expanded: false }).count() === 0) return true;
  }
  return false;
}

function isPermittedGitHubUrl(value: string | null): value is string {
  try {
    const url = new URL(value ?? "");
    return url.protocol === "https:" && url.hostname === "github.com" && !url.username && !url.password && !url.port;
  } catch {
    return false;
  }
}

function validateMarkdown(markdown: string, citations: readonly PreservedCitation[]): void {
  for (const citation of citations) {
    const expectedLabel = neutralizeMarkdownLabel(citation.sourceLabel);
    requireObservation(markdown.includes(`[${expectedLabel}](${citation.originalUrl})`), "BROWSER_MARKDOWN_SOURCE_LINK_MISSING");
    requireObservation(!markdown.includes(citation.snapshotExcerpt), "BROWSER_MARKDOWN_SNAPSHOT_LEAKED");
  }
  requireObservation(!/<\/?[a-z][^>]*>/i.test(markdown), "BROWSER_MARKDOWN_RAW_HTML_REJECTED");
  requireObservation(!/\b(?:javascript|data)\s*:/i.test(markdown), "BROWSER_MARKDOWN_ACTIVE_SCHEME_REJECTED");
  requireObservation(!/!\[[^\]]*]\s*\(/.test(markdown), "BROWSER_MARKDOWN_IMAGE_REJECTED");
  const targets = [...markdown.matchAll(/\[[^\]]*]\(([^)]+)\)/g)].map((match) => match[1]);
  requireObservation(targets.every((target) => isPermittedGitHubUrl(target ?? null)), "BROWSER_MARKDOWN_LINK_REJECTED");
}

function neutralizeMarkdownLabel(value: string): string {
  return value
    .replace(/[\r\n]+/g, " ")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll("\\", "\\\\")
    .replaceAll("[", "\\[")
    .replaceAll("]", "\\]")
    .replace(/\b(https?|javascript|data)\s*:/gi, "$1&#58;")
    .trim();
}

function requireObservation(condition: unknown, code: string): asserts condition {
  if (!condition) throw new BrowserCertificationError(code);
}
