<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# Running CimPal as a service

How to run [`serve`](../cli/serve.md) for a team or an orchestrator: the [job API](../cli/serve.md#job-api-v1) over the network, behind TLS, with a token from a secret, health probes, clean shutdown and logs an operator can search. For one-off runs and scheduled tasks, the CLI or container per task is still simpler; see [Deployment modes](deployment-modes.md).

## The shape of it

```
client ──HTTPS──▶ reverse proxy / Ingress ──HTTP──▶ CimPal serve (container) ──▶ /data volume
                  (TLS, public name)                 (token, one command at a time)
```

- **TLS ends at the proxy** (decision D-7). CimPal speaks plain HTTP and should only be reachable from the proxy: an internal Docker network, or a ClusterIP Service.
- **The public name** the clients use goes into `--allowed-host`, because `serve` refuses Host headers it doesn't know.
- **The token** comes from a secret file (`CIMPAL_API_TOKEN_FILE`) that you manage. `serve` never writes it, accepts two tokens while you rotate, and picks up changes within a minute.
- **Several clients** (teams, pipelines, organisations) each get a named token (`name:client:token`) in that file, and optionally quotas (`--client-quotas`). Each sees only its own jobs and uploads, and none can fill the queue or the disk for the others; see [Clients and quotas](../cli/serve.md#clients-and-quotas).
- **Files**, in one of two modes:
  - **Shared volume:** the models are on a volume mounted at `/data`, and paths in job configs are container paths under it. Best for large model sets, and when the orchestrator and CimPal mount the same storage.
  - **Upload and download:** the caller uploads a ZIP (`POST /v1/uploads`), runs jobs on it (`"inputUpload"`), and downloads the outputs (`/files`, `/archive`). No shared storage is needed. Details: [serve, file exchange](../cli/serve.md#file-exchange-uploads-and-downloads).
  - **Both:** keep large, rarely changing inputs on the server under `--read-root`. Upload jobs can read those too, so for example the CGMES constraint sets need not be uploaded every time.

## Examples

| | Folder | What it shows |
|---|---|---|
| Docker Compose | [deploy/docker-compose](../../deploy/docker-compose/README.md) | `serve` and Caddy with its own local CA; the token as a Docker secret; a read-only root filesystem; a readiness healthcheck |
| Kubernetes | [deploy/kubernetes](../../deploy/kubernetes/README.md) | Namespace (Pod Security "restricted"), PVC, Deployment with probes and a read-only root filesystem, Service, ingress-nginx Ingress with TLS. CI deploys it on kind and runs a job through the Ingress on every change. |

## Checklist

| Setting | Value | Why |
|---|---|---|
| `--host 0.0.0.0 --allow-remote` | always in a container | `localhost` inside a container is the container itself |
| `--allowed-host <public name>` | the name in the clients' URLs | The Host check (DNS-rebinding defence) refuses other names |
| `CIMPAL_API_TOKEN_FILE` | the mounted secret | Read-only, rotation without a restart |
| `--log-format json` | for log collectors | One JSON object per line; tokens never logged |
| `--metrics` | if you run Prometheus | `GET /metrics` with an admin token; job and request counters carry a `client` label |
| `--client-quotas <file>` | with several clients | Per-client `maxQueued`, `maxRunning`, `maxUploadBytes`, `maxWorkBytes`, `requestsPerMinute` |
| Liveness `GET /health`, readiness `GET /ready` | with header `Host: localhost:7474` | Kubernetes sends the pod IP as Host otherwise, which is refused |
| Memory limit | from [Sizing](sizing.md), request = limit | Out of memory ends `serve` with exit code 3; it never reports a clean result |
| Grace period | `--shutdown-grace` 60 s, container stop timeout 90 s | A running command can finish its reports on SIGTERM |
| Replicas | 1 per Deployment | Jobs are in memory; scale with more instances, each with its own name. `--workers N` runs N commands in one instance, for many small jobs; see [Sizing](sizing.md#several-commands-in-one-server) |
| `--work-root` | on the data volume (the default `.cimpal-work` is in the first `--write-root` or `--root`) | Uploads and job outputs need disk; size the volume and `--work-max-bytes` for them |
| `--request-read-time` | keep `PT60S` behind a proxy | The proxy buffers uploads; raise it only for large uploads sent directly |

## Files from S3 and other object stores

CimPal doesn't read `s3://` paths itself (decision D-6). With Airflow, either:
- **Copy to the shared volume first:** an upstream task (e.g. `S3Hook.download_file`, or `aws s3 sync`) writes to the volume CimPal mounts, and the CimPal task names the files by their container paths; or
- **Upload them:** a task downloads the objects, zips them, and posts the ZIP to `/v1/uploads`. The job runs with `inputUpload`, and the outputs come back from `/v1/jobs/<id>/archive` (and can go back to S3).

## Operating it

- **Health:**
  - `GET /health` reports `stalled` when a command runs longer than its timeout. Restart the instance if that persists.
  - `GET /ready` turns 503 while the server is stopping, or when its token file is broken. A full queue doesn't count: a single instance must stay reachable for polling and cancelling. New jobs are then answered 503 with `Retry-After`.
- **Jobs:** they are kept in memory and lost on a restart or redeploy. Clients should resubmit a job that comes back 404 after a restart.
- **Token rotation:**
  1. put the new token on a second line of the secret;
  2. switch the clients;
  3. remove the old line.
- **Logs:** `job.finished` events carry `jobId`, `status`, `exitCode` and `durationMs`. `request.rejected` events show refused requests (401, 403, 503) with the proxy's address as `remote`.
- **Metrics:**
  - `sum(cimpal_jobs_finished_total{status="failed"})` and `sum(cimpal_requests_total{outcome="error"})` are worth an alert (`by (client)` to see who).
  - So is `cimpal_jvm_heap_used_bytes` close to `cimpal_jvm_heap_max_bytes`.

## Limits

- **One command at a time** per instance. A queue of `--queue-size` (default 4) waits; more get 503 with `Retry-After`. Several jobs in parallel in one JVM are planned as an option (DEP-8).
- **No built-in TLS or identity provider:** clients are the names in the token file, with two roles (`client`, `admin`). Every client can run every command on the files under the allowed folders, and in shared-folder mode (no upload) all clients read and write the same folders. Ownership separates jobs, uploads and their outputs, not the shared folders: give clients that must not see each other's files uploads only, or separate instances. OIDC/JWT tokens from an organisation's identity provider are a possible later option.
- **Remote address:** `remote` in the log is the proxy's address. `serve` doesn't trust `X-Forwarded-For`, so the proxy's own access log identifies clients.
- **Public probes:** `/health`, `/ready`, `/v1/health` and `/v1/openapi.json` need no token, so through the proxy they show anyone the version and whether the server is busy. If that matters, block those paths at the proxy for outside clients; the orchestrator's probes reach the pod directly.
