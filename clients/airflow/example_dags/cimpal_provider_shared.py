# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""Validate the day's IGM on a shared volume, then branch on the result.

The CimPal server (``serve``) and the Airflow workers see the same volume: the server under
``/data`` (its ``--root``). The validation waits in the triggerer (``deferrable=True``), so a long
run holds no worker slot. Violations are data: the task succeeds, and ``route`` picks the branch.

Connection ``cimpal_default``: type CimPal, host ``https://cimpal.example.com``, password the token.
"""

from __future__ import annotations

from datetime import datetime, timedelta
from typing import Any

from airflow.providers.standard.operators.empty import EmptyOperator
from airflow.sdk import DAG, task

from cimpal_provider.operators.cimpal import CimPalValidateOperator

with DAG(
    dag_id="cimpal_provider_shared",
    start_date=datetime(2026, 10, 1),
    schedule="@daily",
    catchup=False,
    tags=["cimpal", "example"],
) as dag:
    validate = CimPalValidateOperator(
        task_id="validate",
        cimpal_conn_id="cimpal_default",
        input_path="/data/igm/{{ ds }}",  # as the CimPal server sees the volume
        config={
            "workflow": "combined",
            "constraintFiles": ["/data/constraints/CGMES30"],
            "dataFiles": ["models"],  # relative: /data/igm/<date>/models
            "outputDir": "reports",  # relative: /data/igm/<date>/reports
            "xmlBase": "http://iec.ch/TC57/CIM100",
        },
        deferrable=True,
        job_timeout=timedelta(hours=2),
    )

    @task.branch
    def route(summary: dict[str, Any]) -> str:
        return "report_violations" if summary["hasViolations"] else "publish"

    report_violations = EmptyOperator(task_id="report_violations")
    publish = EmptyOperator(task_id="publish")

    route(validate.output) >> [report_violations, publish]
