<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# CimPal `serve` on Kubernetes

A kustomization for one CimPal service behind an ingress-nginx Ingress with TLS. It contains:

| File | What |
|---|---|
| `namespace.yaml` | Namespace `cimpal`, enforcing the *restricted* Pod Security Standard |
| `pvc.yaml` | `cimpal-data` (10 Gi): `/data`, the models jobs read and the reports they write |
| `deployment.yaml` | 1 replica. Its settings are listed below. |
| `service.yaml` | ClusterIP, port 80 → 7474 |
| `ingress.yaml` | Host `cimpal.example.com`, TLS secret `cimpal-tls` |
| `networkpolicy.yaml` | Only the ingress-nginx namespace may connect to the pod (port 7474) |
| `e2e-kind.sh` | The end-to-end test on kind that CI runs; also a worked example |

**Deployment settings:**
- **Command:** `serve --allow-remote --allowed-host=cimpal.example.com --log-format=json --metrics --shutdown-grace=PT60S`.
- **Resources:** 1 CPU, 1.5 Gi memory (request = limit). That is enough for 1 M triples; see [Sizing](../../docs/guide/sizing.md) for larger models.
- **Probes:** liveness `/health`, readiness `/ready`.
- **Security context:** non-root, read-only root filesystem, `/tmp` as an emptyDir.
- **Shutdown:** `terminationGracePeriodSeconds: 90`, longer than the grace period `serve` gives a running command.

TLS ends at the Ingress. Inside the cluster CimPal is plain HTTP, so the NetworkPolicy lets only the ingress controller reach it. That needs a network plugin that enforces NetworkPolicy (Calico, Cilium, …); otherwise any pod can reach the Service. Don't expose it any other way. More in [docs/guide/service.md](../../docs/guide/service.md).

## Deploy

1. Replace `cimpal.example.com` in `ingress.yaml` and `deployment.yaml` (`--allowed-host`) with your name. Set the image tag in `kustomization.yaml` to a release, e.g. `newTag: 2026.10.6.1`.
2. Create the secrets. They are kept out of the manifests, so no token is ever committed.
   ```bash
   kubectl apply -f namespace.yaml
   openssl rand -hex 32 > token && kubectl -n cimpal create secret generic cimpal-token --from-file=token=token && rm token
   kubectl -n cimpal create secret tls cimpal-tls --cert tls.crt --key tls.key   # or let cert-manager create it
   ```
3. Apply and wait:
   ```bash
   kubectl apply -k .
   kubectl -n cimpal rollout status deployment/cimpal
   ```
4. Copy models to the volume, e.g. `kubectl -n cimpal cp model.ttl <pod>:/data/model.ttl`, or fill the volume another way.

## Use

```bash
token=$(kubectl -n cimpal get secret cimpal-token -o jsonpath='{.data.token}' | base64 -d | head -n 1)
curl https://cimpal.example.com/ready
curl -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  -d '{"command":"sparql","config":{"models":["/data/model.ttl"],"query":"SELECT * WHERE { ?s ?p ?o } LIMIT 5"}}' \
  https://cimpal.example.com/v1/jobs
```

Then poll `GET /v1/jobs/<jobId>`, and fetch `/result` and `/log`; see [serve](../../docs/cli/serve.md#job-api-v1).

**Metrics.** `GET /metrics` needs the token. Prometheus scrapes it through the Ingress, as `https://cimpal.example.com/metrics` with `authorization: {credentials_file: …}`. Pod or Service discovery doesn't work, because it would send the pod IP as Host, which the Host check refuses. Don't add pod IPs to `--allowed-host`.

## Rotate or revoke the token

The pod reads `/var/run/secrets/cimpal/token` again within a minute after it changes, and accepts both lines while the file has two.
1. Update the secret to hold the old and the new token, one per line.
2. Switch the clients to the new token.
3. Update the secret to hold only the new one.

Kubernetes takes up to about a minute to update a mounted secret; no restart is needed.
- **Revoke:** a secret with no token (empty, or only `#` comments) revokes every token at the next check.
- **Broken file:** a file that can't be read or is invalid is bridged for one check. After that every request is refused and `/ready` turns 503 until it is fixed, so a broken secret can't leave a revoked token valid.

## Why one replica

`serve` runs one command at a time and keeps its jobs in memory. Two replicas behind one Service would each see only half of the jobs, so a poll could hit the replica that doesn't know the job. For more throughput, run more Deployments, each with its own name, data and token, and spread the work between them. Several jobs in parallel inside one JVM are planned in DEP-8.

## Try it on kind or Docker Desktop

`e2e-kind.sh` does all of this on a throw-away kind cluster with a self-signed certificate. It needs an image built from this repository:

```bash
mvn -B -pl CimPal-CLI -am package -DskipTests
docker build -t cimpal:e2e -f CimPal-CLI/docker/Dockerfile .
IMAGE=cimpal:e2e deploy/kubernetes/e2e-kind.sh
```

The script uses host port 443. `KEEP=1` leaves the cluster running afterwards; remove it with `kind delete cluster --name cimpal-e2e`.
