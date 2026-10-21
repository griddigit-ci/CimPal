# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""The DAGs of the end-to-end test (tests/test_dags.py), polling and deferrable.

``dag.test()`` runs only DAGs it finds in the DAGs folder (Airflow 3 serializes them first), so
they live here and CI points ``AIRFLOW__CORE__DAGS_FOLDER`` at this folder. The test sets
``CIMPAL_E2E_INPUT``, ``CIMPAL_E2E_REPORTS`` and ``CIMPAL_E2E_SEEN``; without them (parsing only)
the paths are placeholders.
"""

from __future__ import annotations

import json
import os
from datetime import datetime
from pathlib import Path
from typing import Any

from airflow.sdk import DAG, task

from cimpal_provider.operators.cimpal import CimPalValidateOperator

INPUT = os.environ.get("CIMPAL_E2E_INPUT", "/nonexistent/input")
REPORTS = os.environ.get("CIMPAL_E2E_REPORTS", "/nonexistent/reports")

for deferrable in (False, True):
    with DAG(dag_id=f"cimpal_e2e_{str(deferrable).lower()}", start_date=datetime(2026, 10, 1), schedule=None):
        validate = CimPalValidateOperator(
            task_id="validate",
            cimpal_conn_id="cimpal_test",
            upload_from=INPUT,
            config={
                "workflow": "combined",
                "constraintFiles": ["constraints/shapes.ttl"],
                "dataFiles": ["models/data.xml"],
                "xmlBase": "http://example.com/data",
            },
            download_outputs_to=f"{REPORTS}/{str(deferrable).lower()}",
            deferrable=deferrable,
        )

        @task
        def record(summary: dict[str, Any], name: str = f"{str(deferrable).lower()}.json") -> None:
            seen = Path(os.environ.get("CIMPAL_E2E_SEEN", "/nonexistent/seen"))
            seen.mkdir(parents=True, exist_ok=True)
            (seen / name).write_text(json.dumps(summary), encoding="utf-8")

        record(validate.output)  # type: ignore[arg-type]
