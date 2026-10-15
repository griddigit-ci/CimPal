<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-14 — Idempotent submission and completion webhooks

| Field | Value |
| --- | --- |
| Status | Not started |
| Phase | D3 (option 2 hardening, see [OPTION-2-ROADMAP.md](OPTION-2-ROADMAP.md)) |
| Depends on | DEP-5, DEP-9; DEP-11 for durable keys (an in-memory variant works before it); DEP-13 for per-client keys and webhook URLs; decision D-19 |
| Size | M |
| Branch | `feature/dep-14-idempotency-webhooks` |
| Requirements | R20 |
| Decisions needed | D-19 webhooks |

## Goal

1. **Safe retries:** a client whose `POST /v1/jobs` or `POST /v1/uploads` answer got lost (a timeout, a dropped connection) can send the same request again and get the **same** job or upload, never a second one. The SDK then retries submits like any other request, which removes the "never retry a submit" rule in DEP-9 and DEP-10.
2. **Fewer polls:** a client can ask to be called back when its job ends, instead of polling.

## Today

- **Submits:** `POST /v1/jobs` always creates a new job. The SDK (`RetryPolicy.may_retry`) retries a POST only when it never reached the server, and the provider fails a task rather than resubmit once a job may exist.
- **Completion:** clients poll `GET /v1/jobs/{id}`. The SDK's `wait()` and the Airflow trigger back off up to 30 s.

## Design

**1. The `Idempotency-Key` header** (the IETF draft "The Idempotency-Key HTTP Header Field"), on `POST /v1/jobs` and `POST /v1/uploads`.
- **Key format:** 1–255 visible ASCII characters (e.g. a UUID); malformed → 400.
- **Store:** the server keeps `(client, key) → (request fingerprint, response status, response body, resource id)` for `--idempotency-ttl` (default 24 h). It is held in the job store (DEP-11: an `idempotency` table) or in memory before DEP-11.
- **Repeat with the same key and the same request** (the SHA-256 of the method, path and body, or of the upload's bytes): the original response is returned (202 with the same job, or 201 with the same upload).
- **Same key, different request:** **422** with problem details "Idempotency-Key reused with a different request".
- **Same key while the first request is still being processed:** **409** + `Retry-After: 1`.
- **Scope:** keys are per client (DEP-13), or global with one shared token.
- **OpenAPI:** documents the header and the 409/422 answers.

**2. SDK and provider.**
- **SDK:** `submit()` and `upload()` send a fresh UUID key per call, reused across that call's retries. `RetryPolicy.may_retry` then allows read errors and timeouts for these POSTs too. Callers may pass their own key (`submit(..., idempotency_key=)`).
- **Airflow provider:** uses a deterministic key from `dag_id/task_id/run_id/try_number`. A retry of the *same try* never duplicates; a new try still submits a new job, which stays the documented behaviour.

**3. Completion webhooks (D-19, opt-in).**
- **Request:** `POST /v1/jobs` accepts `"callback": {"url": "https://…"}`.
- **Allowed targets:** the URL must match the client's allowlist (configured per client with DEP-13, or a global `--callback-allow` list). Without one, callbacks are refused (400), so an arbitrary client can't make the server call internal addresses (**SSRF**).
- **Egress rules:** the egress rules of `ValidationTools` apply: https only (http only for loopback in tests), no private, link-local or loopback addresses after DNS resolution (`requirePublicHost`), and no redirects.
- **When it fires:** when the job reaches a final state.
- **What it sends:** `POST` with a JSON body `{jobId, status, exitCode, hasViolations, finishedAt, links}`. No result body: the client fetches it with its token.
- **Signature:** header `CimPal-Signature: t=<unix>,v1=<hex HMAC-SHA256(secret, t + "." + body)>`, with a per-client secret. Receivers check the signature and the timestamp (replay protection).
- **Delivery:** at least once, with exponential backoff for up to 24 h (durable with DEP-11). Each attempt is recorded and shown in `GET /v1/jobs/{id}` (`callback.lastStatus`).
- **Metrics:** `cimpal_callbacks_total{outcome}`.

**4. Clients.**
- **SDK:** documents verifying signatures, with `cimpal_client.verify_callback(secret, headers, body)`.
- **Airflow provider:** keeps polling, since its trigger is already cheap; webhooks are for other clients.

## Scope

- **Server:** the idempotency store and header handling (jobs, uploads), callback validation, delivery, signing and retries, metrics.
- **API and clients:** the OpenAPI document; SDK key handling and `verify_callback`; the provider's deterministic key.
- **Docs:** `serve.md`, `python-sdk.md`, `airflow.md` ("Why some failures don't retry" changes).

## Acceptance criteria (status checklist)

- [ ] Same key + same body → the same job id, and only one job runs (a test sends 5 concurrent identical requests).
- [ ] Same key + different body → 422; key in progress → 409; malformed key → 400.
- [ ] SDK: a `ReadTimeout` on submit is retried with the same key and yields one job (respx + a real-`serve` IT).
- [ ] Provider: a retried try doesn't create a second job (unit test with the key).
- [ ] Webhooks:
  - refused without an allowlist match;
  - refused for private, loopback and link-local targets (a regression test per class) and for redirects;
  - signed;
  - retried with backoff;
  - never carrying the result body or a token.
- [ ] Keys and deliveries survive a restart with DEP-11.
- [ ] OpenAPI, SDK and docs updated; `/security-review` clean (SSRF above all).

## Instructions for Claude Code

Start a session with: `Execute docs/plans/deployment/DEP-14.md` (in plan mode).

```text
Confirm D-19. If DEP-11 is not merged yet, implement the idempotency store behind an interface with an
in-memory version first.
1. Tests first: duplicate-submit tests (fail today).
2. Idempotency-Key on jobs and uploads; OpenAPI.
3. SDK keys and retry policy; provider key.
4. Webhooks with allowlist, egress checks reused from ValidationTools, signing, retries.
Run /security-review.
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
