# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""The example DAGs import cleanly and have their tasks; a DAG runs end to end against a real serve.

The end-to-end test needs an Airflow metadata database (``airflow db migrate``) and the CLI JAR
(``CimPal-CLI/target/CimPal-CLI.jar`` or ``CIMPAL_JAR``); without them it is skipped, unless
``CIMPAL_INTEGRATION_REQUIRED=1`` (CI).
"""

from __future__ import annotations

import json
import os
import shutil
import socket
import subprocess
import time
from collections.abc import Iterator
from datetime import datetime
from pathlib import Path
from typing import Any

import httpx
import pytest

from .conftest import AIRFLOW, AIRFLOW_REASON

pytestmark = [pytest.mark.airflow, pytest.mark.skipif(not AIRFLOW, reason=AIRFLOW_REASON)]

EXAMPLES = Path(__file__).resolve().parents[1] / "example_dags"
EXPECTED = {
    "cimpal_provider_shared": {"validate", "route", "report_violations", "publish"},
    "cimpal_provider_upload": {"validate", "announce"},
}


def _dagbag() -> Any:
    try:  # Airflow 3.1+ moved DagBag
        from airflow.dag_processing.dagbag import DagBag
    except ImportError:  # pragma: no cover
        from airflow.models.dagbag import DagBag  # type: ignore[no-redef]
    return DagBag(dag_folder=str(EXAMPLES))


def test_the_example_dags_import_without_errors() -> None:
    dagbag = _dagbag()
    assert dagbag.import_errors == {}
    assert set(dagbag.dag_ids) == set(EXPECTED)
    for dag_id, tasks in EXPECTED.items():
        assert {t.task_id for t in dagbag.dags[dag_id].tasks} == tasks


def test_the_validate_tasks_are_deferrable_and_templated() -> None:
    dagbag = _dagbag()
    shared = dagbag.dags["cimpal_provider_shared"].get_task("validate")
    assert shared.deferrable and shared.input_path == "/data/igm/{{ ds }}"


# ---- end to end -------------------------------------------------------------------------------

TOKEN = "integration-token-0123456789abcdef0123456789"
JAR = Path(os.environ.get("CIMPAL_JAR") or Path(__file__).resolve().parents[3] / "CimPal-CLI/target/CimPal-CLI.jar")
SHAPES = """\
@prefix sh: <http://www.w3.org/ns/shacl#> .
@prefix ex: <urn:test:> .
ex:ThingShape a sh:NodeShape ; sh:targetClass ex:Thing ;
    sh:property [ sh:path ex:size ; sh:minCount 1 ] .
"""
DATA = """\
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:ex="urn:test:">
  <ex:Thing rdf:about="#_1"/>
</rdf:RDF>
"""


@pytest.fixture(scope="module")
def serve(tmp_path_factory: pytest.TempPathFactory) -> Iterator[str]:
    java = shutil.which("java")
    if java is None or not JAR.is_file():
        if os.environ.get("CIMPAL_INTEGRATION_REQUIRED") == "1":
            pytest.fail(f"CIMPAL_INTEGRATION_REQUIRED is set, but Java or {JAR} is missing")
        pytest.skip(f"needs Java and {JAR}")
    root = tmp_path_factory.mktemp("root")
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    log = (root.parent / "serve.log").open("wb")
    process = subprocess.Popen(  # noqa: S603 (fixed arguments)
        [
            java,
            "-jar",
            str(JAR),
            "serve",
            "--host",
            "127.0.0.1",
            "--port",
            str(port),
            "--root",
            str(root),
            "--work-root",
            str(root.parent / "work"),
        ],
        env={**os.environ, "CIMPAL_API_TOKEN": TOKEN},
        stdout=log,
        stderr=subprocess.STDOUT,
    )
    base = f"http://127.0.0.1:{port}"
    try:
        deadline = time.monotonic() + 60
        while True:
            assert process.poll() is None, f"serve exited; see {log.name}"
            try:
                if httpx.get(base + "/v1/health", timeout=2).status_code == 200:
                    break
            except httpx.TransportError:
                pass
            assert time.monotonic() < deadline, f"serve did not answer within 60 s; see {log.name}"
            time.sleep(0.3)
        yield base
    finally:
        try:
            httpx.post(
                base + "/shutdown",
                headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"},
                timeout=10,
            )
            process.wait(timeout=60)
        except (httpx.TransportError, subprocess.TimeoutExpired):
            process.kill()
        log.close()


@pytest.mark.integration
@pytest.mark.parametrize("deferrable", [False, True])
def test_a_dag_validates_an_upload_and_branches_on_the_result(
    serve: str, tmp_path: Path, monkeypatch: pytest.MonkeyPatch, deferrable: bool
) -> None:
    from airflow.sdk import DAG, task

    from cimpal_provider.operators.cimpal import CimPalValidateOperator

    monkeypatch.setenv(
        "AIRFLOW_CONN_CIMPAL_TEST",
        json.dumps({"conn_type": "cimpal", "host": serve, "password": TOKEN, "extra": {"poll_interval": 1}}),
    )
    inputs = tmp_path / "input"
    (inputs / "constraints").mkdir(parents=True)
    (inputs / "models").mkdir()
    (inputs / "constraints" / "shapes.ttl").write_text(SHAPES, encoding="utf-8")
    (inputs / "models" / "data.xml").write_text(DATA, encoding="utf-8")
    seen = tmp_path / "seen.json"

    with DAG(dag_id=f"cimpal_e2e_{deferrable}", start_date=datetime(2026, 10, 1), schedule=None) as dag:
        validate = CimPalValidateOperator(
            task_id="validate",
            cimpal_conn_id="cimpal_test",
            upload_from=str(inputs),
            config={
                "workflow": "combined",
                "constraintFiles": ["constraints/shapes.ttl"],
                "dataFiles": ["models/data.xml"],
                "xmlBase": "http://example.com/data",
            },
            download_outputs_to=str(tmp_path / "reports"),
            deferrable=deferrable,
        )

        @task
        def record(summary: dict[str, Any]) -> None:
            seen.write_text(json.dumps(summary), encoding="utf-8")

        record(validate.output)  # type: ignore[arg-type]

    run = dag.test()

    assert str(run.state) in ("success", "DagRunState.SUCCESS"), run.state
    summary = json.loads(seen.read_text(encoding="utf-8"))
    assert summary["status"] == "succeeded" and summary["hasViolations"] is True
    assert summary["totals"]["violations"] == 1
    assert any(name.endswith(".xlsx") for name in summary["outputFiles"])
    assert any(p.suffix == ".xlsx" for p in (tmp_path / "reports").rglob("*"))
