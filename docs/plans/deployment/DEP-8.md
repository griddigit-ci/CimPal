<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-8 — Concurrent workers in one JVM (optional)

| Field | Value |
| --- | --- |
| Status | In review (decision D-8: yes, 2026-10-12) |
| Phase | D1 |
| Depends on | DEP-5; decision D-8 |
| Size | L |
| Branch | `feature/dep-8-concurrent-workers` |
| Requirements | — (scalability option) |
| Decisions needed | D-8 |

## Goal

Allow one `serve` instance to run more than one job at a time, safely. Today one worker is enforced because of `ValidationTools` static flags (`DEBUG`, `exportTurtleValidationReports`) and because `serve`/`mcp`/`run` capture command output by swapping the process-wide `System.out`.

The recommended production shape stays "one worker per container, more replicas" (see the plan README). Do this WP only if a customer needs several jobs per instance (e.g. many small validations where JVM start-up dominates).

## Scope

- Core: remove the remaining static mutable flags from `ValidationTools` (move to per-call options; `DEBUG` → per-run option or a logger level)
- CLI: in-process execution writes to per-invocation streams (picocli `CommandLine.setOut/setErr` and explicit `PrintStream` parameters) instead of `System.setOut`; all commands and Core progress output go through them
- `serve`: `--workers N` (default 1, max = available processors), memory admission (`--max-concurrent-heap-bytes` or a simple "one large job at a time" rule based on input size)
- Concurrency tests

## Acceptance criteria (status checklist)

- [x] No static mutable state reachable from a command run (ArchUnit-style test or a reviewed list in notes) — `MutableStaticsTest` plus the inventory below
- [x] No `System.setOut`/`System.setErr` in `serve`, `mcp`, `run` paths — per-command swaps replaced by `CliStreams`; only process-start settings remain (see notes)
- [x] Stress test: N workers × M jobs of mixed commands; every result equals the single-worker result for the same input (byte-equal JSON after normalisation) — `ConcurrencyIT`
- [x] Memory admission prevents two large jobs from running together (test with synthetic sizes) — `AdmissionGateTest`, `ServeWorkersTest`
- [x] Default stays 1 worker; behaviour with `--workers 1` unchanged
- [x] Sizing guide updated with the multi-worker case
- [x] `/security-review` clean — no High; the Medium and Low findings fixed (see notes)

## Instructions for Claude Code

Start a session with: `Execute docs/plans/deployment/DEP-8.md` (in plan mode).

```text
Only start if decision D-8 in docs/plans/deployment/README.md says yes; otherwise stop and say so.
1. Inventory: list every static mutable field reachable from the 10 serve commands (ValidationTools first) and every System.setOut/setErr use. Put the list in this file's notes before changing code.
2. Characterisation tests first (TEST-3 style) for the outputs that depend on those flags.
3. Replace static flags with per-call options; replace global stream swapping with per-invocation streams passed through picocli and Core APIs.
4. serve --workers N with a bounded pool; keep the bounded queue; add memory admission.
5. Stress test in a failsafe execution (it is slow): mixed jobs, compare with single-worker results.
6. Update docs/cli/serve.md (thread safety section), docs/guide/sizing.md and docs/PROJECT.md (remove the "serve serialises all requests" known issue if closed).
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
| 2026-10-12 | D-8 yes: implement `serve --workers N` | Maintainer |
| 2026-10-12 | Per-run state through inheritable thread-local contexts closed when the run ends (`PathPolicy.runWith`, `RunScope.runIn`, `CliStreams.bind`), not through new parameters on every Core API: Core creates its pools per run, so pool threads inherit the run's context | Claude Code (approved plan) |
| 2026-10-12 | A thread with an ended context, or without one while `serve` holds `PathPolicy.requireContext()`, is refused a path check (fail closed) | Claude Code (approved plan) |
| 2026-10-12 | Output: one routing `PrintStream` for `System.out`/`System.err` instead of passing streams through ~200 call sites; picocli `setOut/setErr` was not enough, as the commands and Core write to `System.out` directly | Claude Code (approved plan) |
| 2026-10-12 | Admission: estimate = 10 × input size, at least 64 MiB; budget `--max-concurrent-heap-bytes` (default 75 % of the heap); FIFO; a command alone always starts; an estimate that fails counts as the whole budget (runs alone) | Claude Code (approved plan) |
| 2026-10-12 | `--workers` is 1–64, not "at most the processors": the cap is fixed so a config doesn't depend on the machine; the docs recommend at most the cores | Claude Code |

## Notes and results

### Inventory (before the change)

**Mutable statics reachable from a `serve` command**

| Where | What happened with two commands at once | Now |
| --- | --- | --- |
| `PathPolicy.active` | One process-wide slot: commands ran under each other's folder rules, or none | Per run (`runWith`, inheritable, closed after); `serve` requires a context |
| `ModelFactory.zipfilesnames` | One list of zip entry names, already racing inside one run (`parallelStream`) | Removed; `unzipNamed` returns each stream with its name |
| `ComparisonSHACLshapes.modelsABPrefMap` | `compare --type shacl` runs used each other's prefixes | `compare()` uses a per-call map; the field stays, deprecated, for the GUI |
| `ValidationTools` remote caches | Cleared at the start of every run, so one run wiped another's cache mid-run | Per run (`RunScope`); process-wide without a scope |
| `ValidationTools.downloadXmlToCache` | Fixed temp name shared by two downloads of the same URL | Unique temp file, per-URL lock, an existing target is kept |
| `ValidationTools.exportTurtleValidationReports` | Unused setter | Removed (always false) |
| `ValidationTools.DEBUG` | GUI only | Reviewed, unchanged |
| `RDFtoSHACLOptionsPresets.DEFAULT_*` | Never written | Made `final` |
| `CustomRDFFormat.RegisterCustomFormatWriters` | Re-registered the writers on every convert | Registers once per JVM |
| ARQ "no SERVICE" setting | Always set to the same value | Unchanged |
| `RunStats` | JVM-wide numbers | Documented: they cover all running commands |
| Out of memory | Halts the JVM | Documented: every running command ends with exit 3 |

**`System.setOut`/`setErr` swaps:** `ServeCommand.runInProcess`, `StderrTee`, `ValidateCommand` (`--format json`), `RunCommand` (`--format json`), `McpCommand` (each tool call), and the `ServeLog` JSON wrapper. Two remain, both set once when the process starts and never per command: `mcp` points `System.out` at stderr to protect its channel, and `serve --log-format json` installs its JSON log as stderr.

**Single-job assumptions in `ServeServer`:** the single-thread worker, `runningJob`/`runningSince`, stall detection, health and metrics, the grace-period log line, and `ServeLog` attributing output to "the running job".

### Results

- Core: 439 tests green with the change (`PathPolicyTest`, new `RunScopeTest`, `ZipBudgetTest`, new `ComparisonSHACLshapesTest`).
- CLI: new `CliStreamsTest`, `AdmissionGateTest`, `ServeWorkersTest`, `MutableStaticsTest`; `ConcurrencyIT` (24 jobs: combined and mapping validation, sparql, convert, SHACL compare, rdfs2shacl; `--workers 4` equals `--workers 1`, logs not mixed) runs in about 8 s.
- Found on the way: `CliStreams.install()` first kept a routing stream in `System.out` without checking its role. After `mcp`'s `System.setOut(System.err)`, a later tool call's JSON went to stderr. `McpCommandTest` caught it, and `CliStreamsTest.stdoutPointedAtStderrStillRoutesAsStdout` now covers it.
- The stress test compares results without `shapeId`: the id of a blank-node property shape changes with every parse, also with one worker.

### Security review (2026-10-12)

No High findings. Fixed, each with a regression test:
- **Medium:** the size estimate walked input folders without an overall bound, outside every timeout. Now one budget of 100 000 entries for the whole estimate (folders count too), depth 32, duplicate paths once, links and junctions not followed; beyond it, or a folder it can't read, the command runs alone (`HeapEstimateTest`).
- **Medium:** when a cached download couldn't be replaced (Windows, file in use), the run silently kept the older copy. Now the copy stands in only if it is byte-identical to what was fetched; otherwise the download fails (`DownloadCacheContentTest`).
- **Low:** an `Error` from the estimator leaked a queue slot, and an out-of-memory there skipped the halt. Now the job is cancelled, the slot freed once, and OOM still stops the server (`ServeWorkersTest`).
- **Low:** `mcp` and `run` now also hold `PathPolicy.requireContext()` while a command runs; `runWith(null, …)` is refused; `CliStreams.install()` on a bound thread no longer makes the job's stream the default (`PathPolicyTest`, `CliStreamsTest`, `ServeCommandTest`).

Left as information: the JSON log may attribute a line a job left unfinished to the next job on the same worker (operator log only, as before); a cache file name close to 255 characters now fails to download (fails closed); in glob mode a matched remote file that fails to download is dropped if another match succeeds (pre-existing, not part of DEP-8).

### Open

- Common-pool threads (`parallelStream` in `ModelFactory`) don't inherit a run's context. Today they only parse in-memory streams; a path check there would be refused under `serve`, `mcp` and `run`, and their output goes to the process streams.
- Manual check with a real server: `serve --workers 3`, three slow validations, `/v1/health` and `/metrics` showing three running.
