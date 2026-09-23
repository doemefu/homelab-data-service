# Repository Commands

## Build & Run
```bash
./mvnw verify                     # Full build + all tests (needs Docker for Testcontainers)
./mvnw test -Dtest=ClassName      # Run a single test class
./mvnw clean package -DskipTests  # Build the jar only
./mvnw spring-boot:run            # Run locally on :8082 (DB_PASSWORD env var required)
docker build -t data-service .    # Build the container image
```

## Checks
```bash
actionlint                                             # GitHub workflow lint
kubectl kustomize k8s                                  # Render manifests (offline)
conftest test --rego-version v0 \
  --policy ../infrastructure/policy/kubernetes/ --all-namespaces <(kubectl kustomize k8s)
```

## Cluster access (local dev, read-only)
```bash
kubectl -n apps port-forward svc/postgresql 5432:5432      # PostgreSQL
kubectl -n apps port-forward svc/data-service 8082:8082    # this service
kubectl -n apps get pods -l app=data-service
```
