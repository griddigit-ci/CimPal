# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""``CimPalJobTrigger``: waits for a CimPal job in the triggerer, so no worker slot is held."""

from __future__ import annotations

import asyncio
import time
from collections.abc import AsyncIterator
from typing import Any

import cimpal_client
from airflow.triggers.base import BaseTrigger, TriggerEvent
from cimpal_client import CimPalError

from .. import _logic
from ..hooks.cimpal import CimPalHook


class CimPalJobTrigger(BaseTrigger):
    """Polls job ``job_id`` until it is final and fires one event.

    The event's payload is ``{"status": "final", "job_id", "job_status"}`` when the job ended
    (whatever its outcome; the operator decides), ``{"status": "timeout", ...}`` when ``deadline``
    (a Unix time) passed first, and ``{"status": "error", "message", "permanent": True}`` for an
    error another try can't fix (a refused token, a dropped job, a broken connection). Passing
    errors (busy, unreachable, 5xx) don't end the wait: giving up would make the task submit the job
    again. The deadline is absolute, so a restarted triggerer doesn't extend it.
    """

    def __init__(
        self,
        job_id: str,
        cimpal_conn_id: str = _logic.DEFAULT_CONN_ID,
        poll_interval: float = _logic.DEFAULT_POLL_INTERVAL,
        deadline: float | None = None,
    ) -> None:
        super().__init__()
        self.job_id = job_id
        self.cimpal_conn_id = cimpal_conn_id
        self.poll_interval = float(poll_interval)
        _logic.check_poll_interval(self.poll_interval, minimum=0.0)
        if self.poll_interval == 0:
            raise ValueError("poll_interval must be positive")
        self.deadline = deadline

    def serialize(self) -> tuple[str, dict[str, Any]]:
        return (
            f"{type(self).__module__}.{type(self).__qualname__}",
            {
                "job_id": self.job_id,
                "cimpal_conn_id": self.cimpal_conn_id,
                "poll_interval": self.poll_interval,
                "deadline": self.deadline,
            },
        )

    async def run(self) -> AsyncIterator[TriggerEvent]:
        try:
            client = await CimPalHook(self.cimpal_conn_id).aget_conn()
        except (ValueError, OSError) as error:  # a broken connection definition
            yield self._event("error", message=str(error), permanent=True)
            return
        async with client as cp:
            while True:
                timeout = None if self.deadline is None else max(0.0, self.deadline - time.time())
                try:
                    job = await cp.wait(
                        self.job_id,
                        timeout=timeout,
                        poll_interval=min(self.poll_interval, 5.0),
                        max_poll_interval=self.poll_interval,
                    )
                except CimPalError as error:
                    if (
                        isinstance(error, cimpal_client.TimeoutError)
                        and error.job_id is not None
                        and error.status is None
                    ):
                        yield self._event(  # wait() ran out of time
                            "timeout",
                            message=f"CimPal job {self.job_id} is still running after the task's timeout; "
                            "it keeps running on the server",
                        )
                        return
                    if _logic.is_permanent(error):
                        yield self._event("error", message=str(error), permanent=True)
                        return
                    # A passing problem (busy, unreachable, 5xx) after the SDK's own retries: keep
                    # waiting, since giving up would make the task submit the job again.
                    self.log.warning("CimPal job %s: %s; trying again", self.job_id, error)
                    if self.deadline is not None and time.time() >= self.deadline:
                        yield self._event("timeout", message=f"CimPal job {self.job_id}: {error}")
                        return
                    await asyncio.sleep(self.poll_interval)
                    continue
                yield self._event("final", job_status=str(job.status))
                return

    def _event(self, status: str, **fields: Any) -> TriggerEvent:
        return TriggerEvent({"status": status, "job_id": self.job_id, **fields})
