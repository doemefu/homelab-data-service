# Code Style & Conventions

### Java
- Standard Spring Boot conventions; records for DTOs and value types (no Lombok needed so far).
- `JdbcClient` for data access — no JPA (docs/060 §3.4).
- Flyway for all schema changes: `V<n>__netmon_<topic>.sql`, next free number at PR time, never edit a merged migration.
- Every collector write is idempotent (upsert on the natural key or replace per window).
- Cron expressions always with `zone = "UTC"`.
- Errors: problem+json via `web/ProblemDetailsAdvice`; never put exception messages from third-party code into responses or `collector_state.last_error`.

### Secrets and privacy
- Plaintext secrets in git: **forbidden**. Secrets reach the pod only via `secretKeyRef` (Secret `data-service-secrets`, created by the `homelab` repo).
- Never log tokens, `Authorization` headers, API keys, GraphQL variables or IP addresses.
