<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-13 — Client identity, job ownership and quotas

| Field | Value |
| --- | --- |
| Status | Not started |
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

- [ ] The existing token file and `CIMPAL_API_TOKEN` keep working (a bare token is client `default`, role admin): all existing tests green.
- [ ] For each `/v1` route, a regression test that client B gets **404** for client A's job, result, log, file, archive, upload and cancel; admin gets 200.
- [ ] `GET /v1/jobs` as a client lists only that client's jobs.
- [ ] A job can't use another client's upload (404 at submit).
- [ ] Quotas: client A at `maxQueued` gets 429 while client B still submits. `maxRunning` lets B's job start while A's wait. Per-client upload and work limits hold.
- [ ] Logs and metrics carry the client name, never a token (a test scans them).
- [ ] OpenAPI updated (429, owner filter); SDK maps 429; docs updated; `/security-review` clean.

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

## Notes and results

(Claude Code: record findings, baseline numbers and open items here.)
