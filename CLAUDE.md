# CLAUDE.md — homelab-data-service

In-progress worklogs are listed at session start by the `homelab-workflow` plugin; start work with `/homelab-workflow:start-task`. Durable facts go to auto memory.

> `.claude/memory/`, `.claude/worklogs/` and `.claude/worktrees/` are gitignored — local-only; cross-check `git log` and GitHub when they look stale.

## Service Overview

The analytical data plane of the homelab (ADR 0002, `../docs/adr/0002-network-telemetry-ownership.md`): owns the central network-monitoring store (`netmon`) and serves its read API to furchert-ch. Sensor history (ADR 0001) comes later.

**Port:** 8082 (cluster-internal only)
**Package:** `ch.furchert.homelab.data`
**Database:** PostgreSQL `data_service`, schema `netmon`, Flyway history `public.flyway_schema_history_data`

## Architecture Context

- **auth-service:** issues the tokens this service validates (JWKS + issuer); the login-event collector pulls `/api/v1/login-events` with client `data-service` (scope `login-events:read`).
- **furchert-ch:** the only API consumer (`/dashboard/network`, client `furchert-ch`, scope `netmon:read`).
- **infrastructure:** deploys it (`cluster/apps/data-service/`; Secret written by playbook 59). Spec `../docs/060-network-monitoring.md`, ADR `../docs/adr/0002-network-telemetry-ownership.md`.
- **Sensor history (later):** follows `../docs/adr/0001-schedules-ownership-and-data-plane.md`.

## The contract

**`docs/060-network-monitoring.md` in the `homelab` repo is the single source of truth** (locally `../infrastructure/docs/060-network-monitoring.md`; parent forwarder `../docs/060-network-monitoring.md`). Schema, collectors, API shapes, auth, env vars and Secret keys are implemented from it. A deviation is a spec change first, then code.

## Non-Negotiables

- Do **not** touch secrets, SOPS files or credentials; secrets reach the pod only via `secretKeyRef`.
- Do **not** use `latest` or version ranges — all versions pinned (actions by SHA, base images by digest).
- Do **not** introduce new dependencies without explicit user approval (the approved set is docs/060 §12 Q5).
- Do **not** log tokens, `Authorization` headers, API keys or IP addresses.
- Flyway only; never edit a merged migration.
- Commit, push and open PRs on feature branches without asking (standing permission, 2026-08-28). Merging, force-pushes, playbook runs, cluster mutations and anything touching SOPS/secrets need an explicit go for that task.
- Before any merge, wait for the Copilot review and fix or answer every comment (see `/homelab-workflow:review-gate`).
- Tests are required for every service (unit + integration).
- All code, comments and documentation in **English**. Minimize diff size: no drive-by refactors.

## Tech Stack (pinned)

| Component | Version |
|-----------|---------|
| Java | 25 |
| Spring Boot | 4.1.2 (manages Tomcat 11.0.27, Jackson 2.21.7/3.1.7) |
| Data access | `spring-boot-starter-jdbc` (`JdbcClient`), Flyway, PostgreSQL driver 42.7.14 |
| Security | `spring-boot-starter-security-oauth2-resource-server` (JWKS + issuer) |
| Metrics | `micrometer-registry-prometheus` (Boot BOM) |
| Testcontainers BOM | 2.0.5, `postgres:17-alpine` |
| Base image | `eclipse-temurin:25-jre-alpine@sha256:…` (build: `25-jdk-alpine@sha256:…`); runtime stage strips the emSign roots from cacerts (temporary, homelab#212) |

## Conventions

- Collectors implement `NetmonCollector`, schedule themselves in UTC and run through `CollectorRunner` (kill switch `netmon.collectors.<name>.enabled`, no-overlap lock, `collector_state` bookkeeping, freshness gauge).
- Each new table registers a `RetentionTarget` for the daily retention job.
- `/api/netmon/**`: `SCOPE_netmon:read` + `sub` in `netmon.api.allowed-clients`; problem+json errors with `code`; `Cache-Control: no-store`.
- Tests: Testcontainers for DB code; real RS256 JWTs against the in-test JWKS (`support/TestJwks`) for security paths; `MockRestServiceServer` for upstream HTTP.

## Agent Team

Plugin agents `homelab-workflow:<role>` (`architect`, `implementer`, `reviewer`, `plan-reviewer`, `doc-auditor`, `documenter`, `devops`). Repo specifics (contract location, stack, packages, checklists, read-only `devops`) are in `.claude/agent-context.md`, one section per role. There are no repo-only agents.

## Process & Conventions

Plugin skills: `/homelab-workflow:start-task`, `workflow` (6-phase workflow, worklogs, plan structure), `review-gate`, `project-board` (Project #5), `cross-repo`.

Rules in `.claude/rules/` (path-scoped): `code-style-conventions.md` (Java, Flyway, secrets and privacy), `documentation-files.md` (docs purpose and owner; docs are written in parallel with the code; link to the contract, never copy it).

## Commands

```bash
./mvnw verify                     # full build + all tests (needs Docker for Testcontainers)
./mvnw test -Dtest=ClassName      # single test class
./mvnw clean package -DskipTests  # jar only
./mvnw spring-boot:run            # run locally on :8082 (DB_PASSWORD env var required)
docker build -t data-service .    # container image
actionlint                        # GitHub workflow lint
kubectl kustomize k8s             # render manifests (offline)
conftest test --rego-version v0 \
  --policy ../infrastructure/policy/kubernetes/ --all-namespaces <(kubectl kustomize k8s)
# read-only cluster access (local dev)
kubectl -n apps port-forward svc/postgresql 5432:5432
kubectl -n apps port-forward svc/data-service 8082:8082
kubectl -n apps get pods -l app=data-service
```
