# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the EUPL-1.2-or-later.
# SPDX-License-Identifier: EUPL-1.2+
"""CimPal in Docker on the Airflow worker, the summary through a file (DEP-3, path A).

The DockerOperator runs the CimPal image (docs/cli/docker.md) with a host folder mounted at
/data. CimPal writes its JSON summary to /data/out/summary.json and, with
`--violations-exit-code 0`, ends successfully when it finds violations. A follow-up task reads
the summary and pushes only the small part to XCom.

Settings (environment variables of the worker, read when the DAG is parsed, because the
operator's mount can't be templated):
    CIMPAL_DATA_DIR     host folder with run.json and the models, default /opt/cimpal-data
    CIMPAL_DOCKER_USER  UID:GID the container runs as. On Linux use the owner of CIMPAL_DATA_DIR,
                        so CimPal can write /data/out and the worker can read the summary
                        (default 10001:10001, the image's user)
Airflow Variable:
    cimpal_image        default ghcr.io/griddigit-ci/cimpal:2026.10.6.1 (a release tag; never :latest)
"""
from __future__ import annotations

import json
import os
from datetime import datetime
from pathlib import Path

from airflow.providers.docker.operators.docker import DockerOperator
from airflow.sdk import dag, task
from docker.types import Mount

DATA_DIR = os.environ.get("CIMPAL_DATA_DIR", "/opt/cimpal-data")
DOCKER_USER = os.environ.get("CIMPAL_DOCKER_USER", "10001:10001")


def compact(summary: dict) -> dict:
    """The part of CimPal's summary worth keeping in XCom: never whole reports.

    Strict on purpose: a missing key raises instead of reading as "conforming", and a run that
    validated no rows is not conforming either.
    """
    totals = summary["totals"]
    return {
        "conforms": totals["total"] > 0 and totals["violations"] == 0 and totals["errors"] == 0,
        "hasViolations": summary["hasViolations"],
        "totals": totals,
        "report": summary.get("report") or summary.get("reports"),
    }


@dag(
    dag_id="cimpal_docker",
    schedule=None,
    start_date=datetime(2026, 1, 1),
    catchup=False,
    max_active_runs=1,  # runs share the summary file and the output folder
    tags=["cimpal", "validation"],
    doc_md=__doc__,
)
def cimpal_docker():
    validate = DockerOperator(
        task_id="validate",
        image="{{ var.value.get('cimpal_image', 'ghcr.io/griddigit-ci/cimpal:2026.10.6.1') }}",
        command=[
            "validate", "--config", "/data/run.json",
            "--summary-file", "/data/out/summary.json",
            "--violations-exit-code", "0",
        ],
        mounts=[Mount(source=DATA_DIR, target="/data", type="bind")],
        user=DOCKER_USER,
        mount_tmp_dir=False,
        auto_remove="success",
    )

    @task
    def read_summary() -> dict:
        summary = json.loads((Path(DATA_DIR) / "out" / "summary.json").read_text(encoding="utf-8"))
        return compact(summary)

    validate >> read_summary()


cimpal_docker()
