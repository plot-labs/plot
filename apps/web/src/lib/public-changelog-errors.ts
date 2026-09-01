import { PlotApiError } from "@plot/api-client";

export function isPublicChangelogNotFound(error: unknown): boolean {
  if (error instanceof PlotApiError) {
    return error.status === 404;
  }
  return (
    typeof error === "object" &&
    error !== null &&
    "status" in error &&
    (error as { status: unknown }).status === 404
  );
}
