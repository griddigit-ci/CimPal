<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# Benchmark: model size → memory and cores

These scripts measure how much heap, CPU and time `validate` needs per model size (DEP-2). The [sizing guide](../../docs/guide/sizing.md) is built from their output, and anyone can re-run them. They need Python 3.10 or later (standard library only) and the CimPal CLI (JAR, classpath or Docker image).

| File | What it does |
|---|---|
| `gen_models.py` | Writes a synthetic CGMES-like model set of a requested size: EQ and SSH RDF/XML, SHACL shapes, a mapping CSV, and a `manifest.json` with the exact triple count and expected violations. |
| `run_bench.py` | Runs `validate --stats` over those models with different heaps and core counts, a cold JVM per run, and writes `results.csv` and `summary.md`. |
| `test_gen_models.py` | Self-test of the generator: `python -m unittest discover -s scripts/bench -p "test_*.py"` |

## The models

`gen_models.py --triples 1M --seed 1 --out DIR` writes one validation row by default: an EQ file and an SSH file that together hold the requested number of unique triples, never more.
- **Content:** `ACLineSegment`s, each with two `Terminal`s and two `ConnectivityNode`s, in `VoltageLevel`s of 100 lines, plus the SSH `Terminal.connected` values.
- **Determinism:** the same seed and parameters give byte-identical files.
- **Shapes:** `bench-shapes.ttl` checks `minCount`, `datatype`, `sh:class` and one SHACL-SPARQL constraint (`r > x`).
- **Violations:** by default 1 % of the lines break exactly one constraint each, round-robin over five kinds. The number of SHACL results is therefore known in advance (`expectedViolationsTotal`).
- **Rows:** `--rows K` splits the triples into K groups, one mapping row each, to measure row parallelism.

The models are synthetic (decision D-4): no ENTSO-E or customer data is needed.

## Running

Build the CLI first (`mvn -B -pl CimPal-CLI -am package -DskipTests`), then from the repository root:

```bash
# Check the set-up: triplesLoaded and the SHACL result count must match the generator's manifest.
python scripts/bench/run_bench.py --sizes 100k --verify --no-matrix

# What the sizing guide used: smallest heap per size, then a core sweep at twice that heap.
python scripts/bench/run_bench.py --sizes 100k,1M,5M --find-min-heap --heap-range 64m:12g \
    --core-sweep --cores 1,2,4,8 --warmup 1 --runs 3 --out target/bench/my-machine

# A plain matrix of heaps and cores.
python scripts/bench/run_bench.py --sizes 1M --heaps 1g,2g,4g --cores 2,4
```

- **Models:** generated once under `target/bench/models/<size>-seed<seed>/` and reused while the seed and generator version match.
- **Results:** `--out` (default `target/bench/results`) receives `results.csv` and `summary.md`.
  - `results.csv` has one row per run (warm-up, probe and verify runs included, marked in `kind`), with every `--stats` field, the exit code, the GC share, the process seconds (JVM start-up included), and the CPU, RAM and OS of the machine.
- **Launchers:**
  - `--jar PATH`, the default: `CimPal-CLI/target/CimPal-CLI.jar`.
  - `--classpath CP`: a development tree. Get `CP` with `mvn -pl CimPal-CLI dependency:build-classpath`, plus `CimPal-CLI/target/classes`.
  - `--docker IMAGE`: the [container image](../../docs/cli/docker.md). `--heaps` becomes `--memory` and `--cores` becomes `--cpus`, and the cgroup's `memory.peak` is recorded too (`cgroupPeakBytes`). On Linux and macOS the container runs as your UID, so it can write to the run's private temporary folder. The repository's tests don't exercise this mode.
- **Executables:** `java`, `docker` and (macOS) `sysctl` come from `PATH`; pass `--java` to pin the JVM.

## Method

- **Cold JVM per run,** as CLI and container users run CimPal: 1 warm-up run (discarded), then 3 measured runs. The summary reports the median and the maximum.
- **The same machine for a whole series,** with nothing else running. Laptops vary with temperature and power mode.
- **Smallest heap (`--find-min-heap`):** bisection between the two ends of `--heap-range`, at all cores. A heap passes when the run completes (exit 0 or 1, not 3) and the GC share, `gcMs / wallMs`, stays under `--max-gc-share` (default 10 %). The bisection stops within 10 % or 32 MB.
- **Cores (`--core-sweep`):** `-XX:ActiveProcessorCount=N` sets how many cores the JVM, and CimPal's `--workers 0` auto-sizing, plan for. It is **not** a CPU quota: JIT and GC threads may still use other cores. Use `--docker` (`--cpus`) or `taskset` for a hard limit. The summary names the core count beyond which the next step improves the median wall time by less than 10 %.

## Budgets (for nightly runs)

`--budget budget.json` checks the median wall time and peak heap of every measured cell per size and exits 1 on a breach:

```json
{"1M": {"maxWallMs": 60000, "maxPeakHeapBytes": 2147483648}}
```

A cell whose runs did not all complete is a breach too. TEST-5's nightly job is meant to call it with budgets taken from the sizing guide plus a margin.
