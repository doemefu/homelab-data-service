# Documentation Files — Purpose & Owner

| File | Audience | Covers |
|------|----------|--------|
| `README.md` | Everyone | Purpose, status, quick reference, build/run |
| `INTERFACES.md` | Integrators | Exposed API, auth, errors, metrics, consumed services, config, Secret keys |
| `DEPLOYMENT.md` | Operators | K8s manifests, Flux bootstrap, owner actions, verification, troubleshooting |
| `CHANGELOG.md` | Users of the API | Changes per release |

The cross-repo contract is `docs/060-network-monitoring.md` in the `homelab` repo; link to it, never copy it.

**Rule:** Docs are written in parallel with the code change, not after.
