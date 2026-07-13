# Local Docker

Local development services will live here.

The API currently runs its own PostgreSQL (pgvector) via
`apps/api/compose.yaml` through Spring Boot Docker Compose support. Shared
services move here when more than one app needs them.

First expected services:

- PostgreSQL
- MinIO or R2-compatible object storage emulator

