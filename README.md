# homelab-data-service

The **analytical data plane** of the doemefu homelab ([ADR 0002](../docs/adr/0002-network-telemetry-ownership.md), extending [ADR 0001](../docs/adr/0001-schedules-ownership-and-data-plane.md)). data-service owns historical and analytical data:

- **Network telemetry** (Epic doemefu/homelab#114): the central `netmon` store in PostgreSQL, filled by scheduled collectors and read by furchert-ch's `/dashboard/network`. This is the first deployable scope.
- **Sensor history** (Epic #1, later): read-only historical InfluxDB queries. Not implemented yet.

**It does not** connect to MQTT, write to InfluxDB, manage users, issue tokens, or own schedules (device-service owns those, ADR 0001).

**Contract:** [`docs/060-network-monitoring.md`](https://github.com/doemefu/homelab/blob/main/docs/060-network-monitoring.md) in the `homelab` (infrastructure) repo is the single source of truth for the schema, the API, auth and the platform wiring. Implement from it; do not reverse-engineer this code.

## Status

| Sub-project | Scope | State |
|---|---|---|
| NM-0 (#13) | Spring Boot skeleton, Flyway `V1__netmon_baseline`, JWT resource server, `GET /api/netmon/status`, collector runner + retention skeleton + freshness gauge, image/CI/k8s | this bootstrap |
| NM-1 (#14) | Cloudflare collector, IP enrichment, inbound read API | planned |
| NM-3 (#15) | LAN snapshot collector + API | planned |
| NM-2 (#16) | Egress snapshot collector + API | planned |
| NM-4 (#17) | Login-event puller + API | planned |

## Quick reference

| Item | Value |
|---|---|
| Port | 8082 (cluster-internal only; no tunnel route) |
| Package | `ch.furchert.homelab.data` |
| Stack | Java 25, Spring Boot 4.1.1, Spring Security 7 resource server, JdbcClient (no JPA), Flyway, Micrometer Prometheus |
| Database | PostgreSQL `data_service`, schema `netmon`, history `public.flyway_schema_history_data` |
| Image | `ghcr.io/doemefu/homelab-data-service:main-<YYYYMMDDTHHMMSS>` (linux/amd64 + linux/arm64) |
| Deploy | Flux, from `k8s/` (wiring in the `homelab` repo, `cluster/apps/data-service/`) |

## Build and test

Requires Java 25 and Docker (Testcontainers starts `postgres:17-alpine`).

```bash
./mvnw verify                      # all unit + integration tests
./mvnw test -Dtest=ClassName       # one test class
docker build -t data-service .     # container image
```

## Run locally

```bash
kubectl -n apps port-forward svc/postgresql 5432:5432   # or any local Postgres with a data_service DB/role
export DB_PASSWORD='<from your password manager, never commit>'
./mvnw spring-boot:run
curl -s localhost:8082/actuator/health
```

Without a token `GET /api/netmon/status` answers 401; see [INTERFACES.md](INTERFACES.md) for how a token is obtained.

## Documentation

| File | Covers |
|---|---|
| [INTERFACES.md](INTERFACES.md) | Exposed API, auth rules, errors, metrics, consumed services, env vars, Secret keys |
| [DEPLOYMENT.md](DEPLOYMENT.md) | Kubernetes, Flux bootstrap order, owner actions, JVM settings, troubleshooting |
| [CHANGELOG.md](CHANGELOG.md) | Changes per release |
| [CLAUDE.md](CLAUDE.md) | Conventions for agent-assisted work in this repo |
