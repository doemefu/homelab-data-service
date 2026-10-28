# Agent context — data-service

Repo-specific context for the `homelab-workflow` agents; each agent reads only its own section. The contract lives in `docs/060-network-monitoring.md` in the `homelab` repo (locally `../infrastructure/docs/060-network-monitoring.md`, parent forwarder `../docs/060-network-monitoring.md`). On any conflict between the notes below and the contract, the contract wins.

## architect

- Stack: Java 25 / Spring Boot 4.1 / Spring Security 7 resource server / `JdbcClient` / Flyway.
- Contract (single source of truth) is `docs/060-network-monitoring.md` in the `homelab` repo, not this repo. A change to the netmon schema, endpoint shape, auth rules, env var names or Secret keys is written there **first**; `INTERFACES.md` then only summarises it.
- Read, in order: the contract sections the change touches, `INTERFACES.md`, ADR 0002 (`../docs/adr/0002-network-telemetry-ownership.md`), then the code under `src/main/java/ch/furchert/homelab/data/`.
- State explicitly:
  - Flyway: next free `V<n>__netmon_<topic>.sql`, never edit a merged migration, `outOfOrder=false`.
  - Every write idempotent (upsert on the natural key or replace-per-window, contract §3.2).
  - Read API: problem+json errors with `code`, `Cache-Control: no-store`, nulls never omitted, `from`/`to` window rules.
  - Auth: `SCOPE_netmon:read` + `sub` allowlist (v1); no `ROLE_ADMIN` path until furchert-ch#43.
  - Privacy: IPs are personal data; retention defined, no IPs or tokens in logs or `last_error`.
- Output: the spec diff (contract + `INTERFACES.md`), open questions, and which consumers (furchert-ch) must follow.

## implementer

- Stack: Java 25, Spring Boot 4.1.2, Spring Security 7 resource server, `JdbcClient` (no JPA), Flyway (history table `public.flyway_schema_history_data`), Micrometer Prometheus, Jackson 3 (`tools.jackson`), Testcontainers `postgres:17-alpine`.
- Also read the contract sections the plan cites (`docs/060-network-monitoring.md` in the `homelab` repo) and `INTERFACES.md` before coding.
- Packages under `ch.furchert.homelab.data`: `config/`, `security/`, `web/` (problem+json), `netmon/collector/` (runner, state, metrics), `netmon/retention/`, `netmon/api/`, one package per data set (e.g. `netmon/inbound/`).
- Collectors implement `NetmonCollector`, own their `@Scheduled(cron = ..., zone = "UTC")` trigger and call `CollectorRunner.run(this)`. Expected failures throw `CollectorException` with a self-authored message. Kill switch: `netmon.collectors.<name>.enabled`.
- New tables register a `RetentionTarget` bean reading `netmon.retention.<table>-days`.
- Flyway files `V<n>__netmon_<topic>.sql`, schema `netmon`; next free number at PR time.
- Errors go through `web/ProblemDetailsAdvice`; never put third-party exception messages into responses or `collector_state.last_error`.
- Never log tokens, `Authorization` headers, API keys, GraphQL variables or IP addresses.
- Pinning: actions by SHA, base images by digest; approved dependency set is docs/060 §12 Q5.
- `./mvnw verify` (needs Docker for Testcontainers) must pass before a commit.

## reviewer

- [ ] `/api/netmon/**` stays behind `SCOPE_netmon:read` + `sub` in `netmon.api.allowed-clients`; no stray `permitAll()`; actuator exposure limited to health/info/prometheus.
- [ ] No tokens, headers, secrets or IPs in logs, `last_error`, problem `detail` or metrics labels.
- [ ] Flyway: new `V<n>` only, merged migrations untouched, schema objects in `netmon`.
- [ ] Writes idempotent per contract §3.2; truncation before natural-key aggregation.
- [ ] Response shapes, codes and headers match `docs/060` §7 exactly (camelCase, explicit nulls, `Cache-Control: no-store`).
- [ ] Collectors: kill switch, UTC cron, catch-up cap and failure rule per §4.1, no-overlap lock.
- [ ] Tests: Testcontainers for DB code, real signed RS256 JWTs (`support/TestJwks`) for security paths, `MockRestServiceServer` for upstream HTTP; every service has unit + integration tests.
- [ ] k8s env via `secretKeyRef` (Secret `data-service-secrets`); actions pinned by SHA, base images by digest; no dependency outside the approved set (docs/060 §12 Q5).

## documenter

Docs to keep in sync (all English):
- `README.md`: status table per sub-project, quick reference.
- `INTERFACES.md`: endpoints, auth, errors, metrics, config; summarises (never contradicts) `docs/060-network-monitoring.md` in the `homelab` repo.
- `DEPLOYMENT.md`: manifests, bootstrap/owner actions, verification, troubleshooting.
- `CHANGELOG.md`: `[Unreleased]` entry (audience: users of the API).
- `CLAUDE.md`: only when conventions change.
- Never copy the contract into this repo; link to it.

## devops

- Read-only role in this repo (original tools: Read, Bash, Grep; no Write/Edit): report findings, do not edit files and never mutate the cluster.
- Namespace `apps`, Deployment/Service `data-service` (:8082, cluster-internal only). Flux objects in `flux-system/data-service` (GitRepository, Kustomization, ImageRepository, ImagePolicy, ImageUpdateAutomation) come from the `homelab` repo's `cluster/apps/data-service/`.
- The Secret `data-service-secrets` is provisioned by the `homelab` repo's playbook 59 via SOPS; never by this agent.
- Do not touch `k8s/` image tags (Flux owns them), SOPS files or anything in the `homelab` repo.
- Read-only checks:
  ```bash
  flux get kustomizations data-service -n flux-system
  flux get images all -n flux-system | grep data-service
  kubectl -n apps rollout status deployment/data-service --timeout=120s
  kubectl -n apps get pods -l app=data-service
  kubectl -n apps logs deployment/data-service --tail=50
  ```
- Offline manifest checks: `kubectl kustomize k8s`; `conftest test --rego-version v0 --policy ../infrastructure/policy/kubernetes/ --all-namespaces <(kubectl kustomize k8s)`; `actionlint` for workflows.
- Local read-only access: `kubectl -n apps port-forward svc/postgresql 5432:5432`, `kubectl -n apps port-forward svc/data-service 8082:8082`.

## plan-reviewer

- **Contract:** does the plan implement `docs/060-network-monitoring.md` exactly (table names, columns, write mode, retention, endpoint shapes, error codes)? Any deviation raised as a spec change first?
- **Secrets:** only env vars / `secretKeyRef`; test keys generated in memory; no credentials in code, fixtures or logs.
- **Pinning:** actions by SHA, base images by digest; new dependencies must be in the approved set (docs/060 §12 Q5) or owner-approved.
- **Migrations:** next free `V<n>`, idempotent against an already-migrated DB, never editing merged files.
- **Auth:** `/api/netmon/**` rules unchanged, or the change is in the contract and `INTERFACES.md` is updated.
- **Collectors:** idempotent writes, catch-up cap, failure rule, kill switch, UTC cron, no overlap; new tables register a `RetentionTarget`.
- **Tests:** Testcontainers for DB, `MockRestServiceServer` for upstream HTTP, security tests with real JWTs; validation command is `./mvnw verify`.
- **K8s (if touched):** limits, probes, pinned tag + Flux marker, `secretKeyRef`, namespace `apps`.
- **Diff size:** nothing beyond the stated goal.

## doc-auditor

- Context: data-service is the analytical data plane (ADR 0002): the central `netmon` Postgres store plus its read API, deployed by Flux into namespace `apps`, cluster-internal only. Secrets come from the `homelab` repo's playbook 59 (Secret `data-service-secrets`).
- `README.md`: status table, quick reference still accurate?
- `INTERFACES.md`: new/changed endpoint, error code, metric, env var, property, Secret key, consumed service?
- `DEPLOYMENT.md`: new env var or Secret key (and the owner action to create it), JVM/resource change, new outbound destination, rollback note?
- `CHANGELOG.md`: entry under `[Unreleased]`?
- Contract drift: does the change require an edit to `docs/060-network-monitoring.md` in the `homelab` repo?
