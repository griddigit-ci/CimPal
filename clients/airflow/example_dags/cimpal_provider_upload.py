# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""Validate models the workers have, without a shared volume: upload them, fetch the reports.

The operator zips ``upload_from`` and uploads it; relative paths in ``config`` resolve inside the
upload, and the job's outputs go to its own folder on the server, from where
``download_outputs_to`` fetches them. ``fail_on_violations=True`` fails the task on findings.

Connection ``cimpal_default``: type CimPal, host ``https://cimpal.example.com``, password the token.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from airflow.sdk import DAG, task

from cimpal_provider.operators.cimpal import CimPalValidateOperator

with DAG(
    dag_id="cimpal_provider_upload",
    start_date=datetime(2026, 10, 1),
    schedule=None,
    catchup=False,
    tags=["cimpal", "example"],
) as dag:
    validate = CimPalValidateOperator(
        task_id="validate",
        cimpal_conn_id="cimpal_default",
        upload_from="/opt/airflow/work/{{ ds }}/input",  # a folder on the worker
        config={
            "workflow": "combined",
            "constraintFiles": ["constraints"],
            "dataFiles": ["models"],
            "xmlBase": "http://iec.ch/TC57/CIM100",
        },
        download_outputs_to="/opt/airflow/work/{{ ds }}/reports",
        fail_on_violations=True,
        deferrable=True,
    )

    @task
    def announce(summary: dict[str, Any]) -> None:
        print(f"CimPal job {summary['jobId']}: {summary['totals']}; reports: {summary['outputFiles']}")

    announce(validate.output)
