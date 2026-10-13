# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""Operators that run CimPal commands as jobs on a CimPal server (``serve``)."""

from __future__ import annotations

import time
from collections.abc import Iterator, Mapping, Sequence
from contextlib import contextmanager
from datetime import timedelta
from typing import Any, ClassVar

import cimpal_client
from airflow.configuration import conf
from airflow.sdk import BaseOperator, Context
from airflow.sdk.exceptions import AirflowException, AirflowFailException
from cimpal_client import CimPal, CimPalError, ConflictError, Job, JobStatus

from .. import _logic
from ..hooks.cimpal import CimPalHook
from ..triggers.cimpal import CimPalJobTrigger

#: Progress lines of the job copied into the task log at the end.
LOG_LINES = 1000


@contextmanager
def airflow_errors() -> Iterator[None]:
    """CimPal errors as Airflow failures: those another try can't fix (401, 403, 400, 404, 410) skip
    the task's retries; the rest (busy, timeouts, server errors) may be retried."""
    try:
        yield
    except CimPalError as error:
        if _logic.is_permanent(error):
            raise AirflowFailException(str(error)) from error
        raise AirflowException(str(error)) from error


class CimPalJobOperator(BaseOperator):
    """Runs one CimPal command as a job and waits for it.

    :param command: ``validate``, ``sparql``, ``convert``, ``compare``, ``compare-instances``,
        ``rdfs2shacl``, ``organize``, ``excel2shacl``, ``gen-instances`` or ``manifest``.
    :param config: the command's config, as in a ``--config`` file (templated).
    :param input_path: a folder as the server sees it (a shared volume): the config's relative file
        paths resolve against it (templated).
    :param upload_from: a folder, ``.zip`` or file on the worker to upload; the job runs on it, and
        relative paths in the config resolve inside the upload (templated).
    :param download_outputs_to: with ``upload_from``, a folder on the worker for the job's output
        files (templated).
    :param job_label: the job's label on the server (default ``airflow:<dag>/<task>/<run>``; templated).
    :param fail_on_violations: fail the task when the job found violations or differences. By
        default they are data: the task succeeds and ``hasViolations`` in its XCom tells the DAG.
    :param deferrable: wait in the triggerer instead of a worker slot (default from
        ``[operators] default_deferrable``).
    :param poll_interval: seconds between status checks (default: the connection's
        ``poll_interval`` extra, else 30).
    :param job_timeout: give up waiting after this long; the job keeps running on the server.

    The task's XCom (return value) is small: ``jobId``, ``command``, ``status``, ``exitCode``,
    ``hasViolations``, and from the result ``totals`` and ``report``, plus ``outputFiles``.
    """

    template_fields: Sequence[str] = ("config", "input_path", "upload_from", "download_outputs_to", "job_label")
    template_fields_renderers: ClassVar[dict[str, str]] = {"config": "json"}
    ui_color = "#e8f1fb"

    def __init__(
        self,
        *,
        command: str,
        config: Mapping[str, Any] | None = None,
        cimpal_conn_id: str = _logic.DEFAULT_CONN_ID,
        input_path: str | None = None,
        upload_from: str | None = None,
        download_outputs_to: str | None = None,
        job_label: str | None = None,
        fail_on_violations: bool = False,
        deferrable: bool = conf.getboolean("operators", "default_deferrable", fallback=False),
        poll_interval: float | None = None,
        job_timeout: timedelta | float | None = None,
        **kwargs: Any,
    ) -> None:
        super().__init__(**kwargs)
        if command not in _logic.COMMANDS:
            raise ValueError(f"Unknown CimPal command {command!r}; one of {', '.join(_logic.COMMANDS)}")
        if input_path and upload_from:
            raise ValueError("Use either input_path (a shared volume) or upload_from (an upload), not both")
        if download_outputs_to and not upload_from:
            raise ValueError("download_outputs_to needs upload_from: only jobs on an upload have output files to fetch")
        if poll_interval is not None and poll_interval <= 0:
            raise ValueError("poll_interval must be positive")
        self.command = command
        self.config = dict(config or {})
        self.cimpal_conn_id = cimpal_conn_id
        self.input_path = input_path
        self.upload_from = upload_from
        self.download_outputs_to = download_outputs_to
        self.job_label = job_label
        self.fail_on_violations = fail_on_violations
        self.deferrable = deferrable
        self.poll_interval = poll_interval
        self.job_timeout = job_timeout.total_seconds() if isinstance(job_timeout, timedelta) else job_timeout
        self.job_id: str | None = None

    # ---- run --------------------------------------------------------------------------------------

    def execute(self, context: Context) -> dict[str, Any]:
        hook = CimPalHook(self.cimpal_conn_id)
        interval = hook.poll_interval(self.poll_interval)
        config = self.config
        if self.input_path:
            config = _logic.resolve_paths(self.command, config, self.input_path)
        label = self.job_label or _logic.default_label(self.dag_id, self.task_id, context.get("run_id"))
        with airflow_errors(), hook.get_conn() as cp:
            upload = None
            if self.upload_from:
                upload = cp.upload(self.upload_from)
                self.log.info("Uploaded %s as %s (%d bytes)", self.upload_from, upload.upload_id, upload.total_bytes)
            job = cp.submit(self.command, config, input_upload=upload, label=label)
            self.job_id = str(job.job_id)
            self.log.info("Submitted CimPal job %s (%s) to %s", self.job_id, self.command, cp.base_url)
            context["ti"].xcom_push(key="job_id", value=self.job_id)

            if self.deferrable:
                deadline = None if self.job_timeout is None else time.time() + self.job_timeout
                self.defer(
                    trigger=CimPalJobTrigger(self.job_id, self.cimpal_conn_id, interval, deadline),
                    method_name="execute_complete",
                )
            try:
                job = cp.wait(
                    job, timeout=self.job_timeout, poll_interval=min(interval, 5.0), max_poll_interval=interval
                )
            except cimpal_client.TimeoutError as error:
                if error.status is None:
                    raise AirflowException(
                        f"CimPal job {self.job_id} is still running after {self.job_timeout} s; "
                        "it keeps running on the server"
                    ) from error
                raise
            return self._finish(cp, job)

    def execute_complete(self, context: Context, event: dict[str, Any]) -> dict[str, Any]:
        """Resumes after the trigger: fetches the finished job and its result on a worker."""
        self.job_id = event.get("job_id")
        if event.get("status") == "timeout":
            raise AirflowException(event.get("message", "CimPal job timed out"))
        if event.get("status") != "final":
            message = event.get("message", "CimPal job could not be followed")
            raise AirflowFailException(message) if event.get("permanent") else AirflowException(message)
        with airflow_errors(), CimPalHook(self.cimpal_conn_id).get_conn() as cp:
            return self._finish(cp, cp.job(str(self.job_id)))

    def _finish(self, cp: CimPal, job: Job) -> dict[str, Any]:
        self._copy_log(cp, job)
        result = cp.result(job) if job.status is not JobStatus.CANCELLED else None
        if job.status is not JobStatus.SUCCEEDED:
            message = _logic.failure_message(job, result)
            # Exit 2 is bad input (a wrong config, a refused path): another try fails the same way.
            raise AirflowFailException(message) if job.exit_code == 2 else AirflowException(message)
        files = None
        if self.download_outputs_to:
            written = cp.download_outputs(job, self.download_outputs_to)
            files = sorted(str(p.relative_to(self.download_outputs_to)).replace("\\", "/") for p in written)
            self.log.info("Downloaded %d output file(s) to %s", len(written), self.download_outputs_to)
        summary = _logic.summary(job, result, files)
        if self.fail_on_violations and job.has_violations:
            raise AirflowFailException(_logic.violations_message(job, result))
        return summary

    def _copy_log(self, cp: CimPal, job: Job) -> None:
        """The job's progress lines into the task log (they were sanitised by the server)."""
        try:
            page = cp.log(job)
        except CimPalError as error:
            self.log.warning("Could not read the log of CimPal job %s: %s", job.job_id, error)
            return
        for line in page.lines[:LOG_LINES]:
            self.log.info("[cimpal] %s", line)

    def on_kill(self) -> None:
        """Cancels the job while it is still queued; a running job can't be stopped and continues."""
        if not self.job_id:
            return
        try:
            with CimPalHook(self.cimpal_conn_id).get_conn() as cp:
                cp.cancel(self.job_id)
            self.log.info("Cancelled the queued CimPal job %s", self.job_id)
        except ConflictError:
            self.log.warning(
                "CimPal job %s is already running; it can't be stopped and continues on the server", self.job_id
            )
        except CimPalError as error:
            self.log.warning("Could not cancel CimPal job %s: %s", self.job_id, error)


class CimPalValidateOperator(CimPalJobOperator):
    """Runs ``validate``: a CIM/CGMES validation against SHACL constraints.

    The XCom carries ``hasViolations`` and ``totals`` for a branch task; reports stay on the server
    (shared volume) or are downloaded with ``download_outputs_to``. See :class:`CimPalJobOperator`.
    """

    def __init__(self, *, config: Mapping[str, Any] | None = None, **kwargs: Any) -> None:
        if "command" in kwargs:
            raise TypeError("CimPalValidateOperator always runs validate; use CimPalJobOperator for other commands")
        super().__init__(command="validate", config=config, **kwargs)
