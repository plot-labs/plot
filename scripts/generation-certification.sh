#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'
umask 077

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
API_DIR="$ROOT/apps/api"
WEB_DIR="$ROOT/apps/web"
STAGE="${1:-all}"
API_PID=""
API_JVM_PID=""
WEB_PID=""
WEB_JVM_PID=""
POSTGRES_CONTAINER=""
BROWSER_ATTEMPT_OUTCOME=""
MODEL_REPLACEMENT_ATTEMPT_ID=""
MODEL_REPLACEMENT_RESULT_PATH=""

fail() { printf '%s\n' "generation certification: $1" >&2; exit 2; }
require_binary() { command -v "$1" >/dev/null 2>&1 || fail "required binary missing: $1"; }
require_env() { test -n "${!1:-}" || fail "required environment missing: $1"; }
require_true() { test "${!1:-}" = "true" || fail "$1 must be true"; }
require_file() { test -f "${!1:-}" || fail "required file missing: $1"; }
curl_probe() { curl --noproxy '*' --connect-timeout 2 --max-time 5 "$@"; }
curl_request() { curl --noproxy '*' --connect-timeout 5 --max-time 180 "$@"; }

validate_utc_timestamp() {
  jq -en --arg value "$1" '$value | fromdateiso8601' >/dev/null 2>&1 || fail "$2 must be UTC RFC3339"
}

prepare_artifact_root() {
  local root="$1" label="$2" parent real_root
  test ! -e "$root" && test ! -L "$root" || fail "$label artifact root must not pre-exist"
  mkdir -p "$(dirname "$root")"
  parent="$(cd "$(dirname "$root")" && pwd -P)"
  case "$parent/$(basename "$root")" in "$ROOT"/*) ;; *) fail "$label artifact root must be inside the checkout" ;; esac
  git -C "$ROOT" check-ignore -q "$root" || fail "$label artifact root is not ignored"
  mkdir -m 700 "$root"
  test -d "$root" && test ! -L "$root" || fail "$label artifact root is unsafe"
  real_root="$(cd "$root" && pwd -P)"
  case "$real_root" in "$ROOT"/*) ;; *) fail "$label artifact root escaped the checkout" ;; esac
}

validate_existing_artifact_root() {
  local root="$1" label="$2" real_root
  test -d "$root" && test ! -L "$root" || fail "$label artifact root is missing or unsafe"
  real_root="$(cd "$root" && pwd -P)"
  case "$real_root" in "$ROOT"/*) ;; *) fail "$label artifact root escaped the checkout" ;; esac
  git -C "$ROOT" check-ignore -q "$root" || fail "$label artifact root is not ignored"
  test -z "$(find "$root" -type l -print -quit)" || fail "$label artifact tree contains a symlink"
}

clean_env() {
  local -a values
  local name
  values=("PATH=$PATH")
  for name in HOME USER LOGNAME SHELL TMPDIR LANG LC_ALL JAVA_HOME; do
    if test -n "${!name:-}"; then values+=("$name=${!name}"); fi
  done
  while test "$#" -gt 0 && test "$1" != "--"; do
    name="$1"
    if printenv "$name" >/dev/null 2>&1; then values+=("$name=${!name}"); fi
    shift
  done
  test "${1:-}" = "--" || fail "clean environment command separator missing"
  shift
  env -i "${values[@]}" "$@"
}

docker_local() { clean_env -- docker "$@"; }
docker_database() { clean_env POSTGRES_PASSWORD -- docker "$@"; }

stop_pid() {
  local pid="$1"
  test -n "$pid" || return 0
  kill "$pid" 2>/dev/null || true
  local count=0
  while kill -0 "$pid" 2>/dev/null && test "$count" -lt 20; do
    sleep 1
    count=$((count + 1))
  done
  kill -9 "$pid" 2>/dev/null || true
}

stop_api() { stop_pid "$API_JVM_PID"; stop_pid "$API_PID"; API_JVM_PID=""; API_PID=""; }
stop_web() { stop_pid "$WEB_JVM_PID"; stop_pid "$WEB_PID"; WEB_JVM_PID=""; WEB_PID=""; }
stop_postgres() {
  if test -n "$POSTGRES_CONTAINER"; then
    docker_local stop "$POSTGRES_CONTAINER" >/dev/null 2>&1 || true
    docker_local rm -v "$POSTGRES_CONTAINER" >/dev/null 2>&1 || true
    POSTGRES_CONTAINER=""
  fi
}
is_descendant() {
  local child="$1" ancestor="$2" parent
  while test "$child" -gt 1 2>/dev/null; do
    test "$child" = "$ancestor" && return 0
    parent="$(ps -o ppid= -p "$child" | tr -d ' ')"
    test -n "$parent" || return 1
    child="$parent"
  done
  return 1
}
emergency_cleanup() { stop_web; stop_api; stop_postgres; }

reject_secret_cli_arguments() {
  test "$#" -le 1 || fail "only a non-secret stage name is accepted"
  case "$STAGE" in
    all|preflight|deterministic|browser|audit|report|cleanup) ;;
    *) fail "unsupported stage" ;;
  esac
}

drop_github_credentials() {
  unset GITHUB_TOKEN GH_TOKEN GITHUB_APP_PRIVATE_KEY GITHUB_INSTALLATION_TOKEN GITHUB_WEBHOOK_SECRET
  unset PLOT_GITHUB_PRIVATE_KEY PLOT_GITHUB_STATE_SECRET
}

drop_openrouter_credentials() {
  unset OPENROUTER_API_KEY OPENAI_API_KEY SPRING_AI_OPENAI_API_KEY SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL
}

drop_provider_credentials() { drop_github_credentials; drop_openrouter_credentials; }
drop_database_credentials() {
  unset POSTGRES_PASSWORD SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD
  unset PLOT_CERTIFICATION_DATABASE_URL PLOT_CERTIFICATION_DATABASE_USERNAME PLOT_CERTIFICATION_DATABASE_PASSWORD
  unset PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN PLOT_CERTIFICATION_DATABASE_FINGERPRINT
}
drop_all_credentials() { drop_provider_credentials; drop_database_credentials; }

validate_static_preflight() {
  require_true PLOT_GENERATION_CERTIFICATION_RUN
  for binary in git docker curl find jq lsof openssl pnpm printenv java shasum; do require_binary "$binary"; done
  local docker_context docker_endpoint
  docker_context="$(docker_local context show)" || fail "Docker context resolution failed"
  docker_endpoint="$(docker_local context inspect "$docker_context" --format '{{.Endpoints.docker.Host}}')" || fail "Docker endpoint resolution failed"
  case "$docker_endpoint" in unix:///*) ;; *) fail "Docker must use a local Unix socket" ;; esac
  require_env PLOT_GENERATION_SOURCE_REVISION
  local revision
  revision="$(git -C "$ROOT" rev-parse HEAD)"
  test "$revision" = "$PLOT_GENERATION_SOURCE_REVISION" || fail "source revision mismatch"
  test -z "$(git -C "$ROOT" status --porcelain)" || fail "implementation revision must be exactly clean"

  require_env PLOT_CERTIFICATION_API_ORIGIN
  require_env PLOT_CERTIFICATION_WEB_ORIGIN
  test "$PLOT_CERTIFICATION_API_ORIGIN" = "http://127.0.0.1:${PLOT_CERTIFICATION_API_PORT:-8080}" || fail "API origin must be canonical loopback"
  test "$PLOT_CERTIFICATION_WEB_ORIGIN" = "http://127.0.0.1:${PLOT_CERTIFICATION_WEB_PORT:-3000}" || fail "web origin must be canonical loopback"
  test "${PLOT_OPENROUTER_BASE_URL:-https://openrouter.ai/api/v1}" = "https://openrouter.ai/api/v1" || fail "OpenRouter origin rejected"
  test "${PLOT_GITHUB_API_BASE_URL:-https://api.github.com}" = "https://api.github.com" || fail "GitHub API origin rejected"
  test "${PLOT_GITHUB_WEB_BASE_URL:-https://github.com}" = "https://github.com" || fail "GitHub web origin rejected"
  export PLOT_GITHUB_API_BASE_URL=https://api.github.com
  export PLOT_GITHUB_WEB_BASE_URL=https://github.com
  if pgrep -f '(^|/)(ngrok|cloudflared|localtunnel)( |$)' >/dev/null 2>&1; then fail "tunnel or reverse proxy detected"; fi

  require_env PLOT_CERTIFICATION_OUTPUT_ROOT
  require_env PLOT_CERTIFICATION_BROWSER_OUTPUT_ROOT
  local report_parent
  prepare_artifact_root "$PLOT_CERTIFICATION_OUTPUT_ROOT" API
  prepare_artifact_root "$PLOT_CERTIFICATION_BROWSER_OUTPUT_ROOT" browser
  export PLOT_CERTIFICATION_SAFE_ROOT="${PLOT_CERTIFICATION_OUTPUT_ROOT}.safe"
  prepare_artifact_root "$PLOT_CERTIFICATION_SAFE_ROOT" safe
  require_env PLOT_CERTIFICATION_REPORT_OUTPUT
  report_parent="$(cd "$(dirname "$PLOT_CERTIFICATION_REPORT_OUTPUT")" && pwd -P)"
  test "$report_parent" = "$ROOT/docs/operations/certifications" || fail "report directory rejected"
  echo "$(basename "$PLOT_CERTIFICATION_REPORT_OUTPUT")" | grep -Eq '^20[0-9]{2}-[0-9]{2}-[0-9]{2}-[a-f0-9]{40}\.md$' || fail "report filename rejected"
  test ! -e "$PLOT_CERTIFICATION_REPORT_OUTPUT" || fail "report path must not already exist"

  require_env PLOT_CERTIFICATION_CAMPAIGN_ID
  require_env PLOT_CERTIFICATION_CAMPAIGN_MANIFEST
  require_env PLOT_CERTIFICATION_CORPUS_HASH
  require_env PLOT_CERTIFICATION_SOURCE_ALIAS
  require_true PLOT_CERTIFICATION_SOURCE_OWNER_APPROVED
  require_env PLOT_CERTIFICATION_SOURCE_APPROVAL
  test -f "$PLOT_CERTIFICATION_SOURCE_APPROVAL" && test ! -L "$PLOT_CERTIFICATION_SOURCE_APPROVAL" || fail "source approval artifact missing or unsafe"
  case "$(cd "$(dirname "$PLOT_CERTIFICATION_SOURCE_APPROVAL")" && pwd -P)/$(basename "$PLOT_CERTIFICATION_SOURCE_APPROVAL")" in
    "$ROOT"/*) ;; *) fail "source approval artifact must be inside the checkout" ;;
  esac
  git -C "$ROOT" check-ignore -q "$PLOT_CERTIFICATION_SOURCE_APPROVAL" || fail "source approval artifact must be ignored"
  require_env PLOT_CERTIFICATION_SOURCE_WINDOW_START
  require_env PLOT_CERTIFICATION_SOURCE_WINDOW_END
  validate_utc_timestamp "$PLOT_CERTIFICATION_SOURCE_WINDOW_START" "source window start"
  validate_utc_timestamp "$PLOT_CERTIFICATION_SOURCE_WINDOW_END" "source window end"

  test "${PLOT_CERTIFICATION_NANO_MODEL:-}" = "openai/gpt-5.4-nano" || fail "Nano profile missing"
  test "${PLOT_CERTIFICATION_MINI_MODEL:-}" = "openai/gpt-4o-mini-2024-07-18" || fail "4o Mini profile missing"
  require_env PLOT_AI_ROUTING_PROVIDER
  test "${PLOT_AI_TRANSPORT_RETRIES:-0}" = "0" || fail "transport retries must be zero"
  test "${PLOT_AI_SCHEMA_RETRIES:-1}" = "1" || fail "schema retry count must be one"
  test "${PLOT_AI_CLAIM_TIMEOUT:-120s}" = "120s" || fail "certification claim timeout must be 120s"
  test "${PLOT_CERTIFICATION_CLAIM_RECOVERY_WAIT_SECONDS:-125}" = "125" || fail "claim recovery wait must be 125 seconds"
  test "${PLOT_AI_PROMPT_LOGGING:-false}" = "false" || fail "prompt logging must remain disabled"
  test "${PLOT_AI_COMPLETION_LOGGING:-false}" = "false" || fail "completion logging must remain disabled"

  require_env PLOT_CERTIFICATION_DATABASE_NAME
  case "$PLOT_CERTIFICATION_DATABASE_NAME" in plot_cert_*) ;; *) fail "disposable database name rejected" ;; esac
  require_env PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN
  local computed_database_fingerprint
  computed_database_fingerprint="sha256:$(printf '%s\n%s' "$PLOT_CERTIFICATION_DATABASE_NAME" "$PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN" | shasum -a 256 | awk '{print $1}')"
  if test -n "${PLOT_CERTIFICATION_DATABASE_FINGERPRINT:-}" && test "$PLOT_CERTIFICATION_DATABASE_FINGERPRINT" != "$computed_database_fingerprint"; then
    fail "disposable database fingerprint mismatch"
  fi
  export PLOT_CERTIFICATION_DATABASE_FINGERPRINT="$computed_database_fingerprint"
  require_env PLOT_CERTIFICATION_DATABASE_PASSWORD
  require_env PLOT_CERTIFICATION_POSTGRES_IMAGE
  case "$PLOT_CERTIFICATION_POSTGRES_IMAGE" in
    postgres@sha256:*|docker.io/library/postgres@sha256:*) ;;
    *) fail "PostgreSQL image must use the approved official repository and a pinned digest" ;;
  esac

  local environment_profile
  environment_profile="$(jq -cnS \
    --arg apiOrigin "$PLOT_CERTIFICATION_API_ORIGIN" \
    --arg webOrigin "$PLOT_CERTIFICATION_WEB_ORIGIN" \
    --arg githubApiOrigin "$PLOT_GITHUB_API_BASE_URL" \
    --arg githubWebOrigin "$PLOT_GITHUB_WEB_BASE_URL" \
    --arg openRouterOrigin "https://openrouter.ai/api/v1" \
    --arg postgresImage "$PLOT_CERTIFICATION_POSTGRES_IMAGE" \
    --arg databaseFingerprint "$PLOT_CERTIFICATION_DATABASE_FINGERPRINT" \
    --arg nanoModel "$PLOT_CERTIFICATION_NANO_MODEL" \
    --arg miniModel "$PLOT_CERTIFICATION_MINI_MODEL" \
    --arg routingProvider "$PLOT_AI_ROUTING_PROVIDER" \
    --arg timeoutSeconds "${PLOT_AI_TIMEOUT_SECONDS:-45}" \
    --arg maxOutputTokens "${PLOT_AI_MAX_OUTPUT_TOKENS:-2000}" \
    --arg transportRetries "${PLOT_AI_TRANSPORT_RETRIES:-0}" \
    --arg schemaRetries "${PLOT_AI_SCHEMA_RETRIES:-1}" \
    --arg claimTimeout "${PLOT_AI_CLAIM_TIMEOUT:-120s}" \
    '{apiOrigin:$apiOrigin,webOrigin:$webOrigin,githubApiOrigin:$githubApiOrigin,githubWebOrigin:$githubWebOrigin,
      openRouterOrigin:$openRouterOrigin,postgresImage:$postgresImage,databaseFingerprint:$databaseFingerprint,
      nanoModel:$nanoModel,miniModel:$miniModel,routingProvider:$routingProvider,timeoutSeconds:$timeoutSeconds,
      maxOutputTokens:$maxOutputTokens,transportRetries:$transportRetries,schemaRetries:$schemaRetries,
      claimTimeout:$claimTimeout}')" || fail "environment profile derivation failed"
  export PLOT_CERTIFICATION_ENVIRONMENT_FINGERPRINT
  PLOT_CERTIFICATION_ENVIRONMENT_FINGERPRINT="sha256:$(printf '%s' "$environment_profile" | shasum -a 256 | awk '{print $1}')"
  unset environment_profile

  export PLOT_CERTIFICATION_STARTED_AT
  PLOT_CERTIFICATION_STARTED_AT="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  export PLOT_CERTIFICATION_RESTART_MARKER="$PLOT_CERTIFICATION_OUTPUT_ROOT/$PLOT_CERTIFICATION_CAMPAIGN_ID/restart/restart-marker.json"
  export PLOT_CERTIFICATION_IMPORT_HANDOFF="$PLOT_CERTIFICATION_OUTPUT_ROOT/operator/github-import.json"
  export PLOT_CERTIFICATION_DRAFT_OPERATOR_DECISION="$PLOT_CERTIFICATION_OUTPUT_ROOT/operator/draft-decision.json"
  export PLOT_CERTIFICATION_CLEANUP_ATTESTATION="$PLOT_CERTIFICATION_OUTPUT_ROOT/operator/cleanup-attestation.json"
  export PLOT_CERTIFICATION_OPERATOR_DECISION="$PLOT_CERTIFICATION_SAFE_ROOT/final-decision.json"
  export PLOT_CERTIFICATION_REPORT_SNAPSHOT="$PLOT_CERTIFICATION_SAFE_ROOT/report-snapshot.json"
  export PLOT_CERTIFICATION_CLEANUP_OUTPUT="$PLOT_CERTIFICATION_SAFE_ROOT/cleanup-result.json"
  export PLOT_CERTIFICATION_RESTART_TRIGGER_REQUEST="$PLOT_CERTIFICATION_OUTPUT_ROOT/operator/restart-request.json"
  export PLOT_CERTIFICATION_WORKSPACE_ID=018fd000-0000-7000-8000-000000000002
  test ! -e "$PLOT_CERTIFICATION_RESTART_TRIGGER_REQUEST" || fail "restart request path must not already exist"
  case "$PLOT_CERTIFICATION_RESTART_TRIGGER_REQUEST" in "$PLOT_CERTIFICATION_OUTPUT_ROOT"/*) ;; *) fail "restart request must be inside the certification output root" ;; esac
  git -C "$ROOT" check-ignore -q "$PLOT_CERTIFICATION_RESTART_TRIGGER_REQUEST" || fail "restart request must be ignored"
}

validate_listener() {
  local port="$1" origin="$2" probe_path="$3"
  lsof -nP -iTCP:"$port" -sTCP:LISTEN | awk 'NR > 1 { print $9 }' | grep -Eq '127\.0\.0\.1|\[::1\]' || fail "listener is not loopback"
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN | awk 'NR > 1 { print $9 }' | grep -Eq '(\*|0\.0\.0\.0):'; then fail "external listener detected"; fi
  local host_status forwarded_status
  host_status="$(curl_probe -sS -o /dev/null -w '%{http_code}' -H 'Host: external.invalid' "$origin$probe_path" || true)"
  forwarded_status="$(curl_probe -sS -o /dev/null -w '%{http_code}' -H 'X-Forwarded-Host: external.invalid' "$origin$probe_path" || true)"
  case "$host_status:$forwarded_status" in 4??:4??) ;; *) fail "Host or forwarded-host rejection not proven" ;; esac
}

wait_ready() {
  local origin="$1" attempts=0
  until curl_probe -fsS "$origin/actuator/health" >/dev/null 2>&1; do
    attempts=$((attempts + 1)); test "$attempts" -lt 90 || fail "listener readiness timed out"; sleep 1
  done
}

start_postgres() {
  export POSTGRES_PASSWORD="$PLOT_CERTIFICATION_DATABASE_PASSWORD"
  POSTGRES_CONTAINER="plot-cert-${PLOT_CERTIFICATION_CAMPAIGN_ID#campaign-}"
  docker_database run -d --rm --name "$POSTGRES_CONTAINER" \
    --label plot.generation-certification="$PLOT_CERTIFICATION_CAMPAIGN_ID" \
    -e POSTGRES_PASSWORD -e POSTGRES_USER=plot_cert -e POSTGRES_DB="$PLOT_CERTIFICATION_DATABASE_NAME" \
    -p "127.0.0.1:${PLOT_CERTIFICATION_DATABASE_PORT:-55432}:5432" \
    "$PLOT_CERTIFICATION_POSTGRES_IMAGE" >/dev/null
  unset POSTGRES_PASSWORD
  local attempts=0
  until docker_local exec "$POSTGRES_CONTAINER" pg_isready -U plot_cert -d "$PLOT_CERTIFICATION_DATABASE_NAME" >/dev/null 2>&1; do
    attempts=$((attempts + 1)); test "$attempts" -lt 60 || fail "disposable PostgreSQL readiness timed out"; sleep 1
  done
  export SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:${PLOT_CERTIFICATION_DATABASE_PORT:-55432}/$PLOT_CERTIFICATION_DATABASE_NAME"
  export SPRING_DATASOURCE_USERNAME=plot_cert
  export SPRING_DATASOURCE_PASSWORD="$PLOT_CERTIFICATION_DATABASE_PASSWORD"
  export PLOT_GENERATION_CERTIFICATION_TOOL=true
  export PLOT_CERTIFICATION_DATABASE_URL="$SPRING_DATASOURCE_URL"
  export PLOT_CERTIFICATION_DATABASE_USERNAME="$SPRING_DATASOURCE_USERNAME"
  (
    cd "$API_DIR"
    clean_env PLOT_GENERATION_CERTIFICATION_TOOL PLOT_CERTIFICATION_DATABASE_URL PLOT_CERTIFICATION_DATABASE_NAME \
      PLOT_CERTIFICATION_DATABASE_USERNAME PLOT_CERTIFICATION_DATABASE_PASSWORD PLOT_CERTIFICATION_DATABASE_FINGERPRINT \
      PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN -- ./gradlew --no-daemon generationCertificationMigrate
    clean_env PLOT_GENERATION_CERTIFICATION_TOOL PLOT_CERTIFICATION_DATABASE_URL PLOT_CERTIFICATION_DATABASE_NAME \
      PLOT_CERTIFICATION_DATABASE_USERNAME PLOT_CERTIFICATION_DATABASE_PASSWORD PLOT_CERTIFICATION_DATABASE_FINGERPRINT \
      PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN PLOT_CERTIFICATION_CAMPAIGN_ID -- \
      ./gradlew --no-daemon generationCertificationPrincipal
  )
}

start_api() {
  local mode="$1" model="${2:-}"
  stop_api
  (
		local -a api_environment
    export SERVER_ADDRESS=127.0.0.1 SERVER_PORT="${PLOT_CERTIFICATION_API_PORT:-8080}"
    export SPRING_DOCKER_COMPOSE_ENABLED=false
    export PLOT_DEV_BOOTSTRAP_ENABLED=false PLOT_LOOPBACK_REQUEST_GUARD_ENABLED=true
    if test "$mode" = github; then export SPRING_PROFILES_ACTIVE=generation-certification,local; else export SPRING_PROFILES_ACTIVE=generation-certification; fi
    if test "$mode" = github; then
      drop_openrouter_credentials
      export PLOT_AI_ENABLED=false PLOT_GITHUB_ENABLED=true PLOT_GITHUB_DEV_ONLY=true PLOT_GITHUB_LOOPBACK_ONLY=true
    else
      drop_github_credentials
    export PLOT_AI_ENABLED=true PLOT_AI_PROVIDER=openrouter PLOT_AI_MODEL="$model" SPRING_AI_MODEL_CHAT=openai
      export PLOT_AI_CLAIM_TIMEOUT=120s
      export PLOT_AI_BASE_URL=https://openrouter.ai/api/v1
      export PLOT_AI_ROUTING_PROVIDER PLOT_AI_ALLOW_FALLBACKS=false PLOT_AI_REQUIRE_PARAMETERS=false
      export PLOT_AI_ZERO_DATA_RETENTION=false PLOT_AI_CONTENT_LOGGING_ENABLED=false
      export PLOT_AI_TIMEOUT="${PLOT_AI_TIMEOUT_SECONDS:-45}s"
      export PLOT_AI_MAX_OUTPUT_TOKENS="${PLOT_AI_MAX_OUTPUT_TOKENS:-2000}"
      export PLOT_AI_TRANSPORT_RETRIES="${PLOT_AI_TRANSPORT_RETRIES:-0}"
      export PLOT_AI_SCHEMA_RETRIES="${PLOT_AI_SCHEMA_RETRIES:-1}"
      export SPRING_AI_OPENAI_MAX_RETRIES=0
      export SPRING_AI_CHAT_OBSERVATIONS_LOG_PROMPT=false SPRING_AI_CHAT_OBSERVATIONS_LOG_COMPLETION=false
      export SPRING_AI_CHAT_CLIENT_OBSERVATIONS_LOG_PROMPT=false SPRING_AI_CHAT_CLIENT_OBSERVATIONS_LOG_COMPLETION=false
      export SPRING_AI_OPENAI_API_KEY="$OPENROUTER_API_KEY"
      export PLOT_GITHUB_ENABLED=false
    fi
    cd "$API_DIR"
    api_environment=(
      SERVER_ADDRESS SERVER_PORT SPRING_PROFILES_ACTIVE
      SPRING_DOCKER_COMPOSE_ENABLED
      SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD
      PLOT_DEV_BOOTSTRAP_ENABLED PLOT_LOOPBACK_REQUEST_GUARD_ENABLED
      PLOT_AI_ENABLED PLOT_AI_PROVIDER PLOT_AI_MODEL PLOT_AI_BASE_URL PLOT_AI_ROUTING_PROVIDER
      PLOT_AI_ALLOW_FALLBACKS PLOT_AI_REQUIRE_PARAMETERS PLOT_AI_ZERO_DATA_RETENTION PLOT_AI_CONTENT_LOGGING_ENABLED
      PLOT_AI_TIMEOUT PLOT_AI_MAX_OUTPUT_TOKENS PLOT_AI_TRANSPORT_RETRIES PLOT_AI_SCHEMA_RETRIES PLOT_AI_CLAIM_TIMEOUT
      SPRING_AI_MODEL_CHAT SPRING_AI_OPENAI_API_KEY SPRING_AI_OPENAI_MAX_RETRIES
      SPRING_AI_CHAT_OBSERVATIONS_LOG_PROMPT SPRING_AI_CHAT_OBSERVATIONS_LOG_COMPLETION
      SPRING_AI_CHAT_CLIENT_OBSERVATIONS_LOG_PROMPT SPRING_AI_CHAT_CLIENT_OBSERVATIONS_LOG_COMPLETION
      PLOT_GITHUB_ENABLED PLOT_GITHUB_DEV_ONLY PLOT_GITHUB_LOOPBACK_ONLY PLOT_GITHUB_APP_ID PLOT_GITHUB_APP_SLUG
      PLOT_GITHUB_PRIVATE_KEY PLOT_GITHUB_STATE_SECRET PLOT_GITHUB_API_BASE_URL PLOT_GITHUB_WEB_BASE_URL
    )
    if test "$mode" = restart; then
      # Keep the certification attestation separate from Spring Boot's
      # management.server.address binding. Setting MANAGEMENT_SERVER_ADDRESS
      # without a separate management port makes Boot reject the restart
      # process before the API listener becomes ready.
      export PLOT_CERTIFICATION_MANAGEMENT_SERVER_ADDRESS=127.0.0.1
      api_environment+=(
        PLOT_CERTIFICATION_MANAGEMENT_SERVER_ADDRESS
        PLOT_GENERATION_CERTIFICATION_TOOL PLOT_CERTIFICATION_DATABASE_URL PLOT_CERTIFICATION_DATABASE_NAME
        PLOT_CERTIFICATION_DATABASE_FINGERPRINT PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN
        PLOT_CERTIFICATION_CAMPAIGN_ID PLOT_CERTIFICATION_RESTART_CHECKPOINT PLOT_CERTIFICATION_OUTPUT_ROOT
        PLOT_CERTIFICATION_RESTART_MARKER PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH PLOT_CERTIFICATION_MODEL_EXECUTION_ID
        PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST_HASH PLOT_CERTIFICATION_ATTEMPT_ID PLOT_CERTIFICATION_SOURCE_SNAPSHOT_SET_HASH
      )
    fi
    if test "$mode" = restart; then
      clean_env "${api_environment[@]}" -- ./gradlew --no-daemon generationCertificationRestartApi
    else
      clean_env "${api_environment[@]}" -- ./gradlew --no-daemon bootRun
    fi
  ) >"$PLOT_CERTIFICATION_OUTPUT_ROOT/api-${mode}.log" 2>&1 &
  API_PID=$!
  wait_ready "$PLOT_CERTIFICATION_API_ORIGIN"
  API_JVM_PID="$(lsof -nP -t -iTCP:"${PLOT_CERTIFICATION_API_PORT:-8080}" -sTCP:LISTEN | head -1)"
  test -n "$API_JVM_PID" || fail "API listener PID missing"
  is_descendant "$API_JVM_PID" "$API_PID" || fail "API listener is not owned by the started process tree"
  validate_listener "${PLOT_CERTIFICATION_API_PORT:-8080}" "$PLOT_CERTIFICATION_API_ORIGIN" "/actuator/health"
}

start_web() {
  stop_web
  (drop_all_credentials; cd "$ROOT"; clean_env -- pnpm --filter @plot/web build)
  (
    drop_all_credentials
    export HOSTNAME=127.0.0.1 PORT="${PLOT_CERTIFICATION_WEB_PORT:-3000}" PLOT_CERTIFICATION_LOOPBACK_GUARD=true
    export PLOT_API_BASE_URL="$PLOT_CERTIFICATION_API_ORIGIN"
    cd "$ROOT"
    clean_env HOSTNAME PORT PLOT_CERTIFICATION_LOOPBACK_GUARD PLOT_API_BASE_URL -- \
      pnpm --filter @plot/web start --hostname 127.0.0.1 --port "$PORT"
  ) >"$PLOT_CERTIFICATION_OUTPUT_ROOT/web.log" 2>&1 &
  WEB_PID=$!
  local attempts=0
  until curl_probe -fsS "$PLOT_CERTIFICATION_WEB_ORIGIN/sessions" >/dev/null 2>&1; do
    attempts=$((attempts + 1)); test "$attempts" -lt 90 || fail "web readiness timed out"; sleep 1
  done
  WEB_JVM_PID="$(lsof -nP -t -iTCP:"${PLOT_CERTIFICATION_WEB_PORT:-3000}" -sTCP:LISTEN | head -1)"
  test -n "$WEB_JVM_PID" || fail "web listener PID missing"
  is_descendant "$WEB_JVM_PID" "$WEB_PID" || fail "web listener is not owned by the started process tree"
  validate_listener "${PLOT_CERTIFICATION_WEB_PORT:-3000}" "$PLOT_CERTIFICATION_WEB_ORIGIN" "/sessions"
}

run_deterministic() {
  export PLOT_CERTIFICATION_DETERMINISTIC_RESULT="$PLOT_CERTIFICATION_OUTPUT_ROOT/deterministic-result.json"
  (
    cd "$API_DIR"
    clean_env PLOT_CERTIFICATION_DETERMINISTIC_RESULT PLOT_GENERATION_SOURCE_REVISION PLOT_CERTIFICATION_CAMPAIGN_ID \
      PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH PLOT_CERTIFICATION_CORPUS_HASH PLOT_CERTIFICATION_PROFILE_HASH -- \
      ./gradlew --no-daemon generationCertificationDeterministic --rerun-tasks
    clean_env -- ./gradlew --no-daemon verifyCertificationIsolation
  )
  (
    cd "$ROOT"
    clean_env -- pnpm --filter @plot/web test
    clean_env -- pnpm --filter @plot/web test:certification-support
    clean_env -- pnpm --filter @plot/api-client test
  )
}

operator_github_import() {
  require_true PLOT_CERTIFICATION_GITHUB_IMPORT_APPROVED
  printf '%s\n' "Complete the approved GitHub import, write $PLOT_CERTIFICATION_IMPORT_HANDOFF, then press return." >&2
  read -r _
  test -f "$PLOT_CERTIFICATION_IMPORT_HANDOFF" && test ! -L "$PLOT_CERTIFICATION_IMPORT_HANDOFF" || fail "GitHub import handoff missing or unsafe"
  jq -e '
    (keys == ["writingBlockIds"]) and (.writingBlockIds | type == "array" and length > 0 and
      all(.[]; type == "string" and test("^[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$"))) and
    ((.writingBlockIds | unique | length) == (.writingBlockIds | length))
  ' "$PLOT_CERTIFICATION_IMPORT_HANDOFF" >/dev/null || fail "GitHub import handoff contract rejected"
  export PLOT_CERTIFICATION_WRITING_BLOCK_IDS
  PLOT_CERTIFICATION_WRITING_BLOCK_IDS="$(jq -r '.writingBlockIds | join(",")' "$PLOT_CERTIFICATION_IMPORT_HANDOFF")"
  build_restart_trigger_request
}

build_restart_trigger_request() {
  export PLOT_GENERATION_CERTIFICATION_TOOL=true
  export PLOT_CERTIFICATION_DATABASE_URL="$SPRING_DATASOURCE_URL"
  export PLOT_CERTIFICATION_DATABASE_USERNAME="$SPRING_DATASOURCE_USERNAME"
  export PLOT_CERTIFICATION_DATABASE_PASSWORD="$SPRING_DATASOURCE_PASSWORD"
  (
    cd "$API_DIR"
    clean_env PLOT_GENERATION_CERTIFICATION_TOOL PLOT_CERTIFICATION_DATABASE_URL PLOT_CERTIFICATION_DATABASE_NAME \
      PLOT_CERTIFICATION_DATABASE_USERNAME PLOT_CERTIFICATION_DATABASE_PASSWORD PLOT_CERTIFICATION_DATABASE_FINGERPRINT \
      PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN PLOT_CERTIFICATION_WORKSPACE_ID PLOT_CERTIFICATION_WRITING_BLOCK_IDS \
      PLOT_CERTIFICATION_RESTART_TRIGGER_REQUEST -- ./gradlew --no-daemon generationCertificationRestartRequest
  )
  require_file PLOT_CERTIFICATION_RESTART_TRIGGER_REQUEST
  test ! -L "$PLOT_CERTIFICATION_RESTART_TRIGGER_REQUEST" || fail "restart request symlink rejected"
  jq -e '
    (keys == ["instruction","sourceScopeId","writingBlockIds"]) and
    (.instruction == "Generate a concise changelog grounded only in the selected evidence.") and
    (.sourceScopeId | type == "string" and test("^[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$")) and
    (.writingBlockIds | type == "array" and length > 0 and length <= 20 and
      all(.[]; type == "string" and test("^[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$"))) and
    ((.writingBlockIds | unique | length) == (.writingBlockIds | length))
  ' "$PLOT_CERTIFICATION_RESTART_TRIGGER_REQUEST" >/dev/null || fail "generated restart request contract rejected"
}

collect_draft_decision() {
  printf '%s\n' "Review the safe matrix, write the strict NO_GO draft decision to $PLOT_CERTIFICATION_DRAFT_OPERATOR_DECISION, then press return." >&2
  read -r _
  test -f "$PLOT_CERTIFICATION_DRAFT_OPERATOR_DECISION" && test ! -L "$PLOT_CERTIFICATION_DRAFT_OPERATOR_DECISION" || fail "draft operator decision missing or unsafe"
  jq -e '.requestedDecision == "NO_GO"' "$PLOT_CERTIFICATION_DRAFT_OPERATOR_DECISION" >/dev/null || fail "draft decision must be NO_GO"
}

scan_and_seal_imported_sources() {
  export PLOT_GENERATION_CERTIFICATION_TOOL=true
  export PLOT_CERTIFICATION_DATABASE_URL="$SPRING_DATASOURCE_URL"
  export PLOT_CERTIFICATION_DATABASE_USERNAME="$SPRING_DATASOURCE_USERNAME"
  export PLOT_CERTIFICATION_DATABASE_PASSWORD="$SPRING_DATASOURCE_PASSWORD"
  export PLOT_CERTIFICATION_IMPORTED_SOURCE_PREFLIGHT_OUTPUT="$PLOT_CERTIFICATION_OUTPUT_ROOT/imported-source-preflight.json"
  (
    cd "$API_DIR"
    clean_env PLOT_GENERATION_CERTIFICATION_TOOL PLOT_CERTIFICATION_DATABASE_URL PLOT_CERTIFICATION_DATABASE_NAME \
      PLOT_CERTIFICATION_DATABASE_USERNAME PLOT_CERTIFICATION_DATABASE_PASSWORD PLOT_CERTIFICATION_DATABASE_FINGERPRINT \
      PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN PLOT_CERTIFICATION_SOURCE_ALIAS PLOT_CERTIFICATION_SOURCE_WINDOW_START \
      PLOT_CERTIFICATION_SOURCE_WINDOW_END PLOT_CERTIFICATION_SOURCE_APPROVAL PLOT_CERTIFICATION_STARTED_AT \
      PLOT_CERTIFICATION_WRITING_BLOCK_IDS PLOT_CERTIFICATION_IMPORTED_SOURCE_PREFLIGHT_OUTPUT -- \
      ./gradlew --no-daemon generationCertificationImportedSourcePreflight
  )
  export PLOT_CERTIFICATION_IMPORTED_SOURCE_PREFLIGHT="$PLOT_CERTIFICATION_IMPORTED_SOURCE_PREFLIGHT_OUTPUT"
  export PLOT_CERTIFICATION_SOURCE_SNAPSHOT_SET_HASH
  PLOT_CERTIFICATION_SOURCE_SNAPSHOT_SET_HASH="$(jq -r '.sourceSnapshotSetHash' "$PLOT_CERTIFICATION_IMPORTED_SOURCE_PREFLIGHT")"
  export PLOT_CERTIFICATION_PROFILE_OUTPUT="$PLOT_CERTIFICATION_OUTPUT_ROOT/generation-profile.json"
  (
    cd "$API_DIR"
    clean_env PLOT_GENERATION_CERTIFICATION_TOOL PLOT_AI_ROUTING_PROVIDER PLOT_AI_TIMEOUT_SECONDS \
      PLOT_AI_MAX_OUTPUT_TOKENS PLOT_AI_TRANSPORT_RETRIES PLOT_AI_SCHEMA_RETRIES PLOT_CERTIFICATION_PROFILE_OUTPUT -- \
      ./gradlew --no-daemon generationCertificationProfile
  )
  export PLOT_CERTIFICATION_PROFILE_HASH
  PLOT_CERTIFICATION_PROFILE_HASH="$(jq -r '.matrixProfileHash' "$PLOT_CERTIFICATION_PROFILE_OUTPUT")"
  echo "$PLOT_CERTIFICATION_PROFILE_HASH" | grep -Eq '^sha256:[a-f0-9]{64}$' || fail "derived generation profile hash rejected"
  printf '%s\n' "Create the campaign manifest using environment fingerprint $PLOT_CERTIFICATION_ENVIRONMENT_FINGERPRINT and the safe imported-source/profile hashes, then press return." >&2
  read -r _
  require_file PLOT_CERTIFICATION_CAMPAIGN_MANIFEST
  export PLOT_CERTIFICATION_SEALED_CAMPAIGN_OUTPUT="$PLOT_CERTIFICATION_OUTPUT_ROOT/sealed-campaign.json"
  (
    cd "$API_DIR"
    clean_env PLOT_GENERATION_CERTIFICATION_TOOL PLOT_CERTIFICATION_IMPORTED_SOURCE_PREFLIGHT \
      PLOT_CERTIFICATION_CAMPAIGN_ID PLOT_CERTIFICATION_CAMPAIGN_MANIFEST PLOT_CERTIFICATION_SEALED_CAMPAIGN_OUTPUT \
      PLOT_CERTIFICATION_CORPUS_HASH PLOT_CERTIFICATION_PROFILE_HASH PLOT_CERTIFICATION_ENVIRONMENT_FINGERPRINT -- \
      ./gradlew --no-daemon generationCertificationCampaignSeal
  )
  export PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH
  PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH="$(jq -r '.campaignManifestHash' "$PLOT_CERTIFICATION_SEALED_CAMPAIGN_OUTPUT")"
}

run_contract_matrix() {
  export PLOT_CERTIFICATION_NANO_MANIFEST_OUTPUT="$PLOT_CERTIFICATION_OUTPUT_ROOT/model-manifests/nano.json"
  export PLOT_CERTIFICATION_MINI_MANIFEST_OUTPUT="$PLOT_CERTIFICATION_OUTPUT_ROOT/model-manifests/mini.json"
  (
    drop_github_credentials
    drop_database_credentials
    unset PLOT_CERTIFICATION_REPLACEMENT_MODEL PLOT_CERTIFICATION_REPLACEMENT_ORDINAL
    unset PLOT_CERTIFICATION_REPLACEMENT_ATTEMPT_ID PLOT_CERTIFICATION_REPLACEMENT_TRIGGER_ATTEMPT_ID
    unset PLOT_CERTIFICATION_REPLACEMENT_RESULT_OUTPUT
    export PLOT_AI_CONTRACT_SMOKE=true
    cd "$API_DIR"
    clean_env OPENROUTER_API_KEY PLOT_AI_CONTRACT_SMOKE PLOT_AI_ROUTING_PROVIDER PLOT_GENERATION_SOURCE_REVISION \
      PLOT_CERTIFICATION_OUTPUT_ROOT PLOT_CERTIFICATION_CAMPAIGN_ID PLOT_CERTIFICATION_CAMPAIGN_MANIFEST \
      PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH PLOT_CERTIFICATION_SOURCE_SNAPSHOT_SET_HASH \
      PLOT_CERTIFICATION_NANO_MANIFEST_OUTPUT PLOT_CERTIFICATION_MINI_MANIFEST_OUTPUT PLOT_AI_TIMEOUT_SECONDS \
      PLOT_AI_MAX_OUTPUT_TOKENS PLOT_AI_TRANSPORT_RETRIES PLOT_AI_SCHEMA_RETRIES -- \
      ./gradlew --no-daemon generationContractSmoke --rerun-tasks
  )
  export PLOT_CERTIFICATION_NANO_MANIFEST="$PLOT_CERTIFICATION_NANO_MANIFEST_OUTPUT"
  export PLOT_CERTIFICATION_MINI_MANIFEST="$PLOT_CERTIFICATION_MINI_MANIFEST_OUTPUT"
  export PLOT_CERTIFICATION_SEALED_BUNDLE_OUTPUT="$PLOT_CERTIFICATION_OUTPUT_ROOT/sealed-bundle.json"
  (
    cd "$API_DIR"
    clean_env PLOT_GENERATION_CERTIFICATION_TOOL PLOT_CERTIFICATION_IMPORTED_SOURCE_PREFLIGHT \
      PLOT_CERTIFICATION_CAMPAIGN_ID PLOT_CERTIFICATION_CAMPAIGN_MANIFEST PLOT_CERTIFICATION_NANO_MANIFEST \
      PLOT_CERTIFICATION_MINI_MANIFEST PLOT_CERTIFICATION_SEALED_BUNDLE_OUTPUT -- \
      ./gradlew --no-daemon generationCertificationSealVerification
  )
  export PLOT_CERTIFICATION_NANO_MANIFEST_HASH PLOT_CERTIFICATION_MINI_MANIFEST_HASH
  export PLOT_CERTIFICATION_NANO_EXECUTION_ID PLOT_CERTIFICATION_MINI_EXECUTION_ID
  PLOT_CERTIFICATION_NANO_MANIFEST_HASH="$(jq -r '.nanoModelExecutionManifestHash' "$PLOT_CERTIFICATION_SEALED_BUNDLE_OUTPUT")"
  PLOT_CERTIFICATION_MINI_MANIFEST_HASH="$(jq -r '.miniModelExecutionManifestHash' "$PLOT_CERTIFICATION_SEALED_BUNDLE_OUTPUT")"
  PLOT_CERTIFICATION_NANO_EXECUTION_ID="$(jq -r '.nanoModelExecutionId' "$PLOT_CERTIFICATION_SEALED_BUNDLE_OUTPUT")"
  PLOT_CERTIFICATION_MINI_EXECUTION_ID="$(jq -r '.miniModelExecutionId' "$PLOT_CERTIFICATION_SEALED_BUNDLE_OUTPUT")"
}

derive_initial_browser_attempt() {
  local key="$1" ordinal="$2" execution_var="PLOT_CERTIFICATION_${1}_EXECUTION_ID" attempt_id
  attempt_id="$(jq -rs --arg execution "${!execution_var}" --argjson ordinal "$ordinal" '
    [.[] | select(.modelExecutionId == $execution and .ordinal == $ordinal)]
    | group_by(.attemptId) | map(select(all(.[]; .outcome != "INCONCLUSIVE")))
    | map(.[0].attemptId) | unique | if length == 1 then .[0] else error("attempt coverage") end
  ' "$PLOT_CERTIFICATION_OUTPUT_ROOT/$PLOT_CERTIFICATION_CAMPAIGN_ID/evidence/"*.json)" || fail "U3 browser attempt identity derivation failed"
  echo "$attempt_id" | grep -Eq '^attempt-[a-f0-9]{16,64}$' || fail "derived browser attempt identity rejected"
  printf '%s\n' "$attempt_id"
}

run_model_replacement() {
  local key="$1" ordinal="$2" requested_attempt_id="$3" trigger_attempt_id="$4" model result_file selected execution_var
  if test "$key" = NANO; then model="$PLOT_CERTIFICATION_NANO_MODEL"; else model="$PLOT_CERTIFICATION_MINI_MODEL"; fi
  execution_var="PLOT_CERTIFICATION_${key}_EXECUTION_ID"
  result_file="$PLOT_CERTIFICATION_OUTPUT_ROOT/$PLOT_CERTIFICATION_CAMPAIGN_ID/model-replacements/$requested_attempt_id.json"
  (
    drop_github_credentials
    drop_database_credentials
    export PLOT_AI_CONTRACT_SMOKE=true
    export PLOT_CERTIFICATION_REPLACEMENT_MODEL="$model"
    export PLOT_CERTIFICATION_REPLACEMENT_ORDINAL="$ordinal"
    export PLOT_CERTIFICATION_REPLACEMENT_ATTEMPT_ID="$requested_attempt_id"
    export PLOT_CERTIFICATION_REPLACEMENT_TRIGGER_ATTEMPT_ID="$trigger_attempt_id"
    export PLOT_CERTIFICATION_REPLACEMENT_RESULT_OUTPUT="$result_file"
    cd "$API_DIR"
    clean_env OPENROUTER_API_KEY PLOT_AI_CONTRACT_SMOKE PLOT_AI_ROUTING_PROVIDER PLOT_GENERATION_SOURCE_REVISION \
      PLOT_CERTIFICATION_OUTPUT_ROOT PLOT_CERTIFICATION_CAMPAIGN_ID PLOT_CERTIFICATION_CAMPAIGN_MANIFEST \
      PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH PLOT_CERTIFICATION_SOURCE_SNAPSHOT_SET_HASH \
      PLOT_CERTIFICATION_NANO_MANIFEST_OUTPUT PLOT_CERTIFICATION_MINI_MANIFEST_OUTPUT PLOT_AI_TIMEOUT_SECONDS \
      PLOT_AI_MAX_OUTPUT_TOKENS PLOT_AI_TRANSPORT_RETRIES PLOT_AI_SCHEMA_RETRIES \
      PLOT_CERTIFICATION_REPLACEMENT_MODEL PLOT_CERTIFICATION_REPLACEMENT_ORDINAL \
      PLOT_CERTIFICATION_REPLACEMENT_ATTEMPT_ID PLOT_CERTIFICATION_REPLACEMENT_TRIGGER_ATTEMPT_ID \
      PLOT_CERTIFICATION_REPLACEMENT_RESULT_OUTPUT -- \
      ./gradlew --no-daemon generationContractSmoke --rerun-tasks
  )
  selected="$(jq -er --arg trigger "$trigger_attempt_id" --arg execution "${!execution_var}" --argjson ordinal "$ordinal" '
    if .schemaVersion == "certification-model-replacement-v1" and
      (.selectedAttemptId | type == "string" and test("^attempt-[a-f0-9]{16,64}$")) and
      .triggeredByBrowserAttemptId == $trigger and .modelExecutionId == $execution and .ordinal == $ordinal and
      (keys | sort == ["modelExecutionId", "ordinal", "schemaVersion", "selectedAttemptId", "triggeredByBrowserAttemptId"])
    then .selectedAttemptId else error("replacement result") end
  ' "$result_file")" || fail "model replacement result rejected"
  MODEL_REPLACEMENT_ATTEMPT_ID="$selected"
  MODEL_REPLACEMENT_RESULT_PATH="$result_file"
}

resolve_browser_observation() {
  local output_root="$1" campaign_id="$2" attempt_id="$3" candidate
  for candidate in "$output_root/$campaign_id/browser/"artifact-*.json; do
    test -f "$candidate" || continue
    if jq -e --arg attempt "$attempt_id" \
      '.attemptId == $attempt and .evidenceType == "BROWSER_OBSERVATION"' "$candidate" >/dev/null; then
      printf '%s\n' "$candidate"
    fi
  done
}

run_browser_attempt() {
  local key="$1" ordinal="$2" attempt_id="$3" replaces_attempt_id="${4:-}" replacement_result_path="${5:-}" manifest_var="PLOT_CERTIFICATION_${1}_MANIFEST"
  local manifest_hash_var="PLOT_CERTIFICATION_${1}_MANIFEST_HASH"
  local observation browser_exit=0 outcome
  if (
    drop_all_credentials
    export PLOT_CERTIFICATION_MODE=real-source
    export PLOT_CERTIFICATION_BASE_URL="$PLOT_CERTIFICATION_WEB_ORIGIN"
    export PLOT_CERTIFICATION_OUTPUT_ROOT="$PLOT_CERTIFICATION_BROWSER_OUTPUT_ROOT"
    export PLOT_CERTIFICATION_CAMPAIGN_MANIFEST
    export PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH
    export PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST="${!manifest_var}"
    export PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST_HASH="${!manifest_hash_var}"
    export PLOT_CERTIFICATION_ATTEMPT_ID="$attempt_id"
    export PLOT_CERTIFICATION_SCENARIO_ID=real-github-journey
    export PLOT_CERTIFICATION_ATTEMPT_ORDINAL="$ordinal"
    export PLOT_CERTIFICATION_WRITING_BLOCK_IDS
    cd "$ROOT"
    clean_env PLOT_CERTIFICATION_MODE PLOT_CERTIFICATION_BASE_URL PLOT_CERTIFICATION_OUTPUT_ROOT \
      PLOT_CERTIFICATION_CAMPAIGN_MANIFEST PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH \
      PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST_HASH \
      PLOT_CERTIFICATION_ATTEMPT_ID PLOT_CERTIFICATION_SCENARIO_ID PLOT_CERTIFICATION_ATTEMPT_ORDINAL \
      PLOT_CERTIFICATION_WRITING_BLOCK_IDS -- pnpm --filter @plot/web e2e:certification
  ); then
    browser_exit=0
  else
    browser_exit=$?
  fi
  unset PLOT_CERTIFICATION_RUN_ID
  observation="$(
    resolve_browser_observation \
      "$PLOT_CERTIFICATION_BROWSER_OUTPUT_ROOT" \
      "$PLOT_CERTIFICATION_CAMPAIGN_ID" \
      "$attempt_id"
  )"
  test "$(printf '%s\n' "$observation" | grep -c .)" = 1 || fail "browser observation resolution failed"
  export PLOT_CERTIFICATION_BROWSER_OBSERVATION="$observation"
  export "PLOT_CERTIFICATION_${key}_ATTEMPT_${ordinal}_ID=$attempt_id"
  if jq -e '.codes | index("PENDING_AUDIT_RECONCILIATION") != null' "$observation" >/dev/null; then
    if jq -e '.outcome == "INCONCLUSIVE"' "$observation" >/dev/null; then
      test "$browser_exit" = 0 || fail "successful browser observation accompanied a failing runner"
    else
      test "$browser_exit" != 0 || fail "hard browser observation accompanied a successful runner"
    fi
    run_audit_and_reconcile "$key" "$ordinal" "$replaces_attempt_id" false "$replacement_result_path"
  else
    test "$browser_exit" != 0 || fail "terminal browser observation accompanied a successful runner"
    run_audit_and_reconcile "$key" "$ordinal" "$replaces_attempt_id" true "$replacement_result_path"
  fi
  outcome="$(jq -r '.outcome' "$PLOT_CERTIFICATION_RECONCILIATION_OUTPUT")"
  case "$outcome" in PASS|HARD_GATE_FAIL|INCONCLUSIVE) BROWSER_ATTEMPT_OUTCOME="$outcome" ;; *) fail "browser reconciliation outcome rejected" ;; esac
}

run_browser_slot() {
  local key="$1" ordinal="$2" attempt_id prior_attempt_id="" replacement_result_path="" outcome count=0 requested_attempt_id
  attempt_id="$(derive_initial_browser_attempt "$key" "$ordinal")"
  while true; do
    count=$((count + 1))
    test "$count" -le 4 || fail "browser replacement bound exhausted"
    run_browser_attempt "$key" "$ordinal" "$attempt_id" "$prior_attempt_id" "$replacement_result_path"
    outcome="$BROWSER_ATTEMPT_OUTCOME"
    if test "$outcome" != INCONCLUSIVE; then return 0; fi
    prior_attempt_id="$attempt_id"
    requested_attempt_id="attempt-$(openssl rand -hex 16)"
    run_model_replacement "$key" "$ordinal" "$requested_attempt_id" "$prior_attempt_id"
    attempt_id="$MODEL_REPLACEMENT_ATTEMPT_ID"
    replacement_result_path="$MODEL_REPLACEMENT_RESULT_PATH"
    test "$attempt_id" != "$prior_attempt_id" || fail "browser replacement reused an attempt identity"
  done
}

run_audit_and_reconcile() {
  local key="$1" ordinal="$2" attempt_var="PLOT_CERTIFICATION_${1}_ATTEMPT_${2}_ID"
  local execution_var="PLOT_CERTIFICATION_${1}_EXECUTION_ID" manifest_var="PLOT_CERTIFICATION_${1}_MANIFEST"
  local manifest_hash_var="PLOT_CERTIFICATION_${1}_MANIFEST_HASH" replacement_attempt_id="${3:-}" terminal_only="${4:-false}"
  local replacement_result_path="${5:-}"
  export PLOT_GENERATION_CERTIFICATION_TOOL=true
  export PLOT_CERTIFICATION_DATABASE_URL="$SPRING_DATASOURCE_URL"
  export PLOT_CERTIFICATION_DATABASE_USERNAME="$SPRING_DATASOURCE_USERNAME"
  export PLOT_CERTIFICATION_DATABASE_PASSWORD="$SPRING_DATASOURCE_PASSWORD"
  export PLOT_CERTIFICATION_MODEL_EXECUTION_ID="${!execution_var}"
  export PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST="${!manifest_var}"
  export PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST_HASH="${!manifest_hash_var}"
  export PLOT_CERTIFICATION_ATTEMPT_ID="${!attempt_var}"
  export PLOT_CERTIFICATION_ATTEMPT_ORDINAL="$ordinal"
  export PLOT_CERTIFICATION_SCENARIO_ID=real-github-journey
  require_env PLOT_CERTIFICATION_WORKSPACE_ID
  local namespace
  namespace="$(jq -r '.idempotencyNamespace' "${!manifest_var}")"
  echo "$namespace" | grep -Eq '^namespace-[a-f0-9]{16,64}$' || fail "sealed idempotency namespace rejected"
  export PLOT_CERTIFICATION_IDEMPOTENCY_KEY="$namespace:$PLOT_CERTIFICATION_ATTEMPT_ID"
  unset PLOT_CERTIFICATION_REPLACES_ATTEMPT_ID
  unset PLOT_CERTIFICATION_MODEL_REPLACEMENT_RESULT
  if test -n "$replacement_attempt_id"; then
    echo "$replacement_attempt_id" | grep -Eq '^attempt-[a-f0-9]{16,64}$' || fail "U3 replacement attempt identity rejected"
    test "$replacement_attempt_id" != "$PLOT_CERTIFICATION_ATTEMPT_ID" || fail "U3 replacement attempt identity reused"
    export PLOT_CERTIFICATION_REPLACES_ATTEMPT_ID="$replacement_attempt_id"
    test -f "$replacement_result_path" || fail "U3 model replacement proof missing"
    export PLOT_CERTIFICATION_MODEL_REPLACEMENT_RESULT="$replacement_result_path"
  elif test -n "$replacement_result_path"; then
    fail "U3 model replacement proof has no predecessor"
  fi
  unset PLOT_CERTIFICATION_BROWSER_TERMINAL_ONLY PLOT_CERTIFICATION_AUDIT_ENVELOPE
  if test "$terminal_only" = true; then
    export PLOT_CERTIFICATION_BROWSER_TERMINAL_ONLY=true
  else
    (
      cd "$API_DIR"
      clean_env PLOT_GENERATION_CERTIFICATION_TOOL OPENROUTER_API_KEY PLOT_CERTIFICATION_DATABASE_URL \
        PLOT_CERTIFICATION_DATABASE_NAME PLOT_CERTIFICATION_DATABASE_USERNAME PLOT_CERTIFICATION_DATABASE_PASSWORD \
        PLOT_CERTIFICATION_DATABASE_FINGERPRINT PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN \
        PLOT_CERTIFICATION_CAMPAIGN_ID PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH PLOT_CERTIFICATION_MODEL_EXECUTION_ID \
        PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST_HASH PLOT_CERTIFICATION_ATTEMPT_ID \
        PLOT_CERTIFICATION_CAMPAIGN_MANIFEST PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST \
        PLOT_CERTIFICATION_SCENARIO_ID PLOT_CERTIFICATION_ATTEMPT_ORDINAL PLOT_CERTIFICATION_WORKSPACE_ID \
        PLOT_CERTIFICATION_RUN_ID PLOT_CERTIFICATION_IDEMPOTENCY_KEY PLOT_CERTIFICATION_SOURCE_SNAPSHOT_SET_HASH \
        PLOT_CERTIFICATION_OUTPUT_ROOT -- ./gradlew --no-daemon generationCertificationAudit
    )
    export PLOT_CERTIFICATION_AUDIT_ENVELOPE="$PLOT_CERTIFICATION_OUTPUT_ROOT/$PLOT_CERTIFICATION_CAMPAIGN_ID/audit/$PLOT_CERTIFICATION_ATTEMPT_ID.json"
  fi
  export PLOT_CERTIFICATION_RECONCILIATION_OUTPUT="$PLOT_CERTIFICATION_OUTPUT_ROOT/$PLOT_CERTIFICATION_CAMPAIGN_ID/reconciliation/$PLOT_CERTIFICATION_ATTEMPT_ID.json"
  (
    cd "$API_DIR"
    clean_env PLOT_GENERATION_CERTIFICATION_TOOL PLOT_CERTIFICATION_CAMPAIGN_MANIFEST \
      PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST PLOT_CERTIFICATION_BROWSER_OBSERVATION \
      PLOT_CERTIFICATION_AUDIT_ENVELOPE PLOT_CERTIFICATION_RECONCILIATION_OUTPUT \
      PLOT_CERTIFICATION_REPLACES_ATTEMPT_ID PLOT_CERTIFICATION_MODEL_REPLACEMENT_RESULT \
      PLOT_CERTIFICATION_BROWSER_TERMINAL_ONLY -- \
      ./gradlew --no-daemon generationCertificationReconcile
  )
  unset PLOT_CERTIFICATION_BROWSER_TERMINAL_ONLY
}

run_process_restart_gate() {
  require_env PLOT_CERTIFICATION_RESTART_TRIGGER_REQUEST
  export PLOT_GENERATION_CERTIFICATION_TOOL=true
  export PLOT_CERTIFICATION_ATTEMPT_ID="$PLOT_CERTIFICATION_RESTART_ATTEMPT_ID"
  export PLOT_CERTIFICATION_RESTART_CHECKPOINT=WRITER_OUTPUT
  if test "$PLOT_CERTIFICATION_SELECTED_MODEL" = "$PLOT_CERTIFICATION_NANO_MODEL"; then
    export PLOT_CERTIFICATION_MODEL_EXECUTION_ID="$PLOT_CERTIFICATION_NANO_EXECUTION_ID"
    export PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST_HASH="$PLOT_CERTIFICATION_NANO_MANIFEST_HASH"
  elif test "$PLOT_CERTIFICATION_SELECTED_MODEL" = "$PLOT_CERTIFICATION_MINI_MODEL"; then
    export PLOT_CERTIFICATION_MODEL_EXECUTION_ID="$PLOT_CERTIFICATION_MINI_EXECUTION_ID"
    export PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST_HASH="$PLOT_CERTIFICATION_MINI_MANIFEST_HASH"
  else
    fail "selected model is not one of the certified profiles"
  fi
  local selected_manifest selected_namespace
  if test "$PLOT_CERTIFICATION_SELECTED_MODEL" = "$PLOT_CERTIFICATION_NANO_MODEL"; then selected_manifest="$PLOT_CERTIFICATION_NANO_MANIFEST"; else selected_manifest="$PLOT_CERTIFICATION_MINI_MANIFEST"; fi
  export PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST="$selected_manifest"
  selected_namespace="$(jq -r '.idempotencyNamespace' "$selected_manifest")"
  echo "$selected_namespace" | grep -Eq '^namespace-[a-f0-9]{16,64}$' || fail "restart idempotency namespace rejected"
  start_api restart "$PLOT_CERTIFICATION_SELECTED_MODEL"
  curl_request -fsS -D "$PLOT_CERTIFICATION_OUTPUT_ROOT/restart-trigger-headers.txt" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $selected_namespace:$PLOT_CERTIFICATION_RESTART_ATTEMPT_ID" \
    --data-binary "@$PLOT_CERTIFICATION_RESTART_TRIGGER_REQUEST" "$PLOT_CERTIFICATION_API_ORIGIN/api/generations" \
    >"$PLOT_CERTIFICATION_OUTPUT_ROOT/restart-trigger-response.json"
  local count=0
  until test -f "$PLOT_CERTIFICATION_RESTART_MARKER"; do
    count=$((count + 1)); test "$count" -lt 120 || fail "durable checkpoint marker timed out"; sleep 1
  done
  export PLOT_CERTIFICATION_IDEMPOTENCY_KEY="$selected_namespace:$PLOT_CERTIFICATION_RESTART_ATTEMPT_ID"
  export PLOT_CERTIFICATION_DATABASE_URL="$SPRING_DATASOURCE_URL"
  export PLOT_CERTIFICATION_DATABASE_USERNAME="$SPRING_DATASOURCE_USERNAME"
  export PLOT_CERTIFICATION_DATABASE_PASSWORD="$SPRING_DATASOURCE_PASSWORD"
  export PLOT_CERTIFICATION_RESTART_STATE_OUTPUT="$PLOT_CERTIFICATION_OUTPUT_ROOT/restart-before.json"
  (
    cd "$API_DIR"
    clean_env PLOT_GENERATION_CERTIFICATION_TOOL PLOT_CERTIFICATION_DATABASE_URL PLOT_CERTIFICATION_DATABASE_NAME \
      PLOT_CERTIFICATION_DATABASE_USERNAME PLOT_CERTIFICATION_DATABASE_PASSWORD PLOT_CERTIFICATION_DATABASE_FINGERPRINT \
      PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN PLOT_CERTIFICATION_IDEMPOTENCY_KEY PLOT_CERTIFICATION_CAMPAIGN_ID \
      PLOT_CERTIFICATION_CAMPAIGN_MANIFEST PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH \
      PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST PLOT_CERTIFICATION_MODEL_EXECUTION_ID \
      PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST_HASH PLOT_CERTIFICATION_ATTEMPT_ID \
      PLOT_CERTIFICATION_SOURCE_SNAPSHOT_SET_HASH PLOT_CERTIFICATION_RESTART_CHECKPOINT \
      PLOT_CERTIFICATION_RESTART_STATE_OUTPUT -- ./gradlew --no-daemon generationCertificationRestartState
  )
  local marker_pid
  marker_pid="$(jq -r '.processId' "$PLOT_CERTIFICATION_RESTART_MARKER")"
  echo "$marker_pid" | grep -Eq '^[1-9][0-9]*$' || fail "restart JVM PID marker rejected"
  test "$marker_pid" = "$API_JVM_PID" || fail "restart marker PID does not own the API listener"
  stop_api
  sleep "${PLOT_CERTIFICATION_CLAIM_RECOVERY_WAIT_SECONDS:-125}"
  start_api model "$PLOT_CERTIFICATION_SELECTED_MODEL"
  count=0
  local restart_run_id
  restart_run_id="$(jq -r '.id' "$PLOT_CERTIFICATION_OUTPUT_ROOT/restart-trigger-response.json")"
  echo "$restart_run_id" | grep -Eq '^[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$' || fail "restart run identity rejected"
  until curl_probe -fsS "$PLOT_CERTIFICATION_API_ORIGIN/api/generations/$restart_run_id" \
    | jq -e '.status | IN("READY", "NEEDS_REVIEW", "NEEDS_YOUR_CALL", "FAILED")' >/dev/null; do
    count=$((count + 1)); test "$count" -lt 180 || fail "restarted run did not settle"; sleep 1
  done
  export PLOT_CERTIFICATION_RESTART_STATE_OUTPUT="$PLOT_CERTIFICATION_OUTPUT_ROOT/restart-after.json"
  (
    cd "$API_DIR"
    clean_env PLOT_GENERATION_CERTIFICATION_TOOL PLOT_CERTIFICATION_DATABASE_URL PLOT_CERTIFICATION_DATABASE_NAME \
      PLOT_CERTIFICATION_DATABASE_USERNAME PLOT_CERTIFICATION_DATABASE_PASSWORD PLOT_CERTIFICATION_DATABASE_FINGERPRINT \
      PLOT_CERTIFICATION_DATABASE_DISPOSABLE_TOKEN PLOT_CERTIFICATION_IDEMPOTENCY_KEY PLOT_CERTIFICATION_CAMPAIGN_ID \
      PLOT_CERTIFICATION_CAMPAIGN_MANIFEST PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH \
      PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST PLOT_CERTIFICATION_MODEL_EXECUTION_ID \
      PLOT_CERTIFICATION_MODEL_EXECUTION_MANIFEST_HASH PLOT_CERTIFICATION_ATTEMPT_ID \
      PLOT_CERTIFICATION_SOURCE_SNAPSHOT_SET_HASH PLOT_CERTIFICATION_RESTART_CHECKPOINT \
      PLOT_CERTIFICATION_RESTART_STATE_OUTPUT -- ./gradlew --no-daemon generationCertificationRestartState
  )
  stop_api
  export PLOT_CERTIFICATION_RESTART_BEFORE="$PLOT_CERTIFICATION_OUTPUT_ROOT/restart-before.json"
  export PLOT_CERTIFICATION_RESTART_AFTER="$PLOT_CERTIFICATION_OUTPUT_ROOT/restart-after.json"
  export PLOT_CERTIFICATION_RESTART_RESULT="$PLOT_CERTIFICATION_OUTPUT_ROOT/restart-result.json"
  (
    cd "$API_DIR"
    clean_env PLOT_GENERATION_CERTIFICATION_TOOL PLOT_CERTIFICATION_RESTART_BEFORE \
      PLOT_CERTIFICATION_RESTART_AFTER PLOT_CERTIFICATION_RESTART_RESULT -- \
      ./gradlew --no-daemon generationCertificationRestartReconcile
  )
}

derive_restart_selection() {
  export PLOT_CERTIFICATION_MODEL_EVIDENCE_DIRECTORY="$PLOT_CERTIFICATION_OUTPUT_ROOT/$PLOT_CERTIFICATION_CAMPAIGN_ID/evidence"
  export PLOT_CERTIFICATION_RECONCILIATION_DIRECTORY="$PLOT_CERTIFICATION_OUTPUT_ROOT/$PLOT_CERTIFICATION_CAMPAIGN_ID/reconciliation"
  export PLOT_CERTIFICATION_RESTART_SELECTION_OUTPUT="$PLOT_CERTIFICATION_OUTPUT_ROOT/restart-selection.json"
  (
    cd "$API_DIR"
    clean_env PLOT_GENERATION_CERTIFICATION_TOOL PLOT_CERTIFICATION_CAMPAIGN_MANIFEST \
      PLOT_CERTIFICATION_NANO_MANIFEST PLOT_CERTIFICATION_MINI_MANIFEST \
      PLOT_CERTIFICATION_MODEL_EVIDENCE_DIRECTORY PLOT_CERTIFICATION_RECONCILIATION_DIRECTORY \
      PLOT_CERTIFICATION_RESTART_SELECTION_OUTPUT -- \
      ./gradlew --no-daemon generationCertificationRestartSelection
  )
  local selected_execution selected_model selected_hash
  selected_execution="$(jq -er --arg campaign "$PLOT_CERTIFICATION_CAMPAIGN_ID" --arg hash "$PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH" '
    if .schemaVersion == "certification-restart-selection-v1" and .campaignId == $campaign and
      .campaignManifestHash == $hash and
      (.eligibleModelExecutionId == null or (.eligibleModelExecutionId | type == "string")) and
      (.restartModelExecutionId | type == "string") and (.restartRequestedModel | type == "string") and
      (keys | sort == ["campaignId", "campaignManifestHash", "eligibleModelExecutionId", "restartModelExecutionId", "restartRequestedModel", "schemaVersion"])
    then .restartModelExecutionId else error("restart selection") end
  ' "$PLOT_CERTIFICATION_RESTART_SELECTION_OUTPUT")" || fail "restart selection result rejected"
  selected_model="$(jq -r '.restartRequestedModel' "$PLOT_CERTIFICATION_RESTART_SELECTION_OUTPUT")"
  if test "$selected_execution" = "$PLOT_CERTIFICATION_NANO_EXECUTION_ID" && test "$selected_model" = "$PLOT_CERTIFICATION_NANO_MODEL"; then
    export PLOT_CERTIFICATION_SELECTED_MODEL="$PLOT_CERTIFICATION_NANO_MODEL"
    selected_hash="$PLOT_CERTIFICATION_NANO_MANIFEST_HASH"
  elif test "$selected_execution" = "$PLOT_CERTIFICATION_MINI_EXECUTION_ID" && test "$selected_model" = "$PLOT_CERTIFICATION_MINI_MODEL"; then
    export PLOT_CERTIFICATION_SELECTED_MODEL="$PLOT_CERTIFICATION_MINI_MODEL"
    selected_hash="$PLOT_CERTIFICATION_MINI_MANIFEST_HASH"
  else
    fail "restart selection does not match a sealed model execution"
  fi
  export PLOT_CERTIFICATION_RESTART_ATTEMPT_ID
  PLOT_CERTIFICATION_RESTART_ATTEMPT_ID="attempt-$(printf '%s' "process-restart:$selected_hash" | shasum -a 256 | awk '{print substr($1,1,32)}')"
}

run_live_matrix() {
  start_api github
  start_web
  operator_github_import
  scan_and_seal_imported_sources
  run_deterministic
  stop_api
  run_contract_matrix
  local key model ordinal
  for key in NANO MINI; do
    if test "$key" = NANO; then model="$PLOT_CERTIFICATION_NANO_MODEL"; else model="$PLOT_CERTIFICATION_MINI_MODEL"; fi
    start_api model "$model"
    for ordinal in 1 2 3; do run_browser_slot "$key" "$ordinal"; done
    stop_api
  done
  stop_web
  derive_restart_selection
  run_process_restart_gate
}

run_report() {
  local phase="$1" decision_path report_output
  export PLOT_GENERATION_CERTIFICATION_TOOL=true
  if test "$phase" = FINAL; then
    require_file PLOT_CERTIFICATION_REPORT_SNAPSHOT
    require_file PLOT_CERTIFICATION_CLEANUP_OUTPUT
    require_file PLOT_CERTIFICATION_OPERATOR_DECISION
    require_env PLOT_CERTIFICATION_REPORT_SNAPSHOT_HASH
    (
      cd "$API_DIR"
      clean_env PLOT_GENERATION_CERTIFICATION_TOOL PLOT_CERTIFICATION_REPORT_SNAPSHOT \
        PLOT_CERTIFICATION_REPORT_SNAPSHOT_HASH PLOT_CERTIFICATION_CLEANUP_OUTPUT \
        PLOT_CERTIFICATION_OPERATOR_DECISION PLOT_CERTIFICATION_REPORT_OUTPUT -- \
        ./gradlew --no-daemon generationCertificationFinalReport
    )
    return
  fi
  export PLOT_CERTIFICATION_REPORT_PHASE=DRAFT
  export PLOT_CERTIFICATION_MODEL_EVIDENCE_DIRECTORY="$PLOT_CERTIFICATION_OUTPUT_ROOT/$PLOT_CERTIFICATION_CAMPAIGN_ID/evidence"
  export PLOT_CERTIFICATION_RECONCILIATION_DIRECTORY="$PLOT_CERTIFICATION_OUTPUT_ROOT/$PLOT_CERTIFICATION_CAMPAIGN_ID/reconciliation"
  export PLOT_CERTIFICATION_DETERMINISTIC_RESULT="$PLOT_CERTIFICATION_OUTPUT_ROOT/deterministic-result.json"
  export PLOT_CERTIFICATION_RESTART_RESULT="$PLOT_CERTIFICATION_OUTPUT_ROOT/restart-result.json"
  require_file PLOT_CERTIFICATION_DRAFT_OPERATOR_DECISION
  decision_path="$PLOT_CERTIFICATION_DRAFT_OPERATOR_DECISION"
  report_output="$PLOT_CERTIFICATION_OUTPUT_ROOT/draft-report.md"
  (
    drop_all_credentials
    export PLOT_CERTIFICATION_OPERATOR_DECISION="$decision_path"
    export PLOT_CERTIFICATION_REPORT_OUTPUT="$report_output"
    cd "$API_DIR"
    clean_env PLOT_GENERATION_CERTIFICATION_TOOL PLOT_CERTIFICATION_REPORT_PHASE \
      PLOT_CERTIFICATION_CAMPAIGN_MANIFEST PLOT_CERTIFICATION_NANO_MANIFEST PLOT_CERTIFICATION_MINI_MANIFEST \
      PLOT_CERTIFICATION_MODEL_EVIDENCE_DIRECTORY PLOT_CERTIFICATION_RECONCILIATION_DIRECTORY \
      PLOT_CERTIFICATION_DETERMINISTIC_RESULT PLOT_CERTIFICATION_RESTART_RESULT PLOT_CERTIFICATION_CLEANUP_OUTPUT \
      PLOT_CERTIFICATION_OPERATOR_DECISION PLOT_CERTIFICATION_REPORT_OUTPUT -- \
      ./gradlew --no-daemon generationCertificationReport
  )
}

run_report_snapshot() {
  export PLOT_GENERATION_CERTIFICATION_TOOL=true
  export PLOT_CERTIFICATION_MODEL_EVIDENCE_DIRECTORY="$PLOT_CERTIFICATION_OUTPUT_ROOT/$PLOT_CERTIFICATION_CAMPAIGN_ID/evidence"
  export PLOT_CERTIFICATION_RECONCILIATION_DIRECTORY="$PLOT_CERTIFICATION_OUTPUT_ROOT/$PLOT_CERTIFICATION_CAMPAIGN_ID/reconciliation"
  export PLOT_CERTIFICATION_DETERMINISTIC_RESULT="$PLOT_CERTIFICATION_OUTPUT_ROOT/deterministic-result.json"
  export PLOT_CERTIFICATION_RESTART_RESULT="$PLOT_CERTIFICATION_OUTPUT_ROOT/restart-result.json"
  (
    drop_all_credentials
    export PLOT_CERTIFICATION_OPERATOR_DECISION="$PLOT_CERTIFICATION_DRAFT_OPERATOR_DECISION"
    cd "$API_DIR"
    clean_env PLOT_GENERATION_CERTIFICATION_TOOL PLOT_CERTIFICATION_CAMPAIGN_MANIFEST \
      PLOT_CERTIFICATION_NANO_MANIFEST PLOT_CERTIFICATION_MINI_MANIFEST \
      PLOT_CERTIFICATION_MODEL_EVIDENCE_DIRECTORY PLOT_CERTIFICATION_RECONCILIATION_DIRECTORY \
      PLOT_CERTIFICATION_DETERMINISTIC_RESULT PLOT_CERTIFICATION_RESTART_RESULT \
      PLOT_CERTIFICATION_OPERATOR_DECISION PLOT_CERTIFICATION_REPORT_SNAPSHOT -- \
      ./gradlew --no-daemon generationCertificationReportSnapshot
  )
  export PLOT_CERTIFICATION_REPORT_SNAPSHOT_HASH
  PLOT_CERTIFICATION_REPORT_SNAPSHOT_HASH="sha256:$(shasum -a 256 "$PLOT_CERTIFICATION_REPORT_SNAPSHOT" | awk '{print $1}')"
}

run_cleanup_gate() {
  validate_existing_artifact_root "$PLOT_CERTIFICATION_OUTPUT_ROOT" API
  validate_existing_artifact_root "$PLOT_CERTIFICATION_BROWSER_OUTPUT_ROOT" browser
  printf '%s\n' "Revoke both credentials, dispose the state secret, write $PLOT_CERTIFICATION_CLEANUP_ATTESTATION, then press return." >&2
  read -r _
  test -f "$PLOT_CERTIFICATION_CLEANUP_ATTESTATION" && test ! -L "$PLOT_CERTIFICATION_CLEANUP_ATTESTATION" || fail "cleanup attestation missing or unsafe"
  jq -e '
    (keys == ["attestedAt","attestedByOperatorAlias","campaignId","campaignManifestHash","githubCredentialRevoked","openRouterCredentialRevoked","sourceRevision","stateSecretDisposed"]) and
    (.attestedByOperatorAlias | type == "string" and test("^operator-[a-f0-9]{16,64}$")) and
    (.attestedAt | type == "string") and (.githubCredentialRevoked == true) and
    (.openRouterCredentialRevoked == true) and (.stateSecretDisposed == true)
  ' "$PLOT_CERTIFICATION_CLEANUP_ATTESTATION" >/dev/null || fail "cleanup attestation contract rejected"
  test "$(jq -r '.campaignId' "$PLOT_CERTIFICATION_CLEANUP_ATTESTATION")" = "$PLOT_CERTIFICATION_CAMPAIGN_ID" || fail "cleanup campaign identity mismatch"
  test "$(jq -r '.campaignManifestHash' "$PLOT_CERTIFICATION_CLEANUP_ATTESTATION")" = "$PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH" || fail "cleanup manifest identity mismatch"
  test "$(jq -r '.sourceRevision' "$PLOT_CERTIFICATION_CLEANUP_ATTESTATION")" = "$PLOT_GENERATION_SOURCE_REVISION" || fail "cleanup revision identity mismatch"
  validate_utc_timestamp "$(jq -r '.attestedAt' "$PLOT_CERTIFICATION_CLEANUP_ATTESTATION")" "cleanup attestation time"
  test "$(jq -r '.attestedAt' "$PLOT_CERTIFICATION_CLEANUP_ATTESTATION")" \> "$PLOT_CERTIFICATION_STARTED_AT" ||
    test "$(jq -r '.attestedAt' "$PLOT_CERTIFICATION_CLEANUP_ATTESTATION")" = "$PLOT_CERTIFICATION_STARTED_AT" ||
    fail "cleanup attestation predates this campaign execution"
  export PLOT_CERTIFICATION_GITHUB_CREDENTIAL_REVOKED=true
  export PLOT_CERTIFICATION_OPENROUTER_CREDENTIAL_REVOKED=true
  export PLOT_CERTIFICATION_STATE_SECRET_DISPOSED=true
  export PLOT_CERTIFICATION_CLEANUP_ATTESTED_AT
  PLOT_CERTIFICATION_CLEANUP_ATTESTED_AT="$(jq -r '.attestedAt' "$PLOT_CERTIFICATION_CLEANUP_ATTESTATION")"
  export PLOT_CERTIFICATION_OPERATOR_ALIAS
  PLOT_CERTIFICATION_OPERATOR_ALIAS="$(jq -r '.attestedByOperatorAlias' "$PLOT_CERTIFICATION_CLEANUP_ATTESTATION")"
  test "$PLOT_CERTIFICATION_OPERATOR_ALIAS" = "$(jq -r '.operatorAlias' "$PLOT_CERTIFICATION_DRAFT_OPERATOR_DECISION")" || fail "cleanup operator alias mismatch"
  unset OPENROUTER_API_KEY OPENAI_API_KEY SPRING_AI_OPENAI_API_KEY GITHUB_TOKEN GH_TOKEN
  unset GITHUB_APP_PRIVATE_KEY GITHUB_INSTALLATION_TOKEN PLOT_GITHUB_PRIVATE_KEY PLOT_GITHUB_STATE_SECRET
  stop_web; stop_api
  stop_postgres
  export PLOT_CERTIFICATION_LISTENER_COUNT
  PLOT_CERTIFICATION_LISTENER_COUNT="$(( $(lsof -nP -t -iTCP:"${PLOT_CERTIFICATION_API_PORT:-8080}" -sTCP:LISTEN 2>/dev/null | wc -l | tr -d ' ') + $(lsof -nP -t -iTCP:"${PLOT_CERTIFICATION_WEB_PORT:-3000}" -sTCP:LISTEN 2>/dev/null | wc -l | tr -d ' ') ))"
  export PLOT_CERTIFICATION_DATABASE_DISPOSITION=UNRESOLVED
  local database_inventory
  if database_inventory="$(docker_local ps -a --filter "name=^plot-cert-${PLOT_CERTIFICATION_CAMPAIGN_ID#campaign-}$" --format '{{.Names}}' 2>/dev/null)" &&
    test -z "$database_inventory"; then
    export PLOT_CERTIFICATION_DATABASE_DISPOSITION=DESTROYED
  fi
  if purge_transient_certification_evidence; then
    export PLOT_CERTIFICATION_RAW_ARTIFACTS_DELETED=true
    export PLOT_CERTIFICATION_BROWSER_ARTIFACTS_DELETED=true
  else
    export PLOT_CERTIFICATION_RAW_ARTIFACTS_DELETED=false
    export PLOT_CERTIFICATION_BROWSER_ARTIFACTS_DELETED=false
  fi
  export PLOT_CERTIFICATION_CLEANUP_RECORDED_AT
  PLOT_CERTIFICATION_CLEANUP_RECORDED_AT="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  export PLOT_GENERATION_CERTIFICATION_TOOL=true
  (
    cd "$API_DIR"
    clean_env PLOT_GENERATION_CERTIFICATION_TOOL PLOT_CERTIFICATION_CAMPAIGN_ID \
      PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH PLOT_GENERATION_SOURCE_REVISION \
      PLOT_CERTIFICATION_CLEANUP_RECORDED_AT PLOT_CERTIFICATION_OPERATOR_ALIAS \
      PLOT_CERTIFICATION_CLEANUP_ATTESTED_AT PLOT_CERTIFICATION_LISTENER_COUNT \
      PLOT_CERTIFICATION_GITHUB_CREDENTIAL_REVOKED PLOT_CERTIFICATION_OPENROUTER_CREDENTIAL_REVOKED \
      PLOT_CERTIFICATION_STATE_SECRET_DISPOSED PLOT_CERTIFICATION_RAW_ARTIFACTS_DELETED \
      PLOT_CERTIFICATION_BROWSER_ARTIFACTS_DELETED PLOT_CERTIFICATION_DATABASE_DISPOSITION \
      PLOT_CERTIFICATION_RETAINED_OWNER_ALIAS PLOT_CERTIFICATION_RETAINED_EXPIRES_AT \
      PLOT_CERTIFICATION_CLEANUP_OUTPUT -- ./gradlew --no-daemon generationCertificationCleanup
  )
}

purge_transient_certification_evidence() {
  local root parent approval
  for root in "$PLOT_CERTIFICATION_OUTPUT_ROOT" "$PLOT_CERTIFICATION_BROWSER_OUTPUT_ROOT"; do
    test -d "$root" && test ! -L "$root" && test -z "$(find "$root" -type l -print -quit)" || return 1
    parent="$(cd "$(dirname "$root")" && pwd -P)"
    case "$parent/$(basename "$root")" in "$ROOT"/*) ;; *) return 1 ;; esac
    git -C "$ROOT" check-ignore -q "$root" || return 1
    rm -rf "$root"
    test ! -e "$root" || return 1
  done
  approval="$PLOT_CERTIFICATION_SOURCE_APPROVAL"
  test -f "$approval" && test ! -L "$approval" || return 1
  parent="$(cd "$(dirname "$approval")" && pwd -P)"
  case "$parent/$(basename "$approval")" in "$ROOT"/*) ;; *) return 1 ;; esac
  git -C "$ROOT" check-ignore -q "$approval" || return 1
  rm -f "$approval"
  test ! -e "$approval" || return 1
}

purge_safe_certification_artifacts() {
  validate_existing_artifact_root "$PLOT_CERTIFICATION_SAFE_ROOT" safe
  rm -rf "$PLOT_CERTIFICATION_SAFE_ROOT"
  test ! -e "$PLOT_CERTIFICATION_SAFE_ROOT"
}

main() {
  trap emergency_cleanup EXIT INT TERM
  reject_secret_cli_arguments "$@"
  case "$STAGE" in
    deterministic) run_deterministic ;;
    preflight) validate_static_preflight ;;
    browser) validate_static_preflight; start_postgres; run_live_matrix ;;
    audit) run_audit_and_reconcile "${PLOT_CERTIFICATION_MODEL_KEY:-NANO}" "${PLOT_CERTIFICATION_ATTEMPT_ORDINAL:-1}" ;;
    report) run_report "${PLOT_CERTIFICATION_REPORT_PHASE:-DRAFT}" ;;
    cleanup) run_cleanup_gate ;;
    all)
      validate_static_preflight
      mkdir -p "$PLOT_CERTIFICATION_OUTPUT_ROOT" "$PLOT_CERTIFICATION_BROWSER_OUTPUT_ROOT"
      start_postgres
      run_live_matrix
      collect_draft_decision
      run_report DRAFT
      run_report_snapshot
      run_cleanup_gate
      printf '%s\n' "Write the final operator decision to $PLOT_CERTIFICATION_OPERATOR_DECISION with a timestamp at or after cleanup, then press return." >&2
      read -r _
      run_report FINAL
      if ! purge_safe_certification_artifacts; then
        rm -f "$PLOT_CERTIFICATION_REPORT_OUTPUT"
        fail "safe report-input purge failed; report removed"
      fi
      expected_report_status="?? ${PLOT_CERTIFICATION_REPORT_OUTPUT#"$ROOT/"}"
      test "$(git -C "$ROOT" status --porcelain)" = "$expected_report_status" || {
        rm -f "$PLOT_CERTIFICATION_REPORT_OUTPUT"
        fail "unexpected worktree change after final report; report removed"
      }
      ;;
  esac
}

if test "${BASH_SOURCE[0]}" = "$0"; then
  main "$@"
fi
