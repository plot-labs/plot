import type { Reporter } from "@playwright/test/reporter";

/** Real-source runs deliberately communicate only through their immutable observation envelope and exit code. */
export default class RedactedReporter implements Reporter {
  printsToStdio(): boolean { return false; }
}
