# Phase 4 Kubernetes / KEDA deployment

The manifests in `infra/k8s` are a deployment baseline, not a turnkey production
cluster. Use an immutable image digest or release tag; replace
`REPLACE_WITH_IMMUTABLE_TAG` in both deployments before applying them. Do not
deploy `latest`.

## Prerequisites

- Install KEDA and ensure its Prometheus scaler can reach the Prometheus service.
- Run Prometheus in the `monitoring` namespace with Kubernetes pod discovery
  enabled. Configure it to use the `springkb-prometheus-discovery` service
  account (or grant the same read-only pod list/watch permissions to its
  existing service account).
- Merge `infra/prometheus/kubernetes-scrape-job.yml` into Prometheus's active
  scrape configuration and reload it. It discovers annotated application pods
  in the `default` namespace. If deploying elsewhere, update the namespace
  filter and the RBAC scope.
- Make the Prometheus address in both `ScaledObject` manifests match the
  in-cluster Prometheus service DNS name.
- Provision `springkb-db` with `url`, `user`, and `password`, and `springkb-redis`
  with `password`, in the application namespace. These Secret objects are
  intentionally not checked into source control.
- Provide the required model/embedding provider credentials through an external
  Secret manager or a separately managed Kubernetes Secret before enabling
  model-dependent workloads.

## Apply

After substituting the image tag and configuring Prometheus:

```powershell
kubectl apply -f infra/k8s/prometheus-scrape-rbac.yaml
kubectl apply -f infra/k8s/ai-engine-deployment.yaml
kubectl apply -f infra/k8s/kb-indexer-deployment.yaml
kubectl apply -f infra/k8s/ai-engine-scaledobject.yaml
kubectl apply -f infra/k8s/kb-indexer-scaledobject.yaml
```

The AI engine scales from the sum of its in-flight API/MCP requests (target 10
per replica, 2–20 replicas). The indexer scales from pending/processing indexing
outbox rows (target 50 per replica, 1–8 replicas). Both metrics include the
runtime role tag to avoid cross-scaling. The indexer deployment enables backlog
polling with `SPRINGTECHKB_INGESTION_OUTBOX_METRICS_ENABLED=true`; it is disabled
by default for roles that do not need this database query. The indexer remains
API-isolated; its outbox dispatcher runs in the background. Prometheus scraping
is unauthenticated at the application endpoint, so keep it on a trusted cluster
network and do not expose port 8080 publicly.

The image registry, cluster network policies, external Secret integration,
Prometheus service name and resource limits are environment-specific production
decisions. A live Kubernetes/KEDA scale test is still required in the target
cluster; manifest validation alone does not demonstrate production capacity or
availability.
