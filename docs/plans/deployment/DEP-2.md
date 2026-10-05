<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-2 — Resource statistics, benchmark and sizing guide

| Field | Value |
| --- | --- |
| Status | Not started |
| Phase | D0 |
| Depends on | DEP-1 (soft: container runs give cgroup-limited numbers) |
| Size | M |
| Branch | `feature/dep-2-sizing` |
| Requirements | R2 (OOM part), R3, R4 |
| Decisions needed | D-4 reference models |

## Goal

Be able to state, with evidence, how much CPU and memory CimPal needs: "for a model of N triples, give it X GB and Y cores; it takes about Z minutes". Three parts: CimPal reports its own resource use per run; a benchmark runner sweeps model sizes, memory limits and core counts; the results become a sizing guide.

## How resource use is measured (method)

| Metric | Source | Notes |
| --- | --- | --- |
| Wall time | `System.nanoTime()` around the command | Reported separately for load, validate/process and report-writing phases where the code allows |
| CPU time | `com.sun.management.OperatingSystemMXBean.getProcessCpuTime()` | CPU-seconds; CPU time ÷ wall time ≈ cores actually used |
| Peak heap | Sum of `MemoryPoolMXBean.getPeakUsage().getUsed()` over heap pools | What `-Xmx` must cover |
| Max heap | `Runtime.maxMemory()` | The limit the run had |
| Peak RSS | Linux: `VmHWM` from `/proc/self/status`; container: `memory.peak` (cgroup v2) read by the runner | What Kubernetes sees; null where unavailable (Windows) |
| GC time | `GarbageCollectorMXBean.getCollectionTime()` | High GC share means the heap is too small |
| Available cores | `Runtime.availableProcessors()` | Honours container limits and `-XX:ActiveProcessorCount` |
| Input size | Bytes of input files, and triples loaded | Triples are the size measure for sizing, not MB |

Rules: same hardware for a whole series, record the hardware, 1 warm-up + 3 measured runs, report median and max, cold JVM per run (that is how CLI and container users run it).

## Scope

- CLI: a global `--stats` option (JSON-capable commands) that adds a `stats` object to the JSON result; additive change to the JSON Schemas
- Core: a small `RunStats` collector (no static state) plus a triples counter at model-load points used by `validate`
- Top-level `OutOfMemoryError` handling in `CimPalCli`: exit 3, message on stderr pointing to `-Xmx` and the sizing guide
- Synthetic scalable model generator in Core test support (`TestModels`), usable from a runner
- `scripts/bench/` runner (Python 3, standard library only) and `scripts/bench/README.md`
- `docs/guide/sizing.md` (new; DEP-4 links it), results CSV under `docs/guide/sizing-data/`

## Acceptance criteria (status checklist)

- [ ] `--stats` adds `stats` = `{wallMs, phases{...}, cpuMs, peakHeapBytes, maxHeapBytes, peakRssBytes|null, gcMs, availableProcessors, inputBytes, triplesLoaded, javaVersion, os}`; default output unchanged; JSON Schemas updated and tested
- [ ] Stats collection adds < 2 % to wall time (measured on the medium synthetic model; numbers in notes)
- [ ] `OutOfMemoryError` anywhere in a command → exit 3, one clear stderr line naming `-Xmx`/container memory and `docs/guide/sizing.md`; test with a tiny `-Xmx` in a forked JVM (failsafe)
- [ ] Synthetic generator produces EQ+SSH-like models at a requested triple count (100k, 1M, 5M, 10M) deterministically (seeded), with matching SHACL shapes, so a validation does realistic work
- [ ] `scripts/bench/run_bench.py`: matrix over models × `-Xmx` (or container `--memory`) × cores (`-XX:ActiveProcessorCount` or `--cpus`); runs the JAR (or the DEP-1 image with `--docker`); writes one CSV row per run with all stats fields plus hardware info; median/max summary
- [ ] Finds, per model size, the smallest heap that completes without OOM and with GC share < 10 %, and the core count beyond which wall time improves < 10 %
- [ ] `docs/guide/sizing.md`: method, hardware, results table per size class, rule of thumb (GB heap per million triples, recommended cores), container memory = heap ÷ 0.75, Kubernetes `requests/limits` example, how to re-run
- [ ] Hook for TEST-5: the runner can assert time and heap budgets and exit non-zero (`--budget file.json`)

## Instructions for Claude Code

Start a session with: `Execute docs/plans/deployment/DEP-2.md` (in plan mode).

```text
Implement R3/R4 and the OOM part of R2 (docs/plans/deployment/README.md).
1. Core: add eu.griddigit.cimpal.core.stats.RunStats (instance-based, no statics): start/stop, named phases, snapshot of CPU time, heap pool peaks (reset peaks at start), GC time, available processors, peak RSS (Linux /proc/self/status VmHWM; null elsewhere). Unit tests.
2. Triples: count triples at the model-load points used by validate (mapping, timestamped, manual workflows) and by sparql/compare where cheap. Pass the RunStats through the builder options (MappingValidationOptions / SHACLValidationOptions), not through ValidationTools statics.
3. CLI: a --stats flag on the four JSON-capable commands; when set, the JSON result gets a "stats" object (fields in the acceptance list). Update the JSON Schemas (TEST-4 location if it exists, else CimPal-CLI/src/test/resources/schemas/) and docs/cli/*.md. Default output must be byte-identical without --stats (characterisation test).
4. CimPalCli: catch OutOfMemoryError at the top level → stderr message + exit 3. Failsafe test that runs the packaged JAR with -Xmx16m on a model that needs more and asserts exit 3 and the message.
5. Test support: TestModels.scalable(long triples, long seed) writes an EQ+SSH-like RDF/XML set plus shapes that exercise minCount, datatype, class and SPARQL constraints, with a controlled violation rate (~1 %).
6. scripts/bench/run_bench.py (stdlib only, Python 3.10+): generates/locates models, runs the matrix, collects --stats JSON (plus cgroup memory.peak in --docker mode), writes CSV + a markdown summary; --budget mode for TEST-5. Document in scripts/bench/README.md.
7. Run the benchmark on the maintainer's machine for 100k / 1M / 5M triples (10M if it fits), record hardware, write docs/guide/sizing.md and the CSV under docs/guide/sizing-data/. Use synthetic models unless decision D-4 allows ENTSO-E models.
Tests first for RunStats, the --stats JSON shape and the OOM exit. Run /security-review (reads /proc, process handling).
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
