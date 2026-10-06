# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the EUPL-1.2-or-later.
# SPDX-License-Identifier: EUPL-1.2+
"""CimPal as a plain process on the Airflow worker (DEP-3, path A).

For workers that have a Java 25 runtime and CimPal-CLI.jar. CimPal writes its JSON summary to
<data dir>/out/summary.json and, with `--violations-exit-code 0`, ends successfully when it finds
violations; a follow-up task pushes only the small part of the summary to XCom.

Settings:
    CIMPAL_DATA_DIR   worker environment variable: folder with run.json and the models; default
                      this repository's example data next to this file
    cimpal_jar        Airflow Variable: path of CimPal-CLI.jar, default /opt/cimpal/CimPal-CLI.jar

Security: nothing a triggerer types reaches the shell. The data folder is fixed when the DAG is
parsed (no trigger-time param), and values go to the command as environment variables, never
pasted into its text. One run at a time, because runs share the summary file.
"""
from __future__ import annotations

import json
import os
from datetime import datetime
from pathlib import Path

from airflow.providers.standard.operators.bash import BashOperator
from airflow.sdk import dag, task

DATA_DIR = os.environ.get("CIMPAL_DATA_DIR", str(Path(__file__).resolve().parent / "data"))
SUMMARY = Path(DATA_DIR) / "out" / "summary.json"


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
    dag_id="cimpal_bash",
    schedule=None,
    start_date=datetime(2026, 1, 1),
    catchup=False,
    max_active_runs=1,
    tags=["cimpal", "validation"],
    doc_md=__doc__,
)
def cimpal_bash():
    validate = BashOperator(
        task_id="validate",
        bash_command=(
            'java -jar "$CIMPAL_JAR" validate --config "$CIMPAL_DATA_DIR/run.json"'
            ' --summary-file "$CIMPAL_DATA_DIR/out/summary.json" --violations-exit-code 0'
        ),
        env={
            "CIMPAL_JAR": "{{ var.value.get('cimpal_jar', '/opt/cimpal/CimPal-CLI.jar') }}",
            "CIMPAL_DATA_DIR": DATA_DIR,
        },
        append_env=True,
    )

    @task
    def read_summary() -> dict:
        return compact(json.loads(SUMMARY.read_text(encoding="utf-8")))

    validate >> read_summary()


cimpal_bash()
