<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# Running CimPal from Apache Airflow

*Last reviewed 2026-10-13 · Describes the release after CimPal 2026.10.6.1: `--summary-file`, `--violations-exit-code` and the provider are not in 2026.10.6.1*

There are three ways to run CimPal from Airflow; [Which path](#which-path-a-b-or-c) compares them. The simplest is one task per validation (path A). Airflow starts CimPal as a container or a process, CimPal writes a small JSON summary, and the following tasks act on it. There is no CimPal server to run. Runnable examples are in [`integrations/airflow/`](../../integrations/airflow/README.md).

```
upstream (get models) ──▶ validate (CimPal) ──▶ branch on hasViolations ──▶ report / publish
                              │
                              └── summary JSON ─▶ XCom (small) ; Excel/Turtle reports ─▶ shared storage
```

## Which path: A, B or C

| | **A: CLI or container per task** | **B: `serve` over HTTP** | **C: the CimPal provider** |
|---|---|---|---|
| How | `KubernetesPodOperator`, `DockerOperator` or `BashOperator` starts CimPal | `HttpOperator`/`HttpSensor` call a [CimPal server](../cli/serve.md)'s `/v1` API | `CimPalValidateOperator` (package `airflow-provider-cimpal`) talks to a CimPal server |
| Runs on | the Airflow worker or a pod per run | a CimPal server (one or more replicas) | a CimPal server |
| Waiting | the task holds its worker slot (or pod) | your sensor polls | **deferrable**: no worker slot while the job runs |
| Files | shared volume | shared volume, or upload and download in your own code | shared volume (`input_path`), or `upload_from` / `download_outputs_to` |
| Memory per run | set per task (pod limits) | the server's heap, shared | the server's heap, shared |
| Good for | few, large runs; no server to operate | any HTTP client, other schedulers | many runs from Airflow, with the least DAG code |

Start with A unless you already run a CimPal server, or many validations a day make a warm server worth it; then use C. B is what C does for you, for non-Python tools.

## Pick an operator

| Operator | When | CimPal runs as |
|---|---|---|
| `KubernetesPodOperator` | Airflow on Kubernetes; the recommended production shape | the [Docker image](../cli/docker.md), one pod per run, with its own memory and CPU limits |
| `DockerOperator` | Workers with a Docker daemon | the Docker image, on the worker |
| `BashOperator` | Workers with Java 25 and `CimPal-CLI.jar` | a plain JVM process |

## The two options that make it work

- **`--summary-file <path>`** writes the JSON result to a file, as well as to stdout. It is exactly the document `--format json` prints, and it is written atomically, so a reader never sees half a file. With `KubernetesPodOperator` and `do_xcom_push=True`, use `--summary-file /airflow/xcom/return.json`: that file *is* the task's XCom.
- **`--violations-exit-code 0`**: by default, "violations found" is exit code 1, and Airflow fails a task on any non-zero exit. With 0, findings are data: the task succeeds, and `hasViolations` in the summary tells the next task what happened.

Both are also config keys (`summaryFile`, `violationsExitCode`) and work on `validate`, `sparql`, `compare` and `compare-instances`.

## Exit codes and task states

| CimPal exit | Meaning | Default task state | With `--violations-exit-code 0` |
|---|---|---|---|
| 0 | Ran, no violations | success | success |
| 1 | Ran, violations or differences found | **failed** | success; `hasViolations: true` |
| 1 | `validate`: a row failed with an error (unreadable model, failed `owl:imports`) | failed | **still failed**: an error is never a pass |
| 2 | Bad input: missing file, bad config, refused path | failed | failed |
| 3 | Internal error, or out of memory | failed | failed |

Exit 2 should not be retried until the input is fixed. Exit 3 after out of memory needs more memory (see the [sizing guide](sizing.md)); other exit-3 errors may be worth a retry.

## One run at a time

The examples set `max_active_runs=1`: runs of one DAG share the output folder and the summary file, so a second run could overwrite the first run's summary before its follow-up task reads it. To run several validations in parallel, give each its own folder (e.g. one DAG per model set) or write to `out/{{ run_id }}/`.

## XCom: keep it small

XCom lives in Airflow's metadata database and is meant for a few kilobytes. Put the reports on shared storage and only the summary in XCom.
- Run CimPal with `"samples": 0` (config) or `--samples 0`. The summary then holds the totals, `hasViolations` and the report path, without per-shape sample focus nodes.
- The examples push only `{conforms, hasViolations, totals, report}`.
- `totals` counts **mapping rows**: conforming, with violations, with errors. The number of individual SHACL findings is in the Excel report.

## Files, volumes and S3

CimPal reads models and writes reports on a file system. It never reads S3 itself (decision D-6).
- **Kubernetes:** a PersistentVolumeClaim mounted at `/data`. Upstream tasks put the models there, and downstream tasks read the reports.
- **Docker or Bash:** a folder on the worker, or a network share mounted on all workers.
- **Models in S3:** add an upstream task that copies them from S3 to the volume (e.g. `S3Hook.download_file`, or `aws s3 sync`), and a downstream task that uploads `out/` if the reports should go back to S3.
- **Paths:** paths in a CimPal config are relative to the config file, so one `run.json` next to the models works wherever the volume is mounted.

## Memory and CPU

Give the pod or container the memory the model needs. The [sizing guide](sizing.md) has figures by model size: plan 0.6 GB of heap per million triples, and a container limit of heap ÷ 0.75. In Kubernetes, set memory requests equal to limits. Out of memory ends the run with exit 3 and a clear message, never with a false "conforming".

## Path C: the CimPal provider

`airflow-provider-cimpal` (Apache-2.0, in [`clients/airflow/`](../../clients/airflow/README.md)) adds a `cimpal` connection type, a hook, operators, a deferrable trigger and a sensor. It uses the [Python SDK](python-sdk.md) and needs Airflow 3.2 or later and a running CimPal server ([`serve`](../cli/serve.md), see [Running CimPal as a service](service.md)).

```bash
pip install "git+https://github.com/griddigit-ci/CimPal.git@<tag>#subdirectory=clients/python" \
            "git+https://github.com/griddigit-ci/CimPal.git@<tag>#subdirectory=clients/airflow"
```

**The connection** (Admin › Connections, type *CimPal*, id `cimpal_default`):
- **Host:** `cimpal.example.com`, or a full URL;
- **Scheme:** `https` by default;
- **Port:** optional;
- **API token:** the server's bearer token.
- **Extras:**
  - `verify` (TLS verification, or the path of a CA bundle);
  - `timeout` (seconds per request);
  - `retries`;
  - `poll_interval` (seconds, default 30);
  - `allow_insecure_http`.

The **Test** button checks that the server answers and accepts the token.

```python
from datetime import timedelta
from cimpal_provider.operators.cimpal import CimPalValidateOperator

validate = CimPalValidateOperator(
    task_id="validate",
    input_path="/data/igm/{{ ds }}",          # shared volume, as the server sees it
    config={"workflow": "combined", "constraintFiles": ["/data/constraints/CGMES30"],
            "dataFiles": ["models"], "outputDir": "reports"},
    deferrable=True,                           # wait in the triggerer, not a worker slot
    job_timeout=timedelta(hours=2),
)
```

**Files:**
- **Shared volume:** with `input_path`, the config's relative file paths resolve against that folder as the server sees it. Options that may hold text instead of a path (`datatypeMap`, a SPARQL `query`) are left as they are.
- **Upload instead:** `upload_from="/local/folder"` zips and uploads it. Relative paths then resolve inside the upload, and `download_outputs_to="/local/reports"` fetches the job's output files when it is done.

**The XCom** (the task's return value) is small: `jobId`, `command`, `status`, `exitCode`, `hasViolations`, `totals`, `report`, and `outputFiles` in upload mode. Branch on `hasViolations` as in path A. The job's progress lines are copied into the task log.

**Task outcome:**

| The job | The task |
|---|---|
| succeeded, no violations | succeeds |
| succeeded, violations found | succeeds (violations are data); fails without retries with `fail_on_violations=True` |
| failed with exit 2 (bad input) | fails without retries, with the server's message |
| failed with exit 3 (an internal error) | fails; Airflow's retries apply |
| refused before it was submitted: 401 token, 403 path outside the allowed folders | fails without retries |
| couldn't be submitted: server busy or unreachable (after the client's own retries) | fails; Airflow's retries apply |
| cancelled, timed out on the server, or still running after `job_timeout` | fails without retries |
| couldn't be followed after it was submitted (e.g. the server stayed unreachable) | fails without retries |

**Why some failures don't retry:** a running job can't be stopped. An Airflow retry submits a new job, so retrying a job that may still be running would make two of them. The deferrable wait keeps trying through a busy or restarting server until `job_timeout`.

**Killing a task** (`on_kill`): a job that is still queued is cancelled. A running one can't be stopped and finishes on the server.

**Template with care:**
- `upload_from` and `download_outputs_to` are paths on the worker. Don't fill them from `dag_run.conf` or `params` that users who may only trigger runs can set: the upload would send any worker file to the server, and the download would write into any folder.
- The connection always needs its API token. The provider never falls back to a `CIMPAL_API_TOKEN` set on the workers.

**Other classes:**
- `CimPalJobOperator(command=...)` runs any of the ten commands;
- `CimPalJobSensor(job_id=...)` waits for a job something else submitted.

Examples: [`clients/airflow/example_dags/`](../../clients/airflow/example_dags/).

## Security notes

- The image runs as UID 10001 and works with a read-only root filesystem when `/tmp` is writable. The KPO example sets both.
- Only mount what the run needs. Keep the models read-only where you can, with only `out/` writable.
- Don't put secrets in `JAVA_OPTS` or the config file: they show in the pod spec and logs. Pass `GITHUB_TOKEN` (for remote `owl:imports`) from a Kubernetes Secret or an Airflow Connection.
