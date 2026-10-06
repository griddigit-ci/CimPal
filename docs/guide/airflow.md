<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# Running CimPal from Apache Airflow

The simplest way to use CimPal in an Airflow pipeline is one task per validation (path A). Airflow starts CimPal as a container or a process, CimPal writes a small JSON summary, and the following tasks act on it. There is no CimPal server to run. Runnable examples are in [`integrations/airflow/`](../../integrations/airflow/README.md).

```
upstream (get models) ──▶ validate (CimPal) ──▶ branch on hasViolations ──▶ report / publish
                              │
                              └── summary JSON ─▶ XCom (small) ; Excel/Turtle reports ─▶ shared storage
```

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

## Security notes

- The image runs as UID 10001 and works with a read-only root filesystem when `/tmp` is writable. The KPO example sets both.
- Only mount what the run needs. Keep the models read-only where you can, with only `out/` writable.
- Don't put secrets in `JAVA_OPTS` or the config file: they show in the pod spec and logs. Pass `GITHUB_TOKEN` (for remote `owl:imports`) from a Kubernetes Secret or an Airflow Connection.
