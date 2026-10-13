<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the Apache License, Version 2.0.
  SPDX-License-Identifier: Apache-2.0
-->
# airflow-provider-cimpal

An [Apache Airflow](https://airflow.apache.org/) provider for [CimPal](https://github.com/griddigit-ci/CimPal): run CIM/CGMES validations and the other CimPal commands as jobs on a CimPal server (`serve`) from a DAG. Long validations wait in the triggerer (deferrable), not in a worker slot.

```python
from cimpal_provider.operators.cimpal import CimPalValidateOperator

validate = CimPalValidateOperator(
    task_id="validate_igm",
    cimpal_conn_id="cimpal_default",
    config={"workflow": "combined", "constraintFiles": ["constraints"], "dataFiles": ["models"]},
    upload_from="/opt/airflow/work/{{ ds }}/input",       # or input_path= on a shared volume
    download_outputs_to="/opt/airflow/work/{{ ds }}/reports",
    deferrable=True,
)
# validate.output: {"jobId", "status", "exitCode", "hasViolations", "totals", "report", "outputFiles"}
```

**Requirements:** Airflow 3.2 or later (decision D-12), Python 3.10 or later, and `cimpal-client` (the [Python SDK](../python/README.md)) at the same version. The package version is the CimPal version it was built with.

**Contents:**

| | |
|---|---|
| Connection type `cimpal` | Host (or full URL), Scheme, Port, API token (password); extras `verify`, `timeout`, `retries`, `poll_interval`, `allow_insecure_http` |
| `CimPalHook` | `CimPal`/`AsyncCimPal` clients from the connection; the connection test calls `/v1/health` and an authenticated request |
| `CimPalJobOperator`, `CimPalValidateOperator` | Submit, then wait by polling or deferred; small XCom; `fail_on_violations`; `on_kill` cancels a queued job |
| `CimPalJobTrigger` | Asynchronous polling in the triggerer |
| `CimPalJobSensor` | Waits for a job submitted elsewhere |

The user guide is [docs/guide/airflow.md](../../docs/guide/airflow.md) (path C). The examples are in [`example_dags/`](example_dags/).

**Licence:** Apache-2.0. CimPal itself is EUPL-1.2.

## Development

Airflow runs on Linux and macOS only. On Windows the Airflow-dependent tests are skipped and only `tests/test_logic.py` runs; CI runs everything.

```bash
python -m venv .venv && . .venv/bin/activate
pip install "apache-airflow==3.3.2" \
  --constraint https://raw.githubusercontent.com/apache/airflow/constraints-3.3.2/constraints-3.12.txt
pip install -e ../python -e ".[dev]"
ruff check src tests example_dags hatch_build.py && mypy
airflow db migrate                   # dag.test() needs the metadata database
pytest                               # the dag.test() runs need ../../CimPal-CLI/target/CimPal-CLI.jar
```

The file options that `input_path` resolves mirror the CLI's `PathGuard`; `tests/test_logic.py` fails when the two differ.

## Releasing (maintainers)

As for `cimpal-client` ([clients/python/README.md](../python/README.md#releasing-maintainers)): after a CimPal release, from the tagged commit, `python -m build` and `twine upload`. Publish `cimpal-client` first, since the provider requires it at the same version.
