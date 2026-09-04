set dotenv-load
set shell := ["bash", "-uc"]

# Show available commands
[default]
help:
    just --list

# Install JavaScript workspace dependencies
install:
    bun install

# Start the Spring Boot API
dev-api:
    cd apps/api && set -a && source .env.local && set +a && ./gradlew bootRun

# Start the Next.js app
dev-web:
    bun --filter @plot/web dev

# Forward Polar sandbox webhooks to the local API
polar-listen:
    polar listen http://127.0.0.1:8080/api/polar/webhook

# Test the Spring Boot API
test-api:
    cd apps/api && ./gradlew test

# Run citation/generation quality deterministic eval (fixture-based)
eval-citation:
    cd apps/api && ./gradlew test --tests "com.plot.api.ai.provider.ArtifactWorkflowCitationEvalTest"

# Run citation/generation quality live eval (requires AI credentials)
eval-citation-live:
    cd apps/api && ./gradlew liveEval

# Test the browser API client contract
test-api-client:
    bun --filter @plot/api-client test


# Test the Next.js app
test-web:
    bun --filter @plot/web test

# Run all tests
test: test-api test-api-client test-web
    @echo "Tests complete"

# Lint the Next.js app
lint-web:
    bun --filter @plot/web lint

# Run all lint checks
lint: lint-web
    @echo "Lint complete"

# Build the Spring Boot API
build-api:
    cd apps/api && ./gradlew build

# Regenerate jOOQ sources from a fresh PostgreSQL 16 database and compare the baseline
verify-jooq:
    cd apps/api && bash scripts/regenerate-jooq.sh check

# Regenerate and replace the checked-in jOOQ source baseline after review
update-jooq:
    cd apps/api && bash scripts/regenerate-jooq.sh update

# Build the Next.js app
build-web:
    bun --filter @plot/web build

# Build all apps
build: build-api build-web
    @echo "Build complete"
