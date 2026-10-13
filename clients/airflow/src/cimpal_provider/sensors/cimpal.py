# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""``CimPalJobSensor``: waits for a CimPal job that something else submitted."""

from __future__ import annotations

import time
from collections.abc import Sequence
from typing import Any

from airflow.configuration import conf
from airflow.sdk import BaseSensorOperator, Context, PokeReturnValue
from airflow.sdk.exceptions import AirflowException, AirflowFailException
from cimpal_client import CimPal, CimPalError, Job, JobStatus

from .. import _logic
from ..hooks.cimpal import CimPalHook
from ..operators.cimpal import airflow_errors
from ..triggers.cimpal import CimPalJobTrigger


class CimPalJobSensor(BaseSensorOperator):
    """Succeeds when CimPal job ``job_id`` (templated) has succeeded; fails when it failed, was
    cancelled or ran out of time on the server. Pushes the same small XCom as the operators.

    With ``deferrable=True`` it waits in the triggerer; ``timeout`` (the sensor's) bounds the wait.
    """

    template_fields: Sequence[str] = ("job_id",)

    def __init__(
        self,
        *,
        job_id: str,
        cimpal_conn_id: str = _logic.DEFAULT_CONN_ID,
        fail_on_violations: bool = False,
        deferrable: bool = conf.getboolean("operators", "default_deferrable", fallback=False),
        **kwargs: Any,
    ) -> None:
        super().__init__(**kwargs)
        self.job_id = job_id
        self.cimpal_conn_id = cimpal_conn_id
        self.fail_on_violations = fail_on_violations
        self.deferrable = deferrable

    def execute(self, context: Context) -> Any:
        if not self.deferrable:
            return super().execute(context)
        if self.poke(context) is False:
            self.defer(
                trigger=CimPalJobTrigger(
                    str(self.job_id), self.cimpal_conn_id, float(self.poke_interval), time.time() + float(self.timeout)
                ),
                method_name="execute_complete",
            )
        return self._xcom

    def poke(self, context: Context) -> bool | PokeReturnValue:
        with airflow_errors(), CimPalHook(self.cimpal_conn_id).get_conn() as cp:
            job = cp.job(str(self.job_id))
            if job.status in (JobStatus.QUEUED, JobStatus.RUNNING):
                self.log.info("CimPal job %s is %s", job.job_id, job.status)
                return False
            self._xcom = self._outcome(cp, job)
            return PokeReturnValue(is_done=True, xcom_value=self._xcom)

    def execute_complete(self, context: Context, event: dict[str, Any]) -> dict[str, Any]:
        if event.get("status") == "timeout":
            raise AirflowException(event.get("message", "CimPal job timed out"))
        if event.get("status") != "final":
            raise AirflowFailException(event.get("message", "CimPal job could not be followed"))
        with airflow_errors(), CimPalHook(self.cimpal_conn_id).get_conn() as cp:
            job = cp.job(str(self.job_id))
            if job.status in (JobStatus.QUEUED, JobStatus.RUNNING):
                raise AirflowFailException(f"CimPal job {self.job_id} is {job.status}, not finished")
            return self._outcome(cp, job)

    def _outcome(self, cp: CimPal, job: Job) -> dict[str, Any]:
        if job.status is not JobStatus.SUCCEEDED:
            result = None
            if job.status is JobStatus.FAILED:
                try:  # its error detail, if the server kept it
                    result = cp.result(job)
                except CimPalError:
                    result = None
            raise AirflowFailException(_logic.failure_message(job, result))
        result = cp.result(job)
        if self.fail_on_violations and job.has_violations:
            raise AirflowFailException(_logic.violations_message(job, result))
        return _logic.summary(job, result)

    _xcom: dict[str, Any] | None = None
