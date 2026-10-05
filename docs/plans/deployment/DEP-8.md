<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-8 — Concurrent workers in one JVM (optional)

| Field | Value |
| --- | --- |
| Status | Not started (optional — only after decision D-8) |
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

- [ ] No static mutable state reachable from a command run (ArchUnit-style test or a reviewed list in notes)
- [ ] No `System.setOut`/`System.setErr` in `serve`, `mcp`, `run` paths
- [ ] Stress test: N workers × M jobs of mixed commands; every result equals the single-worker result for the same input (byte-equal JSON after normalisation)
- [ ] Memory admission prevents two large jobs from running together (test with synthetic sizes)
- [ ] Default stays 1 worker; behaviour with `--workers 1` unchanged
- [ ] Sizing guide updated with the multi-worker case
- [ ] `/security-review` clean

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

## Notes and results

(Claude Code: record findings, baseline numbers and open items here.)
