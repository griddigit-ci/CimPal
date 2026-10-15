<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-15 — Job isolation: one child process per job

| Field | Value |
| --- | --- |
| Status | Not started |
| Phase | D3 (option 2 hardening, see [OPTION-2-ROADMAP.md](OPTION-2-ROADMAP.md)) |
| Depends on | DEP-5, DEP-8 (independent of DEP-11…14) |
| Size | M–L |
| Branch | `feature/dep-15-job-isolation` |
| Requirements | R21 |
| Decisions needed | none (plan mode: confirm the default stays in-process) |

## Goal

With `--isolation process`, each job runs in its own child JVM. That gives:
- **Memory limits per job:** each child gets its own `-Xmx` from the job's size estimate, capped.
- **Failure isolation:** one job's out-of-memory error ends only that job. Today it halts the whole server and every job running on it.
- **Real cancellation:** `DELETE` on a running job kills its process; today a running job can't be stopped.
- **Hard timeouts:** a job over `--job-timeout` is killed instead of becoming "stalled".

## Today

- **One JVM:** jobs run in-process, in one heap. `OutOfMemoryExit.halt()` ends the server after any job's OOM, because the heap can no longer be trusted (DEP-2).
- **No stopping:** a running job can't be interrupted, so it can only be marked `timed_out` or "stalled".
- **Shared heap:** `--workers N` shares the heap; the `AdmissionGate` estimate keeps big jobs apart, but it is only an estimate.

## Design

1. **`JobRunner` interface** (in `ServeServer`/`ServeCommand`):
   - `InProcessRunner` is today's `ServeCommand.runInProcess` with `CliStreams` and `RunScope`, and stays the default.
   - `ProcessRunner` starts `java -Xmx<n> [--enable-native-access…] -cp <same classpath> eu.griddigit.CimPal.cli.CimPalCli <command> --config <checked config> --format json`.
2. **The child JVM.**
   - **Classpath and JVM options:** the same JAR and module path as the server, read from `ProcessHandle.current().info()` and `java.home`. An explicit `--isolation-java` overrides them.
   - **Heap:** `-Xmx` is the job's DEP-8 estimate × a safety factor, capped by `--max-job-heap-bytes`. The admission budget then counts reserved child heaps (real numbers, not estimates).
   - **Environment:** a minimal environment, not inherited. In particular it carries no token variables: the child never receives the server's tokens.
   - **Path policy:** the same, enforced by passing the checked config and running the child with the CLI's own checks. PathGuard has already checked the config, and the CLI re-checks it under its `--root` options, which the server passes explicitly.
3. **Output.**
   - The child's stdout is the job's JSON result.
   - Its stderr lines go into the job log (cleaned and capped, as `StderrTee` does).
   - Exit codes 0/1/2/3 map as today; an OOM in the child ends it with exit 3 ("out of memory") and the job fails. **The server keeps running.**
4. **Cancel and timeout.**
   - `DELETE /v1/jobs/{id}` on a running job: `destroy()`, then after a grace `destroyForcibly()`. The job becomes `cancelled` ("stopped while running; outputs may be incomplete"). The OpenAPI document updates the 409 case (only `--isolation in-process` still refuses).
   - `--job-timeout` kills the child, and the job becomes `timed_out` with "stopped".
   - Server shutdown stops claiming jobs and waits for children within `--shutdown-grace`, then kills them.
5. **Cost:** JVM start-up adds about 1–2 s per job, so in-process stays the default and process isolation is recommended for large models and shared services.
6. **Combinations:** it combines with DEP-11/12 (the claim loop doesn't care how a job runs) and with `--workers N` (N children at most).

## Scope

- **Code:** `JobRunner`, `ProcessRunner`, options (`--isolation`, `--max-job-heap-bytes`, `--isolation-java`), cancel of running jobs, kill on timeout, and admission by reserved heap.
- **OpenAPI and SDK:** `cancel()` documents that running jobs can now be stopped with isolation.
- **Docs:** `serve.md`, `sizing.md` (memory per job), `service.md`.

## Acceptance criteria (status checklist)

- [ ] `--isolation in-process` (the default) is unchanged: all existing tests green.
- [ ] With `--isolation process`: `ConcurrencyIT`'s 24 mixed jobs give the same results as in-process.
- [ ] A job that runs out of memory (forced with a tiny `--max-job-heap-bytes`) fails alone, and a parallel job and the server carry on (IT).
- [ ] `DELETE` on a running job stops it within the grace; the job is `cancelled`, and no orphan process remains (a test checks the process tree).
- [ ] `--job-timeout` kills a running child.
- [ ] The child environment holds no server token and no unrelated variables (a test inspects it via a probe command).
- [ ] Path policy: a config path outside the roots is refused in the child too (a regression test).
- [ ] Docs updated; `/security-review` clean (process creation as an argument list, environment, classpath injection).

## Instructions for Claude Code

Start a session with: `Execute docs/plans/deployment/DEP-15.md` (in plan mode).

```text
1. Tests first: OOM isolation and running-job cancel (fail today).
2. JobRunner + ProcessRunner (ProcessBuilder with an argument list, minimal env), output and log capture.
3. Cancel/timeout kill, shutdown handling, admission by reserved heap.
4. ConcurrencyIT in process mode; docs.
Run /security-review (processes: argument lists only, no shell; environment; classpath).
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
