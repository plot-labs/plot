import type { Artifact } from "@plot/api-client";

/** An unresolved statement the API reports before it lets a draft be exported or published. */
export type StatementWarning = { key: string; sentenceNumber: number; excerpt: string };

export function isStatementWarning(value: unknown): value is StatementWarning {
  if (!value || typeof value !== "object") return false;
  const warning = value as Record<string, unknown>;
  return typeof warning.key === "string" && typeof warning.sentenceNumber === "number" && typeof warning.excerpt === "string";
}

/** Scrolls the editor to a warned statement, focuses it, and highlights it briefly. */
export function focusStatement(pack: Artifact, sentenceNumber: number) {
  const sentenceId = [...pack.variant.sentences].sort((a, b) => a.orderIndex - b.orderIndex)[sentenceNumber - 1]?.id;
  const sentence = sentenceId
    ? document.querySelector<HTMLElement>(`[data-statement-id="${sentenceId}"]`)
    : null;
  sentence?.scrollIntoView?.({ block: "center", behavior: "smooth" });
  sentence?.focus();
  if (sentence) {
    sentence.dataset.statementHighlight = "true";
    window.setTimeout(() => {
      if (sentence.isConnected) delete sentence.dataset.statementHighlight;
    }, 2_000);
  }
}
