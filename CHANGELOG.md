# Changelog

All notable changes to homelab-data-service. Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- NM-0 bootstrap (#13, Epic doemefu/homelab#114): Spring Boot 4.1.1 / Java 25 service on port 8082.
- Flyway `V1__netmon_baseline`: schema `netmon` and table `netmon.collector_state` in DB `data_service` (history table `public.flyway_schema_history_data`).
- JWT resource server against auth-service's JWKS with issuer validation. `/api/netmon/**` requires scope `netmon:read` and a `sub` in `netmon.api.allowed-clients` (default `furchert-ch`).
- `GET /api/netmon/status`: collector freshness, `Cache-Control: no-store`, and RFC 9457 problem+json errors with a `code` member.
- Collector runner with per-collector kill switch, no-overlap lock and state bookkeeping, plus the daily retention job skeleton (03:30 UTC, no tables yet).
- Gauge `netmon_collector_last_success_timestamp_seconds{collector}` on `/actuator/prometheus`.
- Multi-arch image `ghcr.io/doemefu/homelab-data-service`, CI, CodeQL, Dependabot, and `k8s/` manifests with the Flux image-policy marker.

### Changed
- README: the scope now follows ADR 0001/0002. Schedule CRUD belongs to device-service, and data-service is the analytical data plane.
