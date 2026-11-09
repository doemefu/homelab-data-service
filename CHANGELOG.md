# Changelog

All notable changes to homelab-data-service. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Security
- Spring Boot 4.1.1 → 4.1.2 (Jackson 2.21.7/3.1.7 fixes, Tomcat 11.0.27, PostgreSQL driver 42.7.14); the `tomcat.version` override is removed.
- Deployment hardening (#24): `runAsNonRoot` (uid 100 / gid 101), `RuntimeDefault` seccomp, no privilege escalation, all capabilities dropped, read-only root filesystem with an `emptyDir` at `/tmp`.
- emSign ECC Root CA – G3, emSign Root CA – C1 and emSign ECC Root CA – C3 (eMudhra compromise) are not trusted: a temporary build step deleted them from the JRE cacerts (#38, #39; doemefu/homelab#212) until Temurin 25.0.5+8, whose cacerts no longer contain them; the step is removed (doemefu/homelab#212).

### Added
- WA-3b (#55, docs/100 §7.5-§8.5, rev 3.5): Flyway `V8__webanalytics_path_aggregates` with `daily_transition`, `daily_funnel`, `daily_group_path`, `daily_entry_exit`, `daily_intent_seen`, `daily_dimension` (visits and engaged visits, no conversions), `daily_cf_baseline` (from netmon request groups, no IP) and `daily_submits`; rollup definition 2 (the last 30 days are recomputed).
- Rollup: ordered funnels F1/F2, page-group paths, transitions, entry × exit, seen-before, audience dimensions, submits by submission day; Cloudflare baseline in a separate non-fatal step with a one-time backfill.
- Read API `GET /api/webanalytics/funnels`, `/paths`, `/audience`, `/coverage` (`webanalytics.coverage.go-live-date`), and `previous`/`next` in `/pages/detail`; `/funnels`, `/paths`, `/audience` and previous/next are bounded by `definitionFrom`.
- WA-3a follow-ups: bounded startup seed query, `EXISTS` semi-join for returning visitors, `/clicks` remainder (`kind` null or the filtered kind, `target` `(other)`) merged with stored outbound `(other)` rows, provisional days hidden from the enquiry series.
- WA-3a (#54, docs/100 §7.5-§8.4, rev 3.4): Flyway `V7__webanalytics_core_aggregates` with `rollup_day` (incl. `final`) and the id-free tables `daily_visits`, `daily_visitors`, `monthly_visitors`, `daily_page`, `daily_section`, `daily_click`, `daily_source`, `daily_conversion`.
- Collector `webanalytics-rollup` (01:12 and 07:30 Europe/Zurich, one runner lock, **disabled by default**): clock-derived day planning (gap scan, provisional/final with the elapsed-time close at D + 1 07:15, definition backfill), per-day transaction with advisory lock, Java visit model (gap, landing split, 6 h cut, 30 s merge, orphans, exclusion), visitor uniques from the visit model, top-50 `(other)` folding.
- Read API `GET /api/webanalytics/status`, `/overview`, `/pages`, `/pages/detail`, `/clicks`, `/sources` behind `analytics:read` + `webanalytics.api.read-clients` + no `ROLE_*`; final days only; ranges, compare, filters echo, limits.
- `/api/netmon/status` lists only the `netmon` collector group; collectors gain `group()` and `staleAfter()` (netmon behaviour unchanged).
- In-memory ingest activity (`lastEventAt`, `eventsToday`) for `/status`, seeded at startup.
- WA-1 (#53, docs/100 §6, §7.1, §7.2, §7.9): Flyway `V6__webanalytics_ingest` with the schema `webanalytics` and the tables `events`, `daily_ingest_stats` and `daily_enquiries` (shape, vocabulary, range and time CHECKs; `UNIQUE (pageview_id, seq)`).
- `POST /api/webanalytics/events` for furchert-ch's forward queue: own security chain (`analytics:write` + `sub` in `webanalytics.api.write-clients` + no `ROLE_*` authority), 64 KiB body cap incl. chunked bodies, ≤ 200 events (413), strict JSON (unknown, duplicate or missing keys, coercion, depth > 8, strings > 512 → 400), per-event validation with reasons `shape`/`enum`/`range`/`time`/`path`/`other`, dedupe on `eventId`, `(pageview_id, seq)` reuse rejected, response `{accepted, duplicates, rejected, capped}`.
- Enquiry counter: `enquiry` events increment `daily_enquiries` (Europe/Zurich day of `occurredAt`) behind an in-memory 2 h dedupe; no `events` row, not in the response counters.
- `daily_ingest_stats`: accepted, duplicate, rejected and edge-drop counts per Europe/Zurich day of receipt.
- Metrics `webanalytics_events_accepted_total`, `webanalytics_events_duplicate_total`, `webanalytics_enquiries_total`, `webanalytics_events_rejected_total{reason}`, `webanalytics_edge_dropped_total{reason}`.
- Properties `webanalytics.api.write-clients` (`[furchert-ch]`) and `webanalytics.timezone` (`Europe/Zurich`); `spring.flyway.schemas` gains `webanalytics`.
- NM-4 (#17): Flyway `V5__netmon_login_events` with `login_events` (docs/060 §3.3): upsert do-nothing on `event_id`, CHECKs for outcome, IP source, HMAC format and "subject only on success".
- Collector `login-events`: every minute pulls the auth-service login-event outbox (`GET /api/v1/login-events`) with a cached `client_credentials` token (`scope=login-events:read`), pages while `hasMore` (max 10 pages per run), keeps the producer's id cursor in `collector_state.cursor` and enriches public client IPs (`seen_in` `login`). Without `AUTH_CLIENT_SECRET` it fails with `credentials` and exports no gauge; a disabled outbox (503) is a success with an `upstream` warning.
- `GET /api/netmon/logins/summary` (totals, `byIp`, `bySubject` with `failureSameHmac`, timeline) and `/logins/events` (cursor paging, `outcome`/`ip` filters, 8-character HMAC prefix only); `/ips/{ip}` now returns `logins: {success, failure, locked}` instead of `null`.
- Retention for `login_events` (180 d).
- Warning code `partial` (V5 extends the `collector_state.last_error_code` CHECK): a `login-events` run that skipped outbox rows violating the table contract.
- `reputation`: threshold (1) of docs/060 §4.5, ≥ 1 `failure`/`locked` login in 24 h, is now the top-priority AbuseIPDB candidate.
- Collectors can add error codes to the runner's backoff (`NetmonCollector.backsOffAfter`); `login-events` backs off after `credentials` too. The other collectors keep their behaviour.
- `k8s/deployment.yaml`: `AUTH_TOKEN_URL`, `AUTH_SERVICE_URL`, `AUTH_CLIENT_ID` and `AUTH_CLIENT_SECRET` (Secret key `auth-client-secret`, `optional: true`).
- NM-2 (#16): Flyway `V4__netmon_egress` with `egress_flow_snapshots` (docs/060 §3.3), including a generated `destination_host` and a nullable `destination_ip` for destinations coroot reports by name only.
- Collector `egress`: hourly at :07 UTC snapshots the coroot-node-agent TCP counters from Prometheus per completed hour (the six §4.6 queries incl. the series-presence query, `ip_to_fqdn` join, failed connects joined on `destination` when unambiguous), replaced per `window_start`, capped at 2 000 rows (`truncated` warning), `is_new` against the previous 30 days on a rollout-stable workload identity, 48 h catch-up. Without running agents it succeeds with `lastErrorCode=upstream`.
- `GET /api/netmon/egress/top` (`scope=external|all`, `namespace`, `workload`, top-N `limit`), matching furchert-ch's `EgressFlow` shape.
- Retention for `egress_flow_snapshots` (30 d); public external destination IPs enter `ip_enrichment` with `seen_in` `egress`.
- NM-3 (#15): Flyway `V3__netmon_lan` with `lan_connection_snapshots`, `ufw_block_snapshots` and `ssh_auth_snapshots` (docs/060 §3.3).
- Collector `lan`: every 15 min (:04/:19/:34/:49 UTC) snapshots the node-script metrics from Prometheus (`PROMETHEUS_URL`) per completed 15-minute window, replaced per `(window_start, node)`; bucket gauges only through instant selectors guarded by the bucket end; one retry at T+12m for a node that had not published yet; 48 h catch-up. Without any node exposing the metrics it succeeds with `lastErrorCode=upstream`.
- `GET /api/netmon/lan/connections`, `/lan/ufw-blocks` (`lowerBound: true`) and `/lan/ssh-auth`; `/ips/{ip}` now returns `lan: {ufwBlocks, sshFailed}`.
- Retention for the NM-3 tables (30/90/90 d); public LAN source IPs enter `ip_enrichment` with `seen_in` `lan`.
- `k8s/deployment.yaml`: `PROMETHEUS_URL`.
- NM-1 (#14): Flyway `V2__netmon_inbound` with `inbound_request_groups`, `firewall_events`, `ip_enrichment`, `blocklist_snapshots` and `blocklist_entries` (docs/060 §3.3).
- Collectors `cloudflare-requests` (hourly request groups at 5-minute freshness, `is_final`, 5-minute slicing on full pages) and `cloudflare-firewall` (keyset-paged firewall events with a 10-minute overlap) against the Cloudflare GraphQL Analytics API.
- Collector `blocklists`: daily Spamhaus DROP v4 and FireHOL level1 refresh with ETag/sha256 `unchanged` detection; private and bogon ranges are dropped.
- IP enrichment for every public source IP (first/last seen, data sets, Cloudflare geo/ASN, blocklist hits).
- Collector `reputation` (AbuseIPDB), disabled until `ABUSEIPDB_API_KEY` is set.
- `GET /api/netmon/inbound/summary`, `/inbound/firewall-events` (opaque cursor paging) and `/ips/{ip}`, with `invalid_window`/`invalid_parameter`/`not_found` problem codes.
- Retention for the NM-1 tables (90/180/180/30 d); `blocklist_snapshots` still referenced by current entries are kept.
- Collector runner: exponential backoff after `rate_limited`/`upstream` failures (max 30 min), success-with-warning (`truncated`), and collectors that are unavailable without configuration.
- `k8s/deployment.yaml`: `CLOUDFLARE_GRAPHQL_URL`, `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ZONE_ID`, `ABUSEIPDB_API_KEY` (Secret keys `optional: true`).

### Changed
- `k8s/deployment.yaml`: the `webanalytics-rollup` collector is enabled (#54) with `NETMON_COLLECTORS_WEBANALYTICS_ROLLUP_ENABLED: "true"`, after gate N1 (doemefu/homelab#350); rollback = remove the entry or set it to `"false"`.
- Cloudflare-baseline backfill in per-day chunks (#55): every missing day from netmon's second day to yesterday is written in its own statement and transaction, so one timed-out day no longer blocks the rest (three failures in a row defer the remaining days to the next run); a failed run resumes with the days still missing, filled days are skipped, and the nightly replaces of rolled days no longer wait for a completed backfill. The replace of rolled days skips a day whose backfill just failed in the same run and stops after three failures in a row.
- `/coverage` also starts its comparison window at `definitionFrom` (#55), so a go-live date before the first definition-2 day never counts submissions of days without `daily_submits`.
- `DEPLOYMENT.md`: restart the pod with `kubectl -n apps delete pod -l app=data-service` instead of `kubectl rollout restart`, because Flux strips the restart annotation on its next apply.
- The freshness gauge is registered only for collectors that are enabled and able to succeed. A collector switched off, `reputation` without a key, or a Cloudflare collector without credentials therefore cannot trip `NetmonCollectorStale`.
- Cloudflare request groups carry no ASN (`httpRequestsAdaptiveGroups` does not offer it; probe 2026-09-24). `inbound_request_groups` has no `asn`/`asn_org` columns, and ASN reaches `ip_enrichment` from firewall events only.
- `@EnableScheduling` moved to `SchedulingConfig` (`netmon.scheduling.enabled`, default `true`).

## [0.1.0] — 2026-09-23

### Added
- NM-0 bootstrap (#13, PR #18, Epic doemefu/homelab#114): Spring Boot 4.1.1 / Java 25 service on port 8082.
- Flyway `V1__netmon_baseline`: schema `netmon` and table `netmon.collector_state` in DB `data_service` (history table `public.flyway_schema_history_data`).
- JWT resource server against auth-service's JWKS with issuer validation. `/api/netmon/**` requires scope `netmon:read` and a `sub` in `netmon.api.allowed-clients` (default `furchert-ch`).
- `GET /api/netmon/status`: collector freshness, `Cache-Control: no-store`, and RFC 9457 problem+json errors with a `code` member.
- Collector runner with per-collector kill switch, no-overlap lock and state bookkeeping, plus the daily retention job skeleton (03:30 UTC, no tables yet).
- Gauge `netmon_collector_last_success_timestamp_seconds{collector}` on `/actuator/prometheus`.
- Multi-arch image `ghcr.io/doemefu/homelab-data-service`, CI, CodeQL, Dependabot, and `k8s/` manifests with the Flux image-policy marker.

### Changed
- README: the scope now follows ADR 0001/0002. Schedule CRUD belongs to device-service, and data-service is the analytical data plane.
