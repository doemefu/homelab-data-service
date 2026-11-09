# Interfaces — homelab-data-service

The binding contract is `docs/060-network-monitoring.md` in the `homelab` repo ([link](https://github.com/doemefu/homelab/blob/main/docs/060-network-monitoring.md), added by doemefu/homelab#126). This file summarises what the current code implements. Section numbers (§) refer to that spec, except in sections 1b and 1c, where they refer to the web analytics contract `docs/100-web-analytics.md` in the same repo ([link](https://github.com/doemefu/homelab/blob/main/docs/100-web-analytics.md), rev 3.5 at 687d19f, added in WA-0, doemefu/homelab#349).

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

Top outbound TCP destinations per workload, aggregated from the hourly `egress_flow_snapshots` rows that overlap the window. Since data-service#57 the hourly rows are kept 8 days (staging: 30) and older days come from the daily aggregate `egress_flow_daily`:

- A window that starts at or after `now − 8 d` (the raw retention; one hour of grace for windows derived from a slightly earlier clock, such as furchert-ch's `now − 30 d`) reads the hourly rows only, exactly as before. The dashboard's 24 h and 7 d ranges are such windows, and during the staging override (30 d) every window that ends at about now, i.e. every dashboard range.
- An older window reads `egress_flow_daily` for every whole UTC day inside it up to the raw cut-off (`start of the current UTC day − 7 d`), and the hourly rows from the cut-off on. A partial first day, and a partial last day before the cut-off, are left out: windows older than the cut-off start at the next whole UTC day.
- Every field keeps its meaning: `node` = the node with the most hourly rows (ties by name), `fqdn` = the newest non-null, `firstSeenInWindow` = the earliest hourly window (never more than the overlapping hour before `from`), `isNew` = any of them new.

Default window 24 h. `scope` is `external` (default) or `all` (adds `pod`, `service`, `lan` and `loopback` destinations); anything else is 400 `invalid_parameter`. `namespace` and `workload` narrow by exact match. `limit` is a top-N limit (default 10, max 50).

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
- `node` is the most frequent node of the aggregated hourly rows. `firstSeenInWindow` is the start of the earliest aggregated hour, so it can be up to one hour before `from` (an hour overlapping the window).
- `isNew` is `true` if any aggregated hour was the first in 30 days in which this workload reached this destination and port (since data-service#57: not seen earlier the same UTC day in the hourly rows, nor in the `egress_flow_daily` rows of the previous 30 UTC days). The identity is namespace + workload + container, so rollouts do not re-report known destinations. The first hours after the collector starts report everything as new.
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

## 1b. Exposed: web analytics write API (WA-1, WA-5)

Contract: `docs/100-web-analytics.md` §6.1 to §6.3, §7.2, §7.9 (Epic doemefu/homelab#348, issue #53). Base URL (cluster-internal only): `http://data-service.apps.svc.cluster.local:8082/api/webanalytics`. The only caller is furchert-ch's server-side forward queue. WA-5 (#56) adds caps and 429, `DELETE /visitors/{id}` with tombstones, retention of `events` and the capped/today/table-bytes gauges (below and section 2). The read API is section 1c.

### Authentication and authorization (§6.1)

- `Authorization: Bearer <JWT>` from auth-service; signature, `exp` and `iss` validated as for netmon.
- Access requires scope `analytics:write` **and** a `sub` in `webanalytics.api.write-clients` (default `[furchert-ch]`) **and** no `ROLE_*` authority. auth-service puts `role` only into user-driven tokens, so the last rule is defence in depth against role-bearing user tokens (for example a username equal to a client id, or a user token that obtained the scope through an authorization-code flow). It is not a positive client-credentials check; that one belongs to doemefu/homelab-auth-service#143.
- A write token has no access to `/api/netmon/**` (403), and a `netmon:read` or `analytics:read` token has no access here (403).
- Token request (one token per scope, never combined):

  ```
  POST http://auth-service.apps.svc.cluster.local:8080/oauth2/token
  Authorization: Basic base64(furchert-ch:<client secret>)
  Content-Type: application/x-www-form-urlencoded

  grant_type=client_credentials&scope=analytics:write
  ```

### `POST /api/webanalytics/events` (§6.2)

Body: `{"events": [...], "edgeDrops": {...}}` with exactly the §6.2 shapes: 28 event fields and the nine `edgeDrops` keys (`bot`, `signed_in`, `consent`, `invalid`, `path`, `rate_limited`, `mint_limited`, `overflow`, `expired`). Every key must be present (`null` where absent); `events` may be empty.

| Status | When |
|---|---|
| 200 | Envelope valid: `{"accepted": n, "duplicates": n, "rejected": n, "capped": false}` |
| 429 (WA-5) | A cap would be exceeded: `{"accepted": 0, "duplicates": 0, "rejected": 0, "capped": true, "capScope": "hour" \| "day", "retryAt": "2026-11-09T13:00:00Z"}` (`application/json`, not a problem document); see "Caps" below |
| 400 `invalid_parameter` | Not JSON, unknown, duplicate or missing key, wrong JSON type (no coercion between numbers, strings and booleans), nesting > 8, a string > 512 characters, `events`/`edgeDrops` null, an `edgeDrops` count null or outside 0..1 000 000 |
| 401 / 403 | Token missing or invalid / scope, `sub` or `ROLE_*` rule not met |
| 405 `invalid_parameter` | Any method but `POST` (with a write token; without one 401) |
| 413 `invalid_parameter` | Body > 64 KiB (Content-Length, or counted while reading a chunked body), or more than 200 events (furchert-ch splits the batch on 413) |
| 415 `invalid_parameter` | Content type not `application/json` |
| 500 `internal` | Storage failure, or token validation impossible (JWKS unreachable) |

Every response, including 401/403/405, carries `Cache-Control: no-store` and `Pragma: no-cache`. Problem `detail` texts are fixed and never echo input.

**Per event** (a failing event is rejected alone; the batch still answers 200). Rules run in this order; the first failure is the reason (`webanalytics_events_rejected_total{reason}`):

| Reason | Rule |
|---|---|
| `shape` | `eventId` not a lowercase UUID v4; per-type null pattern violated (fields of another type set, required fields missing, `referrerDomain`/UTM on a non-landing pageview, enquiry with any non-core field); `clickTarget` set for `mailto`/`tel` or missing otherwise; `visitorId`/`pageviewId` not a lowercase UUID v4; malformed `referrerDomain`, UTM (incl. a run of ≥ 6 digits) or click target (`cta`/`link`/`faq` id pattern, `lang` `de`/`en`, `outbound` host) |
| `enum` | `type`, `locale`, `deviceClass`, `browserFamily`, `osFamily`, `viewport`, `clickKind`, `clickArea`, `goal` outside their vocabulary; `country` not `^[A-Z]{2}$` or `XX`/`T1` |
| `range` | `seq` not 0 (pageview) or 1..99 (engagement, click); `engagedMs` outside 0..1 800 000; `scrollMax` not a step; `sectionMask` outside 0..255 |
| `time` | `occurredAt`/`receivedAt` not ISO-8601 instants; `receivedAt` outside [now − 2 h, now + 1 min]; `occurredAt` outside [`receivedAt` − 15 min, `receivedAt`] |
| `path` | `path` or a `nav` target not matching `^/([a-z0-9][a-z0-9-]{0,63}(/[a-z0-9][a-z0-9-]{0,63}){0,2})?$` (max 200); `nav` also accepts `(other)` |
| `other` | `(pageviewId, seq)` already taken by another `eventId` (in the batch or stored) |

data-service checks the patterns of furchert-ch's registries (`TRACKED_PATHS`, `TRACK_IDS`), not the registries themselves. The canonical C13 fixtures are [`src/test/resources/webanalytics/c13-fixtures.json`](src/test/resources/webanalytics/c13-fixtures.json) (forwarded model, `<uuid>` and `now±Nm` tokens, one expected outcome per case); furchert-ch copies the file for its forward-model contract test.

**Storage and dedupe:** analytics events go to `webanalytics.events` with `INSERT … ON CONFLICT DO NOTHING` in one transaction per batch (`statement_timeout` 2 s). A known `eventId` (stored or earlier in the batch) counts as a duplicate. The validator is a strict superset of the table's CHECKs, because a CHECK violation would fail the whole batch and PostgreSQL would log the failing row (incl. the visitor id) in its server log; data-service itself logs only the exception class (`[webanalytics] batch write failed: <class>`).

**Enquiries:** an `enquiry` carries only `eventId`, `type`, `occurredAt`, `receivedAt`; any other non-null field rejects it (`shape`). A valid enquiry increments `daily_enquiries` for the Europe/Zurich day of `occurredAt` and `webanalytics_enquiries_total`; it never creates an `events` row and appears in none of the response counters. Its `eventId` is deduplicated in memory for 2 h (a repeat is ignored silently; the memory is lost on restart and valid for one replica). An invalid enquiry counts as `rejected`.

**Ingest statistics:** every batch adds its counts to `daily_ingest_stats` for the Europe/Zurich day of receipt: `accepted` and `duplicate` (reason `''`), `rejected` (the reason above) and `edge` (the `edgeDrops` key). Zero counts write no row; enquiries are not counted there.

**Caps (WA-5, §6.3, §6.4):**
- Counted are accepted **analytics** events (pageview, engagement, click). `goal` and `enquiry` are exempt, so a batch with only goals and enquiries (the consumer's priority-only batch while paused) is **always accepted**, also at a cap.
- Daily cap `webanalytics.ingest.daily-cap` (10 000) per UTC day and hourly cap `hourly-cap` (2 500) per UTC hour, both by the `received_at` of each event. Only the current UTC hour and day are enforced: an event whose `received_at` lies in an already ended window (possible for ≤ 2 h after a boundary) is charged there but cannot be capped.
- All-or-nothing: if the batch's analytics events would exceed a cap, the whole batch is answered 429 and **nothing** of it is stored or counted: no rows, no `accepted`/`duplicate`/`rejected` stats or metrics, and **its `edgeDrops` are not counted** (re-send them with the next accepted batch). The day cap is checked first; `capScope` names the window, `retryAt` its end (next full UTC hour, or 00:00 UTC), ISO-8601 in whole seconds ending in `Z`.
- The capped batch is recorded as `daily_ingest_stats(day, 'capped', 'hour'|'day', <analytics events of the batch>)` (Zurich day of receipt) in its own transaction; this is the source of `/coverage.dataQuality.cappedDays`.
- Per-visitor cap `visitor-daily-cap` (500) accepted analytics events per visitor id and UTC day: the excess is rejected individually (`visitor_cap`, the batch still answers 200); goals are exempt. Counts start at 0 at 00:00 UTC; an event received on the previous day is not checked.
- Caps of 0 are valid: every batch with analytics events gets the capped 429 (data-side stop; goal, enquiry and erasure keep working).
- The ledger is in memory (per pod), seeded at startup from `events.received_at` (per-visitor counts on first sight of an id), and checks and reserves under one lock, so concurrent batches cannot jointly exceed a cap. A failed startup seed counts from 0 and is retried once after 5 minutes. After a restart within a capped window the capped state is restored from the seeded counts.

**WA-5 reject reasons:** `erased` (the visitor id was erased within the last 2 h, any event type with a visitor id) and `visitor_cap` (above). Both count as `rejected` in the response, the metric and `daily_ingest_stats`, but they are not validation failures: `/coverage.dataQuality.secondLine` lists only the six validation reasons.

### `DELETE /api/webanalytics/visitors/{visitorId}` (WA-5, §6.5)

Same access as `POST /events` (`analytics:write`, `sub` in `write-clients`, no `ROLE_*`). Called synchronously by furchert-ch's forget handler (3 attempts, 3 s each).

| Status | When |
|---|---|
| 204 | Every `events` row of the id is deleted, also when nothing matched; repeated calls are idempotent |
| 400 `invalid_parameter` | The id is not a lowercase UUID v4 |
| 401 / 403 | As for `POST /events` |
| 405 | Any method but `DELETE` (with a write token) |
| 503 `internal` | Not completed within the per-request bound of 2.5 s, or the barrier lock / a statement timed out; deleted batches are committed, the tombstone already keeps new rows out, a retry finishes it |
| 500 `internal` | Any other storage failure (retryable) |

- Order: (1) the id is tombstoned for 2 h (events for it are rejected `erased`); (2) a barrier transaction takes the exclusive side of an advisory lock that every ingest batch holds shared, with `lock_timeout` 1 s, and commits at once: a batch that validated before the tombstone existed has committed before any row is deleted, and every later batch sees the tombstone; (3) deletes in batches of 5 000 rows (`ctid`, each its own transaction). The 2.5 s bound is checked before every batch, and each batch's `statement_timeout` is the remaining time (at most 2 s, at least 100 ms), so a response stays within about 2.5 s. The lock is not held across the deletes, so parallel erasures meet only at the barrier.
- Tombstones: hard cap 10 000 ids, oldest evicted (sustained abuse with more than 10 000 erasures in 2 h evicts older tombstones); a repeated erasure refreshes the entry; lost on restart (the furchert-ch queue purge covers the normal case).
- Problem bodies never contain the id: `instance` is the template `/api/webanalytics/visitors/%7BvisitorId%7D` (braces percent-encoded). Logs carry the row count only: `[webanalytics] visitor erased rows=<n>`.
- Erasure removes raw events only; the aggregates are id-free. Provisional days are recomputed without the erased events; final days keep their aggregates (freeze guard (d)). Erasure does not lower the cap counts or `eventsToday` until the next restart re-seeds them.

**Accepted residuals:** a batch that failed with 5xx and is retried reports its `edgeDrops` again unless furchert-ch zeroes them on retry (spec §6.2); two concurrent batches that reuse the same `(pageviewId, seq)` with different ids can classify the loser as a duplicate instead of `other`.

## 1c. Exposed: web analytics read API and rollup (WA-3a, WA-3b)

Contract: `docs/100-web-analytics.md` §7.5, §7.6, §7.8, §8.1-§8.5 (rev 3.5; issues #54, #55). Base URL (cluster-internal only): `http://data-service.apps.svc.cluster.local:8082/api/webanalytics`. The only caller is furchert-ch's `/dashboard/analytics` (WA-4a).

### Authentication and authorization (§8.1)

- `GET` only. Access requires scope `analytics:read` **and** a `sub` in `webanalytics.api.read-clients` (default `[furchert-ch]`) **and** no `ROLE_*` authority (defence in depth, as for writes). A write token, a `netmon:read` token or a role-bearing user token gets 403; other methods on a read path get 405; anonymous 401. Every response carries `Cache-Control: no-store` and `Pragma: no-cache`.
- Token request: `grant_type=client_credentials&scope=analytics:read` (one token per scope).

### Common rules (§8.1, rev 3.5)

| Item | Behaviour |
|---|---|
| Source | Aggregates only (`rollup_day`, the WA-3a and WA-3b tables, `daily_enquiries`, `daily_ingest_stats`); never `webanalytics.events` or `netmon.*` (source-scan test). Every aggregate query joins `rollup_day` on `final`, so a provisional day is never served, also an old one below `lastCompleteDay`. `daily_enquiries` is not rolled up: a day whose rollup is still provisional is hidden, a day without any `rollup_day` row counts |
| Range | `from`, `to` = Zurich dates (`YYYY-MM-DD`, inclusive); default `to` = `lastCompleteDay`, `from` = `to` − 27 d; `to` clamped to `lastCompleteDay`; span ≤ 366 d; malformed, `from > to` or too long → 400 `invalid_window`; `to < from` after clamping → 200 with an empty range |
| `range` object | `{"from", "to", "days", "lastCompleteDay", "previous": {"from", "to"} \| null}`; before the first final day (or when nothing is left after clamping) `from`/`to`/`previous` are `null`, `days` 0, lists empty, counts 0 |
| Compare | `compare=previous` (default; the same number of days immediately before, or the previous calendar month when the range is a whole month) or `none` (`range.previous` and `previous` `null`). After the `definitionFrom` clamp (`/funnels`, `/paths`, `/audience`, detail previous/next) the previous range can be shorter than the current one: its start is clamped while its end is kept; `range.previous` shows the real dates |
| Filters | `locale=de\|en` (entry locale), `device=desktop\|mobile\|tablet`; other values → 400 `invalid_parameter`; echoed as `"filters": {"applied": {…}, "ignored": [...]}` (`/status` ignores both) |
| Lists | `limit` default 20, 1-100 (else 400 `invalid_parameter`); the remainder is one `(other)` item, merged with stored `(other)` rows |
| Numbers | Counts only; rates, intervals and medians are computed by furchert-ch |

### Endpoints (§8.2)

| Endpoint | Content |
|---|---|
| `GET /status` | `collectors` (only `webanalytics-*`; `stale` after 26 h), `lastCompleteDay` (newest final day), `provisionalDay` (newest non-final day newer than it), `rawRetentionStart` (WA-5: `today − (webanalytics.retention.events-days − 1)`, the oldest Zurich day whose raw events are all retained, the freeze-guard (b) bound), `definitionVersion` (2 since WA-3b), `ingest {lastEventAt, eventsToday, dailyCap, capped}` from memory (`eventsToday` = stored pageview/engagement/click events with `received_at` in the current UTC day, the daily cap's count; WA-5: `dailyCap` = `webanalytics.ingest.daily-cap`, `capped` = the `webanalytics_ingest_capped` state) |
| `GET /overview` | `current`/`previous` visit counts plus `enquiriesTotal` (ignores filters), zero-filled `daily` series, `visitors` only without filters and for exactly 7 days (`7d`), 28 days (`28d`) or a whole calendar month (`month`), else `null` |
| `GET /pages` | Items per path with pageviews by page locale, visits viewed, entries, exits, landing engaged/converting visits, engagement samples, engaged-ms sum, 8-bucket histogram, scroll samples and reach |
| `GET /pages/detail?path=&limit=` | `path` required (locale-free canonical path, else 400); `summary`, `clicks` (for every page), a zero-filled `daily` series, `previous`/`next` `[{path, count}]` from `daily_transition` (incl. `(entrance)`/`(exit)`, top `limit` (default 5) + `(other)`; bounded by `definitionFrom` as `/funnels`, empty lists before it); `sections` and `faqOpens` only for `/automation` (else `null`) |
| `GET /clicks?kind=` | `kind` optional (one of the eight click kinds; missing = all); items with `target` (`null` for mailto/tel), `path`, `area`, `clicks`, `visits`, `convertingVisits`, `pageVisits` (visits viewing the path in the same range and filters). Remainder item (rev 3.5): `{"kind": null, "target": "(other)", "path": null, "area": null, …, "pageVisits": null}` (`kind` is the filtered kind when `kind` is set), `clicks`, `visits` and `convertingVisits` summed, merged with the stored outbound `(other)` rows; a `nav` `(other)` target stays its own item |
| `GET /sources` | All seven channels (zero-filled), referrers, campaigns, converters by landing channel, first-known channel (`null` when unknown) and entry path |
| `GET /funnels` (WA-3b) | `definitionVersion` (2), `definitionFrom` (first final day of the current definition after the newest final day of an older definition, so the range from it on is contiguous; `from` and the previous range are clamped to it, none → empty range), `funnels [{id: "offer" \| "contact_form", steps [{index, key, visits}], previous [...] \| null}]` with every step 0-filled. Steps count only in order (§8.5) |
| `GET /paths` (WA-3b) | `definitionFrom` (clamp as `/funnels`), `groupPaths [{path, visits, convertingVisits}]` (e.g. `home > offer > contact > (exit)`), `entryExit [{entry, exit, visits}]`, `seenBeforeContacting {intentVisits, items [{seen, visits}]}` (`about`, `projects`, `prices`, `none`, 0-filled; a visit counts once per value), `transitions [{fromPath, toPath, count}]`; lists with `limit` + `(other)`. `locale`/`device` are echoed under `applied` but only `transitions` honours them |
| `GET /audience` (WA-3b) | Filters ignored; `from` clamped to `definitionFrom` as `/funnels` (none → empty range). `newVsReturning {newVisits, returningVisits}`, `repeatOfferViewers {value, basis}` (`28d` for exactly 28 days, `month` for a whole calendar month, else both `null`), `deviceClass`, `entryLocale` and `dimensions {country, browserFamily, osFamily, viewport, visitorType}`, each `[{value, visits}]` (missing value → `null`; country top 50 per day + `(other)`). Dimensions carry no conversions; `daily_dimension` stores `visits` and `engaged_visits` |
| `GET /coverage` (WA-3b) | Filters ignored. `baselineFrom` = `webanalytics.coverage.go-live-date` (`null` while unset or in the future → nothing compared); `daily [{day, consentedPageviews, cloudflareRequests, sampled}]` (0-filled) and `byPage [{path, locale, consentedPageviews, cloudflareRequests, sampled}]` over `[max(from, baselineFrom, definitionFrom), to]` (no final day of the current definition → empty); `enquiries {consentedSubmits (Σ daily_submits), totalSubmits (Σ daily_enquiries)}` by submission day over the same window; `dataQuality {edge {…}, secondLine {…}, duplicates, cappedDays}` from `daily_ingest_stats` over `[from, to]`, every reason key 0-filled; `secondLine` holds the six validation reasons only (never `erased`/`visitor_cap`); `cappedDays` (WA-5) = Zurich receipt days in `[from, to]` with a capped batch (stage `capped`), 0 when none. `consentedPageviews` = Σ `daily_page.pageviews` (visit-start day, included visits), not `rollup_day.raw_pageviews`. The Cloudflare side includes bots and Next.js prefetch/RSC requests, so any ratio is only indicative |

### Rollup collector `webanalytics-rollup` (§7.5, §7.6)

- Runs at **01:12** and **07:30 Europe/Zurich** (documented exception to UTC crons) through the shared collector runner, so the two runs never overlap. **Ships disabled** (`netmon.collectors.webanalytics-rollup.enabled: false`, gate N1); it is listed in `/api/webanalytics/status`, never in `/api/netmon/status`.
- Each run plans its days from the clock and `rollup_day`: days missing since the first rolled day (oldest first, ≤ 31), closed days still provisional (≤ 31), and days with an outdated definition within 30 days (≤ 31); without any rolled day only yesterday. A day D closes at D + 1 07:15 Zurich (elapsed time: 06:15 on fall-back, 08:15 on spring-forward days); a rollup before the close writes `final = false`. Normally the 01:12 run writes yesterday provisional and the 07:30 run rewrites it final; on a spring-forward day the next 01:12 run finalises it.
- **Freeze guard (WA-5, §7.5):** a day is (re)computed only if (a) it is before today; (b) it is not older than `today − (webanalytics.retention.events-days − 1)` (the gap scan also starts there, so refused days never use up the per-run budget); (c) it is within 30 days or has no `rollup_day` row; (d) it is not a **final** day whose `rollup_day.raw_events > 0` while its raw events are now gone (read after the advisory lock in the day transaction). A day kept by (d) is skipped, the run continues and ends with warning `partial` "freeze guard kept n day(s)" (combined with any CF-baseline message). Provisional days are always recomputed, so an erased visitor's provisional day goes to zero and is finalised.
- Known difference from spec §7.5 (accepted, spec note pending): condition (d) applies only to final rows (WA-5 plan ruling R1).
- Per day one transaction: `pg_try_advisory_xact_lock(hashtext('webanalytics-rollup'))` (held elsewhere → the run stops with warning `partial`), `work_mem` 16 MB, `statement_timeout` 120 s, delete the day from every rollup table, batch insert, `rollup_day` (also for empty days), mark. `collector_state.last_window_end` = start of the day after the newest day of the all-final run from the first rolled day (display only). A failing day aborts the run (`internal`).
- Visit model: visits split at a gap > 30 min between pageviews/goals, at a landing with referrer or UTM, and after 6 h (continuous activity beyond 6 h is cut; accepted data-quality limit); engagement/click events of a known pageview join its visit regardless of the gap; same-path pageviews ≤ 30 s apart merge (engaged time = MAX per original pageview, summed); visits without a pageview are orphans; > 100 pageview events or > 500 events → excluded (counted only in `excluded_visits`). Engaged visit = ≥ 10 s, or ≥ 2 distinct pages, or a key event (`cta`/`mailto`/`tel` click, `/contact` pageview, form start, goal). Visitor figures (daily, 7/28-day, monthly) count distinct visitors of included visits starting in the window; a visitor is new on a day when one of its visits that day is new. Open dimensions keep the day's top 50 by visits and fold the rest into `(other)`.
- Rows are computed in Java and batch-inserted instead of `INSERT … SELECT` (documented deviation, spec §7.5); same write mode (delete + insert per day, one transaction).
- WA-3b (definition 2): per day also transitions (consecutive identical paths collapsed), funnels F1/F2 (greedy earliest match in order; section reach and form start take their pageview's start time, because snapshots arrive on navigation away or hide: a documented ordering limit), page-group paths (`home`, `offer`, `proof`, `personal`, `contact`, `other`; repeats collapsed; first 4 groups, then `…`, always `(exit)`), entry × exit, seen-before, dimensions (visits only) and `daily_submits` (goals by submission day). Days older than 30 days keep definition 1 without WA-3b rows.
- **Cloudflare baseline** (`daily_cf_baseline`): a cross-schema read of `netmon.inbound_request_groups` (`window_start`, `host`, `method`, `path`, `status`, `request_count`, `sampled`; never `client_ip`): host `furchert.ch`, `GET`, 2xx, page pattern with the `/de`/`/en` prefix as locale, `dashboard`/`imprint`/`privacy` excluded, grouped per Zurich day. Runs after the analytics days in its own, non-fatal transactions (a failure ends the run with warning `partial`, never aborts the rollup): a backfill of every day netmon holds except the oldest, partial one that has no baseline row yet (on an empty table the one-time pre-launch backfill), in per-day chunks: each day is its own statement and transaction under the advisory lock, re-checked as empty after the lock, so a timed-out day does not block the others (three failed days in a row stop the step and defer the rest) and the next run resumes with the days still missing (a day for which netmon yields no row is retried on every run, one bounded query each); then a replace for each rolled day, except a day whose backfill just failed in the same run, stopping after three failures in a row (warning `CF baseline refresh: n day(s) failed (…)[, remaining days skipped]`); a final day whose refresh failed or was skipped keeps its earlier rows and is not retried. Days older than netmon's retention (90 d) are never replaced, and a day netmon no longer holds keeps its stored rows. Hours without traffic are simply absent (accepted limit).
- Known difference from spec §7.8 and §8.2 (accepted, spec note pending, tracked in doemefu/homelab#348 (spec rev 3.7)): §7.8 describes a one-time backfill "when empty"; the backfill fills every missing day in per-day chunks on every run (identical on an empty table) and stops after three failed days in a row. §8.2 gives the `/coverage` window as `[max(from, baselineFrom), to]`; it is `[max(from, baselineFrom, definitionFrom), to]` and empty without a final day of the current definition.
- Known difference from spec §7.5 "First run" (accepted, spec note pending, tracked in doemefu/homelab#348 (spec rev 3.7)): a very first run at 07:30 on a spring-forward day writes yesterday as provisional instead of nothing; provisional days are never served and the next 01:12 run finalises it.

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
| `webanalytics_events_accepted_total` | counter | — | Analytics events stored (enquiries excluded) |
| `webanalytics_events_duplicate_total` | counter | — | Analytics events whose `eventId` was already stored or earlier in the batch |
| `webanalytics_enquiries_total` | counter | — | `daily_enquiries` increments |
| `webanalytics_events_rejected_total` | counter | `reason` (`shape`, `enum`, `range`, `time`, `path`, `other`; WA-5 `erased`, `visitor_cap`) | Rejected events, enquiries included; `erased`/`visitor_cap` are not validation failures (the `WebAnalyticsSecondLineRejects` rule excludes them) |
| `webanalytics_edge_dropped_total` | counter | `reason` (the nine `edgeDrops` keys) | Events furchert-ch dropped before forwarding, as reported per accepted batch (not counted for a capped 429) |
| `webanalytics_ingest_capped` | gauge | — | WA-5. 1 from a capped 429 until its `retryAt`, else 0. Evaluated at scrape time: it **returns to 0 at `retryAt` without any traffic** (it does not wait for the next batch). Restored after a restart when the seeded counts are at a cap. Registered at startup with 0 |
| `webanalytics_events_today` | gauge | — | WA-5. Accepted analytics events with `received_at` in the current UTC day (= `/status` `eventsToday`, the daily cap's count); resets at 00:00 UTC. An erasure does not lower it until the next restart re-seeds it |
| `webanalytics_raw_table_bytes` | gauge | — | WA-5. `pg_total_relation_size('webanalytics.events')` (heap + indexes + TOAST), measured at startup and every 15 min (:03/:18/:33/:48 UTC). **Absent until the first successful measurement**, then keeps its last value if a measurement fails. Steady state at the caps (396 days, ≈ 3.96 M rows) is estimated at about 2.0–2.2 GB with indexes; the alert threshold and PVC sizing are decided in doemefu/homelab#351 and #57 |

The `webanalytics_*` counters are recorded after the batch's transaction commits and are exported with 0 from startup (all eight reject reasons, incl. `erased` and `visitor_cap`). The rollup's `netmon_collector_last_success_timestamp_seconds{collector="webanalytics-rollup"}` series exists only while the collector is enabled (gate N1); both daily runs update it.

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
| `netmon.egress_flow_snapshots` | data-service | V4. Outbound TCP flows per hour, node, `container_id`, destination and post-NAT destination: bytes, connects, failed connects, FQDN, scope, `is_new`; replaced per `window_start`, at most 2 000 rows per hour. `destination_ip` is null for name-only destinations; `destination_host` (generated) is the IP or the name. Retention 8 d (staging override 30 d), and only for UTC days that already have `egress_flow_daily` rows (data-service#57); the `(destination_ip)` index stays. |
| `netmon.egress_flow_daily` | data-service | V9 (data-service#57). Daily aggregate of `egress_flow_snapshots` per UTC day and `(workload_key, destination_host, destination_port, destination_scope, node)`: sums, `first_window`, newest `fqdn`/`fqdn_window`, `is_new` (any), `hourly_rows`, `computed_at`. DDL only in V9; data-service recomputes a day with every window replace and repairs missing or stale days at the start of each `egress` run (no window is collected while a day of the 30-day `is_new` look-back lacks daily rows). Retention 32 d. |
| `netmon.login_events` | data-service | V5. Form-login attempts pulled from the auth-service outbox: outcome, client IP and its source, username HMAC, subject (success only), user agent; upsert do-nothing on `event_id`. Retention 180 d. |
| Schema `webanalytics` | data-service | V6 (docs/100 §7.1); `spring.flyway.schemas: public,netmon,webanalytics` |
| `webanalytics.events` | data-service | V6. Accepted pageview, engagement, click and goal events with the pseudonymous `visitor_id` (never leaves data-service); `INSERT … ON CONFLICT DO NOTHING` on `event_id` and `(pageview_id, seq)`; shape, vocabulary, range and time CHECKs. WA-5: deleted 396 days after `occurred_at` (`webanalytics.retention.events-days`, at least 370) by the 03:30 UTC retention job (log `[retention] webanalytics.events deleted=<n>`); erasure by visitor id via `DELETE /visitors/{visitorId}`. |
| `webanalytics.daily_ingest_stats` | data-service | V6. Id-free counts per Zurich day, `stage` and `reason`; upsert-increment at ingest; never touched by the rollup, no retention. WA-5 adds the stage `capped` (reason `hour`/`day`) and the `rejected` reasons `erased`, `visitor_cap` (no migration: the CHECKs are format-only). |
| `webanalytics.daily_enquiries` | data-service | V6. Id-free count of successful contact submissions per Zurich day; increment-only, no retention. |
| `webanalytics.rollup_day` | data-service | V7. One row per rolled-up day: raw events/pageviews of the Zurich day, visits, orphan visits, `definition_version`, `final`, `rolled_up_at` |
| `webanalytics.daily_transition`, `daily_funnel`, `daily_group_path`, `daily_entry_exit`, `daily_intent_seen`, `daily_dimension`, `daily_cf_baseline`, `daily_submits` | data-service | V8 (WA-3b). Id-free (no uuid, IP or visitor/visit id; `daily_dimension` without conversions); replaced per day by the rollup (`daily_cf_baseline` from netmon, see section 1c); no retention |
| `webanalytics.daily_visits`, `daily_visitors`, `monthly_visitors`, `daily_page`, `daily_section`, `daily_click`, `daily_source`, `daily_conversion` | data-service | V7. Id-free aggregates (no uuid or visitor/visit id column), keyed by entry locale and device class where visit-scoped; replaced per day by the rollup; no retention |
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
| `netmon.retention.egress-flow-snapshots-days` | `8` | Retention of the hourly egress rows (≥ 2; only days with daily rows are deleted). Staging: `NETMON_RETENTION_EGRESSFLOWSNAPSHOTSDAYS=30` in `k8s/deployment.yaml` until the daily parity is checked (data-service#57) |
| `netmon.retention.egress-flow-daily-days` | `32` | Retention of `egress_flow_daily` (≥ `egress-flow-snapshots-days` + 2) |
| `netmon.egress.max-windows-per-run` | `12` | Catch-up throttle for `egress` (the 48 h cap is 48 windows) |
| `netmon.retention.login-events-days` | `180` | Retention |
| `netmon.auth-service.page-size` / `max-pages` | `500` / `10` | Outbox `limit` (1..1000) and pages per run |
| `netmon.egress.max-rows-per-window` | `2000` | Row cap per hour, kept by bytes sent then connects; exceeding it reports `truncated` |
| `netmon.egress.pod-cidr` / `service-cidr` / `lan-cidr` | `10.42.0.0/16` / `10.43.0.0/16` / `192.168.1.0/24` | Destination scope `pod` / `service` / `lan`; loopback is `loopback`, everything else `external` |
| `netmon.scheduling.enabled` | `true` | Turns every `@Scheduled` trigger off (the test suite sets `false`) |
| `webanalytics.api.write-clients` | `[furchert-ch]` | Token `sub` values allowed on `POST /api/webanalytics/events`; empty admits nobody |
| `webanalytics.coverage.go-live-date` | unset (empty entry in `application.yaml`) | First day of tracked traffic (`/coverage.baselineFrom`); set in WA-6 with the env var `WEBANALYTICS_COVERAGE_GOLIVEDATE` (`GoLiveDateBindingTest`) |
| `webanalytics.api.read-clients` | `[furchert-ch]` | Token `sub` values allowed on `GET /api/webanalytics/*`; empty admits nobody |
| `netmon.collectors.webanalytics-rollup.enabled` | `false` (set explicitly in `application.yaml`) | Rollup kill switch; enabled only after gate N1 with the env var `NETMON_COLLECTORS_WEBANALYTICS_ROLLUP_ENABLED=true`, which binds only because the YAML entry exists (DEPLOYMENT.md "WA-3a rollout") |
| `webanalytics.timezone` | `Europe/Zurich` | Day boundary of `daily_ingest_stats` and `daily_enquiries` |
| `webanalytics.ingest.daily-cap` / `hourly-cap` / `visitor-daily-cap` | `10000` / `2500` / `500` | WA-5 caps (≥ 0; 0 caps every batch with analytics events); env `WEBANALYTICS_INGEST_DAILYCAP`, `…_HOURLYCAP`, `…_VISITORDAILYCAP` (`WebAnalyticsCapsBindingTest`) |
| `webanalytics.retention.events-days` | `396` | WA-5 raw-event retention; below 370 the application does not start; env `WEBANALYTICS_RETENTION_EVENTSDAYS`. Also the freeze-guard (b) bound and `/status.rawRetentionStart` |

Kubernetes Secret `data-service-secrets` (ns `apps`, created by the `homelab` repo's playbook 59): keys `db-username`, `db-password`; NM-1 reads `cloudflare-api-token` → `CLOUDFLARE_API_TOKEN`, `cloudflare-zone-id` → `CLOUDFLARE_ZONE_ID` (both added by doemefu/homelab#116) and `abuseipdb-api-key` → `ABUSEIPDB_API_KEY` (optional; set via playbook 59, doemefu/homelab#146; the reputation collector is disabled while the key is absent). All three `secretKeyRef`s are `optional: true`. NM-4 adds `auth-client-secret` (§9).
