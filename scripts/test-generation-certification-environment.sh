#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/generation-certification.sh"

export PLOT_CERTIFICATION_ALLOWED_CANARY="allowed"
export PLOT_CERTIFICATION_AMBIENT_SECRET_CANARY="must-not-cross"
export OPENAI_API_KEY="must-not-cross"
export GH_TOKEN="must-not-cross"

observed="$(clean_env PLOT_CERTIFICATION_ALLOWED_CANARY -- bash -c '
  test "$PLOT_CERTIFICATION_ALLOWED_CANARY" = allowed
  test -z "${PLOT_CERTIFICATION_AMBIENT_SECRET_CANARY:-}"
  test -z "${OPENAI_API_KEY:-}"
  test -z "${GH_TOKEN:-}"
  printf isolated
')"
test "$observed" = isolated

observation_root="$(mktemp -d)"
trap 'rm -rf "$observation_root"' EXIT
mkdir -p "$observation_root/campaign-aaaaaaaaaaaaaaaa/browser"
printf '%s\n' \
  '{"attemptId":"attempt-bbbbbbbbbbbbbbbb","evidenceType":"BROWSER_OBSERVATION"}' \
  > "$observation_root/campaign-aaaaaaaaaaaaaaaa/browser/artifact-a.json"
printf '%s\n' \
  '{"attemptId":"attempt-cccccccccccccccc","evidenceType":"BROWSER_OBSERVATION"}' \
  > "$observation_root/campaign-aaaaaaaaaaaaaaaa/browser/artifact-z.json"

resolved="$(
  resolve_browser_observation \
    "$observation_root" \
    "campaign-aaaaaaaaaaaaaaaa" \
    "attempt-bbbbbbbbbbbbbbbb"
)"
test "$resolved" = "$observation_root/campaign-aaaaaaaaaaaaaaaa/browser/artifact-a.json"
