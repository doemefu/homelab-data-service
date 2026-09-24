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

Collector freshness for the UI's honest-fallback banner. One element per registered collector, sorted by name: `blocklists`, `cloudflare-firewall`, `cloudflare-requests`, `egress`, `lan`, `login-events`, `reputation`, `retention`.

```json
{ "collectors": [ { "name": "cloudflare-requests", "enabled": true, "lastSuccessAt": "2026-09-23T10:30:02Z",
                    "lastWindowEnd": "2026-09-23T10:00:00Z", "consecutiveFailures": 0, "lastErrorCode": null,
                    "stale": false } ] }
```

- `enabled` reflects the kill switch `netmon.collectors.<name>.enabled` (default `true`). It is also `false` for a collector that lacks required configuration: `reputation` while `ABUSEIPDB_API_KEY` is unset.
- `lastErrorCode` is `null`, `credentials`, `rate_limited`, `upstream`, `truncated`, `partial` or `internal`; never a message. `truncated` is a warning on a successful run (a window exceeded the Cloudflare page limit), so it can appear with `consecutiveFailures: 0`. The same holds for `lan` with `upstream` and `consecutiveFailures: 0`: the run worked, but either no node exposes the NM-3 metrics at the latest evaluation point (the `netmon_node` role is not rolled out, or node-exporter scraping is broken), or an expected node never published a window the run finalised (its script is failing). `egress` reports `upstream` with `consecutiveFailures: 0` while no coroot-node-agent publishes connect series at the latest evaluation point (the DaemonSet is gated off, or not scraped), and `truncated` when a window hit `netmon.egress.max-rows-per-window` or the bytes-sent query returned its full `topk` of 500 series (flows outside it then show 0 bytes sent). A real Prometheus outage is a failure (`consecutiveFailures` > 0). `login-events` reports `upstream` with `consecutiveFailures: 0` while auth-service's outbox is disabled (503, "no data yet"), and `partial` when a run skipped outbox rows that violate the `login_events` contract (the message carries only the count). Consumers show an unknown code as a generic warning.
- `lastWindowEnd` is the collector's high-water mark: for `cloudflare-requests` the end of the newest contiguous final hour, for `cloudflare-firewall` the `until` of the last complete run, for `lan` the end of the newest contiguous final 15-minute window (see DEPLOYMENT "NM-3 rollout" for when a window is final), for `egress` the end of the newest collected hour, for `login-events` the start of the last run that drained the outbox minus auth-service's 10 s settle window (completeness follows the outbox id order, so the mark keeps moving on days without logins).
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
  "logins": {"success": 0, "failure": 0, "locked": 0}, "lan": {"ufwBlocks": 0, "sshFailed": 0} }
```

- `abuseIpDb` is `{score, reports, checkedAt}` or `null` if never checked.
- `firewallEvents` holds the last 20 events of the window in the firewall-events item shape.
- `lan` (NM-3) sums, within the window, the UFW blocks from this IP (`ufwBlocks`) and its unsuccessful SSH attempts (`sshFailed` = `failed` + `invalid_user`). A LAN-only IP is private and therefore not found here; public attackers reach `ip_enrichment` through the LAN collector as well (`seenIn` contains `lan`).
- `logins` (NM-4) counts this IP's login events in the window by outcome (zeros when it never logged in). Private addresses (e.g. `remote-addr` of an in-cluster caller) are not enriched and therefore 404 here.

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

### `GET /api/netmon/egress/top?from&to&scope&namespace&workload&limit` (§7.2, NM-2)

Top outbound TCP destinations per workload, aggregated from the hourly `egress_flow_snapshots` rows that overlap the window. Default window 24 h. `scope` is `external` (default) or `all` (adds `pod`, `service`, `lan` and `loopback` destinations); anything else is 400 `invalid_parameter`. `namespace` and `workload` narrow by exact match. `limit` is a top-N limit (default 10, max 50).

```json
{ "items": [ {"namespace": "apps", "workload": "litellm", "container": "litellm", "node": "mba1",
              "destinationIp": "34.117.59.81", "destinationPort": 443, "fqdn": "api.example.com", "scope": "external",
              "bytesSent": 301, "bytesReceived": 3001, "connects": 4, "failedConnects": 1,
              "firstSeenInWindow": "2026-09-24T08:00:00Z", "isNew": true} ] }
```

- One item per `(namespace, workload, container, destination, destinationPort)`; items are ordered by `bytesSent + bytesReceived` descending. A Deployment rollout does not split an item: `workload` is the pod name without its ReplicaSet/DaemonSet suffix (`litellm-5d8f7c9b6-x2k9p` → `litellm`); StatefulSet pods keep their name; CronJob pods carry the CronJob name.
- Host processes (systemd units such as k3s) have `namespace` and `workload` `null`, and `container` is the unit name (`k3s.service`).
- `destinationIp` is the post-NAT IP literal (RFC 5952). **Name-only destinations:** coroot reports an external destination whose name resolves to several external IPs (typical for SaaS APIs and CDNs) by name only, without an IP. For such an item `destinationIp` carries that name and `fqdn` the same name. Consumers must not assume `destinationIp` is an IP literal.
- `fqdn` is otherwise the name coroot saw in DNS answers for the IP (`ip_to_fqdn`, newest row that has one), or `null`.
- `node` is the most frequent node of the aggregated rows. `firstSeenInWindow` is the start of the earliest hour inside the window with this item.
- `isNew` is `true` if any aggregated hour was the first in 30 days in which this workload reached this destination and port. The identity is namespace + workload + container, so rollouts do not re-report known destinations. The first hours after the collector starts report everything as new.
- Counters are `increase()` over each hour: an hour in which a destination's counter series first appears is a lower bound, and a destination seen only by the presence query has zero counters in that hour. `failedConnects` of a Service with several backends forms its own item with the Service IP (`scope` `service`, only with `scope=all`).

### `GET /api/netmon/logins/summary?from&to&limit` (§7.2, NM-4)

Form-login outcomes from auth-service (`login_events`, `occurred_at` in `[from, to)`). Default window 24 h. `limit` is the top-N limit of `byIp` and `bySubject` (default 10, max 50).

```json
{ "totals": {"success": 2, "failure": 4, "locked": 1},
  "byIp": [ {"ip": "203.0.113.7", "success": 0, "failure": 2, "locked": 1, "country": "DE", "blocklisted": true, "abuseScore": 87} ],
  "bySubject": [ {"subject": "dominic", "success": 1, "failureSameHmac": 1} ],
  "timeline": [ {"bucketStart": "2026-09-24T08:00:00Z", "success": 0, "failure": 2, "locked": 1} ] }
```

- `byIp` lists IPs with the most unsuccessful attempts (`failure` + `locked`) first, then by total, then by IP. Events without a client IP are counted in `totals` but not listed. `country`, `blocklisted` and `abuseScore` come from `ip_enrichment`, so a private IP has `null`/`false`/`null`.
- `bySubject` has one row per account that either logged in successfully in the window or was the target of failures in the window. `failureSameHmac` counts `failure` events (not `locked`) whose username HMAC equals an HMAC seen on any retained success of that subject, so failed attempts against a known account show up even if the account did not log in during the window. Attempted usernames are never stored. Ordered by `failureSameHmac`, then `success`, both descending.
- `timeline` has buckets with data only, 1 h up to a 7-day window, otherwise 1 d (UTC), the same rule as the inbound timeline.
- Rotating auth-service's `LOGIN_EVENT_HMAC_KEY` breaks the HMAC match: for up to the 180 d retention, failures after a rotation match only successes recorded after it.

### `GET /api/netmon/logins/events?from&to&outcome&ip&limit&cursor` (§7.2, NM-4)

```json
{ "items": [ {"occurredAt": "2026-09-24T09:20:00Z", "outcome": "failure", "clientIp": "203.0.113.8",
              "ipSource": "cf-connecting-ip", "subject": null, "usernameHmacPrefix": "3fa9c1d2", "userAgent": null,
              "country": null, "blocklisted": false} ],
  "nextCursor": null }
```

- Items are ordered by `occurredAt` descending, with opaque cursor paging (`limit` default 50, max 500) like the firewall events.
- `outcome` filters by `success`, `failure` or `locked`; anything else is 400 `invalid_parameter`. `ip` must be an IP literal.
- `country` and `blocklisted` are `null`/`false` when `clientIp` is null or private (private IPs are never enriched, so `/ips/{ip}` answers 404 for them).
- `clientIp` is `null` when auth-service saw no valid IP. `ipSource` is `cf-connecting-ip` or `remote-addr`; both are header-derived and spoofable in-cluster (docs/060 §10). `subject` is set only for `success`.
- Only the first 8 hex characters of the username HMAC are served; the full HMAC never leaves the database.

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
| `netmon_collector_last_success_timestamp_seconds` | gauge | `collector` | Unix time of the collector's last successful run; **NaN until the first success** (§4.1). Registered at startup only for collectors that are enabled and able to succeed. No series is exported for a collector whose kill switch is off, for `reputation` without `ABUSEIPDB_API_KEY`, or for `cloudflare-requests`/`cloudflare-firewall` started without `CLOUDFLARE_API_TOKEN`/`CLOUDFLARE_ZONE_ID`, or for `login-events` started without `AUTH_CLIENT_SECRET`. Those three still run and report `credentials` in `/status`. |

The `NetmonCollectorStale` rule is owned by the infra repo (doemefu/homelab#116, PR #131). It treats a NaN sample as "never succeeded" once the pod (`kube_pod_start_time`) is older than the collector's threshold. The thresholds are 26 h for `blocklists`/`retention`, 3 h for `egress`, 90 min for `lan`/`reputation`, and 15 min for every other collector (the 5-minute collectors `cloudflare-requests`/`cloudflare-firewall` and the 1-minute `login-events`). Because unconfigured collectors export no series, the rule never fires for a collector that is off on purpose.

## 3. Consumed

| Service | Address | Use |
|---|---|---|
| PostgreSQL | `postgresql.apps.svc.cluster.local:5432`, DB `data_service`, role `data_service` | The netmon store; Flyway migrations on startup |
| auth-service JWKS | `http://auth-service.apps.svc.cluster.local:8080/oauth2/jwks` | Token signature keys (fetched on the first request, then cached) |
| Cloudflare GraphQL Analytics | `https://api.cloudflare.com/client/v4/graphql` (`api.cloudflare.com:443`) | `cloudflare-requests` (query A, `httpRequestsAdaptiveGroups`, every 5 min at :00) and `cloudflare-firewall` (query B, `firewallEventsAdaptive`, every 5 min at :30) for zone `CLOUDFLARE_ZONE_ID`, `Authorization: Bearer $CLOUDFLARE_API_TOKEN` (§4.2). Probe limits (2026-09-24, both datasets): 31 d history, 30 d per query, 10 000 rows per page, 40 fields. Normal load 3 queries per 5 min, capped at 60 per run during catch-up. |
| Spamhaus DROP v4 | `https://www.spamhaus.org/drop/drop_v4.json` (`www.spamhaus.org:443`) | `blocklists`, daily 05:00 UTC. NDJSON; Spamhaus sends no ETag, so `unchanged` is detected by sha256. |
| FireHOL level1 | `https://raw.githubusercontent.com/firehol/blocklist-ipsets/master/firehol_level1.netset` (`raw.githubusercontent.com:443`) | `blocklists`, daily 05:00 UTC, `If-None-Match` with the stored ETag. Private/bogon ranges in the list are dropped. |
| AbuseIPDB | `https://api.abuseipdb.com/api/v2/check` (`api.abuseipdb.com:443`) | `reputation`, every 30 min, **only when `ABUSEIPDB_API_KEY` is set**. The key is optional and set via playbook 59 (doemefu/homelab#146); the collector is disabled while the key is absent. ≤ 10 checks per run, ≤ 200 per UTC day; only public, non-blocklisted IPs that crossed a §4.5 threshold. |
| Prometheus | `http://kube-prometheus-stack-prometheus.monitoring.svc.cluster.local:9090` (`PROMETHEUS_URL`, cluster-internal, no auth) | `lan` (NM-3), every 15 min at :04/:19/:34/:49 UTC: `POST /api/v1/query` (form fields `query`, `time`), 10 s timeouts. Reads the node-script metrics `homelab_lan_connections`, `homelab_ufw_blocks_bucket`, `homelab_sshd_auth_bucket`, `homelab_netmon_bucket_end_timestamp_seconds` and `homelab_netmon_last_success_timestamp_seconds` (§5.2, produced by the `netmon_node` role from doemefu/homelab#117). Five instant queries per window (§4.6), plus up to three for a retry. `egress` (NM-2), hourly at :07 UTC, same API: six instant queries per completed hour at `time` = the hour's end (§4.6: `increase(...[1h])` of `container_net_tcp_bytes_sent_total` (top 500), `..._bytes_received_total`, `..._successful_connects_total`, `..._failed_connects_total`, `last_over_time(ip_to_fqdn[1h])` and the series-presence query `group by (…) (last_over_time(container_net_tcp_successful_connects_total[1h]))`), plus one agent-presence query per run. The metrics come from the coroot-node-agent DaemonSet (doemefu/homelab#118, §6); without running agents the collector succeeds with an `upstream` warning. |
| auth-service login-event outbox | `http://auth-service.apps.svc.cluster.local:8080` (`AUTH_TOKEN_URL`, `AUTH_SERVICE_URL`, cluster-internal) | `login-events` (NM-4), every minute at :15 s UTC (§7.6). A `client_credentials` token for client `data-service` (`AUTH_CLIENT_ID`/`AUTH_CLIENT_SECRET`, HTTP Basic with form-urlencoded id and secret) with `scope=login-events:read`, cached until 60 s before `expires_in`. Then `GET /api/v1/login-events?after=<cursor>&limit=500` while `hasMore`, at most 10 pages per run. The cursor is the producer's `nextAfter`, kept in `collector_state.cursor`. Status mapping: blank secret, token 400/401 and page 403 give `credentials`; a page 401 is retried once with a fresh token and is `credentials` only if rejected again; 401 and 403 drop the cached token. 429 gives `rate_limited`; other errors give `upstream`. Unlike the other collectors, `login-events` also backs off after `credentials`, so a wrong secret does not produce a failed client authentication in auth-service every minute. A 503 means the producer's feature is disabled, and the run succeeds with an `upstream` warning ("no data yet"). |

The four destinations on the internet (Cloudflare, Spamhaus, GitHub, AbuseIPDB) are data-service's only outbound destinations outside the cluster (§10); blocklists are fetched only by data-service.

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
| `netmon.egress_flow_snapshots` | data-service | V4. Outbound TCP flows per hour, node, `container_id`, destination and post-NAT destination: bytes, connects, failed connects, FQDN, scope, `is_new`; replaced per `window_start`, at most 2 000 rows per hour. `destination_ip` is null for name-only destinations; `destination_host` (generated) is the IP or the name. Retention 30 d. |
| `netmon.login_events` | data-service | V5. Form-login attempts pulled from the auth-service outbox: outcome, client IP and its source, username HMAC, subject (success only), user agent; upsert do-nothing on `event_id`. Retention 180 d. |
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
| `PROMETHEUS_URL` | `http://kube-prometheus-stack-prometheus.monitoring.svc.cluster.local:9090` | Prometheus base URL for `lan` and `egress` |
| `AUTH_TOKEN_URL` | `http://auth-service.apps.svc.cluster.local:8080/oauth2/token` | auth-service token endpoint for `login-events` |
| `AUTH_SERVICE_URL` | `http://auth-service.apps.svc.cluster.local:8080` | auth-service base URL; the outbox is `/api/v1/login-events` |
| `AUTH_CLIENT_ID` | `data-service` | OAuth2 client for the outbox |
| `AUTH_CLIENT_SECRET` | empty | Plain client secret (Secret key `auth-client-secret`). Empty = `login-events` fails with `credentials` |

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
| `netmon.retention.egress-flow-snapshots-days` | `30` | Retention |
| `netmon.egress.max-windows-per-run` | `12` | Catch-up throttle for `egress` (the 48 h cap is 48 windows) |
| `netmon.retention.login-events-days` | `180` | Retention |
| `netmon.auth-service.page-size` / `max-pages` | `500` / `10` | Outbox `limit` (1..1000) and pages per run |
| `netmon.egress.max-rows-per-window` | `2000` | Row cap per hour, kept by bytes sent then connects; exceeding it reports `truncated` |
| `netmon.egress.pod-cidr` / `service-cidr` / `lan-cidr` | `10.42.0.0/16` / `10.43.0.0/16` / `192.168.1.0/24` | Destination scope `pod` / `service` / `lan`; loopback is `loopback`, everything else `external` |
| `netmon.scheduling.enabled` | `true` | Turns every `@Scheduled` trigger off (the test suite sets `false`) |

Kubernetes Secret `data-service-secrets` (ns `apps`, created by the `homelab` repo's playbook 59): keys `db-username`, `db-password`; NM-1 reads `cloudflare-api-token` → `CLOUDFLARE_API_TOKEN`, `cloudflare-zone-id` → `CLOUDFLARE_ZONE_ID` (both added by doemefu/homelab#116) and `abuseipdb-api-key` → `ABUSEIPDB_API_KEY` (optional; set via playbook 59, doemefu/homelab#146; the reputation collector is disabled while the key is absent). All three `secretKeyRef`s are `optional: true`. NM-4 adds `auth-client-secret` (§9).
