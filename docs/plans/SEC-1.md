<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# SEC-1 — Harden serve

| Field | Value |
| --- | --- |
| Status | Not started |
| Phase | 1 |
| Depends on | TEST-1 |
| Size | M |
| Branch | `feature/sec-1-...` |

## Goal

Close gaps G1, G4 and G6: `serve` must not be callable by browser pages or other users, must bound its resources, and `run` must not nest servers or pipelines.

## Scope

- `ServeCommand`
- `RunCommand` (step allowlist, nesting cap)
- `docs/cli/serve.md`, `docs/cli/run.md`

## Acceptance criteria (status checklist)

- [ ] Random 256-bit token per start, written to a user-only file; required on every endpoint except GET /health; constant-time compare; never logged
- [ ] Host header must be localhost/127.0.0.1/[::1] with the right port, else 403
- [ ] Any Origin header not in `--allow-origin` → 403; no CORS headers
- [ ] POST requires `application/json` (415 otherwise); body capped at 1 MB (413)
- [ ] /shutdown is POST-only and token-protected
- [ ] Non-loopback `--host` requires token + `--allow-remote` and prints a warning
- [ ] Per-request timeout and bounded queue (503 when full)
- [ ] `run` refuses serve, mcp and run as steps
- [ ] Tests for every item above

## Instructions for Claude Code

Start a session with: `Execute docs/plans/SEC-1.md` (in plan mode).

```text
Harden ServeCommand (gaps G1, G4, G6). Write the failing security tests first (CLI module, using the TEST-1 harness), then fix.
Required behaviour:
- On start, generate a 256-bit random token; write it to a user-only file (default %LOCALAPPDATA%/CimPal/serve.token or ~/.cimpal/serve.token; configurable via --token-file; also accept CIMPAL_API_TOKEN). Every endpoint except GET /health requires `Authorization: Bearer <token>`; compare in constant time. Never log the token.
- Reject requests whose Host header is not localhost:<port> / 127.0.0.1:<port> / [::1]:<port> (DNS-rebinding defence). Reject any request carrying an Origin header unless it is in an explicit --allow-origin list. Require Content-Type application/json on POST. No CORS headers.
- Limit request bodies to 1 MB (configurable); read through a bounded stream, return 413.
- /shutdown: POST only, token required.
- --host other than loopback requires both a token and --allow-remote, and prints a warning.
- Per-request timeout (default 30 min, configurable) and a bounded queue (default 4) returning 503 when full.
- `run` must refuse serve, mcp and run as pipeline steps; cap nesting.
Tests must cover: missing/wrong token 401, bad Host 403, foreign Origin 403, text/plain POST 415, oversized body 413, GET /shutdown 405, queue full 503, run refusing nested serve.
Update docs/cli/serve.md (security section, token usage in curl examples) and docs/cli/run.md.
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
