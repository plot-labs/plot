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

# Run the explicit real-model generation contract smoke (never part of normal CI)
generation-contract-smoke:
    @test "${PLOT_AI_CONTRACT_SMOKE:-}" = "true" || (echo "Set PLOT_AI_CONTRACT_SMOKE=true to opt in" >&2; exit 2)
    @test -n "${OPENAI_API_KEY:-}" || (echo "OPENAI_API_KEY is required" >&2; exit 2)
    @test -n "${PLOT_AI_MODEL:-}" || (echo "PLOT_AI_MODEL is required" >&2; exit 2)
    cd apps/api && ./gradlew test --tests com.plot.api.ai.provider.OpenAiGenerationContractSmokeTest --rerun-tasks

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
