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
