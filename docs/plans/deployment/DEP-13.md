<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-13 — Client identity, job ownership and quotas

| Field | Value |
| --- | --- |
| Status | Done ([PR #75](https://github.com/griddigit-ci/CimPal/pull/75)) |
| Phase | D3 (option 2 hardening, see [OPTION-2-ROADMAP.md](OPTION-2-ROADMAP.md)) |
| Depends on | DEP-5, DEP-6, DEP-7; decision D-17. Works with the in-memory store; DEP-11 adds the `owner` column. |
| Size | M |
| Branch | `feature/dep-13-client-identity` |
| Requirements | R19 |
| Decisions needed | D-17 identity model |

## Goal

A shared CimPal service used by several independent clients (organisations, teams, pipelines) keeps them apart:
- every request is attributed to a named client;
- a client sees only its own jobs and uploads;
- each client has limits, so one client can't fill the queue or the disk for everyone;
- the logs say who did what.

## Today

- **Tokens:** `TokenSource` accepts one or more tokens, from `CIMPAL_API_TOKEN` or a token file with one token per line (for rotation). Every valid token has the same rights.
- **No owner:** jobs (`Job`) and uploads (`WorkArea.Upload`) have no owner. `GET /v1/jobs` lists everyone's jobs; any client can read any job's result, log and files, and cancel any queued job, given its id.
- **Global limits only:** `--queue-size`, `--max-upload-bytes`, `--work-max-bytes`.

## Design (D-17: named tokens)

**1. Named tokens.** The token file gets an optional name and role per line, keeping today's format valid:

```text
# name:role:token     (role: client | admin; a bare token line = name "default", role admin)
etl-airflow:client:3f9c...
team-b:client:a71e...
ops:admin:9d02...
```

- **Rotation:** two lines with the same name are two valid tokens of one client.
- **Comparison:** constant-time, as today.
- **Identity:** the name is an identifier, not a secret, and is logged; the token never is.
- **Mapping:** `TokenSource` returns a `Client(name, role)` instead of a boolean.

**2. Ownership.**
- **Stamping:** jobs and uploads get the `owner` of the client that created them.
- **For a `client` token:**
  - `GET /v1/jobs` lists only its own jobs;
  - `GET`/`DELETE` of another client's job, result, log, file or upload answers **404**, not 403, so ids don't leak existence;
  - `inputUpload` must be an upload of the same owner.
- **For an `admin` token:** sees everything; `GET /v1/jobs?owner=` filters.
- **Synchronous endpoints** (`POST /<command>`) are attributed too.

**3. Per-client quotas** (in a quota file or the token file's extension; defaults for all clients):

| Quota | Effect when exceeded |
| --- | --- |
| `maxQueued` (default: `--queue-size`) | 429 `Too Many Requests` + `Retry-After` for that client only |
| `maxRunning` (default: `--workers`) | The client's further jobs wait, and others' jobs may overtake them (fairness) |
| `maxUploadBytes`, `maxWorkBytes` | 413 / 507 for that client |
| `requestsPerMinute` (optional) | 429 |

- **Fairness:** the worker loop picks the oldest queued job whose owner is under `maxRunning`. Round-robin between clients is optional (decide in plan mode).

**4. Attribution everywhere.**
- **Logs:** JSON logs gain `client`.
- **Metrics:** gain a `client` label on job and request counters. The cardinality is bounded by the token file.
- **Problem details:** carry no other client's data.

**5. OpenAPI and SDK.**
- **OpenAPI:** documents 429 and the owner filter (admin).
- **SDK:** maps 429 to `BusyError`, which retries with `Retry-After`.
- **Provider:** nothing changes, since the connection's token identifies the client.

**6. Later option (not in this WP):** OIDC/JWT bearer tokens validated against an organisation's identity provider, with the `sub`/`azp` claim as the client name. Plan it as a follow-up if a customer asks.

## Scope

- **Code:** `TokenSource` (the format, `Client`), the request pipeline in `ServeServer` (attach the client to the exchange), and owner checks on every `/v1` route and upload. Quotas and fairness in the worker loop, plus 429 responses.
- **Logs, metrics and API surface:** logs and metrics, the OpenAPI document, SDK error mapping, `serve.md`, `service.md` and `security.md`.

## Acceptance criteria (status checklist)

- [x] The existing token file and `CIMPAL_API_TOKEN` keep working (a bare token is client `default`, role admin): all existing tests green.
- [x] For each `/v1` route, a regression test that client B gets **404** for client A's job, result, log, file, archive, upload and cancel; admin gets 200.
- [x] `GET /v1/jobs` as a client lists only that client's jobs.
- [x] A job can't use another client's upload (404 at submit).
- [x] Quotas: client A at `maxQueued` gets 429 while client B still submits. `maxRunning` lets B's job start while A's wait. Per-client upload and work limits hold.
- [x] Logs and metrics carry the client name, never a token (a test scans them).
- [x] OpenAPI updated (429, owner filter); SDK maps 429; docs updated; `/security-review` clean (no High; both Medium findings fixed, see the notes).

## Instructions for Claude Code

Start a session with: `Execute docs/plans/deployment/DEP-13.md` (in plan mode).

```text
Confirm D-17 (named tokens; OIDC later) and whether fairness is round-robin between clients.
1. Tests first: the cross-client 404 matrix over every /v1 route (fails today).
2. TokenSource format + Client; attach the client to each request; ownership checks.
3. Quotas and fairness; 429.
4. Logs, metrics, OpenAPI, SDK, docs.
Run /security-review (authorisation on every route, existence leaks, quota bypass).
```

Standard footer (applies to every work package):

- Read CLAUDE.md, docs/PROJECT.md and this plan file first. Do not redo full codebase discovery.
- Start in plan mode: propose the plan, list the files you will touch, wait for approval.
- Work on branch feature/<wp-id>-<short-name> off devel. Do not push, tag or open a PR unless asked.
- Follow CLAUDE.md conventions (license header, package case, JPMS requires, logic in Core).
- Tests first where possible. Finish with `mvn -B verify` green and report the test count before and after.
- Run /security-review on the diff if the change touches I/O, network, processes, parsing or serve/mcp/run.
- At the end: tick this file's checklist, add to its decisions log, update docs/PROJECT.md (date, status, known issues, next steps) and docs/cli/* if CLI behaviour changed. Summarise what changed and what is left open.

## Decisions log

| Date | Decision | By |
| --- | --- | --- |
| 2026-10-15 | D-17 confirmed: named tokens `name:role:token` in the token file; a bare token (and `CIMPAL_API_TOKEN`, a generated token) is client `default`, role admin. OIDC/JWT stays a later option. | Maintainer |
| 2026-10-15 | Fairness: the oldest queued job whose client is under its `maxRunning` runs next; no round-robin. | Maintainer |
| 2026-10-15 | Quotas in a separate `--client-quotas` JSON file (read at start), not in the token file, so the secret holds only tokens. | Maintainer |
| 2026-10-15 | `/shutdown` and `/metrics` need an admin token (metrics carry client names). A bare token is admin, so existing setups keep working. | Maintainer |
| 2026-10-15 | `maxQueued` has no default of its own (the server's `--queue-size` bounds it); with the plan's default (`--queue-size`) a single client hit 429 where the full queue answered 503 before. A client is refused once its queued and running jobs reach `maxQueued + maxRunning`, which mirrors the server's `--queue-size + --workers`. | Claude Code |
| 2026-10-15 | `requestsPerMinute` counts every request with the client's token, polling included; a token bucket that refills evenly. A client's `?owner=` other than itself is 403 (it names no object, so it leaks nothing). | Claude Code |
| 2026-10-15 | An upload can only be used by a job of the same client, admins included. | Claude Code |

## Notes and results

**2026-10-15 (Claude Code).**
- **What changed:** `TokenSource` (`Client`, named lines, up to 256 tokens, 64 KiB file), `Job`/`JobManager` (`owner`), `WorkArea` (upload owner, per-owner bytes), `ServeServer` (`requireClient`/`requireAdmin`, ownership on every route, quotas, job turns), new `ClientQuotas` and `JobScheduler`, `ServeMetrics` (`client` label, `cimpal_quota_refused_total`), `ServeCommand --client-quotas`, OpenAPI (`owner`, `?owner=`, 429 on every route with a token), SDK (429 → `BusyError`, retried; generated layer regenerated), docs (`serve.md` *Clients and quotas*, service, security, SDK guides).
- **Tests:** baseline CLI 283 (5 skipped), now 312 (after the review fixes). New: `JobManagerTest` (3, eviction across clients), `ClientIsolationTest` (12: the 404 matrix over every job and upload route compared byte for byte with a missing id, admin 200, list and `?owner=`, admin-only routes, `maxQueued`, `maxRunning` overtaking, per-client upload and work limits, `requestsPerMinute`, 429 on every route with a token, a scan of logs and metrics for tokens), `ClientQuotasTest` (4), `JobSchedulerTest` (3), `TokenSourceTest` (+7). The OpenAPI response coverage of 429 and the owner 403 is checked by `ClientIsolationTest`. The tests were written against the new API, so they don't compile on `devel`; they don't show "failing before" by themselves.
- **SDK:** 119 passed (+2 for 429); the Airflow provider's tests pass with `owner` in the fixture.
- **Metrics change:** `cimpal_jobs_finished_total` and `cimpal_requests_total` have a `client` label now; a dashboard that matched the exact series must aggregate (`sum by (status)`).
- **Open:**
  - Shared-folder mode: every client reads and writes the same `--root` folders; ownership covers `/v1` jobs, uploads and outputs only (documented in `service.md` and `PROJECT.md`).
  - `--client-quotas` is read at start only; the token file reloads.
  - Synchronous endpoints count against `requestsPerMinute` and the server queue, not `maxQueued`.
  - The `owner` field is required in the OpenAPI `Job`, so this SDK needs a server with DEP-13 (no released server has `/v1` yet).
  - DEP-11 must store `owner` with the job.
- **Security review (2026-10-15, `security-reviewer` agent):** no Critical or High findings; no route lets a client read, download, cancel or delete another client's job or upload, or learn that it exists. Fixed in this WP:
  - (Medium) store eviction was global, so one client's flood could evict another client's finished jobs and outputs. `JobManager.evict` now drops the oldest finished job of the client holding the most jobs (then bytes) first (`JobManagerTest`).
  - (Medium) `maxWorkBytes` didn't bound job outputs: a client could keep submitting jobs on one upload. A submit with an upload is now refused with 507 while the client's share is full (`ClientIsolationTest.jobOutputsCountAgainstMaxWorkBytes`). A single job's outputs are still not bounded while it runs.
  - (Low) every request hashed the presented token once per accepted token (256 now): the presented token is hashed once and compared with precomputed digests.
  - (Low) a bare token with colons may read as a named line: documented and pinned (`TokenSourceTest`); the "two roles" error no longer echoes the name.
  - (Info) a slot leak if `jobs.create` returned null; a `queuedTasks` entry left when another turn ran the job; the stale `WorkArea` Javadoc.
  - Tests added for 404 (not 409) on another client's queued and running job and in-use upload.
  - A temporary revert of `Client.sees()` makes 5 isolation tests fail (and the class's spec check), so the matrix is a real regression test.
- **Cross-job output through `CliStreams` (review Info, checked 2026-10-15): no leak.** A job's binding (and its `PathPolicy`) is inherited by the threads it starts. Core's own pools are created per call and shut down with it; `HttpClient`s are per request; slf4j-simple writes on the calling thread. The one shared pool, the common `ForkJoinPool` behind `parallelStream()` in `ModelFactory`, has innocuous workers on JDK 25 that inherit nothing, so their lines go to the server's stderr, not to any job. Pinned by `CliStreamsTest.commonPoolWorkersNeverCarryAJobsStreams` and the new `MutableStaticsTest.noStaticFieldHoldsThreadsThatOutliveAJob`, which fails on any static executor, thread, timer, thread group or `HttpClient` in Core or the CLI (checked to fail with a probe field).
- **Left open from the review:** without `--client-quotas` one client can still fill the queue (documented: set `default.maxQueued`); synchronous endpoints bypass `maxQueued`/`maxRunning`; `/health` shows server-wide counts without a token (unchanged).

