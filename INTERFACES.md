# Interfaces — homelab-data-service

The binding contract is `docs/060-network-monitoring.md` in the `homelab` repo ([link](https://github.com/doemefu/homelab/blob/main/docs/060-network-monitoring.md), added by doemefu/homelab#126). This file summarises what the current code implements. Section numbers (§) refer to that spec.

## 1. Exposed: netmon read API

Base URL (cluster-internal only): `http://data-service.apps.svc.cluster.local:8082/api/netmon`

### Authentication and authorization (§7.5)

- `Authorization: Bearer <JWT>` issued by auth-service. Signature (JWKS), `exp` and `iss` are validated.
- v1 grants access **only** if the token has scope `netmon:read` **and** its `sub` is listed in `netmon.api.allowed-clients` (default `[furchert-ch]`).
- `ROLE_ADMIN` is **not** accepted. User tokens with `role=ADMIN` are held by several SSO clients (grafana, n8n, litellm, Home Assistant), so accepting them would expose IP-level data to any of those apps.
- Authorities: the auth-service converter pattern merges `SCOPE_*` (from `scope`) and `ROLE_*` (from `role`).

The token is a client-credentials token of the existing `furchert-ch` client (needs doemefu/homelab-auth-service#93):

```
POST http://auth-service.apps.svc.cluster.local:8080/oauth2/token
Authorization: Basic base64(furchert-ch:<client secret>)
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&scope=netmon:read
```

### Common conventions (§7.1)

| Item | Behaviour |
|---|---|
| Caching | Every response under `/api/netmon/**`, including 401/403, carries `Cache-Control: no-store` and `Pragma: no-cache` |
| JSON | camelCase; timestamps ISO-8601 UTC with `Z`; absent optional values are `null`, never omitted |
| Unknown query parameters | Ignored |
| Methods | Read-only; anything but `GET` answers 405 |

### Errors (§7.1, §7.4)

RFC 9457 `application/problem+json` with `type` (`about:blank`), `title`, `status`, `detail`, `instance` and `code`:

| Status | `code` | When |
|---|---|---|
| 400, 405, 406, 415 | `invalid_parameter` | Malformed request (NM-1+ adds `invalid_window` for bad `from`/`to`) |
| 401 | `unauthorized` | Missing, malformed, expired, wrongly signed or wrong-issuer token; also carries `WWW-Authenticate: Bearer …` |
| 403 | `forbidden` | Valid token without `netmon:read` or with a `sub` outside the allowlist |
| 404 | `not_found` | Unknown path (and, from NM-1, an unknown IP) |
| 500 | `internal` | Unexpected error, or token validation impossible because auth-service's JWKS is unreachable |

`detail` is a fixed text; it never echoes a token, an exception message or a stack trace.

### `GET /api/netmon/status` (§7.2)

Collector freshness for the UI's honest-fallback banner. One element per registered collector, sorted by name. NM-0 registers only `retention`.

```json
{ "collectors": [ { "name": "retention", "enabled": true, "lastSuccessAt": null, "lastWindowEnd": null,
                    "consecutiveFailures": 0, "lastErrorCode": null, "stale": false } ] }
```

- `enabled` reflects the kill switch `netmon.collectors.<name>.enabled` (default `true`).
- `lastErrorCode` is `null`, `credentials`, `rate_limited`, `upstream`, `truncated` or `internal`; never a message.
- `stale` is `true` when the last success is older than 3 × the collector's cadence. Before the first success, the service start time is the reference, so a new deployment is not stale before a collector could have run. A disabled collector is never stale.

Further endpoints (`/inbound/*`, `/ips/{ip}`, `/lan/*`, `/egress/top`, `/logins/*`) arrive with NM-1..NM-4.

## 2. Exposed: actuator

| Path | Access | Purpose |
|---|---|---|
| `/actuator/health` (+ `/liveness`, `/readiness`) | public | Kubernetes probes (the deployment probes `/actuator/health`) |
| `/actuator/info` | public | — |
| `/actuator/prometheus` | public, cluster-internal | Prometheus scrape (§12 Q9); no IP-level labels |
| any other `/actuator/**` | denied (403) | — |

### Metrics

| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `netmon_collector_last_success_timestamp_seconds` | gauge | `collector` | Unix time of the collector's last successful run; **NaN until the first success** (§4.1). Registered for every collector at startup. |

A `NetmonCollectorStale` rule (infra-owned) should alert on `time() - netmon_collector_last_success_timestamp_seconds > 3 × cadence` and treat NaN as "never succeeded" (for example with an additional `absent`/NaN check once the collector is expected to have run).

## 3. Consumed

| Service | Address | Use |
|---|---|---|
| PostgreSQL | `postgresql.apps.svc.cluster.local:5432`, DB `data_service`, role `data_service` | The netmon store; Flyway migrations on startup |
| auth-service JWKS | `http://auth-service.apps.svc.cluster.local:8080/oauth2/jwks` | Token signature keys (fetched on the first request, then cached) |

NM-1..NM-4 add Prometheus, Cloudflare GraphQL, the blocklists, AbuseIPDB and auth-service's login-event endpoint (§2, §10).

## 4. Database ownership (§3)

| Object | Owner | Notes |
|---|---|---|
| Schema `netmon` | data-service | Created by Flyway |
| `netmon.collector_state` | data-service | V1. One row per collector: high-water mark, cursor, attempt/success times, failure counter, `last_error` (class + short collector-authored message) and `last_error_code` (the §7.2 enum, enforced by a CHECK) |
| `public.flyway_schema_history_data` | data-service | Flyway history |

No other service reads or writes this database.

## 5. Configuration

| Env var | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/data_service` | JDBC URL |
| `DB_USERNAME` | `data_service` | DB role |
| `DB_PASSWORD` | — (required) | DB password |
| `JWKS_URI` | `http://localhost:8080/oauth2/jwks` | auth-service JWKS |
| `JWT_ISSUER` | `https://auth.furchert.ch` | Expected `iss` |
| `JAVA_TOOL_OPTIONS` | — | JVM flags (set in `k8s/deployment.yaml`) |

| Property | Default | Purpose |
|---|---|---|
| `netmon.api.allowed-clients` | `[furchert-ch]` | Token `sub` values allowed on `/api/netmon/**` |
| `netmon.collectors.<name>.enabled` | `true` | Per-collector kill switch |

Kubernetes Secret `data-service-secrets` (ns `apps`, created by the `homelab` repo's playbook 59): keys `db-username`, `db-password`. NM-1 adds `cloudflare-api-token`, `cloudflare-zone-id` and optionally `abuseipdb-api-key`; NM-4 adds `auth-client-secret` (§9).
