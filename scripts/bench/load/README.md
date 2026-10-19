<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# Load tests: what one `serve` instance handles

These scripts measure CimPal as a service under concurrent load (DEP-16): throughput by model size, queue wait, the API's latency while jobs run, heap per worker, and what happens when the queue is full. The [sizing guide](../../../docs/guide/sizing.md#running-as-a-service-capacity) and the [SLOs](../../../docs/guide/service.md#service-levels) are built from their output, and anyone can re-run them. The single-run measurements (heap and time per model size) are [one folder up](../README.md).

| File | What it does |
|---|---|
| `run.py` | One load run: jobs arrive (open load at a rate, or closed load with N clients), against a running server or one it starts. Writes `jobs.csv`, `probes.csv`, `metrics.csv`, `http.csv`, `summary.json` and `report.md`. |
| `sweep.py` | The capacity experiments: the throughput limit of one worker, workers × cores, queue wait against load, overload. Writes `service.json` and `service.md`; `--publish NAME` puts them in `docs/guide/sizing-data/`. |
| `test_load.py` | Self-test of the statistics and reports: `python -m unittest discover -s scripts/bench/load -p "test_*.py"` |

They need Python 3.10+ and the SDK (`pip install -e clients/python`); the client is `AsyncCimPal`, no other load framework. The models are the DEP-2 synthetic ones (`gen_models.py`), generated once under `target/bench/models/`.

## Scenarios

| Name | What a job does |
|---|---|
| `small` | `validate` (mapping workflow) of a 100k-triple model from a shared folder |
| `medium` | the same with 1M triples |
| `sparql` | a SPARQL count over the 100k model's EQ file |
| `upload` | upload the 100k model as a ZIP, validate it, download the output archive, delete the upload |

`--scenario mixed` weighs them 6 : 1 : 2 : 1; `--scenario small=3,upload=1` sets your own mix.

## Running

Build the CLI first (`mvn -B -pl CimPal-CLI -am package -DskipTests`), then from the repository root:

```bash
# Ten minutes of mixed jobs, 6 per minute, against a server started here with 2 workers.
python scripts/bench/load/run.py --start-server --workers 2 --heap 4g --scenario mixed --rate 6 --duration 600

# The throughput limit: 4 clients that always have a job waiting.
python scripts/bench/load/run.py --start-server --workers 2 --load-model closed --clients 4 --scenario medium --duration 300

# Against a running server: its --root must hold the models, its --write-root the outputs.
python scripts/bench/load/run.py --url https://cimpal.example.com --token-file admin.token \
    --server-models-root /data/models --server-output-root /data/out --scenario upload --rate 10 --duration 600

# The experiments behind the sizing guide (a few hours), then publish them.
python scripts/bench/load/sweep.py --publish 2026-10-20-laptop-i7-1360P-service
```

- **The SDK retries** 503 and 429 (`--retries`, default 3) as clients do. `http.csv` and the report count every 503 and 429 answer, retried or not; a job the SDK gave up on is `refused_503`.
- **Probes:** `GET /v1/health` and `GET /v1/jobs/{id}` (the newest job) every second, without retries: their latency is the API's under load.
- **Server samples:** `/metrics` every 2 s (start `serve` with `--metrics`; an admin token).
- **Cores:** `--cores N` pins the server to the first N logical CPUs (affinity; `taskset` on Linux) and sets `-XX:ActiveProcessorCount=N`. On a hybrid CPU (performance and efficiency cores) the first CPUs are usually the performance cores' hyper-threads, so "4 cores" there is two physical cores. In a container, `--cpus` is the stricter limit.
- **Exit code:** 0 when every job succeeded, 1 otherwise; `--fail-on-slo` also fails on a missed SLO (the nightly smoke job).

## Reading the report

- **Done/h** is completed jobs per hour from the first arrival to the last finish. Under open load it follows the arrival rate until the server is saturated; under closed load it is the server's limit.
- **Wait** is `startedAt − createdAt` (server clocks), **Run** `finishedAt − startedAt`, **End-to-end** `finishedAt − createdAt`.
- **Queue wait growing:** the median wait of the last third of the jobs is more than twice the first third's plus 5 s. The arrival rate is then above what the server handles, and waits grow without bound.
- **SLOs:** availability (probes answered without a server error), GET p95, submit p95, queue wait p95, against the targets in [service.md](../../../docs/guide/service.md#service-levels).
