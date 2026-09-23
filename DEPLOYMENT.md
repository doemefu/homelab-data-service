# Deployment — homelab-data-service

data-service runs in namespace `apps` of the homelab k3s cluster and is deployed by Flux from this repo's `k8s/` directory. The platform side (Postgres DB/role, the Secret, the Flux objects) lives in the `homelab` repo (doemefu/homelab#115, PR doemefu/homelab#126). Contract: `docs/060-network-monitoring.md` §9 and §11.

## Manifests (`k8s/`)

| File | Content |
|---|---|
| `deployment.yaml` | `data-service`, `replicas: 1`, `automountServiceAccountToken: false`, port 8082, env from `data-service-secrets`, requests `100m`/`256Mi`, limits `1000m`/`512Mi`, startup/liveness/readiness on `/actuator/health` (300 s startup budget), Flux marker `# {"$imagepolicy": "flux-system:data-service"}` |
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

## Bootstrap sequence (NM-0)

`k8s/deployment.yaml` starts with the placeholder tag `main-20260923T000000`. That image was never built. auth-service and device-service were bootstrapped the same way: Flux image automation replaces the placeholder with the newest real tag and commits that change to `main`.

1. **Owner:** add the SOPS variable `data_service_db_password` in the `homelab` repo.
2. **Merge this PR** (#13). `Build and Push` publishes the first `main-<timestamp>` image.
3. **Owner:** make the GHCR package `homelab-data-service` public, or enable the `ghcr-auth` `secretRef` in `cluster/apps/data-service/imagerepo.yaml`. New GHCR packages are private by default.
4. **Owner:** create an SSH deploy key with **write** access on this repo and store its private half as Secret `data-service-flux-auth` in `flux-system`. Allow the Flux push to `main` in this repo's branch ruleset, as for the other service repos.
5. **Merge homelab#126 and run playbook 59** (owner go). This creates the `data_service` role and DB and the `data-service-secrets` Secret. A second run must report 0 changes.
6. Flux reconciles `cluster/apps/data-service/`. The GitRepository and Kustomization apply `k8s/`, and the pod briefly shows `ImagePullBackOff` on the placeholder tag. The ImagePolicy then picks the tag from step 2, and ImageUpdateAutomation commits `chore: update data-service image to …` to `main`. That commit does not trigger a rebuild because `k8s/**` is ignored.
7. The pod starts and Flyway applies `V1__netmon_baseline`.

Steps 1, 3, 4 and 5 are owner actions (secrets, cluster mutations, merges). No automation in this repo performs them.

## Verification (§11 NM-0)

```bash
kubectl -n apps get pods -l app=data-service                 # Ready
kubectl -n apps logs deploy/data-service | grep -E 'Successfully applied|Started DataServiceApplication'
kubectl -n apps exec postgresql-0 -c postgresql -- psql -U postgres -d data_service \
  -c "select version, description, success from public.flyway_schema_history_data"   # contains version 1

kubectl -n apps port-forward svc/data-service 8082:8082 &
curl -s -o /dev/null -w '%{http_code}\n' localhost:8082/api/netmon/status              # 401
# With a furchert-ch client-credentials token (needs homelab-auth-service#93); read the secret into
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
| 403 for a furchert-ch token | Token lacks `netmon:read`: the client migration from homelab-auth-service#93 is not applied, or the scope was not requested |
