set dotenv-load
set shell := ["bash", "-uc"]

# Show available commands
[default]
help:
    just --list

# Install JavaScript workspace dependencies
install:
    CI=true pnpm install

# Start the Spring Boot API
dev-api:
    cd apps/api && ./gradlew bootRun

# Start the Next.js app
dev-web:
    pnpm --filter @plot/web dev

# Test the Spring Boot API
test-api:
    cd apps/api && ./gradlew test

# Run the explicit preflight plus scored two-model OpenRouter matrix (never part of normal CI)
generation-contract-smoke:
    @test "${PLOT_AI_CONTRACT_SMOKE:-}" = "true" || (echo "Set PLOT_AI_CONTRACT_SMOKE=true to opt in" >&2; exit 2)
    @test -n "${OPENROUTER_API_KEY:-}" || (echo "OPENROUTER_API_KEY is required" >&2; exit 2)
    @test -n "${PLOT_AI_ROUTING_PROVIDER:-}" || (echo "PLOT_AI_ROUTING_PROVIDER is required" >&2; exit 2)
    @test -n "${PLOT_GENERATION_SOURCE_REVISION:-}" || (echo "PLOT_GENERATION_SOURCE_REVISION is required" >&2; exit 2)
    @test -n "${PLOT_CERTIFICATION_OUTPUT_ROOT:-}" || (echo "PLOT_CERTIFICATION_OUTPUT_ROOT is required" >&2; exit 2)
    @test -n "${PLOT_CERTIFICATION_CAMPAIGN_ID:-}" || (echo "PLOT_CERTIFICATION_CAMPAIGN_ID is required" >&2; exit 2)
    @test -n "${PLOT_CERTIFICATION_CAMPAIGN_MANIFEST:-}" || (echo "PLOT_CERTIFICATION_CAMPAIGN_MANIFEST is required" >&2; exit 2)
    @test -n "${PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH:-}" || (echo "PLOT_CERTIFICATION_CAMPAIGN_MANIFEST_HASH is required" >&2; exit 2)
    @test -n "${PLOT_CERTIFICATION_SOURCE_SNAPSHOT_SET_HASH:-}" || (echo "PLOT_CERTIFICATION_SOURCE_SNAPSHOT_SET_HASH is required" >&2; exit 2)
    @test -n "${PLOT_CERTIFICATION_NANO_MANIFEST_OUTPUT:-}" || (echo "PLOT_CERTIFICATION_NANO_MANIFEST_OUTPUT is required" >&2; exit 2)
    @test -n "${PLOT_CERTIFICATION_MINI_MANIFEST_OUTPUT:-}" || (echo "PLOT_CERTIFICATION_MINI_MANIFEST_OUTPUT is required" >&2; exit 2)
    cd apps/api && ./gradlew generationContractSmoke --rerun-tasks

# Run the no-network static certification preflight (revision, paths, origins, DB identity, and operator inputs)
generation-certification-preflight:
    ./scripts/generation-certification.sh preflight

# Prove one canonical ZDR route and authoritative metadata with a synthetic canary
generation-certification-model-route-preflight:
    @test "${PLOT_GENERATION_CERTIFICATION_PREFLIGHT:-}" = "true" || (echo "Set PLOT_GENERATION_CERTIFICATION_PREFLIGHT=true to opt in" >&2; exit 2)
    @test -n "${OPENROUTER_API_KEY:-}" || (echo "OPENROUTER_API_KEY is required" >&2; exit 2)
    @test -n "${PLOT_AI_MODEL:-}" || (echo "PLOT_AI_MODEL is required" >&2; exit 2)
    @test -n "${PLOT_AI_ROUTING_PROVIDER:-}" || (echo "PLOT_AI_ROUTING_PROVIDER is required" >&2; exit 2)
    cd apps/api && ./gradlew generationCertificationPreflight --rerun-tasks

# Run the complete operator-owned certification sequence (all live calls are explicit opt-in)
generation-certification:
    ./scripts/generation-certification.sh all

# Run only offline deterministic generation/citation/recovery gates
generation-certification-deterministic:
    ./scripts/generation-certification.sh deterministic

# Run one configured serial real-source browser observation
generation-certification-browser:
    @test "${PLOT_CERTIFICATION_MODE:-}" = "real-source" || (echo "PLOT_CERTIFICATION_MODE=real-source is required" >&2; exit 2)
    pnpm --filter @plot/web e2e:certification

# Extract the persisted audit envelope for one configured attempt
generation-certification-audit:
    @test "${PLOT_GENERATION_CERTIFICATION_TOOL:-}" = "true" || (echo "PLOT_GENERATION_CERTIFICATION_TOOL=true is required" >&2; exit 2)
    cd apps/api && ./gradlew generationCertificationAudit

# Offline-reconcile one configured browser and persisted-audit pair
generation-certification-reconcile:
    @test "${PLOT_GENERATION_CERTIFICATION_TOOL:-}" = "true" || (echo "PLOT_GENERATION_CERTIFICATION_TOOL=true is required" >&2; exit 2)
    cd apps/api && ./gradlew generationCertificationReconcile

# Render an allow-listed draft or final certification report
generation-certification-report:
    @test "${PLOT_GENERATION_CERTIFICATION_TOOL:-}" = "true" || (echo "PLOT_GENERATION_CERTIFICATION_TOOL=true is required" >&2; exit 2)
    cd apps/api && ./gradlew generationCertificationReport

# Evaluate cleanup/disposition evidence; PASS is required before final GO
generation-certification-cleanup:
    @test "${PLOT_GENERATION_CERTIFICATION_TOOL:-}" = "true" || (echo "PLOT_GENERATION_CERTIFICATION_TOOL=true is required" >&2; exit 2)
    cd apps/api && ./gradlew generationCertificationCleanup

# Run all tests
test: test-api
    @echo "Tests complete"

# Lint the Next.js app
lint-web:
    pnpm --filter @plot/web lint

# Run all lint checks
lint: lint-web
    @echo "Lint complete"

# Build the Spring Boot API
build-api:
    cd apps/api && ./gradlew build

# Build the Next.js app
build-web:
    pnpm --filter @plot/web build

# Build all apps
build: build-api build-web
    @echo "Build complete"
