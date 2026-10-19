# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""The synchronous client, :class:`CimPal`."""

from __future__ import annotations

import os
import time
from collections.abc import Callable, Iterator, Mapping
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


class CimPal:
    """A client of a CimPal ``serve`` job API (``/v1``).

    ``token`` defaults to ``token_file``, then to the environment variables ``CIMPAL_API_TOKEN``
    and ``CIMPAL_API_TOKEN_FILE``. 503 answers and requests that never reached the server are
    retried ``retries`` times with exponential ``backoff`` (seconds), honouring ``Retry-After``.

    Use it as a context manager, or call :meth:`close`::

        with CimPal("https://cimpal.example.com", token="...") as cp:
            job = cp.wait(cp.submit("validate", config), timeout=3600)
            summary = cp.result(job)
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
        http_client: httpx.Client | None = None,
    ) -> None:
        self._token = _core.resolve_token(token, token_file)
        self.base_url = _core.normalize_base_url(base_url, token=self._token, allow_insecure_http=allow_insecure_http)
        self._retry = _core.RetryPolicy(retries=retries, backoff=backoff)
        self._check_api_version = check_api_version
        self._owns_client = http_client is None
        self._http = http_client or httpx.Client(timeout=timeout, verify=verify, follow_redirects=False)
        self._sleep: Callable[[float], None] = time.sleep

    def __repr__(self) -> str:
        return f"CimPal({self.base_url!r}, token={'***' if self._token else None})"

    def __enter__(self) -> CimPal:
        return self

    def __exit__(
        self, kind: type[BaseException] | None, value: BaseException | None, traceback: TracebackType | None
    ) -> None:
        self.close()

    def close(self) -> None:
        if self._owns_client:
            self._http.close()

    # ---- requests -------------------------------------------------------------------------------

    def _send(
        self,
        method: str,
        path: str,
        *,
        params: Mapping[str, Any] | None = None,
        json: Any = None,
        content: Callable[[], Iterator[bytes]] | None = None,
        extra_headers: Mapping[str, str] | None = None,
        stream: bool = False,
        idempotent: bool = False,
    ) -> httpx.Response:
        """Sends a request with retries; returns a successful response or raises the matching error."""
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
                response = self._http.send(request, stream=True)
            except httpx.TransportError as exc:
                if attempt < self._retry.retries and self._retry.may_retry(exc, method, idempotent):
                    self._sleep(self._retry.delay(attempt))
                    attempt += 1
                    continue
                raise _core.transport_error(exc, method, url) from None
            if response.status_code < 300:
                if not stream:
                    response.read()
                return response
            error = _core.error_from_response(_core.read_error_body(response))
            response.close()
            if attempt < self._retry.retries and self._retry.may_retry_in_progress(response, idempotent):
                self._sleep(self._retry.delay(attempt, _core.parse_retry_after(response.headers.get("Retry-After"))))
                attempt += 1
                continue
            if (
                isinstance(error, errors.BusyError)
                and attempt < self._retry.retries
                and self._retry.may_retry_busy(response, method, idempotent)
            ):
                self._sleep(self._retry.delay(attempt, error.retry_after))
                attempt += 1
                continue
            raise error

    def _ensure_compatible(self) -> None:
        if self._check_api_version:
            self._check_api_version = False
            self.health()

    # ---- server ---------------------------------------------------------------------------------

    def health(self) -> Health:
        """``GET /v1/health``: whether the server is up, and how busy. Warns on an unknown API version."""
        health = Health.from_dict(self._send("GET", "/v1/health").json())
        _core.check_api_version(health.api_version)
        return health

    # ---- uploads --------------------------------------------------------------------------------

    def upload(self, path: str | os.PathLike[str], *, idempotency_key: str | None = None) -> Upload:
        """Uploads a ``.zip``, or a folder or file zipped on the fly, for jobs to run on (``input_upload``).

        Sent with an ``Idempotency-Key`` (``idempotency_key``, or a fresh one), so a lost answer is retried
        safely: the server answers the same upload again.
        """
        key = _core.idempotency_key(idempotency_key)
        self._ensure_compatible()
        source = _core.zip_source(path)
        try:

            def chunks() -> Iterator[bytes]:
                with source.path.open("rb") as file:
                    while block := file.read(_core.CHUNK):
                        yield block

            response = self._send(
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

    def get_upload(self, upload: Upload | str) -> Upload:
        return Upload.from_dict(self._send("GET", f"/v1/uploads/{_core.upload_id(upload)}").json())

    def delete_upload(self, upload: Upload | str) -> None:
        """Deletes an upload no queued or running job uses (409 otherwise)."""
        self._send("DELETE", f"/v1/uploads/{_core.upload_id(upload)}")

    # ---- jobs -----------------------------------------------------------------------------------

    def submit(
        self,
        command: str,
        config: Mapping[str, Any] | None = None,
        *,
        input_upload: Upload | str | None = None,
        label: str | None = None,
        idempotency_key: str | None = None,
        callback_url: str | None = None,
    ) -> Job:
        """Queues ``command`` with ``config`` (as in a ``--config`` file) and returns the queued job at once.

        Sent with an ``Idempotency-Key`` (``idempotency_key``, or a fresh one per call), so a lost answer is
        retried safely: the server answers the same job, never a second one. Pass your own key to make a
        submit safe to repeat across calls (e.g. after a crash of your program).

        ``callback_url`` asks the server to ``POST`` the job's outcome there when it ends (it must be under
        your client's allowed prefixes); check it with :func:`cimpal_client.verify_callback`.
        """
        key = _core.idempotency_key(idempotency_key)
        self._ensure_compatible()
        body = _core.submit_body(command, config, input_upload, label, callback_url)
        response = self._send("POST", "/v1/jobs", json=body, extra_headers={"Idempotency-Key": key}, idempotent=True)
        return Job.from_dict(response.json())

    def job(self, job: _core.JobRef) -> Job:
        """The job's current state."""
        return Job.from_dict(self._send("GET", f"/v1/jobs/{_core.job_id(job)}").json())

    def list_jobs(self, status: JobStatus | str | None = None, limit: int | None = None) -> list[Job]:
        """The stored jobs, newest first, optionally of one status."""
        params: dict[str, Any] = {}
        if status is not None:
            params["status"] = str(JobStatus(status))
        if limit is not None:
            params["limit"] = limit
        return JobList.from_dict(self._send("GET", "/v1/jobs", params=params).json()).jobs

    def cancel(self, job: _core.JobRef) -> Job:
        """Cancels a queued job; a running one can't be cancelled (:class:`~cimpal_client.ConflictError`)."""
        return Job.from_dict(self._send("DELETE", f"/v1/jobs/{_core.job_id(job)}").json())

    def wait(
        self,
        job: _core.JobRef,
        timeout: float | None = None,
        *,
        poll_interval: float = 2.0,
        max_poll_interval: float = 30.0,
    ) -> Job:
        """Polls until the job is final (succeeded, failed, cancelled or timed out) and returns it.

        Raises :class:`~cimpal_client.TimeoutError` after ``timeout`` seconds; the job keeps running.
        """
        _core.check_poll_interval(poll_interval, max_poll_interval)
        deadline = None if timeout is None else time.monotonic() + timeout
        interval = poll_interval
        while True:
            current = self.job(job)
            if current.status in _core.FINAL_STATUSES:
                return current
            if deadline is not None:
                left = deadline - time.monotonic()
                if left <= 0:
                    raise errors.TimeoutError(
                        f"Job {current.job_id} is still {current.status} after {timeout} s", job_id=str(current.job_id)
                    )
                self._sleep(min(interval, left))
            else:
                self._sleep(interval)
            interval = min(max_poll_interval, interval * 1.5)

    def result(self, job: _core.JobRef) -> dict[str, Any]:
        """The finished job's result: the document the command prints with ``--format json``."""
        return _core.result_document(self._send("GET", f"/v1/jobs/{_core.job_id(job)}/result"))

    def log(self, job: _core.JobRef, offset: int = 0) -> JobLog:
        """Up to 1000 progress lines from ``offset``; continue from ``next_offset``."""
        return JobLog.from_dict(
            self._send("GET", f"/v1/jobs/{_core.job_id(job)}/log", params={"offset": offset}).json()
        )

    # ---- outputs --------------------------------------------------------------------------------

    def files(self, job: _core.JobRef) -> FileList:
        """The files a job that ran on an upload wrote to its output folder."""
        return FileList.from_dict(self._send("GET", f"/v1/jobs/{_core.job_id(job)}/files").json())

    def download_file(self, job: _core.JobRef, path: str, destination: str | os.PathLike[str]) -> Path:
        """Downloads one output file (``path`` as :meth:`files` lists it) to ``destination``."""
        response = self._send("GET", _core.file_url_path(_core.job_id(job), path), stream=True)
        return _save(response, Path(destination))

    def download_outputs(self, job: _core.JobRef, folder: str | os.PathLike[str]) -> list[Path]:
        """Downloads every output file of the job into ``folder``, keeping their relative paths."""
        root = Path(folder)
        root.mkdir(parents=True, exist_ok=True)
        written = []
        for item in self.files(job).files:
            written.append(self.download_file(job, item.path, _core.safe_destination(root, item.path)))
        return written

    def download_archive(self, job: _core.JobRef, destination: str | os.PathLike[str]) -> Path:
        """Downloads all output files of the job as one ZIP."""
        response = self._send(
            "GET", f"/v1/jobs/{_core.job_id(job)}/archive", stream=True, extra_headers={"Accept": "application/zip"}
        )
        return _save(response, Path(destination))


def _save(response: httpx.Response, target: Path) -> Path:
    """Streams a response into ``target``; a download cut off midway leaves ``target`` as it was."""
    partial = _core.partial_file(target)
    try:
        with partial.open("wb") as out:
            for block in response.iter_bytes(_core.CHUNK):
                out.write(block)
        os.replace(partial, target)
    except httpx.TransportError as exc:
        partial.unlink(missing_ok=True)
        raise _core.transport_error(exc, response.request.method, str(response.request.url)) from None
    except BaseException:
        partial.unlink(missing_ok=True)
        raise
    finally:
        response.close()
    return target
