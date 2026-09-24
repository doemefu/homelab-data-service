# Deployment — homelab-data-service

data-service runs in namespace `apps` of the homelab k3s cluster and is deployed by Flux from this repo's `k8s/` directory. The platform side (Postgres DB/role, the Secret, the Flux objects) lives in the `homelab` repo (doemefu/homelab#115, PR doemefu/homelab#126). Contract: `docs/060-network-monitoring.md` §9 and §11.

## Manifests (`k8s/`)

| File | Content |
|---|---|
| `deployment.yaml` | `data-service`, `replicas: 1`, `automountServiceAccountToken: false`, port 8082, env from `data-service-secrets` (DB keys required; Cloudflare/AbuseIPDB keys `optional: true`), requests `100m`/`256Mi`, limits `1000m`/`512Mi`, startup/liveness/readiness on `/actuator/health` (300 s startup budget), Flux marker `# {"$imagepolicy": "flux-system:data-service"}` |
| `service.yaml` | ClusterIP `data-service:8082` (port name `http`) |
| `kustomization.yaml` | Lists both; this is the path (`./k8s`) that Flux syncs and updates |

There is no Ingress and no Cloudflare Tunnel route. Only cluster-internal callers (furchert-ch) reach the API.

### JVM settings

`JAVA_TOOL_OPTIONS=-Xmx128m -XX:+UseSerialGC -XX:MaxMetaspaceSize=96m`.

This follows the §9 / 052 baseline except the metaspace cap. A local run of the NM-0 image under a 512 MiB limit measured about 56 MB of metaspace after startup and the first requests, which is 87 % of the 64m baseline before any collector code exists. The cap is therefore 96m. Heap use was about 37 MB and the container used about 170 MiB in total. Raise `-Xmx` to `192m` if a later collector needs it (§9 allows this).

## Image

GitHub Actions `Build and Push` (`.github/workflows/build.yml`) runs on every push to `main` except `k8s/**` changes. It runs `./mvnw verify`, then builds `linux/amd64` + `linux/arm64` and pushes:

- `ghcr.io/doemefu/homelab-data-service:<short-sha>`
- `ghcr.io/doemefu/homelab-data-service:main-<YYYYMMDDTHHMMSS>` — the tag Flux selects with `^main-[0-9]{8}T[0-9]{6}$`

No `latest` tag is published.

## Bootstrap sequence (NM-0) — completed 2026-09-23

`k8s/deployment.yaml` starts with the placeholder tag `main-20260923T000000`. That image was never built. auth-service and device-service were bootstrapped the same way: Flux image automation replaces the placeholder with the newest real tag and commits that change to `main`.

1. **Owner:** add the SOPS variable `data_service_db_password` in the `homelab` repo.
2. **Merge PR #18** (issue #13). `Build and Push` publishes the first `main-<timestamp>` image.
3. **Owner:** make the GHCR package `homelab-data-service` public. New GHCR packages are private by default. Keeping it private needs **two** credentials: `ghcr-auth` in `flux-system`, with the `secretRef` in `cluster/apps/data-service/imagerepo.yaml` enabled, only lets Flux scan tags. The pod also needs a docker-registry Secret in `apps`, referenced via `imagePullSecrets` in `k8s/deployment.yaml`, or the rollout ends in `ImagePullBackOff`. See the infrastructure repo's `DEPLOYMENT.md`, section "data-service (Flux, NM-0 onboarding)".
4. **Owner:** create an SSH deploy key with **write** access on this repo and store its private half as Secret `data-service-flux-auth` in `flux-system`. Allow the Flux push to `main` in this repo's branch ruleset, as for the other service repos.
5. **Merge homelab#126 and run playbook 59** (owner go). This creates the `data_service` role and DB and the `data-service-secrets` Secret. A second run must report 0 changes.
6. Flux reconciles `cluster/apps/data-service/`. The GitRepository and Kustomization apply `k8s/`, and the pod briefly shows `ImagePullBackOff` on the placeholder tag. The ImagePolicy then picks the tag from step 2, and ImageUpdateAutomation commits `chore: update data-service image to …` to `main`. That commit does not trigger a rebuild because `k8s/**` is ignored.
7. The pod starts and Flyway applies `V1__netmon_baseline`.

Steps 1, 3, 4 and 5 are owner actions (secrets, cluster mutations, merges). No automation in this repo performs them.

## NM-1 rollout (#14): Cloudflare inbound, blocklists, enrichment

NM-1 adds four collectors and the migration `V2__netmon_inbound` (five tables, additive). Merge order across repos (docs/060 §11): **doemefu/homelab#116 → this repo's #14 PR → doemefu/furchert-ch#61**.

1. **Cloudflare field probe (§4.2): done 2026-09-24.** The owner ran the `settings` probe against zone furchert.ch. Result:

   | | `httpRequestsAdaptiveGroups` | `firewallEventsAdaptive` |
   |---|---|---|
   | `enabled` | true | true |
   | `maxDuration` (max span of one query) | 2 592 000 s (30 d) | 2 592 000 s (30 d) |
   | `notOlderThan` (history available) | 2 678 400 s (31 d) | 2 678 400 s (31 d) |
   | `maxPageSize` | 10 000 | 10 000 |
   | `maxNumberOfFields` | 40 | 40 |
   | ASN fields | **not available** (`clientAsn`, `clientASNDescription` missing) | available |

   The collectors request only fields the probe lists:
   - `httpRequestsAdaptiveGroups` (query A): `count`, `avg.sampleInterval`, and the dimensions `clientIP`, `clientCountryName`, `clientRequestHTTPHost`, `clientRequestHTTPMethodName`, `clientRequestPath`, `edgeResponseStatus`. There is no ASN. A request-only IP has no ASN until it appears in a firewall event.
   - `firewallEventsAdaptive` (query B): `datetime`, `rayName`, `clientIP`, `clientCountryName`, `clientAsn`, `clientASNDescription`, `action`, `source`, `ruleId`, `clientRequestHTTPHost`, `clientRequestHTTPMethodName`, `clientRequestPath`, `userAgent`.

   If Cloudflare later drops a field, every run fails with `upstream` (GraphQL `errors[]`) until the field is removed from the query. The design keeps 5-minute runs and at most 24 h per query and catch-up, so the 31-day history is not used yet. The page sizes 5000/1000 stay below the 10 000 maximum. A 30-day initial backfill is a follow-up.
2. **Owner:** merge homelab#116 and run playbook 59. It adds the Secret keys `cloudflare-api-token` and `cloudflare-zone-id` to `data-service-secrets` (SOPS vars `data_service_cloudflare_analytics_token`, `data_service_cloudflare_zone_id`), plus the ServiceMonitor and the `NetmonCollectorStale` rule.
3. **Merge this PR.** Flux rolls out the new image; Flyway applies V2 on startup.
4. The env vars use `optional: true`. If step 2 has not run yet, the pod still starts; `cloudflare-requests` and `cloudflare-firewall` then fail every run with `lastErrorCode=credentials` in `/api/netmon/status`, never call out, and export no freshness gauge, so `NetmonCollectorStale` stays quiet. A running pod does not see Secret changes in env vars: after playbook 59 adds the keys, restart it once (`kubectl -n apps rollout restart deploy/data-service`, owner go).
5. `reputation` (AbuseIPDB) stays disabled (`enabled=false`, no gauge) until the owner approves a key and adds `abuseipdb-api-key` to the Secret.

### Verification (§11 NM-1)

```bash
kubectl -n apps logs deploy/data-service | grep -E 'Migrating schema .* to version "2|\[cloudflare-|\[blocklists\]'
PSQL='kubectl -n apps exec postgresql-0 -c postgresql -- psql -U postgres -d data_service -c'
$PSQL "select version, success from public.flyway_schema_history_data order by installed_rank"     # 1 and 2
$PSQL "select window_start, is_final, count(*), sum(request_count) from netmon.inbound_request_groups group by 1,2 order by 1 desc limit 5"
$PSQL "select count(*) from netmon.firewall_events"            # run twice 5+ min apart: stable unless new events
$PSQL "select list_name, outcome, entry_count, fetched_at from netmon.blocklist_snapshots order by fetched_at desc limit 4"
$PSQL "select count(*) from netmon.ip_enrichment where blocklisted and (ip << '10.0.0.0/8' or ip << '172.16.0.0/12' or ip << '192.168.0.0/16')"   # 0
$PSQL "select collector, last_success_at, last_window_end, consecutive_failures, last_error_code from netmon.collector_state"
```

The first blocklist refresh runs at the next 05:00 UTC. There is no manual trigger in v1 (§7.3), so the blocklist checks above pass only after that run.

## NM-3 rollout (#15): LAN snapshots

NM-3 adds the `lan` collector, the migration `V3__netmon_lan` (three tables, additive) and `PROMETHEUS_URL` (a plain value, no Secret). Order across repos (docs/060 §11): **doemefu/homelab#117 role rollout → this repo's #15 PR → doemefu/furchert-ch#62**. This PR does not depend on the role at merge time.

1. **Merge this PR.** Flux rolls out the new image; Flyway applies V3 on startup.
2. **Until the owner runs `10_base.yml --tags netmon_node`** (doemefu/homelab#117, needs the LAN), no node exposes the metrics. `lan` still succeeds every 15 min, advances its high-water mark and reports `lastErrorCode=upstream` with `consecutiveFailures: 0` in `/api/netmon/status`. Its freshness gauge is set on every run, so `NetmonCollectorStale` (90 min class) stays quiet; the WARN line `[lan] run completed with warning: code=upstream warning=CollectorWarning: no node exposes the homelab_netmon metrics` is expected.
3. After the role rollout, the next run (at most 15 min later) writes rows and `lastErrorCode` becomes `null`. It also backfills up to 48 h, but only from when the script started publishing.

**How windows are finalised (§4.6).** For the window `[T-15m, T)`, connections are evaluated at `T`, and node discovery plus the guarded bucket queries at `T+4m`. A node exposing `homelab_netmon_last_success_timestamp_seconds` is expected; it has published when its `homelab_netmon_bucket_end_timestamp_seconds` equals `T`. An expected node that has not published by `T+4m` holds the high-water mark and is re-checked once at `T+12m` (next run). If it still has not published then, the window is final without bucket rows for that node (WARN `[lan] window end=… not published by nodes=[…]`), and the mark moves on. A node whose series are gone entirely, such as a rebooting MacBook, is not expected and does not block anything. Its connection samples from the window are still written.

### Verification (§11 NM-3)

```bash
PSQL='kubectl -n apps exec postgresql-0 -c postgresql -- psql -U postgres -d data_service -c'
$PSQL "select version, success from public.flyway_schema_history_data order by installed_rank"     # 1, 2 and 3
$PSQL "select collector, last_success_at, last_window_end, consecutive_failures, last_error_code from netmon.collector_state where collector = 'lan'"
$PSQL "select node, dport, src_ip, max(peak_connections) from netmon.lan_connection_snapshots where dport = 1883 group by 1,2,3 order by 1,3"   # mosquitto clients by LAN IP
$PSQL "select window_start, node, sum(blocks) from netmon.ufw_block_snapshots group by 1,2 order by 1 desc limit 8"   # a test block appears within 30 min
```

## Verification (§11 NM-0)

```bash
kubectl -n apps get pods -l app=data-service                 # Ready
kubectl -n apps logs deploy/data-service | grep -E 'Successfully applied|Started DataServiceApplication'
kubectl -n apps exec postgresql-0 -c postgresql -- psql -U postgres -d data_service \
  -c "select version, description, success from public.flyway_schema_history_data"   # contains version 1

kubectl -n apps port-forward svc/data-service 8082:8082 &
curl -s -o /dev/null -w '%{http_code}\n' localhost:8082/api/netmon/status              # 401
# With a furchert-ch client-credentials token (provided by homelab-auth-service PR #95, Flyway V6, live since 2026-09-23); read the secret into
# a variable without echoing it, and never paste tokens into logs or tickets:
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" localhost:8082/api/netmon/status   # 200
```

A token without `netmon:read` must get 403.

## Rollback

- Code: revert the image tag commit in `k8s/deployment.yaml`, or suspend the Flux Kustomization `data-service`.
- Schema: migrations are forward-only. An older image ignores newer migrations only if they are additive, so never edit or delete a merged migration.
- Collectors: set `netmon.collectors.<name>.enabled=false` to stop one source without a code change. Collector names contain dashes (for example `cloudflare-requests`), which a plain env var cannot express, so use `SPRING_APPLICATION_JSON` in the deployment:

  ```yaml
  - name: SPRING_APPLICATION_JSON
    value: '{"netmon":{"collectors":{"cloudflare-requests":{"enabled":false}}}}'
  ```

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Pod `CreateContainerConfigError` | Secret `data-service-secrets` missing, because playbook 59 has not run |
| `ImagePullBackOff` on `main-20260923T000000` | Expected until Flux writes the first real tag (step 6). If it persists, check GHCR visibility (step 3) and the Flux deploy key and ruleset (step 4). |
| Startup fails with `FlywayValidateException` | A merged migration was edited, or migrations were merged out of order |
| Every API call answers 500 `code=internal` | auth-service JWKS unreachable (`[auth] token validation unavailable` in the log) |
| 401 for a fresh furchert-ch token | Wrong `JWT_ISSUER`, or the token was minted by another auth-service instance or key |
| 403 for a furchert-ch token | Token lacks `netmon:read`: the client migration from homelab-auth-service PR #95 (Flyway V6) is not applied, or the scope was not requested |
| `/status`: `cloudflare-*` with `lastErrorCode=credentials` | `CLOUDFLARE_API_TOKEN`/`CLOUDFLARE_ZONE_ID` empty (Secret keys missing, or pod not restarted after playbook 59), token revoked/expired, or the token lacks Analytics:Read (firewall events may also need Firewall Services:Read, §4.2) |
| `/status`: `cloudflare-*` with `lastErrorCode=upstream` | Cloudflare 5xx/unreachable, or GraphQL `errors[]` — the WARN log line `[cloudflare] GraphQL errors: … first=…` names the problem (typically a field not available on the plan) |
| `/status`: `lastErrorCode=rate_limited` | Cloudflare 429; runs back off exponentially up to 30 min |
| `/status`: `lan` with `lastErrorCode=upstream` and 0 failures | No node exposes the NM-3 metrics: the `netmon_node` role is not rolled out (doemefu/homelab#117) or node-exporter is not scraping its textfile directory. Check `max by (node) (homelab_netmon_last_success_timestamp_seconds)` in Prometheus |
| `/status`: `lan` with `lastErrorCode=upstream` and failures > 0 | Prometheus unreachable or answering 5xx (`PROMETHEUS_URL`); runs back off up to 30 min and catch up afterwards (48 h cap) |
| `/status`: `lan` with `lastErrorCode=internal` | Prometheus rejected a query (HTTP 400/422 `bad_data`), a data-service bug |
| `/status`: `lastErrorCode=truncated` with 0 failures | A 5-minute slice or a firewall page hit the page limit; data was written, the window may be incomplete |
| `/status`: `blocklists` with `upstream` | One list failed to download or parse; see `netmon.blocklist_snapshots.error`. The previous entries stay active |
