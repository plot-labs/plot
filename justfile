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
    cd apps/api && set -a && source .env.local && set +a && ./gradlew bootRun

# Start the Next.js app
dev-web:
    pnpm --filter @plot/web dev

# Forward Polar sandbox webhooks to the local API
polar-listen:
    polar listen http://127.0.0.1:8080/api/polar/webhook

# Test the Spring Boot API
test-api:
    cd apps/api && ./gradlew test

# Test the Next.js app
test-web:
    pnpm --filter @plot/web test

# Run all tests
test: test-api test-web
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
