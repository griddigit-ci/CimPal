# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""The asynchronous client, :class:`AsyncCimPal` (the same methods as :class:`~cimpal_client.CimPal`)."""

from __future__ import annotations

import asyncio
import os
import time
from collections.abc import AsyncIterator, Awaitable, Callable, Mapping
from pathlib import Path
from types import TracebackType
from typing import Any

import httpx

from . import _core, errors
from ._generated.models.file_list import FileList
from ._generated.models.health import Health
from ._generated.models.job import Job
from ._generated.models.job_list import JobList
from ._generated.models.job_log import JobLog
from ._generated.models.job_status import JobStatus
from ._generated.models.upload import Upload


class AsyncCimPal:
    """The asynchronous :class:`~cimpal_client.CimPal`, for asyncio code such as an Airflow trigger.

    ::

        async with AsyncCimPal("https://cimpal.example.com", token="...") as cp:
            job = await cp.wait(await cp.submit("validate", config), timeout=3600)
            summary = await cp.result(job)
    """

    def __init__(
        self,
        base_url: str,
        token: str | None = None,
        *,
        token_file: str | os.PathLike[str] | None = None,
        timeout: float | httpx.Timeout = _core.DEFAULT_TIMEOUT,
        retries: int = 3,
        backoff: float = 1.0,
        verify: bool | str = True,
        check_api_version: bool = True,
        allow_insecure_http: bool = False,
        http_client: httpx.AsyncClient | None = None,
    ) -> None:
        self._token = _core.resolve_token(token, token_file)
        self.base_url = _core.normalize_base_url(base_url, token=self._token, allow_insecure_http=allow_insecure_http)
        self._retry = _core.RetryPolicy(retries=retries, backoff=backoff)
        self._check_api_version = check_api_version
        self._owns_client = http_client is None
        self._http = http_client or httpx.AsyncClient(timeout=timeout, verify=verify, follow_redirects=False)
        self._sleep: Callable[[float], Awaitable[None]] = asyncio.sleep

    def __repr__(self) -> str:
        return f"AsyncCimPal({self.base_url!r}, token={'***' if self._token else None})"

    async def __aenter__(self) -> AsyncCimPal:
        return self

    async def __aexit__(
        self, kind: type[BaseException] | None, value: BaseException | None, traceback: TracebackType | None
    ) -> None:
        await self.aclose()

    async def aclose(self) -> None:
        if self._owns_client:
            await self._http.aclose()

    # ---- requests -------------------------------------------------------------------------------

    async def _send(
        self,
        method: str,
        path: str,
        *,
        params: Mapping[str, Any] | None = None,
        json: Any = None,
        content: Callable[[], AsyncIterator[bytes]] | None = None,
        extra_headers: Mapping[str, str] | None = None,
        stream: bool = False,
        idempotent: bool = False,
    ) -> httpx.Response:
        url = self.base_url + path
        attempt = 0
        while True:
            request = self._http.build_request(
                method,
                url,
                params=params,
                json=json,
                content=content() if content else None,
                headers={**_core.headers(self._token), **(extra_headers or {})},
            )
            try:
                response = await self._http.send(request, stream=True)
            except httpx.TransportError as exc:
                if attempt < self._retry.retries and self._retry.may_retry(exc, method, idempotent):
                    await self._sleep(self._retry.delay(attempt))
                    attempt += 1
                    continue
                raise _core.transport_error(exc, method, url) from None
            if response.status_code < 300:
                if not stream:
                    await response.aread()
                return response
            error = _core.error_from_response(await _core.read_error_body_async(response))
            await response.aclose()
            if attempt < self._retry.retries and self._retry.may_retry_in_progress(response, idempotent):
                await self._sleep(
                    self._retry.delay(attempt, _core.parse_retry_after(response.headers.get("Retry-After")))
                )
                attempt += 1
                continue
            if (
                isinstance(error, errors.BusyError)
                and attempt < self._retry.retries
                and self._retry.may_retry_busy(response, method, idempotent)
            ):
                await self._sleep(self._retry.delay(attempt, error.retry_after))
                attempt += 1
                continue
            raise error

    async def _ensure_compatible(self) -> None:
        if self._check_api_version:
            self._check_api_version = False
            await self.health()

    # ---- server ---------------------------------------------------------------------------------

    async def health(self) -> Health:
        health = Health.from_dict((await self._send("GET", "/v1/health")).json())
        _core.check_api_version(health.api_version)
        return health

    # ---- uploads --------------------------------------------------------------------------------

    async def upload(self, path: str | os.PathLike[str], *, idempotency_key: str | None = None) -> Upload:
        key = _core.idempotency_key(idempotency_key)
        await self._ensure_compatible()
        zipping = asyncio.ensure_future(asyncio.to_thread(_core.zip_source, path))
        try:
            source = await asyncio.shield(zipping)
        except asyncio.CancelledError:
            # The thread can't be stopped; delete its ZIP once it is done.
            zipping.add_done_callback(_discard_zip)
            raise
        try:

            async def chunks() -> AsyncIterator[bytes]:
                with source.path.open("rb") as file:
                    while block := file.read(_core.CHUNK):
                        yield block

            response = await self._send(
                "POST",
                "/v1/uploads",
                content=chunks,
                extra_headers={
                    "Content-Type": "application/zip",
                    "Content-Length": str(source.size()),
                    "Idempotency-Key": key,
                },
                idempotent=True,
            )
            return Upload.from_dict(response.json())
        finally:
            source.cleanup()

    async def get_upload(self, upload: Upload | str) -> Upload:
        return Upload.from_dict((await self._send("GET", f"/v1/uploads/{_core.upload_id(upload)}")).json())

    async def delete_upload(self, upload: Upload | str) -> None:
        await self._send("DELETE", f"/v1/uploads/{_core.upload_id(upload)}")

    # ---- jobs -----------------------------------------------------------------------------------

    async def submit(
        self,
        command: str,
        config: Mapping[str, Any] | None = None,
        *,
        input_upload: Upload | str | None = None,
        label: str | None = None,
        idempotency_key: str | None = None,
        callback_url: str | None = None,
    ) -> Job:
        key = _core.idempotency_key(idempotency_key)
        await self._ensure_compatible()
        body = _core.submit_body(command, config, input_upload, label, callback_url)
        response = await self._send(
            "POST", "/v1/jobs", json=body, extra_headers={"Idempotency-Key": key}, idempotent=True
        )
        return Job.from_dict(response.json())

    async def job(self, job: _core.JobRef) -> Job:
        return Job.from_dict((await self._send("GET", f"/v1/jobs/{_core.job_id(job)}")).json())

    async def list_jobs(self, status: JobStatus | str | None = None, limit: int | None = None) -> list[Job]:
        params: dict[str, Any] = {}
        if status is not None:
            params["status"] = str(JobStatus(status))
        if limit is not None:
            params["limit"] = limit
        return JobList.from_dict((await self._send("GET", "/v1/jobs", params=params)).json()).jobs

    async def cancel(self, job: _core.JobRef) -> Job:
        return Job.from_dict((await self._send("DELETE", f"/v1/jobs/{_core.job_id(job)}")).json())

    async def wait(
        self,
        job: _core.JobRef,
        timeout: float | None = None,
        *,
        poll_interval: float = 2.0,
        max_poll_interval: float = 30.0,
    ) -> Job:
        _core.check_poll_interval(poll_interval, max_poll_interval)
        deadline = None if timeout is None else time.monotonic() + timeout
        interval = poll_interval
        while True:
            current = await self.job(job)
            if current.status in _core.FINAL_STATUSES:
                return current
            if deadline is not None:
                left = deadline - time.monotonic()
                if left <= 0:
                    raise errors.TimeoutError(
                        f"Job {current.job_id} is still {current.status} after {timeout} s", job_id=str(current.job_id)
                    )
                await self._sleep(min(interval, left))
            else:
                await self._sleep(interval)
            interval = min(max_poll_interval, interval * 1.5)

    async def result(self, job: _core.JobRef) -> dict[str, Any]:
        return _core.result_document(await self._send("GET", f"/v1/jobs/{_core.job_id(job)}/result"))

    async def log(self, job: _core.JobRef, offset: int = 0) -> JobLog:
        return JobLog.from_dict(
            (await self._send("GET", f"/v1/jobs/{_core.job_id(job)}/log", params={"offset": offset})).json()
        )

    # ---- outputs --------------------------------------------------------------------------------

    async def files(self, job: _core.JobRef) -> FileList:
        return FileList.from_dict((await self._send("GET", f"/v1/jobs/{_core.job_id(job)}/files")).json())

    async def download_file(self, job: _core.JobRef, path: str, destination: str | os.PathLike[str]) -> Path:
        response = await self._send("GET", _core.file_url_path(_core.job_id(job), path), stream=True)
        return await _save(response, Path(destination))

    async def download_outputs(self, job: _core.JobRef, folder: str | os.PathLike[str]) -> list[Path]:
        root = Path(folder)
        root.mkdir(parents=True, exist_ok=True)
        written = []
        for item in (await self.files(job)).files:
            written.append(await self.download_file(job, item.path, _core.safe_destination(root, item.path)))
        return written

    async def download_archive(self, job: _core.JobRef, destination: str | os.PathLike[str]) -> Path:
        response = await self._send(
            "GET", f"/v1/jobs/{_core.job_id(job)}/archive", stream=True, extra_headers={"Accept": "application/zip"}
        )
        return await _save(response, Path(destination))


async def _save(response: httpx.Response, target: Path) -> Path:
    """Streams a response into ``target``; a download cut off midway leaves ``target`` as it was."""
    partial = _core.partial_file(target)
    try:
        with partial.open("wb") as out:
            async for block in response.aiter_bytes(_core.CHUNK):
                out.write(block)
        os.replace(partial, target)
    except httpx.TransportError as exc:
        partial.unlink(missing_ok=True)
        raise _core.transport_error(exc, response.request.method, str(response.request.url)) from None
    except BaseException:
        partial.unlink(missing_ok=True)
        raise
    finally:
        await response.aclose()
    return target


def _discard_zip(done: asyncio.Future[_core.ZipSource]) -> None:
    if not done.cancelled() and done.exception() is None:
        done.result().cleanup()
