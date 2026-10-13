# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""Hook, operators, trigger and sensor with Airflow, against a mocked CimPal server (respx).

Airflow runs on Linux and macOS only; elsewhere these tests are skipped.
"""

from __future__ import annotations

import json
import re
import time
from collections.abc import Iterator
from pathlib import Path
from typing import Any
from unittest import mock

import httpx
import pytest
import respx

from .conftest import (
    AIRFLOW,
    AIRFLOW_REASON,
    BASE,
    JOB_ID,
    TOKEN,
    UPLOAD_ID,
    health_json,
    job_json,
    log_json,
    problem,
    upload_json,
)

pytestmark = [pytest.mark.airflow, pytest.mark.skipif(not AIRFLOW, reason=AIRFLOW_REASON)]

if AIRFLOW:
    from airflow.sdk import Connection, PokeReturnValue
    from airflow.sdk.exceptions import AirflowException, AirflowFailException, TaskDeferred
    from airflow.triggers.base import TriggerEvent

    from cimpal_provider.hooks.cimpal import CimPalHook
    from cimpal_provider.operators.cimpal import CimPalJobOperator, CimPalValidateOperator
    from cimpal_provider.sensors.cimpal import CimPalJobSensor
    from cimpal_provider.triggers.cimpal import CimPalJobTrigger


@pytest.fixture
def connection(monkeypatch: pytest.MonkeyPatch) -> Iterator[Any]:
    conn = Connection(
        conn_id="cimpal_default",
        conn_type="cimpal",
        host=BASE,
        password=TOKEN,
        extra=json.dumps({"poll_interval": 1, "retries": 0}),
    )

    def get_connection(cls: Any, conn_id: str) -> Any:
        assert conn_id == "cimpal_default"
        return conn

    async def aget_connection(cls: Any, conn_id: str) -> Any:
        return conn

    monkeypatch.setattr(CimPalHook, "get_connection", classmethod(get_connection))
    monkeypatch.setattr(CimPalHook, "aget_connection", classmethod(aget_connection))
    yield conn


def context() -> Any:  # a stand-in for Airflow's Context
    return {"ti": mock.MagicMock(), "run_id": "manual__2026-10-13"}


def server(
    respx_mock: respx.MockRouter, *statuses: dict[str, Any], result: dict[str, Any] | None = None
) -> respx.Route:
    respx_mock.get("/v1/health").respond(json=health_json())
    submitted = respx_mock.post("/v1/jobs").respond(202, json=job_json("queued"))
    respx_mock.get(f"/v1/jobs/{JOB_ID}").mock(side_effect=[httpx.Response(200, json=s) for s in statuses])
    respx_mock.get(f"/v1/jobs/{JOB_ID}/log").respond(json=log_json())
    respx_mock.get(f"/v1/jobs/{JOB_ID}/result").respond(json=result or {})
    return submitted


# ---- hook -----------------------------------------------------------------------------------------


@respx.mock(base_url=BASE)
def test_test_connection_checks_the_server_and_the_token(respx_mock: respx.MockRouter, connection: Any) -> None:
    respx_mock.get("/v1/health").respond(json=health_json())
    listing = respx_mock.get("/v1/jobs").respond(json={"jobs": []})
    ok, message = CimPalHook().test_connection()
    assert ok and "API version 1" in message
    assert listing.calls.last.request.headers["Authorization"] == f"Bearer {TOKEN}"

    respx_mock.get("/v1/jobs").respond(401, json=problem(401, "Missing or invalid bearer token"))
    ok, message = CimPalHook().test_connection()
    assert not ok and "invalid bearer token" in message
    assert TOKEN not in message


def test_the_hook_reads_the_connection(connection: Any) -> None:
    hook = CimPalHook()
    with hook.get_conn() as cp:
        assert cp.base_url == BASE
    assert hook.poll_interval() == 1.0
    assert hook.poll_interval(5) == 5.0


# ---- operator, polling ----------------------------------------------------------------------------


@respx.mock(base_url=BASE)
def test_validate_submits_waits_and_returns_a_small_xcom(respx_mock: respx.MockRouter, connection: Any) -> None:
    submitted = server(
        respx_mock,
        job_json("running"),
        job_json("succeeded", exit_code=1, has_violations=True),
        result={"hasViolations": True, "totals": {"violations": 2}, "report": "/out/r.xlsx", "shapes": [1] * 50},
    )
    ctx = context()
    op = CimPalValidateOperator(
        task_id="validate", config={"workflow": "combined"}, input_path="/shared/in", deferrable=False
    )
    op.config["constraintFiles"] = ["c.ttl"]
    xcom = op.execute(ctx)

    body = json.loads(submitted.calls.last.request.content)
    assert body["command"] == "validate"
    assert body["config"]["constraintFiles"] == ["/shared/in/c.ttl"]
    assert body["label"].startswith("airflow:") and body["label"].endswith("/validate/manual__2026-10-13")
    assert xcom == {
        "jobId": JOB_ID,
        "command": "validate",
        "status": "succeeded",
        "exitCode": 1,
        "hasViolations": True,
        "totals": {"violations": 2},
        "report": "/out/r.xlsx",
    }
    ctx["ti"].xcom_push.assert_called_once_with(key="job_id", value=JOB_ID)


@respx.mock(base_url=BASE)
def test_fail_on_violations_fails_the_task_with_the_totals(respx_mock: respx.MockRouter, connection: Any) -> None:
    server(respx_mock, job_json("succeeded", exit_code=1, has_violations=True), result={"totals": {"violations": 2}})
    op = CimPalValidateOperator(task_id="v", config={}, fail_on_violations=True, deferrable=False)
    with pytest.raises(AirflowFailException, match='"violations": 2'):
        op.execute(context())


@pytest.mark.parametrize(("exit_code", "kind"), [(2, "AirflowFailException"), (3, "AirflowException")])
@respx.mock(base_url=BASE)
def test_a_failed_job_fails_the_task_with_the_servers_detail(
    respx_mock: respx.MockRouter, connection: Any, exit_code: int, kind: str
) -> None:
    server(respx_mock, job_json("failed", exit_code=exit_code), result={"error": "--data-files is required."})
    op = CimPalJobOperator(task_id="t", command="validate", deferrable=False)
    with pytest.raises(AirflowException, match=re.escape("--data-files is required.")) as raised:
        op.execute(context())
    assert type(raised.value).__name__ == kind


@pytest.mark.parametrize(
    ("status", "kind"), [(401, "AirflowFailException"), (403, "AirflowFailException"), (500, "AirflowException")]
)
@respx.mock(base_url=BASE)
def test_server_errors_map_to_failures_that_retry_only_when_it_can_help(
    respx_mock: respx.MockRouter, connection: Any, status: int, kind: str
) -> None:
    respx_mock.get("/v1/health").respond(json=health_json())
    respx_mock.post("/v1/jobs").respond(status, json=problem(status, "Path outside the allowed roots"))
    op = CimPalJobOperator(task_id="t", command="sparql", deferrable=False)
    with pytest.raises(AirflowException, match="allowed roots") as raised:
        op.execute(context())
    assert type(raised.value).__name__ == kind


@respx.mock(base_url=BASE)
def test_upload_mode_uploads_and_downloads_the_outputs(
    respx_mock: respx.MockRouter, connection: Any, tmp_path: Path
) -> None:
    (tmp_path / "in").mkdir()
    (tmp_path / "in" / "data.xml").write_text("<x/>", encoding="utf-8")
    submitted = server(respx_mock, job_json("succeeded", exit_code=0, has_violations=False), result={"totals": {}})
    respx_mock.post("/v1/uploads").respond(201, json=upload_json())
    respx_mock.get(f"/v1/jobs/{JOB_ID}/files").respond(
        json={"jobId": JOB_ID, "files": [{"path": "reports/r.xlsx", "size": 3, "mediaType": "x"}]}
    )
    respx_mock.get(f"/v1/jobs/{JOB_ID}/files/reports/r.xlsx").respond(content=b"xls")

    op = CimPalValidateOperator(
        task_id="v",
        config={"dataFiles": ["data.xml"]},
        upload_from=str(tmp_path / "in"),
        download_outputs_to=str(tmp_path / "out"),
        deferrable=False,
    )
    xcom = op.execute(context())

    assert json.loads(submitted.calls.last.request.content)["inputUpload"] == UPLOAD_ID
    assert xcom["outputFiles"] == ["reports/r.xlsx"]
    assert (tmp_path / "out" / "reports" / "r.xlsx").read_bytes() == b"xls"


def test_invalid_operator_settings_are_refused() -> None:
    with pytest.raises(ValueError, match="Unknown CimPal command"):
        CimPalJobOperator(task_id="t", command="serve")
    with pytest.raises(ValueError, match="not both"):
        CimPalJobOperator(task_id="t", command="validate", input_path="/a", upload_from="/b")
    with pytest.raises(ValueError, match="needs upload_from"):
        CimPalJobOperator(task_id="t", command="validate", download_outputs_to="/out")
    with pytest.raises(TypeError):
        CimPalValidateOperator(task_id="t", command="sparql")


def test_the_templated_fields() -> None:
    assert set(CimPalJobOperator.template_fields) >= {"config", "input_path", "upload_from", "download_outputs_to"}


# ---- operator, deferrable --------------------------------------------------------------------------


@respx.mock(base_url=BASE)
def test_deferrable_hands_waiting_to_the_trigger_and_resumes_with_the_result(
    respx_mock: respx.MockRouter, connection: Any
) -> None:
    server(respx_mock, job_json("succeeded", exit_code=0, has_violations=False), result={"totals": {"violations": 0}})
    op = CimPalValidateOperator(task_id="v", config={}, deferrable=True, job_timeout=600)
    with pytest.raises(TaskDeferred) as deferred:
        op.execute(context())
    trigger = deferred.value.trigger
    assert isinstance(trigger, CimPalJobTrigger)
    assert deferred.value.method_name == "execute_complete"
    assert trigger.job_id == JOB_ID and trigger.poll_interval == 1.0
    assert trigger.deadline is not None and 590 < trigger.deadline - time.time() <= 600

    xcom = op.execute_complete(context(), {"status": "final", "job_id": JOB_ID, "job_status": "succeeded"})
    assert xcom["status"] == "succeeded" and xcom["totals"] == {"violations": 0}


@pytest.mark.parametrize(
    ("event", "kind"),
    [
        # The job may still run on the server: a retry would submit it twice, so none of these retry.
        ({"status": "timeout", "job_id": JOB_ID, "message": "still running"}, "AirflowFailException"),
        ({"status": "error", "job_id": JOB_ID, "message": "401 token", "permanent": True}, "AirflowFailException"),
    ],
)
def test_execute_complete_turns_trigger_problems_into_failures(event: dict[str, Any], kind: str) -> None:
    op = CimPalValidateOperator(task_id="v", config={}, deferrable=True)
    with pytest.raises(AirflowException) as raised:
        op.execute_complete(context(), event)
    assert type(raised.value).__name__ == kind


# ---- on_kill ----------------------------------------------------------------------------------------


@respx.mock(base_url=BASE)
def test_on_kill_cancels_a_queued_job_and_leaves_a_running_one(respx_mock: respx.MockRouter, connection: Any) -> None:
    cancel = respx_mock.delete(f"/v1/jobs/{JOB_ID}").mock(
        side_effect=[
            httpx.Response(200, json=job_json("cancelled")),
            httpx.Response(409, json=problem(409, "Job is running; only queued jobs can be cancelled")),
        ]
    )
    op = CimPalValidateOperator(task_id="v", config={})
    op.on_kill()  # nothing submitted yet: nothing to cancel
    assert not cancel.called
    op.job_id = JOB_ID
    op.on_kill()
    op.on_kill()  # the 409 is logged, not raised
    assert cancel.call_count == 2


# ---- trigger -------------------------------------------------------------------------------------------


def test_the_trigger_serializes_to_something_that_builds_it_again() -> None:
    trigger = CimPalJobTrigger(JOB_ID, "cimpal_default", 15.0, 1234567890.5)
    path, kwargs = trigger.serialize()
    module, _, name = path.rpartition(".")
    import importlib

    rebuilt = getattr(importlib.import_module(module), name)(**kwargs)
    assert rebuilt.serialize() == (path, kwargs)
    assert json.loads(json.dumps(kwargs)) == kwargs


async def _events(trigger: Any) -> list[Any]:
    return [event async for event in trigger.run()]


@respx.mock(base_url=BASE)
async def test_the_trigger_fires_when_the_job_is_final(respx_mock: respx.MockRouter, connection: Any) -> None:
    respx_mock.get(f"/v1/jobs/{JOB_ID}").mock(
        side_effect=[
            httpx.Response(200, json=job_json("running")),
            httpx.Response(200, json=job_json("failed", exit_code=3)),
        ]
    )
    events = await _events(CimPalJobTrigger(JOB_ID, "cimpal_default", 0.01))
    assert events == [TriggerEvent({"status": "final", "job_id": JOB_ID, "job_status": "failed"})]


@respx.mock(base_url=BASE)
async def test_the_trigger_times_out_at_its_deadline(respx_mock: respx.MockRouter, connection: Any) -> None:
    respx_mock.get(f"/v1/jobs/{JOB_ID}").respond(json=job_json("running"))
    [event] = await _events(CimPalJobTrigger(JOB_ID, "cimpal_default", 0.01, deadline=time.time() - 1))
    assert event.payload["status"] == "timeout"


@respx.mock(base_url=BASE)
async def test_the_trigger_reports_a_refused_token_as_permanent(respx_mock: respx.MockRouter, connection: Any) -> None:
    respx_mock.get(f"/v1/jobs/{JOB_ID}").respond(401, json=problem(401, "invalid token"))
    [event] = await _events(CimPalJobTrigger(JOB_ID, "cimpal_default", 0.01))
    assert event.payload["status"] == "error" and event.payload["permanent"] is True
    assert TOKEN not in json.dumps(event.payload)


# ---- sensor --------------------------------------------------------------------------------------------


@respx.mock(base_url=BASE)
def test_the_sensor_waits_then_pushes_the_summary(respx_mock: respx.MockRouter, connection: Any) -> None:
    respx_mock.get(f"/v1/jobs/{JOB_ID}").mock(
        side_effect=[
            httpx.Response(200, json=job_json("running")),
            httpx.Response(200, json=job_json("succeeded", exit_code=0, has_violations=False)),
        ]
    )
    respx_mock.get(f"/v1/jobs/{JOB_ID}/result").respond(json={"totals": {"violations": 0}})
    sensor = CimPalJobSensor(task_id="s", job_id=JOB_ID)
    assert sensor.poke(context()) is False
    done = sensor.poke(context())
    assert isinstance(done, PokeReturnValue) and done.xcom_value is not None
    assert done.is_done and done.xcom_value["totals"] == {"violations": 0}


@respx.mock(base_url=BASE)
def test_the_sensor_fails_for_a_failed_job(respx_mock: respx.MockRouter, connection: Any) -> None:
    respx_mock.get(f"/v1/jobs/{JOB_ID}").respond(json=job_json("failed", exit_code=2))
    respx_mock.get(f"/v1/jobs/{JOB_ID}/result").respond(json={"error": "bad config"})
    with pytest.raises(AirflowFailException, match="bad config"):
        CimPalJobSensor(task_id="s", job_id=JOB_ID).poke(context())


# ---- security review (DEP-10) ------------------------------------------------------------------------


@respx.mock(base_url=BASE)
def test_a_job_the_server_timed_out_fails_without_retries_and_without_asking_for_a_result(
    respx_mock: respx.MockRouter, connection: Any
) -> None:
    respx_mock.get("/v1/health").respond(json=health_json())
    respx_mock.post("/v1/jobs").respond(202, json=job_json("queued"))
    respx_mock.get(f"/v1/jobs/{JOB_ID}").respond(json=job_json("timed_out"))
    respx_mock.get(f"/v1/jobs/{JOB_ID}/log").respond(json=log_json())
    # no /result route: asking for it would fail the test (respx refuses unmocked requests)
    with pytest.raises(AirflowFailException, match="keeps running there"):
        CimPalValidateOperator(task_id="v", config={}, deferrable=False).execute(context())


@respx.mock(base_url=BASE)
def test_a_cancelled_job_fails_without_retries(respx_mock: respx.MockRouter, connection: Any) -> None:
    respx_mock.get("/v1/health").respond(json=health_json())
    respx_mock.post("/v1/jobs").respond(202, json=job_json("queued"))
    respx_mock.get(f"/v1/jobs/{JOB_ID}").respond(json=job_json("cancelled"))
    respx_mock.get(f"/v1/jobs/{JOB_ID}/log").respond(json=log_json())
    with pytest.raises(AirflowFailException, match="was cancelled"):
        CimPalValidateOperator(task_id="v", config={}, deferrable=False).execute(context())


@respx.mock(base_url=BASE)
def test_running_out_of_time_fails_without_retries_and_submits_once(
    respx_mock: respx.MockRouter, connection: Any
) -> None:
    respx_mock.get("/v1/health").respond(json=health_json())
    submitted = respx_mock.post("/v1/jobs").respond(202, json=job_json("queued"))
    respx_mock.get(f"/v1/jobs/{JOB_ID}").respond(json=job_json("running"))
    op = CimPalValidateOperator(task_id="v", config={}, deferrable=False, job_timeout=0)
    with pytest.raises(AirflowFailException, match="isn't submitted twice"):
        op.execute(context())
    assert submitted.call_count == 1


@respx.mock(base_url=BASE)
def test_an_error_while_waiting_fails_without_retries(respx_mock: respx.MockRouter, connection: Any) -> None:
    respx_mock.get("/v1/health").respond(json=health_json())
    respx_mock.post("/v1/jobs").respond(202, json=job_json("queued"))
    respx_mock.get(f"/v1/jobs/{JOB_ID}").respond(500, json=problem(500, "boom"))
    with pytest.raises(AirflowFailException, match="may still be running"):
        CimPalValidateOperator(task_id="v", config={}, deferrable=False).execute(context())


@respx.mock(base_url=BASE)
def test_execute_complete_never_succeeds_for_a_job_that_is_not_finished(
    respx_mock: respx.MockRouter, connection: Any
) -> None:
    respx_mock.get("/v1/health").respond(json=health_json())
    respx_mock.get(f"/v1/jobs/{JOB_ID}").respond(json=job_json("running"))
    op = CimPalValidateOperator(task_id="v", config={}, deferrable=True)
    with pytest.raises(AirflowFailException, match="not finished"):
        op.execute_complete(context(), {"status": "final", "job_id": JOB_ID, "job_status": "succeeded"})


@respx.mock(base_url=BASE)
def test_a_relative_download_folder_works(
    respx_mock: respx.MockRouter, connection: Any, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.chdir(tmp_path)
    (tmp_path / "in").mkdir()
    (tmp_path / "in" / "d.xml").write_text("<x/>", encoding="utf-8")
    server(respx_mock, job_json("succeeded", exit_code=0, has_violations=False), result={})
    respx_mock.post("/v1/uploads").respond(201, json=upload_json())
    respx_mock.get(f"/v1/jobs/{JOB_ID}/files").respond(
        json={"jobId": JOB_ID, "files": [{"path": "r.xlsx", "size": 1, "mediaType": "x"}]}
    )
    respx_mock.get(f"/v1/jobs/{JOB_ID}/files/r.xlsx").respond(content=b"x")
    op = CimPalValidateOperator(task_id="v", config={}, upload_from="in", download_outputs_to="out", deferrable=False)
    assert op.execute(context())["outputFiles"] == ["r.xlsx"]


@respx.mock(base_url=BASE)
def test_the_copied_server_log_is_cleaned(
    respx_mock: respx.MockRouter, connection: Any, caplog: pytest.LogCaptureFixture
) -> None:
    server(respx_mock, job_json("succeeded", exit_code=0, has_violations=False), result={})
    escape = chr(27)
    respx_mock.get(f"/v1/jobs/{JOB_ID}/log").respond(json=log_json(["ok" + escape + "[31m red", "y" * 5000]))
    with caplog.at_level("INFO"):
        CimPalValidateOperator(task_id="v", config={}, deferrable=False).execute(context())
    copied = [r.getMessage() for r in caplog.records if "[cimpal]" in r.getMessage()]
    assert copied and all(escape not in m and len(m) < 1100 for m in copied)


def test_verify_off_spelled_another_way_fails_the_connection_test(connection: Any) -> None:
    connection.extra = json.dumps({"verify": "off"})  # not a boolean: taken as a CA bundle path, which is missing
    ok, _ = CimPalHook().test_connection()
    assert not ok


async def test_the_trigger_keeps_waiting_through_a_passing_error(connection: Any) -> None:
    with respx.mock(base_url=BASE) as respx_mock:
        respx_mock.get(f"/v1/jobs/{JOB_ID}").mock(
            side_effect=[
                httpx.Response(500, json=problem(500, "restarting")),
                httpx.Response(200, json=job_json("succeeded", exit_code=0, has_violations=False)),
            ]
        )
        events = await _events(CimPalJobTrigger(JOB_ID, "cimpal_default", 0.01))
    assert [e.payload["status"] for e in events] == ["final"]


@respx.mock(base_url=BASE)
async def test_a_dropped_job_ends_the_trigger_as_permanent(respx_mock: respx.MockRouter, connection: Any) -> None:
    respx_mock.get(f"/v1/jobs/{JOB_ID}").respond(404, json=problem(404, "No such job"))
    [event] = await _events(CimPalJobTrigger(JOB_ID, "cimpal_default", 0.01))
    assert event.payload["status"] == "error" and event.payload["permanent"] is True


def test_the_trigger_holds_no_token() -> None:
    _, kwargs = CimPalJobTrigger(JOB_ID, "cimpal_default", 1.0, None).serialize()
    assert TOKEN not in json.dumps(kwargs)


def test_the_sensor_defers_and_resumes(connection: Any) -> None:
    with respx.mock(base_url=BASE) as respx_mock:
        respx_mock.get("/v1/health").respond(json=health_json())
        respx_mock.get(f"/v1/jobs/{JOB_ID}").mock(
            side_effect=[
                httpx.Response(200, json=job_json("running")),
                httpx.Response(200, json=job_json("succeeded", exit_code=1, has_violations=True)),
            ]
        )
        respx_mock.get(f"/v1/jobs/{JOB_ID}/result").respond(json={"totals": {"violations": 1}})
        sensor = CimPalJobSensor(task_id="s", job_id=JOB_ID, deferrable=True, timeout=600, poke_interval=5)
        with pytest.raises(TaskDeferred) as deferred:
            sensor.execute(context())
        assert isinstance(deferred.value.trigger, CimPalJobTrigger)
        summary = sensor.execute_complete(context(), {"status": "final", "job_id": JOB_ID, "job_status": "x"})
    assert summary["hasViolations"] is True
    with pytest.raises(AirflowFailException):
        sensor.execute_complete(context(), {"status": "error", "job_id": JOB_ID, "message": "x", "permanent": True})
