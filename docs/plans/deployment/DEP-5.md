<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-5 — Async job API (`/v1`) and OpenAPI spec

| Field | Value |
| --- | --- |
| Status | Not started |
| Phase | D1 |
| Depends on | SEC-1, SEC-2 (merged); TEST-4 JSON Schemas (soft) |
| Size | L |
| Branch | `feature/dep-5-async-jobs` |
| Requirements | R7, R8, R12 |
| Decisions needed | D-5 HTTP layer, D-13 public spec |

## Goal

Turn `serve` into something an orchestrator can call: submit a job, get an id back immediately, poll its status, fetch the result. Describe the API in an OpenAPI 3.1 document that the server serves and the tests enforce, so the Python SDK (DEP-9) can be generated from it.

## API (v1)

All `/v1` endpoints use the SEC-1 checks (Host, Origin, token, content type, size) and the SEC-2 path policy. Errors use RFC 9457 `application/problem+json` (`type`, `title`, `status`, `detail`, plus `jobId` where relevant).

| Method and path | Purpose | Responses |
| --- | --- | --- |
| `POST /v1/jobs` | Body `{"command": "<one of the 10>", "config": {...}, "label"?: "..."}`. Validates command and paths, enqueues. | 202 + `Location: /v1/jobs/{id}` + job object; 400, 403, 503 (`Retry-After`) |
| `GET /v1/jobs/{id}` | Job object: `jobId`, `command`, `label`, `status` (`queued`, `running`, `succeeded`, `failed`, `cancelled`, `timed_out`), `createdAt`, `startedAt`, `finishedAt`, `exitCode`, `hasViolations`, `error`, `links` | 200, 404 |
| `GET /v1/jobs/{id}/result` | The command's JSON result (same as `--format json`) once finished | 200, 404, 409 (not finished) |
| `GET /v1/jobs/{id}/log?offset=N` | Captured progress lines from offset N (bounded ring buffer), `nextOffset` | 200, 404 |
| `GET /v1/jobs?status=&limit=` | List of recent jobs (newest first) | 200 |
| `DELETE /v1/jobs/{id}` | Cancel a queued job. A running job is not interrupted (SEC-1 decision on truncated reports) | 200, 404, 409 (running or finished) |
| `GET /v1/health` | Same as `/health` plus `version`, `apiVersion`, queue figures | 200 (no token) |
| `GET /v1/openapi.json` | The spec | 200 (no token if D-13 = yes) |

- `succeeded` covers exit 0 and exit 1; `hasViolations` and `exitCode` tell them apart. Exit 2 → `failed` with the error, exit 3 → `failed`.
- `timed_out`: running longer than `--job-timeout` (default `PT2H`). Like SEC-1, the run is not interrupted; the status is set and `/health` shows `stalled`.
- Job store: in memory, `--max-jobs` (default 1000) and `--job-ttl` (default `PT24H`) for finished jobs; oldest finished jobs evicted first; queued/running jobs never evicted. Lost on restart (documented).
- Job ids: random UUID v4 (`SecureRandom`); never sequential.
- The existing synchronous endpoints (`POST /validate`, …) stay unchanged.

## Scope

- CLI: `ServeServer` (routing for `/v1/*`), new `JobManager`, `Job`, `JobStatus`, `ProblemResponse` in `eu.griddigit.CimPal.cli.serve` (or the package `ServeServer` uses)
- The single worker and queue from SEC-1 are reused; the queue now holds jobs
- Progress capture: per-job buffer of stderr/progress lines instead of only the final stdout capture
- `CimPal-CLI/src/main/resources/openapi/cimpal-v1.json` (authored JSON, OpenAPI 3.1), served as-is
- Command config schemas in the spec come from the same source as the MCP tool schemas (`McpCommand.buildTools()`); a test proves they are identical
- `docs/cli/serve.md` (new `/v1` section), `docs/guide/` service page stub

## Acceptance criteria (status checklist)

- [ ] All endpoints in the table, with the listed status codes, behind the SEC-1 checks and SEC-2 path policy
- [ ] A 20-minute job does not hold any HTTP connection open; submit returns in < 1 s
- [ ] Job store limits (`--max-jobs`, `--job-ttl`, `--job-timeout`) enforced and tested, including eviction order
- [ ] `DELETE` cancels queued jobs; running → 409; cancelled jobs never start
- [ ] Log endpoint returns progress lines with offsets; buffer bounded (`--job-log-lines`, default 5000); lines sanitised with `LogSanitizer`
- [ ] OpenAPI 3.1 document valid (validator in test scope, e.g. swagger-parser); every path/method/status in the spec is exercised by a test, and every implemented route is in the spec
- [ ] Command config schemas in the spec equal the MCP tool input schemas (test)
- [ ] Problem+json on every 4xx/5xx under `/v1`; the old endpoints keep `{"error": ...}`
- [ ] SIGTERM/`/shutdown` with queued jobs: queued jobs become `cancelled`; behaviour documented
- [ ] `/security-review` and `/security-check-change` clean or findings fixed with regression tests; mutation check on job-id randomness, token check on every `/v1` route except health/spec
- [ ] `docs/cli/serve.md` documents `/v1`, with curl and Python `requests` examples for submit → poll → result

## Instructions for Claude Code

Start a session with: `Execute docs/plans/deployment/DEP-5.md` (in plan mode).

```text
Add an asynchronous, versioned job API to serve (R7, R8, R12 in docs/plans/deployment/README.md). Read docs/cli/serve.md and the SEC-1/SEC-2 decisions logs first; every SEC-1/SEC-2 guarantee must still hold.
Decision D-5: keep the JDK HttpServer unless you find a blocker; if so, stop and explain in plan mode.
1. Tests first (CLI module, TEST-1 harness, real ServeServer on an ephemeral port): submit → 202 + Location; poll until succeeded; result equals the sync endpoint's JSON for the same config; 404 unknown id; 409 result before finish; cancel queued; 409 cancel running; queue full 503 with Retry-After; max-jobs eviction; job-ttl eviction (inject a Clock); job-timeout → timed_out and health stalled; token required on every /v1 route except /v1/health and /v1/openapi.json; problem+json bodies; path outside roots in config → 403 at submit time (not later).
2. JobManager: wraps the existing single-worker executor and bounded queue; jobs keyed by random UUID; injected Clock; no statics. Job state transitions are atomic; status reads never block on the worker.
3. Progress capture: give each job a bounded line buffer fed by the command's stderr/progress output. Do not rely on swapping System.out globally while another thread reads it; if the current capture mechanism makes this unsafe, keep one worker and document why.
4. OpenAPI: author cimpal-v1.json (3.1) under CimPal-CLI/src/main/resources/openapi/. Generate or copy the command config schemas from McpCommand.buildTools() at build time, or assert equality in a test. Add swagger-parser (or another validator) in test scope only. Serve it at /v1/openapi.json.
5. Flags: --job-timeout (default PT2H), --max-jobs (1000, max 100000), --job-ttl (PT24H), --job-log-lines (5000). Validate ranges like SEC-1 did.
6. Docs: docs/cli/serve.md /v1 section; mark the old sync endpoints as "kept for local use". Update docs/PROJECT.md: REST API section → point to this plan and record what was built.
Run /security-review and /security-check-change; fix findings with regression tests and record them here.
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

## Notes and results

(Claude Code: record findings, baseline numbers and open items here.)
