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
| Job store | `--job-store jdbc` with your PostgreSQL | Jobs, results and logs survive a restart or redeploy; without it they are in memory and lost. See [Jobs that survive a restart](../cli/serve.md#jobs-that-survive-a-restart) |
| Replicas | 1 per Deployment | One queue per instance (in memory or in its database); scale with more instances, each with its own name and database. `--workers N` runs N commands in one instance, for many small jobs; see [Sizing](sizing.md#several-commands-in-one-server) |
| `--work-root` | on the data volume (the default `.cimpal-work` is in the first `--write-root` or `--root`) | Uploads and job outputs need disk; size the volume and `--work-max-bytes` for them |
| `--request-read-time` | keep `PT60S` behind a proxy | The proxy buffers uploads; raise it only for large uploads sent directly |

## Files from S3 and other object stores

CimPal doesn't read `s3://` paths itself (decision D-6). With Airflow, either:
- **Copy to the shared volume first:** an upstream task (e.g. `S3Hook.download_file`, or `aws s3 sync`) writes to the volume CimPal mounts, and the CimPal task names the files by their container paths; or
- **Upload them:** a task downloads the objects, zips them, and posts the ZIP to `/v1/uploads`. The job runs with `inputUpload`, and the outputs come back from `/v1/jobs/<id>/archive` (and can go back to S3).

## Operating it

- **Health:**
  - `GET /health` reports `stalled` when a command runs longer than its timeout. Restart the instance if that persists.
  - `GET /ready` turns 503 while the server is stopping, when its token file is broken, or while its job store database can't be reached. A full queue doesn't count: a single instance must stay reachable for polling and cancelling. New jobs are then answered 503 with `Retry-After`.
- **Jobs:** in memory they are lost on a restart or redeploy, and clients should resubmit a job that comes back 404. With `--job-store jdbc` they survive it: queued jobs run, finished ones stay, and a job that was running ends as `failed` ("Interrupted"), which clients should resubmit.
- **Backups (with `--job-store jdbc`):** back up the database and the work volume together; a job's record is in one, its output files in the other. The database holds the checked configs (paths), results and logs of jobs, never tokens. Its size is roughly `--max-jobs` × (result + `--job-log-lines` lines).
- **Token rotation:**
  1. put the new token on a second line of the secret;
  2. switch the clients;
  3. remove the old line.
- **Logs:** `job.finished` events carry `jobId`, `status`, `exitCode` and `durationMs`. `request.rejected` events show refused requests (401, 403, 503) with the proxy's address as `remote`.
- **Metrics and alerts:** [`deploy/monitoring/`](../../deploy/monitoring/README.md) has Prometheus rules for the service levels below and for what usually goes wrong first: a queue that stays full, a stalled command, a heap above 90 %, a work area above 80 %, a failing job store, failing jobs (`by (client)` on `cimpal_jobs_finished_total` shows who).

## Service levels

What a CimPal service should promise its users, and what the alert rules watch (decided for DEP-16, measured with the [load tests](../../scripts/bench/load/README.md)):

| Service level | Target | Measured from |
|---|---|---|
| **Availability** | 99.5 % of requests answered without a server error, per 30 days (an error budget of about 3.6 hours) | `cimpal_http_responses_total`: `5xx` counts against it; `503` (queue full) and `429` (quota) don't, since clients retry those, nor does `none` (a connection that broke before the answer, usually a client giving up) |
| **API latency** | p95 under 200 ms for status, polling and listing requests, also while every worker is busy | `cimpal_http_request_duration_seconds{kind="api"}` |
| **Submit latency** | p95 under 500 ms for `POST /v1/jobs` | the load tests (the API histogram includes it) |
| **Queue wait** | p95 under 5 minutes at the load the service is sized for | `cimpal_job_queue_wait_seconds` |

- **What it doesn't promise:** how long a job *runs*. That depends on the model and the rules, see [sizing](sizing.md).
- **Sizing for the queue wait:** keep the planned load below about 80 % of an instance's throughput limit for your job mix. Near 100 %, waits grow without bound, and clients see 503 once the queue is full.
- **Your own targets:** these suit jobs of seconds to minutes. With hour-long jobs, raise the queue wait target and the `CimPalQueueWaitHigh` threshold together.

## Limits

- **One command at a time** per instance by default. A queue of `--queue-size` (default 4) waits; more get 503 with `Retry-After`. `--workers` runs several at once in one JVM, limited by `--max-concurrent-heap-bytes`; see [Thread safety and concurrency](../cli/serve.md#thread-safety-and-concurrency).
- **No built-in TLS or identity provider:** clients are the names in the token file, with two roles (`client`, `admin`). Every client can run every command on the files under the allowed folders, and in shared-folder mode (no upload) all clients read and write the same folders. Ownership separates jobs, uploads and their outputs, not the shared folders: give clients that must not see each other's files uploads only, or separate instances. OIDC/JWT tokens from an organisation's identity provider are a possible later option.
- **Remote address:** `remote` in the log is the proxy's address. `serve` doesn't trust `X-Forwarded-For`, so the proxy's own access log identifies clients.
- **Public probes:** `/health`, `/ready`, `/v1/health` and `/v1/openapi.json` need no token, so through the proxy they show anyone the version and whether the server is busy. If that matters, block those paths at the proxy for outside clients; the orchestrator's probes reach the pod directly.
