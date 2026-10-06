<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-2 — Resource statistics, benchmark and sizing guide

| Field | Value |
| --- | --- |
| Status | In review ([PR #54](https://github.com/griddigit-ci/CimPal/pull/54)) |
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

- [x] `--stats` adds `stats` = `{wallMs, phases{...}, cpuMs, peakHeapBytes, maxHeapBytes, peakRssBytes|null, gcMs, availableProcessors, inputBytes, triplesLoaded, javaVersion, os}`. The default output is unchanged (byte-identical, `StatsJsonTest`). The schema is `CimPal-CLI/src/test/resources/fixtures/cli-json/stats.schema.json`, beside the existing schema fixture rather than a new `schemas/` folder, and is validated against real output.
- [x] Stats collection adds < 2 % to wall time: on the 1M model the median process time was 10.80 s with `--stats` and 10.88 s without (−0.8 %, noise).
- [x] `OutOfMemoryError` anywhere in a command → exit 3, one clear stderr line naming `-Xmx`/container memory and `docs/guide/sizing.md`. `OutOfMemoryExitTest` forks `-Xmx16m` JVMs, under surefire rather than failsafe, for mapping, timestamped and `mcp`.
- [x] *(changed)* The synthetic generator is `scripts/bench/gen_models.py` (Python, seeded), not `TestModels.scalable`. It produces EQ+SSH-like models at a requested triple count with exact counts, plus shapes exercising minCount, datatype, class and SPARQL. Violations are controlled (1 %), and `--verify` checks them against a real run.
- [x] `scripts/bench/run_bench.py`:
  - matrix over models × `-Xmx` (or `--memory`) × cores (`-XX:ActiveProcessorCount` or `--cpus`);
  - launchers: the JAR, a classpath, or the DEP-1 image with `--docker`;
  - one CSV row per run with every stats field and the hardware;
  - a median/max summary.
- [x] Finds, per model size, the smallest heap that completes without OOM and with a GC share < 10 %, and the core count beyond which wall time improves < 10 %.
- [x] `docs/guide/sizing.md` covers:
  - method and hardware;
  - a results table per size class;
  - the rule of thumb (0.6 GB heap per million triples; 2–4 cores);
  - container memory = heap ÷ 0.75;
  - a Kubernetes example;
  - how to re-run.
- [x] Hook for TEST-5: `--budget file.json` asserts median time and peak heap per size and exits 1 on a breach.

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
| 2026-10-05 | The model generator is Python in `scripts/bench`, not a Java `TestModels.scalable`. The runner and TEST-5 call it directly, without the Maven test classpath. Java tests keep the small `TestModels` fixtures. | Maintainer |
| 2026-10-05 | D-4: synthetic models only. ENTSO-E conformity models wait for the licence decision. | Maintainer |
| 2026-10-05 | The first benchmark ran on the maintainer's laptop with 100k/1M/5M triples and a reduced matrix: min-heap bisection, then a core sweep at twice that heap. | Maintainer |
| 2026-10-05 | `RunStats` reaches `ValidationTools` as an explicit parameter (`MappingValidationOptions.runStats`). The existing overloads delegate with `null`, so other callers are untouched, and there is no static or thread-local state. | Claude Code |
| 2026-10-05 | `triplesLoaded` means unique triples parsed from instance files. Plain mapping counts the merged data model of each row (a file used by two rows counts twice, as it is parsed twice). Timestamped counts each cached file once, when its cache is dropped. (The CLI's `manual` workflow was removed by PR #53.) | Claude Code |
| 2026-10-05 | Stats go inside the one JSON object (a last field), never as a separate block, so `serve` and `mcp` keep returning a single JSON value. In text/CSV mode or with `--output`, they are one `[STATS]` line on stderr. Wall time ends where the output starts. | Claude Code |
| 2026-10-05 | Out of memory:<ul><li>Core rethrows an `OutOfMemoryError` found in an `ExecutionException` or row-level exception (`OutOfMemoryRethrow`), and stops the row pool first.</li><li>picocli's handler rethrows it, and `main` prints a prebuilt line and halts with 3.</li><li>`serve` answers the request with exit 3 and halts too, because its JVM can't be trusted afterwards.</li><li>`mcp` answers the tool call with JSON-RPC `-32603` before exiting with 3.</li></ul>The container's `ExitOnOutOfMemoryError` (DEP-1) gives the same exit code. | Claude Code; `serve` and `mcp` parts from the security review |
| 2026-10-05 | The OOM test is a surefire test that forks a JVM on the test classpath. Failsafe with the packaged JAR would need the JAR, which Windows locks while the local MCP server runs. | Claude Code |
| 2026-10-05 | `os` in the stats is the name and architecture only. `os.version` (the kernel build) would help `serve`/`mcp` clients pick exploits (security review). | Claude Code |
| 2026-10-05 | `peakHeapBytes` is the sum of each heap pool's own peak, an upper bound that can exceed `-Xmx`. Minimum heaps come from runs at a given `-Xmx`, not from this figure. | Claude Code |

## Notes and results

- **Benchmark (2026-10-05):**
  - Machine: i7-1360P laptop, 16 GB, Windows 11, JDK 25.0.1. 76 runs, about 24 minutes. Data in `docs/guide/sizing-data/`.
  - Smallest heap (GC share < 10 %): 87 MB at 100k, 350 MB at 1M, 1.4 GB at 5M triples, i.e. about 0.26 GB per million triples above about 0.1 GB.
  - Median wall time at twice that heap:

    | Model | 1 core | 2 cores | 4 cores | 8 cores |
    | --- | --- | --- | --- | --- |
    | 1M | 13.1 s | 11.1 s | 9.2 s | 11.8 s |
    | 5M | 39.1 s | 48.4 s | 32.7 s | 29.9 s |

  - The 5M 2-core cell is noise: 41–55 s, from the P/E cores and throttling.
  - The "knee" heuristic says 4 cores (1M) and 1 core (5M, because of that noisy 2-core cell). The guide states 2–4 cores and explains why.
- **Verification:** `run_bench.py --verify` at 20k gave triplesLoaded 19,977 (the manifest's exact count) and 9 SHACL results, the 9 expected across all five violation kinds.
- **Security review (`security-reviewer`):** no Critical or High findings. Fixed here:
  - Medium: `serve` survived an OOM (generic 500, sibling rows still running). It now answers with exit 3 and halts; the row pool gets `shutdownNow()` before the rethrow.
  - Low:
    - `mcp` gave no reply for the OOM request (now JSON-RPC `-32603`);
    - host fingerprinting through `os.version` (removed);
    - row-level `catch (Exception)` sites that could swallow a wrapped OOM (now rethrow);
    - `--docker` runs failing on Linux because of the private temp folder (`--user` uid:gid).
  - Added tests:
    - `ServeServerTest`: OOM halts, an ordinary failure doesn't;
    - `OutOfMemoryExitTest`: timestamped and `mcp` cases;
    - `StatsJsonTest`: escaping of hostile strings.
- **Tests:** CLI `OutOfMemoryExitTest` 3, `StatsJsonTest` 7, `McpCommandTest` +1, `ServeServerTest` +2; Core `RunStatsTest` 8, `OutOfMemoryRethrowTest` 5, `MappingValidatorTest` +2; generator self-test 7.
- **Open:**
  - **Linux runs:** a Linux machine and a `--docker` series, for peak RSS and a real `--cpus` quota.
  - **Other inputs:** multi-row and timestamped benchmarks, and real CGMES conformity models once D-4 allows them.
  - **`manual` workflow:** the review found it always exited 0; PR #53 removed it from the CLI, so that is moot.
  - **DEP-4:** link the guide from the external user guide.
