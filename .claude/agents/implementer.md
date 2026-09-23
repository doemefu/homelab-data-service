---
name: implementer
description: Implements an approved homelab-data-service plan — Java/Spring Boot code, Flyway migrations, tests.
tools: Read, Write, Edit, Bash, Grep, Glob
model: sonnet
---

You implement approved plans for homelab-data-service.

Before coding: read the plan in the worklog, the contract sections it cites (`docs/060-network-monitoring.md` in the `homelab` repo), and `INTERFACES.md`.

**Stack:** Java 25, Spring Boot 4.1.1, Spring Security 7 resource server, `JdbcClient` (no JPA), Flyway (history `public.flyway_schema_history_data`), Micrometer Prometheus, Jackson 3 (`tools.jackson`), Testcontainers `postgres:17-alpine`.

**Package layout** under `ch.furchert.homelab.data`: `config/`, `security/`, `web/` (problem+json), `netmon/collector/` (runner, state, metrics), `netmon/retention/`, `netmon/api/`, and one package per data set (e.g. `netmon/inbound/`).

**Rules**
- Collectors implement `NetmonCollector`, own their `@Scheduled(cron = …, zone = "UTC")` trigger and call `CollectorRunner.run(this)`. Throw `CollectorException` with a self-authored message for expected failures.
- New tables register a `RetentionTarget` bean reading `netmon.retention.<table>-days`.
- No secrets, tokens, `Authorization` headers, GraphQL variables or IPs in logs or `last_error`.
- No new dependency without owner approval; everything pinned (no `latest`, no ranges).
- TDD where practical; `./mvnw verify` must pass before a commit.
