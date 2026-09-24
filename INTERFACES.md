# Interfaces — homelab-data-service

The binding contract is `docs/060-network-monitoring.md` in the `homelab` repo ([link](https://github.com/doemefu/homelab/blob/main/docs/060-network-monitoring.md), added by doemefu/homelab#126). This file summarises what the current code implements. Section numbers (§) refer to that spec.

## 1. Exposed: netmon read API

Base URL (cluster-internal only): `http://data-service.apps.svc.cluster.local:8082/api/netmon`

### Authentication and authorization (§7.5)

- `Authorization: Bearer <JWT>` issued by auth-service. Signature (JWKS), `exp` and `iss` are validated.
- v1 grants access **only** if the token has scope `netmon:read` **and** its `sub` is listed in `netmon.api.allowed-clients` (default `[furchert-ch]`).
- `ROLE_ADMIN` is **not** accepted. User tokens with `role=ADMIN` are held by several SSO clients (grafana, n8n, litellm, Home Assistant), so accepting them would expose IP-level data to any of those apps.
- Authorities: the auth-service converter pattern merges `SCOPE_*` (from `scope`) and `ROLE_*` (from `role`).

The token is a client-credentials token of the existing `furchert-ch` client (provided by doemefu/homelab-auth-service PR #95, Flyway V6, live since 2026-09-23):

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
| 400 | `invalid_window` | `from`/`to` not ISO-8601 instants, or `to - from` not in (0, 30 d] |
| 400, 405, 406, 415 | `invalid_parameter` | Malformed request: `limit` out of range, malformed `ip` or `cursor`, wrong method or media type |
| 401 | `unauthorized` | Missing, malformed, expired, wrongly signed or wrong-issuer token; also carries `WWW-Authenticate: Bearer …` |
| 403 | `forbidden` | Valid token without `netmon:read` or with a `sub` outside the allowlist |
| 404 | `not_found` | Unknown path, or `GET /ips/{ip}` for an IP never seen |
| 500 | `internal` | Unexpected error, or token validation impossible because auth-service's JWKS is unreachable |

`detail` is a fixed text; it never echoes a token, an exception message or a stack trace.

### `GET /api/netmon/status` (§7.2)

Collector freshness for the UI's honest-fallback banner. One element per registered collector, sorted by name: `blocklists`, `cloudflare-firewall`, `cloudflare-requests`, `lan`, `reputation`, `retention`.

```json
{ "collectors": [ { "name": "cloudflare-requests", "enabled": true, "lastSuccessAt": "2026-09-23T10:30:02Z",
                    "lastWindowEnd": "2026-09-23T10:00:00Z", "consecutiveFailures": 0, "lastErrorCode": null,
                    "stale": false } ] }
```

- `enabled` reflects the kill switch `netmon.collectors.<name>.enabled` (default `true`). It is also `false` for a collector that lacks required configuration: `reputation` while `ABUSEIPDB_API_KEY` is unset.
- `lastErrorCode` is `null`, `credentials`, `rate_limited`, `upstream`, `truncated` or `internal`; never a message. `truncated` is a warning on a successful run (a window exceeded the Cloudflare page limit), so it can appear with `consecutiveFailures: 0`. The same holds for `lan` with `upstream` and `consecutiveFailures: 0`: the run worked, but either no node exposes the NM-3 metrics at the latest evaluation point (the `netmon_node` role is not rolled out, or node-exporter scraping is broken), or an expected node never published a window the run finalised (its script is failing). A real Prometheus outage is a failure (`consecutiveFailures` > 0).
- `lastWindowEnd` is the collector's high-water mark: for `cloudflare-requests` the end of the newest contiguous final hour, for `cloudflare-firewall` the `until` of the last complete run, for `lan` the end of the newest contiguous final 15-minute window (see DEPLOYMENT "NM-3 rollout" for when a window is final).
- `stale` is `true` when the last success is older than 3 × the collector's cadence. Before the first success, the service start time is the reference, so a new deployment is not stale before a collector could have run. A disabled collector is never stale.

### `GET /api/netmon/inbound/summary?from&to&host&limit` (§7.2)

Aggregates of `inbound_request_groups` over the window (overlap filter `window_start < to AND window_end > from`), optionally for one `host`. `limit` sizes every top list (default 10, max 50). Default window: the last 24 h.

```json
{ "window": {"from": "2026-09-23T00:00:00Z", "to": "2026-09-24T00:00:00Z"},
  "totals": {"requests": 132, "uniqueClientIps": 2, "sampled": true},
  "topClientIps": [ {"ip": "203.0.113.7", "requests": 125, "country": "DE", "asn": 3320, "asnOrg": "DTAG",
                     "blocklisted": false, "abuseScore": 12, "firewallEvents": 0} ],
  "topCountries": [ {"country": "DE", "requests": 125} ],
  "topAsns":      [ {"asn": 3320, "asnOrg": "DTAG", "requests": 125} ],
  "topHosts":     [ {"host": "furchert.ch", "requests": 125} ],
  "topPaths":     [ {"host": "furchert.ch", "path": "/de", "requests": 120} ],
  "statuses":     [ {"status": 200, "requests": 120} ],
  "timeline":     [ {"bucketStart": "2026-09-23T08:00:00Z", "requests": 132} ] }
```

- `sampled` is `true` if any contributing row had Cloudflare `sampleInterval > 1` (the UI shows "≈").
- `timeline` buckets are 1 h up to a 7-day window, otherwise 1 d (UTC); only buckets with data are listed. `statuses` lists every status, ascending.
- `topClientIps[].country/asn/asnOrg/blocklisted/abuseScore` come from `ip_enrichment`; `firewallEvents` counts that IP's firewall events in `[from, to)` (with the same `host` filter).
- **ASN comes from firewall events only.** Cloudflare's `httpRequestsAdaptiveGroups` offers no ASN dimensions on this zone (settings probe 2026-09-24). `asn`/`asnOrg` of an IP stay `null` until it appears in a firewall event, and `topAsns` counts only requests from such IPs.

### `GET /api/netmon/inbound/firewall-events?from&to&action&host&ip&limit&cursor` (§7.2)

Raw Cloudflare firewall events in `[from, to)`, newest first (`occurredAt`, then insertion order). `limit` default 50, max 500. `nextCursor` is an opaque string to pass back as `cursor`, or `null` on the last page.

```json
{ "items": [ {"occurredAt": "2026-09-23T08:30:00Z", "rayName": "8c1…", "clientIp": "198.51.100.9", "country": "US",
              "asn": 14061, "asnOrg": "DIGITALOCEAN", "action": "block", "securitySource": "firewallManaged",
              "ruleId": "rule-1", "host": "auth.furchert.ch", "method": "POST", "path": "/login",
              "userAgent": "curl/8.0", "blocklisted": true} ],
  "nextCursor": null }
```

### `GET /api/netmon/ips/{ip}?from&to` (§7.2)

Everything known about one IP. Default window **7 d**. A malformed `ip` is 400 `invalid_parameter` (only literals are accepted, never hostnames); an IP never seen is 404 `not_found`. Non-public addresses are never enriched, so they are always 404 here.

```json
{ "ip": "198.51.100.9", "firstSeen": "2026-09-23T08:00:00Z", "lastSeen": "2026-09-23T09:00:00Z",
  "seenIn": ["firewall", "inbound"], "country": "US", "asn": 14061, "asnOrg": "DIGITALOCEAN",
  "blocklists": [ {"list": "firehol-level1", "cidr": "198.51.100.0/24", "fetchedAt": "2026-09-23T05:00:00Z"} ],
  "abuseIpDb": null,
  "inbound": {"requests": 7, "topHosts": [], "topPaths": [], "statuses": []},
  "firewallEvents": [],
  "logins": null, "lan": {"ufwBlocks": 0, "sshFailed": 0} }
```

- `abuseIpDb` is `{score, reports, checkedAt}` or `null` if never checked.
- `firewallEvents` holds the last 20 events of the window in the firewall-events item shape.
- `lan` (NM-3) sums, within the window, the UFW blocks from this IP (`ufwBlocks`) and its unsuccessful SSH attempts (`sshFailed` = `failed` + `invalid_user`). A LAN-only IP is private and therefore not found here; public attackers reach `ip_enrichment` through the LAN collector as well (`seenIn` contains `lan`).
- `logins` (NM-4) is `null` until that sub-project ships.

### `GET /api/netmon/lan/connections?from&to&node&dport` (§7.2, NM-3)

Peak concurrent TCP connections to the watched node ports (1883, 22, 8123, 6443, 10250) per source. Default window 24 h. Rows are aggregated over the window's 15-minute snapshots by `(node, dport, srcIp, state)`; ordered by node, dport, then `peakConnections` descending. `dport` must be 1..65535 (else 400 `invalid_parameter`). Not paged: the node script caps the series per node (§5.2).

```json
{ "items": [ {"node": "raspi5", "dport": 1883, "srcIp": "192.168.1.50", "state": "ESTABLISHED",
              "peakConnections": 3, "windows": 2, "firstSeen": "2026-09-24T09:15:00Z", "lastSeen": "2026-09-24T09:45:00Z"} ] }
```

- `peakConnections` = the highest 15-minute peak in the window; `windows` = number of 15-minute snapshots with this key; `firstSeen` = start of the oldest, `lastSeen` = end of the newest.
- `srcIp` is a LAN IP, `10.42.0.0/16` (any pod) or `other` (overflow beyond the per-node series cap). IP literals in all three LAN endpoints use the RFC 5952 form (`2001:db8::1`; IPv4-mapped addresses as plain IPv4).

### `GET /api/netmon/lan/ufw-blocks?from&to&node&limit` (§7.2, NM-3)

UFW `BLOCK` log lines. Default window 24 h; `limit` is top-N (default 10, max 50), ordered by `blocks` descending.

```json
{ "lowerBound": true, "totals": {"blocks": 14},
  "items": [ {"srcIp": "203.0.113.9", "dport": 23, "proto": "TCP", "blocks": 12, "nodes": ["raspi4", "raspi5"]} ] }
```

- `lowerBound` is always `true`: UFW rate-limits its logging (§5.4), so every count is a minimum.
- `totals.blocks` sums every row in the window (and `node`), not only the top-N items.
- `srcIp="other"` with `dport=0` is the node script's overflow row; `dport=0` is also used for ICMP.

### `GET /api/netmon/lan/ssh-auth?from&to&node` (§7.2, NM-3)

sshd authentication results per node and source. Default window 24 h; ordered by node, then unsuccessful attempts descending.

```json
{ "items": [ {"node": "raspi5", "srcIp": "203.0.113.9", "accepted": 0, "failed": 2, "invalidUser": 3} ] }
```

- The outcomes are disjoint: `failed` = failed authentication for an existing user (a lower bound, §5.4), `invalidUser` = attempts for a non-existent user.
- Tunnelled SSH (`ssh.furchert.ch`) shows the cloudflared pod (`10.42.0.0/16`) or node address, not the client (§10).

Further endpoints (`/egress/top`, `/logins/*`) arrive with NM-2 and NM-4.

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
| `netmon_collector_last_success_timestamp_seconds` | gauge | `collector` | Unix time of the collector's last successful run; **NaN until the first success** (§4.1). Registered at startup only for collectors that are enabled and able to succeed. No series is exported for a collector whose kill switch is off, for `reputation` without `ABUSEIPDB_API_KEY`, or for `cloudflare-requests`/`cloudflare-firewall` started without `CLOUDFLARE_API_TOKEN`/`CLOUDFLARE_ZONE_ID`. Those two still run and report `credentials` in `/status`. |

The `NetmonCollectorStale` rule is owned by the infra repo (doemefu/homelab#116, PR #131). It treats a NaN sample as "never succeeded" once the pod (`kube_pod_start_time`) is older than the collector's threshold. The thresholds are 26 h for `blocklists`/`retention`, 90 min for `lan`/`reputation`, and 15 min for the 5-minute collectors `cloudflare-requests`/`cloudflare-firewall`. Because unconfigured collectors export no series, the rule never fires for a collector that is off on purpose.

## 3. Consumed

| Service | Address | Use |
|---|---|---|
| PostgreSQL | `postgresql.apps.svc.cluster.local:5432`, DB `data_service`, role `data_service` | The netmon store; Flyway migrations on startup |
| auth-service JWKS | `http://auth-service.apps.svc.cluster.local:8080/oauth2/jwks` | Token signature keys (fetched on the first request, then cached) |
| Cloudflare GraphQL Analytics | `https://api.cloudflare.com/client/v4/graphql` (`api.cloudflare.com:443`) | `cloudflare-requests` (query A, `httpRequestsAdaptiveGroups`, every 5 min at :00) and `cloudflare-firewall` (query B, `firewallEventsAdaptive`, every 5 min at :30) for zone `CLOUDFLARE_ZONE_ID`, `Authorization: Bearer $CLOUDFLARE_API_TOKEN` (§4.2). Probe limits (2026-09-24, both datasets): 31 d history, 30 d per query, 10 000 rows per page, 40 fields. Normal load 3 queries per 5 min, capped at 60 per run during catch-up. |
| Spamhaus DROP v4 | `https://www.spamhaus.org/drop/drop_v4.json` (`www.spamhaus.org:443`) | `blocklists`, daily 05:00 UTC. NDJSON; Spamhaus sends no ETag, so `unchanged` is detected by sha256. |
| FireHOL level1 | `https://raw.githubusercontent.com/firehol/blocklist-ipsets/master/firehol_level1.netset` (`raw.githubusercontent.com:443`) | `blocklists`, daily 05:00 UTC, `If-None-Match` with the stored ETag. Private/bogon ranges in the list are dropped. |
| AbuseIPDB | `https://api.abuseipdb.com/api/v2/check` (`api.abuseipdb.com:443`) | `reputation`, every 30 min, **only when `ABUSEIPDB_API_KEY` is set** (not yet). ≤ 10 checks per run, ≤ 200 per UTC day; only public, non-blocklisted IPs that crossed a §4.5 threshold. |

| Prometheus | `http://kube-prometheus-stack-prometheus.monitoring.svc.cluster.local:9090` (`PROMETHEUS_URL`, cluster-internal, no auth) | `lan` (NM-3), every 15 min at :04/:19/:34/:49 UTC: `POST /api/v1/query` (form fields `query`, `time`), 10 s timeouts. Reads the node-script metrics `homelab_lan_connections`, `homelab_ufw_blocks_bucket`, `homelab_sshd_auth_bucket`, `homelab_netmon_bucket_end_timestamp_seconds` and `homelab_netmon_last_success_timestamp_seconds` (§5.2, produced by the `netmon_node` role from doemefu/homelab#117). Five instant queries per window (§4.6), plus up to three for a retry. |

These four are data-service's only outbound destinations outside the cluster (§10); blocklists are fetched only by data-service. NM-2 and NM-4 add more Prometheus queries and auth-service's login-event endpoint.

## 4. Database ownership (§3)

| Object | Owner | Notes |
|---|---|---|
| Schema `netmon` | data-service | Created by Flyway |
| `netmon.collector_state` | data-service | V1. One row per collector: high-water mark, cursor, attempt/success times, failure counter, `last_error` (class + short collector-authored message) and `last_error_code` (the §7.2 enum, enforced by a CHECK) |
| `netmon.inbound_request_groups` | data-service | V2. Cloudflare request groups per hour, without ASN columns (not offered by the dataset); replaced per window, `is_final` once re-collected ≥ 15 min after the hour. Retention 90 d. |
| `netmon.firewall_events` | data-service | V2. Raw Cloudflare firewall events; natural key `(ray_name, security_source, coalesce(rule_id,''), action)`. Retention 180 d. |
| `netmon.ip_enrichment` | data-service | V2. One row per public IP: first/last seen, `seen_in`, Cloudflare geo/ASN, blocklist hits, AbuseIPDB score. Deleted 180 d after `last_seen`. |
| `netmon.blocklist_snapshots` | data-service | V2. One row per fetch (`applied`/`unchanged`/`failed`). Retention 30 d, except snapshots still referenced by current entries. |
| `netmon.blocklist_entries` | data-service | V2. Current CIDRs per list; replaced per list only after a successful parse. No retention. |
| `netmon.lan_connection_snapshots` | data-service | V3. Peak TCP connections per 15-minute window, node, port, source and state; replaced per `(window_start, node)`. Retention 30 d. |
| `netmon.ufw_block_snapshots` | data-service | V3. UFW blocks per 15-minute bucket, node, source, port and protocol (a lower bound); replaced per `(window_start, node)`. Retention 90 d. |
| `netmon.ssh_auth_snapshots` | data-service | V3. sshd outcomes per 15-minute bucket, node and source; replaced per `(window_start, node)`. Retention 90 d. |
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
| `CLOUDFLARE_GRAPHQL_URL` | `https://api.cloudflare.com/client/v4/graphql` | GraphQL endpoint |
| `CLOUDFLARE_API_TOKEN` | empty | Analytics:Read token for zone furchert.ch. Empty = the Cloudflare collectors fail with `credentials` |
| `CLOUDFLARE_ZONE_ID` | empty | Zone tag. Empty = as above |
| `ABUSEIPDB_API_KEY` | empty | Empty = `reputation` disabled |
| `PROMETHEUS_URL` | `http://kube-prometheus-stack-prometheus.monitoring.svc.cluster.local:9090` | Prometheus base URL for `lan` |

| Property | Default | Purpose |
|---|---|---|
| `netmon.api.allowed-clients` | `[furchert-ch]` | Token `sub` values allowed on `/api/netmon/**` |
| `netmon.collectors.<name>.enabled` | `true` | Per-collector kill switch |
| `netmon.cloudflare.groups-page-size` / `firewall-page-size` | `5000` / `1000` | `limit` of queries A/B; lower them if the plan's `maxPageSize` is smaller |
| `netmon.cloudflare.firewall-max-pages` | `20` | Firewall pages per run |
| `netmon.cloudflare.max-queries-per-run` | `60` | Catch-up throttle for `cloudflare-requests` |
| `netmon.blocklists.spamhaus-drop-v4-url` / `firehol-level1-url` | the §4.4 URLs | Blocklist sources |
| `netmon.abuseipdb.daily-budget` / `per-run` | `200` / `10` | AbuseIPDB check budget |
| `netmon.retention.inbound-request-groups-days` | `90` | Retention (values < 1 fail startup) |
| `netmon.retention.firewall-events-days` | `180` | Retention |
| `netmon.retention.ip-enrichment-days` | `180` | Retention on `last_seen` |
| `netmon.retention.blocklist-snapshots-days` | `30` | Retention (referenced snapshots are kept) |
| `netmon.retention.lan-connection-snapshots-days` | `30` | Retention |
| `netmon.retention.ufw-block-snapshots-days` / `ssh-auth-snapshots-days` | `90` / `90` | Retention |
| `netmon.lan.max-windows-per-run` | `32` | Catch-up throttle for `lan` (the 48 h cap is 192 windows) |
| `netmon.scheduling.enabled` | `true` | Turns every `@Scheduled` trigger off (the test suite sets `false`) |

Kubernetes Secret `data-service-secrets` (ns `apps`, created by the `homelab` repo's playbook 59): keys `db-username`, `db-password`; NM-1 reads `cloudflare-api-token` → `CLOUDFLARE_API_TOKEN`, `cloudflare-zone-id` → `CLOUDFLARE_ZONE_ID` (both added by doemefu/homelab#116) and `abuseipdb-api-key` → `ABUSEIPDB_API_KEY` (only once the owner approves a key). All three `secretKeyRef`s are `optional: true`. NM-4 adds `auth-client-secret` (§9).
