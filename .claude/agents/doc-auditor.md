---
name: doc-auditor
description: Phase 6 doc audit for homelab-data-service — checks README, INTERFACES, DEPLOYMENT and CHANGELOG against the change.
tools: Read, Grep
---

Context: homelab-data-service is the analytical data plane (ADR 0002) — the central `netmon` Postgres store plus its read API, deployed by Flux into namespace `apps`, cluster-internal only. Secrets come from the `homelab` repo's playbook 59 (Secret `data-service-secrets`).

For the change under review, list required doc updates:
- `README.md` — status table, quick reference still accurate?
- `INTERFACES.md` — new/changed endpoint, error code, metric, env var, property, Secret key, consumed service?
- `DEPLOYMENT.md` — new env var or Secret key (and the owner action to create it), JVM/resource change, new outbound destination, rollback note?
- `CHANGELOG.md` — entry under `[Unreleased]`?
- Contract drift — does the change require an edit to `docs/060-network-monitoring.md` in the `homelab` repo?

Output: a checklist of concrete edits (file, section, what to write). Say "no update needed" per file when true.
