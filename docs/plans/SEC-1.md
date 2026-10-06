<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# SEC-1 — Harden serve

| Field | Value |
| --- | --- |
| Status | In review ([PR #44](https://github.com/griddigit-ci/CimPal/pull/44)) |
| Phase | 1 |
| Depends on | TEST-1 |
| Size | M |
| Branch | `feature/sec-1-harden-serve` |

## Goal

Close gaps G1, G4 and G6: `serve` must not be callable by browser pages or other users, must bound its resources, and `run` must not nest servers or pipelines.

## Scope

- `ServeCommand`
- `RunCommand` (step allowlist, nesting cap)
- `docs/cli/serve.md`, `docs/cli/run.md`

## Acceptance criteria (status checklist)

- [x] Random 256-bit token per start, written to a user-only file; required on every endpoint except GET /health; constant-time compare; never logged
- [x] Host header must be localhost/127.0.0.1/[::1] with the right port, else 403
- [x] Any Origin header not in `--allow-origin` → 403; no CORS headers
- [x] POST requires `application/json` (415 otherwise); body capped at 1 MB (413)
- [x] /shutdown is POST-only and token-protected
- [x] Non-loopback `--host` requires token + `--allow-remote` and prints a warning
- [x] Per-request timeout and bounded queue (503 when full)
- [x] `run` refuses serve, mcp and run as steps
- [x] Tests for every item above

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
| 2026-09-30 | Default token file: `%LOCALAPPDATA%\CimPal\serve.token` on Windows, `~/.cimpal/serve.token` elsewhere, owner-only permissions; `--token-file` overrides it, and `CIMPAL_API_TOKEN` (at least 32 characters) replaces it, in which case no file is written. The file is deleted on stop, but only if it still holds this instance's token. | Maintainer |
| 2026-09-30 | The HTTP code stays in the CLI (`ServeServer`, `ServeSecurity`), not Core. It depends on `jdk.httpserver` and the serve lifecycle, which only the CLI has. The shared log sanitiser did move to Core (`LogSanitizer`), and `ValidationTools.forLog` now delegates to it. | Claude Code |
| 2026-09-30 | Check order: Host → Origin → exact path → method → token → Content-Type → size. `/health` skips only the token. `/shutdown` also needs `application/json`, so the rule "every POST is JSON" has no exception. | Claude Code |
| 2026-09-30 | Queue: one worker thread (the `ValidationTools` statics need it) and `--queue-size` waiting slots (max 64), else 503 with `Retry-After`. The timeout (`--request-timeout`, default `PT30M`) counts from receipt and returns 504. A running command that times out is not interrupted (`cancel(false)`), because an interrupt could leave truncated reports; it keeps its slot until it returns. | Claude Code |
| 2026-09-30 | `run` steps are an allowlist (the 10 commands `serve` exposes), not a denylist of `serve`/`mcp`/`run`, and in-process command lines (`CimPalCli.inProcess()`) turn off picocli `@file` expansion. The security review showed that `"command": "@file"` passed a denylist. A ThreadLocal depth guard (max 1) is kept as a second line of defence. | Claude Code |
| 2026-09-30 | Slow-client defence: the handler pool is `totalSlots + 16`, and `sun.net.httpserver.maxReqTime` is set to 60 s unless it is already set. `maxRspTime` is deliberately left alone, because its clock starts after the request is read and would cut off long commands. | Claude Code |
| 2026-09-30 | The token file is created and written through one handle (`CREATE_NEW`, `NOFOLLOW_LINKS`), with owner-only permissions set at creation: POSIX `rw-------`, or on Windows an initial `acl:acl` ACL. On POSIX, a parent directory that other users can write to is refused. | Claude Code |
| 2026-09-30 | Breaking change for existing `serve` callers: they must send `Authorization: Bearer <token>` and `Content-Type: application/json`. `mcp` is not affected. | Claude Code |
| 2026-10-01 | Second review round. The Windows token-file ACL names the user looked up as `USERDOMAIN\user.name`, because a bare name can resolve to a same-named local account. The ACL is read back and reset to that user alone before the token is written. On POSIX the parent check runs after creation and compares owners by principal (uid), not by name. A failed check deletes the file before anything is written. | Claude Code |
| 2026-10-01 | On shutdown, queued commands are never started, and `serve` waits up to 60 s for the running command before exiting. `/health` reports `busy`, `queued` and `stalled` (running longer than `--request-timeout`). | Claude Code |
| 2026-10-01 | The proof of the JDK read limit (`ServeServerReadTimeoutTest`) runs in its own surefire execution (`serve-read-timeout`, `-Dsun.net.httpserver.maxReqTime=2`), because the JDK reads the setting once per JVM. Accepted residual: the limit bounds how long a connection is held, not how many a local client opens. | Claude Code |

## Notes and results

### Results 2026-09-30

**Tests:** 95 before SEC-1 on `devel` (Core 81, Main 9, CLI 5). 175 after (Core 92, Main 9, CLI 72 plus 2 in the separate `serve-read-timeout` run). The final `mvn -B clean verify` passed on Windows on 2026-10-01.
- New CLI tests: `ServeServerTest` (32), `ServeSecurityTest` (14), `ServeCommandTest` (6), `RunCommandTest` (14), `ServeServerReadTimeoutTest` (2), plus one in `CimPalCliTest`.
- New Core test: `LogSanitizerTest` (11).
- Two `ServeSecurityTest` cases skip on Windows: the symlink case needs a privilege to create symlinks, and the other is POSIX-only. Both run on the Ubuntu CI leg.

**Coverage:** CLI line coverage rose from 3.0% to 22.6%, and branch coverage from 2.2% to 16.7%. The floors were raised with `Update-CoverageBaseline.ps1` (CLI 0.2206 / 0.1624, Core 0.2793 / 0.1773).

**Proofs:**
- **Mutation check.** Each safeguard was disabled in turn, and its tests failed every time. The safeguards were the Host, Origin and token checks, the queue bound, the step allowlist, the depth guard, `@file` expansion, the handler-pool size, the slot release on a queued timeout, and "delete the token file only if it's ours".
- **Smoke test against the real `CimPal-CLI.jar serve`.** `/health` returned 200 without a token, `/commands` returned 401 without one and 200 with one, `text/plain` got 415, and a bad config got 400. A rebinding Host got 403, a foreign Origin got 403, and `GET /shutdown` got 405. `POST /shutdown` returned 200, the process exited with 0, and the token file was removed. The token appeared in neither stdout nor stderr.
- **Old bug fixed along the way:** the old `serve` never exited after `/shutdown`, because it blocked forever on `Thread.currentThread().join()`.

**Security review** (`/security-check-change`, `security-reviewer` agent) found no High issues.
- **Two Medium findings, both fixed with regression tests.** A step `"command":"@file"` passed the denylist, because picocli expands `@file` arguments. And a few slow connections could exhaust the handler threads, because the JDK server has no read timeout.
- **Three Low findings, all fixed.** The token file's create and restrict steps were not atomic and followed symlinks. The log sanitiser missed U+0085, U+2028 and U+2029. And a command interrupted on timeout could leave truncated reports.
- **Info items, all fixed.** Operator limits are now capped, the thread pools are built before the port is bound, a null path gets 404, and the token file is deleted only if it still holds this instance's token.
- **Re-review of the fixes.** Findings 1, 4 and 6 were confirmed closed. It also found a new Medium in the Windows ACL fix: the bare `user.name` lookup can name a different, same-named account. Several Low items followed:
  - A queued command could still start after shutdown, and exit cut off the running one.
  - A hung command was invisible in `/health`.
  - The POSIX owner was compared by name, and the parent check had a race.
  - Some `run` output was not sanitised.
  - Nothing proved that the JDK honours the read limit.

  All of these were fixed, each with a regression test, in a second round on 2026-10-01. Each new test fails when its fix is removed (mutation check). `ServeServerReadTimeoutTest` shows that a stalled connection is closed after the limit, and that a command running twice as long as the limit still gets its 200.

**Left open:**
1. G4 is only partly closed. `mcp` still has no timeout, queue or memory guard, and `serve` has no memory guard. That belongs to a later WP (SEC-3, or a new one).
2. Callers of `serve` outside this repo must be updated to send the token and `Content-Type` headers.
3. Accepted residuals, documented in `docs/cli/serve.md`. A local process that keeps opening slow connections can still slow `serve` down. A command that hangs holds the worker until restart; `/health` shows `stalled`. On Windows, if the qualified account lookup fails, the token file is created first and restricted before the write; this only matters for a `--token-file` in a shared folder.
4. [PR #44](https://github.com/griddigit-ci/CimPal/pull/44) into `devel`: check that both CI legs are green, including the POSIX-only token-file tests on Ubuntu.

