# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""The synchronous client against a mocked /v1 API."""

from __future__ import annotations

import contextlib
import io
import json
import zipfile
from pathlib import Path

import httpx
import pytest
import respx

import cimpal_client
from cimpal_client import (
    AuthError,
    BadRequestError,
    BusyError,
    CimPal,
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

# ---- requests: token, user agent, base URL -----------------------------------------------------


@respx.mock(base_url=BASE)
def test_every_request_carries_the_token_and_the_user_agent(respx_mock: respx.MockRouter, cp: CimPal) -> None:
    route = respx_mock.get(f"/v1/jobs/{JOB_ID}").respond(json=job_json())
    cp.job(JOB_ID)
    request = route.calls.last.request
    assert request.headers["Authorization"] == f"Bearer {TOKEN}"
    assert request.headers["User-Agent"] == f"cimpal-client/{cimpal_client.__version__}"


def test_the_token_never_shows_in_repr() -> None:
    with CimPal(BASE + "/", token=TOKEN) as cp:
        assert TOKEN not in repr(cp)
        assert TOKEN not in repr(vars(cp))
        assert cp.base_url == BASE


def test_the_token_comes_from_the_environment_or_a_file(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    monkeypatch.setenv("CIMPAL_API_TOKEN", "from-env")
    with CimPal(BASE) as cp:
        assert cp._token is not None and cp._token.reveal() == "from-env"
    token_file = tmp_path / "token"
    token_file.write_text("# rotated 2026-10\n\nfrom-file\nolder-token\n", encoding="utf-8")
    monkeypatch.delenv("CIMPAL_API_TOKEN")
    monkeypatch.setenv("CIMPAL_API_TOKEN_FILE", str(token_file))
    with CimPal(BASE) as cp:
        assert cp._token is not None and cp._token.reveal() == "from-file"
    with CimPal(BASE, token="explicit") as cp:
        assert cp._token is not None and cp._token.reveal() == "explicit"


@pytest.mark.parametrize("url", ["ftp://cimpal.test", "cimpal.test:7474", "http://user:pw@cimpal.test"])
def test_a_base_url_that_is_not_plain_http_is_refused(url: str) -> None:
    with pytest.raises(ValueError, match="base_url"):
        CimPal(url, token=TOKEN)


# ---- jobs ---------------------------------------------------------------------------------------


@respx.mock(base_url=BASE)
def test_submit_sends_the_command_config_label_and_upload(respx_mock: respx.MockRouter, cp: CimPal) -> None:
    route = respx_mock.post("/v1/jobs").respond(202, json=job_json())
    job = cp.submit("validate", {"workflow": "combined"}, input_upload=UPLOAD_ID, label="nightly")
    assert json.loads(route.calls.last.request.content) == {
        "command": "validate",
        "config": {"workflow": "combined"},
        "label": "nightly",
        "inputUpload": UPLOAD_ID,
    }
    assert str(job.job_id) == JOB_ID
    assert job.status is JobStatus.QUEUED


@respx.mock(base_url=BASE)
def test_wait_polls_until_the_job_is_final_and_backs_off(
    respx_mock: respx.MockRouter, cp: CimPal, sleeps: Sleeps
) -> None:
    respx_mock.get(f"/v1/jobs/{JOB_ID}").mock(
        side_effect=[
            httpx.Response(200, json=job_json("queued")),
            httpx.Response(200, json=job_json("running")),
            httpx.Response(200, json=job_json("running")),
            httpx.Response(200, json=job_json("succeeded")),
        ]
    )
    job = cp.wait(JOB_ID, poll_interval=1.0, max_poll_interval=2.0)
    assert job.status is JobStatus.SUCCEEDED
    assert job.has_violations is True
    assert sleeps == [1.0, 1.5, 2.0]


@respx.mock(base_url=BASE, assert_all_called=False)
def test_wait_gives_up_after_its_timeout_without_cancelling(respx_mock: respx.MockRouter, cp: CimPal) -> None:
    respx_mock.get(f"/v1/jobs/{JOB_ID}").respond(json=job_json("running"))
    cancel = respx_mock.delete(f"/v1/jobs/{JOB_ID}")
    with pytest.raises(cimpal_client.TimeoutError) as raised:
        cp.wait(JOB_ID, timeout=0)
    assert raised.value.job_id == JOB_ID
    assert isinstance(raised.value, TimeoutError)  # also the builtin
    assert not cancel.called


@pytest.mark.parametrize("status", ["failed", "cancelled", "timed_out"])
@respx.mock(base_url=BASE)
def test_wait_returns_every_final_status(respx_mock: respx.MockRouter, cp: CimPal, status: str) -> None:
    respx_mock.get(f"/v1/jobs/{JOB_ID}").respond(json=job_json(status))
    assert cp.wait(JOB_ID).status == JobStatus(status)


@respx.mock(base_url=BASE)
def test_result_is_the_commands_json_document(respx_mock: respx.MockRouter, cp: CimPal) -> None:
    respx_mock.get(f"/v1/jobs/{JOB_ID}/result").respond(json={"hasViolations": True, "totals": {"violations": 3}})
    assert cp.result(cp_job(JOB_ID)) == {"hasViolations": True, "totals": {"violations": 3}}


@respx.mock(base_url=BASE)
def test_a_result_that_is_not_json_comes_back_as_output_text(respx_mock: respx.MockRouter, cp: CimPal) -> None:
    respx_mock.get(f"/v1/jobs/{JOB_ID}/result").respond(text="Converted 12 triples", content_type="application/json")
    assert cp.result(JOB_ID) == {"output": "Converted 12 triples"}


@respx.mock(base_url=BASE)
def test_list_cancel_and_log(respx_mock: respx.MockRouter, cp: CimPal) -> None:
    listing = respx_mock.get("/v1/jobs").respond(json={"jobs": [job_json("running")]})
    respx_mock.delete(f"/v1/jobs/{JOB_ID}").respond(json=job_json("cancelled"))
    log = respx_mock.get(f"/v1/jobs/{JOB_ID}/log").respond(
        json={"jobId": JOB_ID, "offset": 2, "nextOffset": 4, "truncated": False, "lines": ["a", "b"]}
    )

    jobs = cp.list_jobs(status="running", limit=10)
    assert [j.status for j in jobs] == [JobStatus.RUNNING]
    assert dict(listing.calls.last.request.url.params) == {"status": "running", "limit": "10"}
    assert cp.cancel(JOB_ID).status is JobStatus.CANCELLED
    page = cp.log(JOB_ID, offset=2)
    assert page.lines == ["a", "b"] and page.next_offset == 4
    assert log.calls.last.request.url.params["offset"] == "2"


def test_a_job_id_that_is_not_a_uuid_is_refused_before_any_request(cp: CimPal) -> None:
    with pytest.raises(ValueError):
        cp.job("../uploads/x")


# ---- uploads and outputs ------------------------------------------------------------------------


@respx.mock(base_url=BASE)
def test_upload_zips_a_folder_without_following_links(respx_mock: respx.MockRouter, cp: CimPal, tmp_path: Path) -> None:
    folder = tmp_path / "models"
    (folder / "sub").mkdir(parents=True)
    (folder / "eq.xml").write_text("<eq/>", encoding="utf-8")
    (folder / "sub" / "ssh.xml").write_text("<ssh/>", encoding="utf-8")
    secret = tmp_path / "secret.txt"
    secret.write_text("not for the server", encoding="utf-8")
    with contextlib.suppress(OSError):  # no symlink rights (Windows): the rest still holds
        (folder / "link.txt").symlink_to(secret)
    route = respx_mock.post("/v1/uploads").respond(201, json=upload_json())

    upload = cp.upload(folder)

    request = route.calls.last.request
    assert request.headers["Content-Type"] == "application/zip"
    names = zipfile.ZipFile(io.BytesIO(request.content)).namelist()
    assert sorted(names) == ["eq.xml", "sub/ssh.xml"]
    assert str(upload.upload_id) == UPLOAD_ID
    assert not list(Path(cimpal_tmp()).glob("cimpal-upload-*.zip")), "the temporary ZIP is deleted"


@respx.mock(base_url=BASE)
def test_upload_sends_a_zip_as_it_is(respx_mock: respx.MockRouter, cp: CimPal, tmp_path: Path) -> None:
    archive = tmp_path / "models.zip"
    with zipfile.ZipFile(archive, "w") as z:
        z.writestr("eq.xml", "<eq/>")
    route = respx_mock.post("/v1/uploads").respond(201, json=upload_json())
    cp.upload(archive)
    assert route.calls.last.request.content == archive.read_bytes()


@respx.mock(base_url=BASE)
def test_get_and_delete_upload(respx_mock: respx.MockRouter, cp: CimPal) -> None:
    respx_mock.get(f"/v1/uploads/{UPLOAD_ID}").respond(json=upload_json())
    deleted = respx_mock.delete(f"/v1/uploads/{UPLOAD_ID}").respond(204)
    assert cp.get_upload(UPLOAD_ID).total_bytes == 12
    cp.delete_upload(UPLOAD_ID)
    assert deleted.called


@respx.mock(base_url=BASE)
def test_download_outputs_keeps_relative_paths(respx_mock: respx.MockRouter, cp: CimPal, tmp_path: Path) -> None:
    respx_mock.get(f"/v1/jobs/{JOB_ID}/files").respond(
        json={
            "jobId": JOB_ID,
            "files": [
                {"path": "report.xlsx", "size": 3, "mediaType": "application/octet-stream"},
                {"path": "turtle/report a.ttl", "size": 4, "mediaType": "text/turtle"},
            ],
        }
    )
    respx_mock.get(f"/v1/jobs/{JOB_ID}/files/report.xlsx").respond(content=b"xls")
    respx_mock.get(f"/v1/jobs/{JOB_ID}/files/turtle/report%20a.ttl").respond(content=b"@ttl")

    written = cp.download_outputs(JOB_ID, tmp_path / "out")

    assert sorted(p.relative_to(tmp_path / "out").as_posix() for p in written) == ["report.xlsx", "turtle/report a.ttl"]
    assert (tmp_path / "out" / "turtle" / "report a.ttl").read_bytes() == b"@ttl"


@pytest.mark.parametrize("name", ["../escape.txt", "/etc/passwd", "a/../../b", "C:/x", "a\\..\\b", ""])
@respx.mock(base_url=BASE)
def test_an_output_name_that_would_leave_the_folder_is_refused(
    respx_mock: respx.MockRouter, cp: CimPal, tmp_path: Path, name: str
) -> None:
    respx_mock.get(f"/v1/jobs/{JOB_ID}/files").respond(
        json={"jobId": JOB_ID, "files": [{"path": name, "size": 1, "mediaType": "text/plain"}]}
    )
    with pytest.raises(ServerError, match="leave the target folder"):
        cp.download_outputs(JOB_ID, tmp_path / "out")
    assert not (tmp_path / "escape.txt").exists()


@respx.mock(base_url=BASE)
def test_download_archive_streams_the_zip(respx_mock: respx.MockRouter, cp: CimPal, tmp_path: Path) -> None:
    respx_mock.get(f"/v1/jobs/{JOB_ID}/archive").respond(content=b"PK\x05\x06" + b"\0" * 18)
    target = cp.download_archive(JOB_ID, tmp_path / "outputs.zip")
    assert target.read_bytes().startswith(b"PK")


@respx.mock(base_url=BASE)
def test_a_download_that_fails_midway_leaves_no_file(respx_mock: respx.MockRouter, cp: CimPal, tmp_path: Path) -> None:
    def broken(request: httpx.Request) -> httpx.Response:
        def body() -> object:
            yield b"partial"
            raise httpx.ReadError("connection reset")

        return httpx.Response(200, content=body())  # type: ignore[arg-type]

    respx_mock.get(f"/v1/jobs/{JOB_ID}/archive").mock(side_effect=broken)
    with pytest.raises(CimPalError, match="could not reach"):
        cp.download_archive(JOB_ID, tmp_path / "outputs.zip")
    assert not (tmp_path / "outputs.zip").exists()


# ---- errors -------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("status", "kind"),
    [
        (400, BadRequestError),
        (401, AuthError),
        (403, ForbiddenError),
        (404, NotFoundError),
        (409, ConflictError),
        (410, GoneError),
        (413, BadRequestError),
        (415, BadRequestError),
        (500, ServerError),
        (504, cimpal_client.TimeoutError),
        (507, ServerError),
    ],
)
@respx.mock(base_url=BASE)
def test_problem_details_become_typed_errors(
    respx_mock: respx.MockRouter, cp: CimPal, status: int, kind: type[CimPalError]
) -> None:
    respx_mock.get(f"/v1/jobs/{JOB_ID}").respond(
        status, json=problem(status, "Something specific", jobId=JOB_ID), content_type="application/problem+json"
    )
    with pytest.raises(kind) as raised:
        cp.job(JOB_ID)
    error = raised.value
    assert error.status == status
    assert error.detail == "Something specific"
    assert error.job_id == JOB_ID
    assert "Something specific" in str(error)
    assert TOKEN not in str(error)


@respx.mock(base_url=BASE)
def test_the_older_error_body_is_understood_too(respx_mock: respx.MockRouter, cp: CimPal) -> None:
    respx_mock.get(f"/v1/jobs/{JOB_ID}").respond(403, json={"error": "Path outside the allowed roots"})
    with pytest.raises(ForbiddenError, match="allowed roots"):
        cp.job(JOB_ID)


# ---- retries ------------------------------------------------------------------------------------


@respx.mock(base_url=BASE)
def test_busy_is_retried_honouring_retry_after(respx_mock: respx.MockRouter, cp: CimPal, sleeps: Sleeps) -> None:
    route = respx_mock.post("/v1/jobs").mock(
        side_effect=[
            httpx.Response(503, json=problem(503, "Server busy"), headers={"Retry-After": "7"}),
            # CimPal's own 503 (problem+json) without Retry-After: the backoff applies
            httpx.Response(503, json=problem(503, "Server busy"), headers={"Content-Type": "application/problem+json"}),
            httpx.Response(202, json=job_json()),
        ]
    )
    cp.submit("sparql", {})
    assert route.call_count == 3
    assert sleeps == [7.0, 2.0]  # Retry-After first, then the backoff (1 s doubled)


@respx.mock(base_url=BASE)
def test_busy_after_the_last_retry_raises_with_retry_after(respx_mock: respx.MockRouter, sleeps: Sleeps) -> None:
    respx_mock.post("/v1/jobs").respond(503, json=problem(503, "Server busy"), headers={"Retry-After": "30"})
    with CimPal(BASE, token=TOKEN, retries=1, check_api_version=False) as cp:
        cp._sleep = sleeps
        with pytest.raises(BusyError) as raised:
            cp.submit("sparql", {})
    assert raised.value.retry_after == 30.0
    assert sleeps == [30.0]


@respx.mock(base_url=BASE)
def test_a_submit_that_may_have_reached_the_server_is_never_retried(respx_mock: respx.MockRouter, cp: CimPal) -> None:
    route = respx_mock.post("/v1/jobs").mock(side_effect=httpx.ReadTimeout("no answer"))
    with pytest.raises(cimpal_client.TimeoutError):
        cp.submit("validate", {})
    assert route.call_count == 1


@respx.mock(base_url=BASE)
def test_a_submit_that_never_reached_the_server_is_retried(respx_mock: respx.MockRouter, cp: CimPal) -> None:
    route = respx_mock.post("/v1/jobs").mock(
        side_effect=[httpx.ConnectError("refused"), httpx.Response(202, json=job_json())]
    )
    cp.submit("validate", {})
    assert route.call_count == 2


@respx.mock(base_url=BASE)
def test_reads_are_retried_after_a_dropped_connection(respx_mock: respx.MockRouter, cp: CimPal) -> None:
    route = respx_mock.get(f"/v1/jobs/{JOB_ID}").mock(
        side_effect=[httpx.RemoteProtocolError("dropped"), httpx.Response(200, json=job_json())]
    )
    cp.job(JOB_ID)
    assert route.call_count == 2


@respx.mock(base_url=BASE)
def test_an_unreachable_server_is_a_cimpal_error_after_the_retries(
    respx_mock: respx.MockRouter, cp: CimPal, sleeps: Sleeps
) -> None:
    respx_mock.get(f"/v1/jobs/{JOB_ID}").mock(side_effect=httpx.ConnectError("refused"))
    with pytest.raises(CimPalError, match="could not reach the server"):
        cp.job(JOB_ID)
    assert sleeps == [1.0, 2.0, 4.0]


# ---- API version --------------------------------------------------------------------------------


@respx.mock(base_url=BASE)
def test_an_unknown_api_version_warns_once_before_the_first_job(respx_mock: respx.MockRouter) -> None:
    health = respx_mock.get("/v1/health").respond(json=health_json(api_version="2"))
    respx_mock.post("/v1/jobs").respond(202, json=job_json())
    with CimPal(BASE, token=TOKEN) as cp:
        with pytest.warns(CompatibilityWarning, match="'2'"):
            cp.submit("validate", {})
        cp.submit("validate", {})
    assert health.call_count == 1


@respx.mock(base_url=BASE)
def test_health(respx_mock: respx.MockRouter, cp: CimPal) -> None:
    respx_mock.get("/v1/health").respond(json=health_json())
    health = cp.health()
    assert health.api_version == "1" and health.workers == 1


# ---- helpers ------------------------------------------------------------------------------------


def cp_job(job_id: str) -> cimpal_client.Job:
    return cimpal_client.Job.from_dict(job_json("succeeded", jobId=job_id))


def cimpal_tmp() -> str:
    import tempfile

    return tempfile.gettempdir()
