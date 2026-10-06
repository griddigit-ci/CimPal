# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the EUPL-1.2-or-later.
# SPDX-License-Identifier: EUPL-1.2+
"""The example DAGs import cleanly under the pinned Airflow, and the Bash DAG runs CimPal.

    pip install -r integrations/airflow/requirements-test.txt \
        --constraint https://raw.githubusercontent.com/apache/airflow/constraints-3.3.2/constraints-3.12.txt
    pytest integrations/airflow/tests

The end-to-end test needs an Airflow metadata database (`airflow db migrate`) and the CLI JAR in
CIMPAL_JAR; without CIMPAL_JAR it is skipped. Airflow runs on Linux and macOS, not on Windows.
"""
from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path

import pytest

try:  # Airflow 3.1+ moved DagBag; the old location still works with a deprecation warning.
    from airflow.dag_processing.dagbag import DagBag
except ImportError:  # pragma: no cover
    from airflow.models.dagbag import DagBag

EXAMPLES = Path(__file__).resolve().parents[1] / "examples"
DATA = EXAMPLES / "data"

EXPECTED_TASKS = {
    "cimpal_kpo": {"validate", "route", "report_violations", "publish"},
    "cimpal_docker": {"validate", "read_summary"},
    "cimpal_bash": {"validate", "read_summary"},
}


@pytest.fixture(scope="module")
def dagbag() -> DagBag:
    return DagBag(dag_folder=str(EXAMPLES), include_examples=False)


def test_every_example_dag_imports_without_errors(dagbag):
    assert dagbag.import_errors == {}


def test_the_examples_are_exactly_the_three_documented_dags(dagbag):
    assert set(dagbag.dag_ids) == set(EXPECTED_TASKS)


@pytest.mark.parametrize("dag_id", sorted(EXPECTED_TASKS))
def test_each_dag_has_its_tasks(dagbag, dag_id):
    dag = dagbag.get_dag(dag_id)
    assert {t.task_id for t in dag.tasks} == EXPECTED_TASKS[dag_id]


@pytest.mark.parametrize("dag_id", sorted(EXPECTED_TASKS))
def test_cimpal_is_told_to_report_violations_as_data(dagbag, dag_id):
    # Without it a model with findings would fail the task instead of reaching the next one.
    validate = dagbag.get_dag(dag_id).get_task("validate")
    command = " ".join(str(part) for part in (
        getattr(validate, "arguments", None) or getattr(validate, "command", None)
        or [getattr(validate, "bash_command", "")]))
    assert "--violations-exit-code 0" in command
    assert "--summary-file" in command


def test_nothing_is_pasted_into_the_bash_command(dagbag):
    # Values reach the shell as environment variables only; Jinja in the command text would let
    # whoever sets them inject shell code.
    validate = dagbag.get_dag("cimpal_bash").get_task("validate")
    assert "{{" not in validate.bash_command
    assert set(validate.env) == {"CIMPAL_JAR", "CIMPAL_DATA_DIR"}


@pytest.mark.parametrize("dag_id", sorted(EXPECTED_TASKS))
def test_one_run_at_a_time_because_runs_share_their_files(dagbag, dag_id):
    assert dagbag.get_dag(dag_id).max_active_runs == 1


def _example_module(name: str):
    spec = importlib.util.spec_from_file_location(name, EXAMPLES / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.mark.parametrize("name", ["cimpal_kpo", "cimpal_docker", "cimpal_bash"])
def test_the_xcom_summary_never_reads_as_conforming_by_default(name):
    compact = _example_module(name).compact
    clean = {"totals": {"conforming": 1, "violations": 0, "errors": 0, "total": 1},
             "hasViolations": False, "report": "r.xlsx"}
    no_rows = {"totals": {"conforming": 0, "violations": 0, "errors": 0, "total": 0},
               "hasViolations": False, "report": "r.xlsx"}

    assert compact(clean)["conforms"] is True
    assert compact(no_rows)["conforms"] is False
    with pytest.raises(KeyError):
        compact({"report": "r.xlsx"})


def test_the_example_data_matches_its_manifest():
    manifest = json.loads((DATA / "manifest.json").read_text(encoding="utf-8"))
    run = json.loads((DATA / "run.json").read_text(encoding="utf-8"))
    assert manifest["expectedViolationsTotal"] > 0
    assert (DATA / run["mappingCsv"]).is_file()
    assert (DATA / run["constraintsRoot"] / "bench-shapes.ttl").is_file()


@pytest.mark.skipif(not os.environ.get("CIMPAL_JAR"), reason="needs CimPal-CLI.jar in CIMPAL_JAR")
def test_bash_dag_runs_cimpal_and_reports_violations_without_failing(dagbag, monkeypatch):
    monkeypatch.setenv("AIRFLOW_VAR_CIMPAL_JAR", os.environ["CIMPAL_JAR"])
    summary_file = DATA / "out" / "summary.json"
    summary_file.unlink(missing_ok=True)

    run = dagbag.get_dag("cimpal_bash").test()

    states = {ti.task_id: str(ti.state) for ti in run.get_task_instances()}
    assert states == {"validate": "success", "read_summary": "success"}, states
    summary = json.loads(summary_file.read_text(encoding="utf-8"))
    assert summary["hasViolations"] is True
    # totals count mapping rows; the example has one row, and its shapes find violations.
    assert summary["totals"]["violations"] == 1
    assert summary["totals"]["errors"] == 0
