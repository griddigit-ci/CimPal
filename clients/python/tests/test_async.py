# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""The asynchronous client against a mocked /v1 API: every method, error mapping and retry rule."""

from __future__ import annotations

import io
import json
import zipfile
from pathlib import Path

import httpx
import pytest
import respx

import cimpal_client
from cimpal_client import (
    AsyncCimPal,
    AuthError,
    BusyError,
    CimPalError,
    CompatibilityWarning,
    ConflictError,
    ForbiddenError,
    GoneError,
    JobStatus,
    NotFoundError,
    ServerError,
)

from .conftest import BASE, JOB_ID, TOKEN, UPLOAD_ID, Sleeps, health_json, job_json, problem, upload_json


@respx.mock(base_url=BASE)
async def test_submit_wait_result(respx_mock: respx.MockRouter, acp: AsyncCimPal, sleeps: Sleeps) -> None:
    submitted = respx_mock.post("/v1/jobs").respond(202, json=job_json())
    respx_mock.get(f"/v1/jobs/{JOB_ID}").mock(
        side_effect=[httpx.Response(200, json=job_json("running")), httpx.Response(200, json=job_json("succeeded"))]
    )
    respx_mock.get(f"/v1/jobs/{JOB_ID}/result").respond(json={"hasViolations": True})

    job = await acp.submit("validate", {"workflow": "combined"}, input_upload=UPLOAD_ID, label="x")
    done = await acp.wait(job, poll_interval=0.5)
    result = await acp.result(done)

    request = submitted.calls.last.request
    assert request.headers["Authorization"] == f"Bearer {TOKEN}"
    assert request.headers["User-Agent"] == f"cimpal-client/{cimpal_client.__version__}"
    assert json.loads(request.content)["inputUpload"] == UPLOAD_ID
    assert done.status is JobStatus.SUCCEEDED
    assert result == {"hasViolations": True}
    assert sleeps == [0.5]


@respx.mock(base_url=BASE)
async def test_wait_times_out(respx_mock: respx.MockRouter, acp: AsyncCimPal) -> None:
    respx_mock.get(f"/v1/jobs/{JOB_ID}").respond(json=job_json("queued"))
    with pytest.raises(cimpal_client.TimeoutError):
        await acp.wait(JOB_ID, timeout=0)


@respx.mock(base_url=BASE)
async def test_jobs_listing_cancel_log_and_health(respx_mock: respx.MockRouter, acp: AsyncCimPal) -> None:
    respx_mock.get("/v1/jobs").respond(json={"jobs": [job_json("queued")]})
    respx_mock.delete(f"/v1/jobs/{JOB_ID}").respond(json=job_json("cancelled"))
    respx_mock.get(f"/v1/jobs/{JOB_ID}/log").respond(
        json={"jobId": JOB_ID, "offset": 0, "nextOffset": 1, "truncated": False, "lines": ["[INFO] start"]}
    )
    respx_mock.get("/v1/health").respond(json=health_json())

    assert [j.status for j in await acp.list_jobs(status=JobStatus.QUEUED)] == [JobStatus.QUEUED]
    assert (await acp.cancel(JOB_ID)).status is JobStatus.CANCELLED
    assert (await acp.log(JOB_ID)).lines == ["[INFO] start"]
    assert (await acp.health()).status.value == "ok"


@respx.mock(base_url=BASE)
async def test_uploads(respx_mock: respx.MockRouter, acp: AsyncCimPal, tmp_path: Path) -> None:
    (tmp_path / "eq.xml").write_text("<eq/>", encoding="utf-8")
    route = respx_mock.post("/v1/uploads").respond(201, json=upload_json())
    respx_mock.get(f"/v1/uploads/{UPLOAD_ID}").respond(json=upload_json())
    deleted = respx_mock.delete(f"/v1/uploads/{UPLOAD_ID}").respond(204)

    upload = await acp.upload(tmp_path / "eq.xml")
    assert zipfile.ZipFile(io.BytesIO(route.calls.last.request.content)).namelist() == ["eq.xml"]
    assert (await acp.get_upload(upload)).total_bytes == 12
    await acp.delete_upload(upload)
    assert deleted.called


@respx.mock(base_url=BASE)
async def test_outputs(respx_mock: respx.MockRouter, acp: AsyncCimPal, tmp_path: Path) -> None:
    respx_mock.get(f"/v1/jobs/{JOB_ID}/files").respond(
        json={"jobId": JOB_ID, "files": [{"path": "a/b.ttl", "size": 2, "mediaType": "text/turtle"}]}
    )
    respx_mock.get(f"/v1/jobs/{JOB_ID}/files/a/b.ttl").respond(content=b"ok")
    respx_mock.get(f"/v1/jobs/{JOB_ID}/archive").respond(content=b"PK")

    written = await acp.download_outputs(JOB_ID, tmp_path / "out")
    assert [p.read_bytes() for p in written] == [b"ok"]
    assert (await acp.download_archive(JOB_ID, tmp_path / "all.zip")).read_bytes() == b"PK"


@respx.mock(base_url=BASE)
async def test_an_escaping_output_name_is_refused(
    respx_mock: respx.MockRouter, acp: AsyncCimPal, tmp_path: Path
) -> None:
    respx_mock.get(f"/v1/jobs/{JOB_ID}/files").respond(
        json={"jobId": JOB_ID, "files": [{"path": "../x", "size": 1, "mediaType": "text/plain"}]}
    )
    with pytest.raises(ServerError):
        await acp.download_outputs(JOB_ID, tmp_path / "out")


@pytest.mark.parametrize(
    ("status", "kind"),
    [
        (401, AuthError),
        (403, ForbiddenError),
        (404, NotFoundError),
        (409, ConflictError),
        (410, GoneError),
        (500, ServerError),
        (504, cimpal_client.TimeoutError),
    ],
)
@respx.mock(base_url=BASE)
async def test_errors(respx_mock: respx.MockRouter, acp: AsyncCimPal, status: int, kind: type[CimPalError]) -> None:
    respx_mock.get(f"/v1/jobs/{JOB_ID}/result").respond(status, json=problem(status, "why"))
    with pytest.raises(kind) as raised:
        await acp.result(JOB_ID)
    assert raised.value.status == status and raised.value.detail == "why"


@respx.mock(base_url=BASE)
async def test_busy_retries_with_retry_after_then_raises(respx_mock: respx.MockRouter, sleeps: Sleeps) -> None:
    respx_mock.post("/v1/uploads").respond(503, json=problem(503, "busy"), headers={"Retry-After": "5"})

    async def sleep(seconds: float) -> None:
        sleeps(seconds)

    async with AsyncCimPal(BASE, token=TOKEN, retries=2, check_api_version=False) as acp:
        acp._sleep = sleep
        with pytest.raises(BusyError) as raised:
            await acp.upload(Path(__file__))
    assert sleeps == [5.0, 5.0]
    assert raised.value.retry_after == 5.0


@respx.mock(base_url=BASE)
async def test_a_submit_that_may_have_arrived_is_not_retried(respx_mock: respx.MockRouter, acp: AsyncCimPal) -> None:
    route = respx_mock.post("/v1/jobs").mock(side_effect=httpx.RemoteProtocolError("dropped"))
    with pytest.raises(CimPalError):
        await acp.submit("validate", {})
    assert route.call_count == 1


@respx.mock(base_url=BASE)
async def test_unknown_api_version_warns(respx_mock: respx.MockRouter) -> None:
    respx_mock.get("/v1/health").respond(json=health_json(api_version="9"))
    respx_mock.post("/v1/jobs").respond(202, json=job_json())
    async with AsyncCimPal(BASE, token=TOKEN) as acp:
        with pytest.warns(CompatibilityWarning):
            await acp.submit("validate", {})


def test_repr_hides_the_token() -> None:
    assert TOKEN not in repr(AsyncCimPal(BASE, token=TOKEN))
