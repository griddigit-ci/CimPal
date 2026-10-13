# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""Regression tests for the DEP-9 security review: redirects, plain HTTP, untrusted server text,
uploads that must not leave their folder, downloads that must not leave theirs."""

from __future__ import annotations

import asyncio
import os
import re
import subprocess
import sys
import tempfile
import threading
from pathlib import Path

import httpx
import pytest
import respx

from cimpal_client import AsyncCimPal, AuthError, BusyError, CimPal, CimPalError, ServerError, _core

from .conftest import BASE, JOB_ID, TOKEN, Sleeps, job_json, problem, upload_json

# ---- redirects are errors, never followed --------------------------------------------------------


@respx.mock(base_url=BASE, assert_all_called=False)
def test_a_redirect_is_an_error_and_is_not_followed(respx_mock: respx.MockRouter, cp: CimPal, tmp_path: Path) -> None:
    respx_mock.get(f"/v1/jobs/{JOB_ID}/result").respond(302, headers={"Location": "https://elsewhere.test/r"})
    respx_mock.get(f"/v1/jobs/{JOB_ID}/archive").respond(301, headers={"Location": "https://elsewhere.test/a"})
    elsewhere = respx_mock.route(host="elsewhere.test")

    with pytest.raises(CimPalError, match=re.escape("redirects to https://elsewhere.test/r")) as raised:
        cp.result(JOB_ID)
    assert raised.value.status == 302
    with pytest.raises(CimPalError):
        cp.download_archive(JOB_ID, tmp_path / "out.zip")
    assert not elsewhere.called
    assert list(tmp_path.iterdir()) == []


@respx.mock(base_url=BASE)
async def test_a_redirect_is_an_error_in_the_async_client_too(respx_mock: respx.MockRouter, acp: AsyncCimPal) -> None:
    respx_mock.get(f"/v1/jobs/{JOB_ID}").respond(307, headers={"Location": "https://elsewhere.test/"})
    with pytest.raises(CimPalError, match="not followed"):
        await acp.job(JOB_ID)


# ---- the token over plain HTTP -----------------------------------------------------------------


@pytest.mark.parametrize("url", ["http://cimpal.corp.example", "http://10.0.0.5:7474"])
def test_a_token_over_plain_http_to_another_machine_is_refused(url: str) -> None:
    with pytest.raises(ValueError, match="unencrypted"):
        CimPal(url, token=TOKEN)
    CimPal(url, token=TOKEN, allow_insecure_http=True).close()  # explicitly allowed
    CimPal(url).close()  # no token, nothing to leak


@pytest.mark.parametrize("url", ["http://localhost:7474", "http://127.0.0.1:7474", "http://[::1]:7474"])
def test_plain_http_to_this_machine_is_fine(url: str) -> None:
    CimPal(url, token=TOKEN).close()


@pytest.mark.parametrize("url", ["https://cimpal.test/?x=1", "https://cimpal.test/#"])
def test_a_base_url_with_a_query_or_fragment_is_refused(url: str) -> None:
    with pytest.raises(ValueError, match="query or a fragment"):
        CimPal(url, token=TOKEN)


@pytest.mark.parametrize("token", ["tökén", "two words", "line\r\nX-Injected: 1", "tab\tchar"])
def test_a_token_that_cant_be_a_header_is_refused_without_echoing_it(token: str) -> None:
    with pytest.raises(AuthError) as raised:
        CimPal(BASE, token=token)
    assert token.strip() not in str(raised.value)


# ---- untrusted server text -----------------------------------------------------------------------


@respx.mock(base_url=BASE)
def test_server_text_in_errors_is_cleaned_and_capped(respx_mock: respx.MockRouter, cp: CimPal) -> None:
    detail = "bad\r\n[INFO] forged log line \x1b[31mred" + "x" * 5000
    respx_mock.get(f"/v1/jobs/{JOB_ID}").respond(400, json=problem(400, detail))
    with pytest.raises(CimPalError) as raised:
        cp.job(JOB_ID)
    message = str(raised.value)
    assert "\r" not in message and "\n" not in message and "\x1b" not in message
    assert raised.value.detail is not None and len(raised.value.detail) <= _core.MAX_ERROR_TEXT + 1


@respx.mock(base_url=BASE)
def test_a_huge_error_body_is_read_only_up_to_a_cap(respx_mock: respx.MockRouter, cp: CimPal) -> None:
    respx_mock.get(f"/v1/jobs/{JOB_ID}").respond(500, content=b"x" * (5 * 1024 * 1024))
    with pytest.raises(ServerError):
        cp.job(JOB_ID)


@respx.mock(base_url=BASE)
def test_an_html_page_is_no_result(respx_mock: respx.MockRouter, cp: CimPal) -> None:
    respx_mock.get(f"/v1/jobs/{JOB_ID}/result").respond(text="<html>Sign in</html>", content_type="text/html")
    with pytest.raises(ServerError, match="expected a JSON result"):
        cp.result(JOB_ID)


@pytest.mark.parametrize("value", ["NaN", "nan", "inf", "-inf", "1e999"])
def test_a_retry_after_that_is_not_a_finite_number_is_ignored(value: str) -> None:
    assert _core.parse_retry_after(value) is None


# ---- retries of a 503 to a POST ----------------------------------------------------------------


@respx.mock(base_url=BASE)
def test_a_proxys_503_to_a_submit_is_not_retried(respx_mock: respx.MockRouter, cp: CimPal) -> None:
    route = respx_mock.post("/v1/jobs").respond(503, text="upstream connect error or disconnect/reset before headers")
    with pytest.raises(BusyError):
        cp.submit("validate", {})
    assert route.call_count == 1


@respx.mock(base_url=BASE)
def test_cimpals_own_503_to_a_submit_is_retried(respx_mock: respx.MockRouter, cp: CimPal) -> None:
    route = respx_mock.post("/v1/jobs").mock(
        side_effect=[
            httpx.Response(503, json=problem(503, "busy"), headers={"Content-Type": "application/problem+json"}),
            httpx.Response(202, json=job_json()),
        ]
    )
    cp.submit("validate", {})
    assert route.call_count == 2


@respx.mock(base_url=BASE)
async def test_the_async_client_retries_reads_after_a_dropped_connection(
    respx_mock: respx.MockRouter, acp: AsyncCimPal, sleeps: Sleeps
) -> None:
    route = respx_mock.get(f"/v1/jobs/{JOB_ID}").mock(
        side_effect=[httpx.ReadError("reset"), httpx.Response(200, json=job_json())]
    )
    await acp.job(JOB_ID)
    assert route.call_count == 2 and sleeps == [1.0]


@pytest.mark.parametrize(("interval", "maximum"), [(0, 30), (-1, 30), (5, 1)])
def test_a_poll_interval_that_would_hammer_the_server_is_refused(cp: CimPal, interval: float, maximum: float) -> None:
    with pytest.raises(ValueError, match="poll_interval"):
        cp.wait(JOB_ID, poll_interval=interval, max_poll_interval=maximum)


# ---- downloads stay in their folder; a failed one keeps the old file ----------------------------


@pytest.mark.parametrize("name", ["//server/share/x", "a/./b", "a//b", "C:x", "x:stream", "CON", "nul.txt", "a.", "a "])
def test_more_names_that_would_leave_the_folder_are_refused(tmp_path: Path, name: str) -> None:
    with pytest.raises(ServerError):
        _core.safe_destination(tmp_path, name)


def test_a_link_in_the_destination_folder_is_not_followed(tmp_path: Path) -> None:
    outside = tmp_path / "outside"
    outside.mkdir()
    folder = tmp_path / "out"
    folder.mkdir()
    _link_dir(folder / "link", outside)
    with pytest.raises(ServerError):
        _core.safe_destination(folder, "link/x.ttl")


@pytest.mark.parametrize("path", ["../uploads/x", "a/../b", "./a", ""])
def test_a_direct_download_path_with_dot_segments_is_refused(cp: CimPal, tmp_path: Path, path: str) -> None:
    with pytest.raises(ValueError):
        cp.download_file(JOB_ID, path, tmp_path / "x")


@respx.mock(base_url=BASE)
def test_a_failed_download_leaves_the_existing_file_as_it_was(
    respx_mock: respx.MockRouter, cp: CimPal, tmp_path: Path
) -> None:
    existing = tmp_path / "outputs.zip"
    existing.write_bytes(b"the earlier download")

    def broken(request: httpx.Request) -> httpx.Response:
        def body() -> object:
            yield b"partial"
            raise httpx.ReadError("reset")

        return httpx.Response(200, content=body())  # type: ignore[arg-type]

    respx_mock.get(f"/v1/jobs/{JOB_ID}/archive").mock(side_effect=broken)
    with pytest.raises(CimPalError):
        cp.download_archive(JOB_ID, existing)
    assert existing.read_bytes() == b"the earlier download"
    assert [p.name for p in tmp_path.iterdir()] == ["outputs.zip"], "no .part file left"


# ---- uploads send nothing from outside the folder -------------------------------------------------


def test_a_linked_folder_inside_the_upload_is_not_sent(tmp_path: Path) -> None:
    outside = tmp_path / "private"
    outside.mkdir()
    (outside / "secret.txt").write_text("not for the server", encoding="utf-8")
    folder = tmp_path / "models"
    folder.mkdir()
    (folder / "eq.xml").write_text("<eq/>", encoding="utf-8")
    _link_dir(folder / "data", outside)  # a junction on Windows, a symlink elsewhere

    source = _core.zip_source(folder)
    try:
        import zipfile

        assert zipfile.ZipFile(source.path).namelist() == ["eq.xml"]
    finally:
        source.cleanup()


@respx.mock(base_url=BASE, assert_all_called=False)
async def test_cancelling_an_upload_while_zipping_leaves_no_zip(
    respx_mock: respx.MockRouter, acp: AsyncCimPal, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    respx_mock.post("/v1/uploads").respond(201, json=upload_json())
    started = threading.Event()
    release = threading.Event()
    made: list[_core.ZipSource] = []
    real = _core.zip_source

    def slow_zip(path: str | os.PathLike[str]) -> _core.ZipSource:
        started.set()
        release.wait(10)
        source = real(path)
        made.append(source)
        return source

    monkeypatch.setattr(_core, "zip_source", slow_zip)
    (tmp_path / "eq.xml").write_text("<eq/>", encoding="utf-8")
    upload = asyncio.ensure_future(acp.upload(tmp_path))
    await asyncio.to_thread(started.wait, 10)
    upload.cancel()
    with pytest.raises(asyncio.CancelledError):
        await upload
    release.set()
    for _ in range(100):
        if made and not made[0].path.exists():
            break
        await asyncio.sleep(0.05)
    assert made and not made[0].path.exists(), "the temporary ZIP was deleted"


def _link_dir(link: Path, target: Path) -> None:
    """A directory link: a symlink, or on Windows without symlink rights a junction."""
    try:
        link.symlink_to(target, target_is_directory=True)
        return
    except OSError:
        if sys.platform != "win32":
            raise
    subprocess.run(["cmd", "/c", "mklink", "/J", str(link), str(target)], check=True, capture_output=True)  # noqa: S603, S607
    assert link.exists()


def test_tempdir_is_clean_of_upload_zips_from_these_tests() -> None:
    leftovers = [p for p in Path(tempfile.gettempdir()).glob("cimpal-upload-*.zip")]
    assert leftovers == []
