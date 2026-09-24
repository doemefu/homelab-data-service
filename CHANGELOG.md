# Changelog

All notable changes to homelab-data-service. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
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
