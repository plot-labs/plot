const SAFE_URL_SCHEMES = new Set(["http:", "https:"]);

/**
 * Source URLs can originate from model output or third-party payloads, so
 * only http(s) links may become anchors. Anything else — javascript:, data:,
 * vbscript:, or unparseable input — is rejected and renders inert.
 */
export function isSafeHttpUrl(value: string | null | undefined): boolean {
  if (!value) return false;
  try {
    return SAFE_URL_SCHEMES.has(new URL(value).protocol);
  } catch {
    return false;
  }
}
