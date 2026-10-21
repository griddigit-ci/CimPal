<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# Option 2 roadmap: CimPal as a REST service, from "built" to "shared production service"

Written 2026-10-15. Input: a discussion with external partners on how they would integrate CimPal. They named two options:
- **Option 1:** a library wrapping native code, ideally not Java. pypowsybl was named as the example and as a cautionary tale.
- **Option 2:** pushing data through a REST interface. They favour it, with one stated con: "without a queuing mechanism we will quickly hit problems". They also noted that "if you provide something as a REST service, then we can also use it from Airflow".

**Decision (maintainer, 2026-10-15):** stick with option 2 only and make it production-grade. Option 1 (a native library) isn't planned. "No Java on the caller's side" is already met by the REST service and by the container.

This file is the entry point for that work. It records what option 2 covers today (DEP-5…DEP-10), the gaps found, and the work packages DEP-11…DEP-16 that close them, in the recommended order. Start a session with `Execute docs/plans/deployment/DEP-<n>.md`, as for every work package.

## 1. What option 2 covers today

All of this is in `devel`. **None of it is released yet:** the last release, 2026.10.6.1, has no `/v1` API.

| Area | What exists | Where |
| --- | --- | --- |
| Async job API | `POST /v1/jobs` → 202 + job id; poll `GET /v1/jobs/{id}`; `/result`, `/log`; cancel while queued; list. All 10 commands. Problem-details errors. | DEP-5, `docs/cli/serve.md` |
| Contract | OpenAPI 3.1 at `GET /v1/openapi.json`; contract tests keep the code and the document in step. | DEP-5 |
| Data in and out | Upload a ZIP (`/v1/uploads`) or use a shared volume; list and download a job's outputs, one by one or as a ZIP. | DEP-7 |
| Queue | Bounded (`--queue-size` ≤ 64): a full queue answers 503 + `Retry-After`. Job timeout, stall detection, TTL and size limits on stored jobs. | DEP-5, DEP-6 |
| Parallelism | `--workers N` in one JVM, with memory admission (`--max-concurrent-heap-bytes`). | DEP-8 |
| Operations | Reverse proxy names, rotatable token file, `/health` `/ready` `/v1/health`, SIGTERM drain, JSON logs, Prometheus metrics, Docker image, Compose and Kubernetes examples (tested on kind in CI). | DEP-6, DEP-1 |
| Security | Bearer token (constant-time compare), Host/Origin checks, allowed folders for every path, body/upload/work-area limits, fail closed. | SEC-1, SEC-2, DEP-6/7 |
| Clients | Python SDK `cimpal-client` (sync and async, typed errors, retries) and Airflow provider `airflow-provider-cimpal` (deferrable operator, sensor, connection type). | DEP-9, DEP-10 |
| Distribution | PyPI publishing with each release (trusted publishing, maintainer approval). | PR #72; needs the maintainer's setup |

## 2. Gaps

Each gap is set against a shared service used by many independent clients, which is what the externals described.

| # | Gap | Today | Consequence | Closed by |
| --- | --- | --- | --- | --- |
| G-A | **Not released** | Everything is in `devel` only | Nobody outside can use `/v1`, the SDK or the provider | Release + PyPI setup (§4, step 0) |
| G-B | **Jobs live in memory** | The queue, job states, results and logs are in the JVM | A restart or crash loses queued jobs and finished results; a client polling after a redeploy gets 404 | **DEP-11** durable job store |
| G-C | **One queue per instance** | A job exists only on the instance that accepted it | No load-balanced pool: clients must pin to a named instance; a dead instance strands its queue | **DEP-12** shared queue across replicas |
| G-D | **No client identity** | One or two shared tokens, and no owner on jobs or uploads | Every client sees, reads and downloads every other client's jobs and outputs; no per-client limits; the audit log can't say who did what | **DEP-13** client identity, ownership, quotas |
| G-E | **Submitting isn't idempotent** | A lost answer to `POST /v1/jobs` can't be retried safely | The SDK must not retry such a submit (it may duplicate a job); clients must check `list_jobs()` | **DEP-14** idempotency keys |
| G-F | **Clients must poll** | No completion notification | Polling load, latency up to the poll interval | **DEP-14** webhooks (opt-in) |
| G-G | **One JVM for all jobs** | Jobs share a heap; a running job can't be stopped | One out-of-memory error ends every running job; a runaway job holds its worker until the server restarts | **DEP-15** a child process per job |
| G-H | **No measured capacity** | The sizing guide covers single runs, not a service under load | No SLOs, no basis for replica counts or autoscaling | **DEP-16** load tests, capacity model, SLOs |
| G-I | Text results labelled JSON | A command that prints text (e.g. `convert`) returns it as its "JSON" result | Generic clients fail to parse; the SDK copes | Small fix (task chip "Serve: wrap non-JSON job results in JSON") |
| G-J | Large results | A result over `--max-result-bytes` is dropped (410) | The client must ask for a summary file instead | Covered by DEP-11 (results stored outside the heap) |
| G-K | Release workflow pins | `build-and-release` uses `actions/checkout@v4` / `setup-java@v4` by tag | Weaker supply-chain posture on the release path | Small fix (pin by SHA) |

The following were reviewed and are **not gaps for now**:
- **TLS inside CimPal:** terminated at the proxy (D-7).
- **Native S3:** D-6 says no; Airflow or the SDK moves the files. Revisit with DEP-12 if a shared volume isn't available.
- **Priorities and fair scheduling:** they come with DEP-13 quotas if needed.

## 3. Scaling model, now and after

| Stage | Shape | Capacity grows by | Limits |
| --- | --- | --- | --- |
| Today | 1 instance, `--workers 1` | Several named instances, each with its own clients | In-memory queue; manual client-to-instance assignment |
| Today | 1 instance, `--workers N` | More workers, within the heap and admission budget | One OOM ends all N running jobs |
| After DEP-11 | 1 instance, durable store | The same, plus restart safety | Still one instance per queue |
| After DEP-12 | N stateless replicas, shared store and shared files | Adding replicas (autoscaling on queue depth) | The database and the shared volume become the shared components to size |
| After DEP-15 | Each job in its own child JVM | The same as above, with per-job memory limits and cancel | Process start-up adds about 1–2 s per job |

## 4. Recommended order

| Step | What | Why first |
| --- | --- | --- |
| 0 | **Release, then the PyPI setup** (`clients/python/README.md`, Releasing) | Nothing reaches users before a release. Done by the maintainer; no work package needed. |
| 1 | **DEP-13** client identity, ownership, quotas | A shared service with independent clients must not let them read each other's data. A security prerequisite. |
| 2 | **DEP-11** durable job store | Restart safety. It is also the foundation DEP-12 builds on. |
| 3 | **DEP-14** idempotent submit and webhooks | Safe retries (removes the "never retry a submit" limitation); less polling. Small once DEP-11 exists. |
| 4 | **DEP-16** load test baseline | Measure before scaling out, so DEP-12's effect can be shown. |
| 5 | **DEP-12** shared queue across replicas | Horizontal scaling and failover. Needs DEP-11. |
| 6 | **DEP-15** a child process per job | OOM isolation and cancel. Independent; can move earlier if large models are the main load. |
| — | Small fixes G-I and G-K | At any time. |

Dependencies:
- DEP-12 needs DEP-11.
- DEP-14's idempotency store needs DEP-11 (an in-memory variant is possible earlier).
- DEP-13 and DEP-15 are independent of the rest.

## 5. Decisions needed (also listed in the deployment README)

| ID | Decision | Recommendation | Needed by |
| --- | --- | --- | --- |
| D-14 | Durable store technology | **PostgreSQL** through JDBC (one dependency: the driver). The job state is relational and transactional, and the queue in DEP-12 can use `SELECT … FOR UPDATE SKIP LOCKED`. Redis was considered and rejected: it adds a second store for results and logs, and durability needs extra configuration. The in-memory store stays the default. | DEP-11 |
| D-15 | Shared queue mechanism | **The DEP-11 database** (row claims with leases). No message broker: one fewer component to operate, and the job table is the single source of truth. | DEP-12 |
| D-16 | Shared files across replicas | **An RWX volume** (NFS, Azure Files, EFS) mounted on every replica at `--work-root`. S3-compatible object storage only if a customer has no RWX volume (would revisit D-6). | DEP-12 |
| D-17 | Client identity | **Named tokens** in the token file (`name:token`, optional role), with `admin` seeing all jobs. OIDC/JWT validation as a later option for organisations with an identity provider. | DEP-13 |
| D-18 | A job running when its node died | **Fail it** (`failed`, "interrupted"), never re-run silently: a validation might have written partial reports. Opt-in `--requeue-interrupted` for idempotent commands. | DEP-11, DEP-12 |
| D-19 | Completion webhooks | **Yes, opt-in**: per client, to an allowlisted URL, HMAC-signed. | DEP-14 |
