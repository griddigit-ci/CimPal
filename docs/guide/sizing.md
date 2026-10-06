<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# Sizing: memory, cores and time by model size

*Last reviewed 2026-10-06 · Describes CimPal 2026.10.6.1 and later; measured on 2026-10-05*

How much heap and how many cores a CimPal validation needs, measured with [`--stats`](../cli/README.md#resource-statistics---stats) and the [benchmark scripts](../../scripts/bench/README.md). The size measure is **triples**: `--stats` reports it as `triplesLoaded`, and it predicts memory far better than file size.

## Short answer

| Model size | RDF/XML on disk | Smallest heap that works | Recommended heap (`-Xmx`) | Container memory limit | Validation time |
|---|---|---|---|---|---|
| 100 k triples | ~7 MB | 87 MB | 512 MB | 768 MB | ~3 s |
| 1 M triples | ~70 MB | 350 MB | 1 GB | 1.5 GB | ~9–13 s |
| 5 M triples | ~350 MB | 1.4 GB | 3 GB | 4 GB | ~30–40 s |

**Rule of thumb:** plan **0.6 GB of heap per million triples**, at least 512 MB, which is about 9× the size of the RDF/XML files. The container gets the heap ÷ 0.75, because the [Docker image](../cli/docker.md#memory-and-cpu) gives the heap 75 % of the container's memory and the rest goes to metaspace, threads and buffers.

**Cores:** 2–4 per validation run. More cores helped little for one model (see below). They pay off when a mapping CSV has several rows, which `--workers` validates in parallel, but that was not measured here.

These figures are a **lower bound for real CGMES data**. The benchmark's shapes are small: 3 node shapes and 14 constraints, one of them SHACL-SPARQL. The CGMES 3.0 constraint sets have hundreds of shapes and many SPARQL constraints. That costs mostly CPU time, but also heap for reports.

**Measure your own models** with `--stats` before fixing production limits:

```bash
java -Xmx4g -jar CimPal-CLI.jar validate --config run.json --format json --stats
```

Then compare `stats.triplesLoaded`, `stats.wallMs` and `stats.gcMs` with the table. A GC share (`gcMs / wallMs`) above about 10 % means the heap is too small.

## When the heap is too small

The run ends at once with exit code **3** and this line on stderr:

```
[ERROR] Out of memory (max heap N MB). Give the JVM more memory (-Xmx, or the container's memory limit); see docs/guide/sizing.md for sizes by model.
```

It is never reported as exit 1 ("violations found") and never as a failed row. `serve` stops as well, after answering the request with exit code 3, and `mcp` answers the tool call with a JSON-RPC error and exits.

Close to the limit, but before that, runs get slow: the GC share climbs. In the benchmark, 1M triples at 302 MB still completed, but with a 13 % GC share.

## Setting the memory

- **JAR:** `java -Xmx3g -jar CimPal-CLI.jar ...`.
- **Docker:** `docker run --memory 4g ...`. The image sets `-XX:MaxRAMPercentage=75`, which gives a 3 GB heap. Or set `-e JAVA_OPTS=-Xmx3g` directly.
- **Kubernetes:** set requests equal to limits for memory, so the pod isn't evicted under node pressure:

```yaml
resources:
  requests:
    memory: "4Gi"   # 5 M triples: 3 GB heap / 0.75
    cpu: "2"
  limits:
    memory: "4Gi"
    cpu: "4"
```

- **Docker Desktop (Windows, macOS):** its Linux VM's memory is the upper bound (WSL 2: `.wslconfig`).

## Measurements

**Machine:**
- Laptop: Intel Core i7-1360P (4 performance and 8 efficiency cores, 16 threads), 16 GB RAM, Windows 11, JDK 25.0.1.
- Date: 2026-10-05.
- Raw data: [`sizing-data/2026-10-05-laptop-i7-1360P.csv`](sizing-data/2026-10-05-laptop-i7-1360P.csv) (76 runs) and its [summary](sizing-data/2026-10-05-laptop-i7-1360P.md).

**Models:** `scripts/bench/gen_models.py`, seed 1, one mapping row each. They are synthetic, CGMES-like EQ and SSH files with `ACLineSegment`, `Terminal`, `ConnectivityNode`, `VoltageLevel` and `Substation`, and 1 % of the lines break one constraint each.

**Method:**
- A cold JVM per run.
- **Smallest heap:** bisection of `-Xmx` at all cores. A heap passes when the run completes with a GC share under 10 %.
- **Cores:** `-XX:ActiveProcessorCount` at twice the smallest heap, with 1 warm-up and 3 measured runs. The tables give medians.
- **What wall time covers:** the command's own time. JVM start-up adds about 1.5–2 s.

### Smallest heap

| Triples | Passes | Fails |
|---|---|---|
| 99,967 | 87 MB (GC 3 %) | — (87 MB was the lowest probe) |
| 999,840 | 350 MB (GC 9 %) | 326 MB (GC 12 %), 255 MB (out of memory) |
| 4,999,327 | 1.4 GB (GC 10 %) | 1.3 GB (out of memory) |

The smallest heap grows by about 0.26 GB per million triples above a base of about 0.1 GB. The recommended 0.6 GB per million leaves room for GC, for more parallel workers and for real constraint sets.

### Cores (at twice the smallest heap)

| Triples | Heap | 1 core | 2 cores | 4 cores | 8 cores |
|---|---|---|---|---|---|
| 100 k | 174 MB | **3.0 s** | 3.6 s | 3.8 s | 4.1 s |
| 1 M | 700 MB | 13.1 s | 11.1 s | **9.2 s** | 11.8 s |
| 5 M | 2.8 GB | 39.1 s | 48.4 s | 32.7 s | **29.9 s** |

- **Gains from cores are modest for one model.** One mapping row is one SHACL validation, and CimPal parallelises it only across target shapes. Going from 1 to 4–8 cores saved 25–30 % at 1M and 5M. At 100k, start-up work dominates and extra threads cost more than they gain.
- **One core needs more heap.** With one core the JVM picks the serial collector, and the GC share at twice the smallest heap rose to 16 % (1M) and 24 % (5M). Give a single-core run about three times the smallest heap.
- **The 2-core 5M result is noisy:** it ranged from 41 s to 55 s. On this laptop the scheduler moves threads between performance and efficiency cores, and the CPU throttles under sustained load. Treat differences under about 20 % as noise.
- **`-XX:ActiveProcessorCount` is not a CPU quota.** It sets the number of cores the JVM and CimPal's worker auto-sizing plan for. JIT and GC threads still used the other cores, which is why CPU time exceeds wall time even at "1 core". A container `--cpus` limit (`run_bench.py --docker`) gives a hard quota.

### Not measured yet

- **Peak RSS:** Windows has no `VmHWM`, so `peakRssBytes` is null here. On Linux, `--stats` and `run_bench.py --docker` (cgroup `memory.peak`) report it.
- **The timestamped workflow and multi-row mappings.**
- **10M triples or more:** it doesn't fit this 16 GB laptop with room to spare. By the rule of thumb, 10M needs about 6 GB of heap and an 8 GB container.
- **Real CGMES conformity models:** that needs decision D-4 on the ENTSO-E model licence.

## Re-running

```bash
mvn -B -pl CimPal-CLI -am package -DskipTests
python scripts/bench/run_bench.py --sizes 100k,1M,5M --find-min-heap --heap-range 64m:12g \
    --core-sweep --cores 1,2,4,8 --warmup 1 --runs 3 --out target/bench/my-machine
```

The runs took about 25 minutes on the laptop above, plus a few minutes to generate the models the first time. Add the resulting `results.csv` and `summary.md` under `docs/guide/sizing-data/` with the date and machine in the name, and update the tables when a new series changes the picture.
