# CLAUDE.md — homelab-data-service

> **Session start:** Read `.claude/memory/MEMORY.md` completely if it exists. The topmost entry shows the current state. If there is an entry with `status: in_progress`, read the linked worklog and ask the user: *"I see we were interrupted at [SLUG]. Continue?"* — before doing anything else.

> **After each completed change:** Insert a new block **at the top** of `.claude/memory/MEMORY.md`.

> `.claude/memory/`, `.claude/worklogs/` and `.claude/worktrees/` are gitignored — local-only; cross-check `git log` and GitHub when they look stale.

## Service Overview

The analytical data plane of the homelab (ADR 0002, `../docs/adr/0002-network-telemetry-ownership.md`): owns the central network-monitoring store (`netmon`) and serves its read API to furchert-ch. Sensor history (ADR 0001) comes later.

**Port:** 8082 (cluster-internal only)
**Package:** `ch.furchert.homelab.data`
**Database:** PostgreSQL `data_service`, schema `netmon`, Flyway history `public.flyway_schema_history_data`

## The contract

**`docs/060-network-monitoring.md` in the `homelab` repo is the single source of truth** (locally `../infrastructure/docs/060-network-monitoring.md`; parent forwarder `../docs/060-network-monitoring.md`). Schema, collectors, API shapes, auth, env vars and Secret keys are implemented from it. A deviation is a spec change first, then code.

## Non-Negotiables

- Do **not** touch secrets, SOPS files or credentials; secrets reach the pod only via `secretKeyRef`.
- Do **not** use `latest` or version ranges — all versions pinned (actions by SHA, base images by digest).
- Do **not** introduce new dependencies without explicit user approval (the approved set is docs/060 §12 Q5).
- Do **not** log tokens, `Authorization` headers, API keys or IP addresses.
- Flyway only; never edit a merged migration.
- Commit, push and open PRs on feature branches without asking (standing permission, 2026-08-28). Merging, force-pushes, playbook runs, cluster mutations and anything touching SOPS/secrets need an explicit go for that task.
- Before any merge, wait for the Copilot review and fix or answer every comment (see `.claude/rules/workflow.md` Phase 5).
- All code, comments and documentation in **English**. Minimize diff size: no drive-by refactors.

## Tech Stack (pinned)

| Component | Version |
|-----------|---------|
| Java | 25 |
| Spring Boot | 4.1.1 (Tomcat override 11.0.25, as in the siblings) |
| Data access | `spring-boot-starter-jdbc` (`JdbcClient`), Flyway, PostgreSQL driver 42.7.13 |
| Security | `spring-boot-starter-security-oauth2-resource-server` (JWKS + issuer) |
| Metrics | `micrometer-registry-prometheus` (Boot BOM) |
| Testcontainers BOM | 2.0.5, `postgres:17-alpine` |
| Base image | `eclipse-temurin:25-jre-alpine@sha256:…` (build: `25-jdk-alpine@sha256:…`) |

## Conventions

- Collectors implement `NetmonCollector`, schedule themselves in UTC and run through `CollectorRunner` (kill switch `netmon.collectors.<name>.enabled`, no-overlap lock, `collector_state` bookkeeping, freshness gauge).
- Each new table registers a `RetentionTarget` for the daily retention job.
- `/api/netmon/**`: `SCOPE_netmon:read` + `sub` in `netmon.api.allowed-clients`; problem+json errors with `code`; `Cache-Control: no-store`.
- Tests: Testcontainers for DB code; real RS256 JWTs against the in-test JWKS (`support/TestJwks`) for security paths; `MockRestServiceServer` for upstream HTTP.

## Agent Team

Project agents in `.claude/agents/`: `architect` (contract first), `implementer`, `reviewer`, `plan-reviewer` (Phase 3), `doc-auditor` (Phase 6), `documenter`, `devops` (read-only cluster checks).

## Process & Conventions

Rules in `.claude/rules/`: `workflow.md` (6-phase workflow), `worklog-conventions.md`, `plan-structure.md`, `commands.md`, `code-style-conventions.md`, `review-guidelines.md`, `documentation-files.md`, `github-project.md` (Project #5 status transitions). Worklog template: `.claude/worklog-template.md`.
