# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""``CimPalJobTrigger``: waits for a CimPal job in the triggerer, so no worker slot is held."""

from __future__ import annotations

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
    (a Unix time) passed first, and ``{"status": "error", "message", "permanent"}`` when the server
    couldn't be asked. The deadline is absolute, so a restarted triggerer doesn't extend it.
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
        timeout = None if self.deadline is None else max(0.0, self.deadline - time.time())
        try:
            async with await CimPalHook(self.cimpal_conn_id).aget_conn() as cp:
                job = await cp.wait(
                    self.job_id,
                    timeout=timeout,
                    poll_interval=min(self.poll_interval, 5.0),
                    max_poll_interval=self.poll_interval,
                )
        except cimpal_client.TimeoutError as error:
            if error.status is None:  # wait() ran out of time, not a 504
                yield TriggerEvent(
                    {
                        "status": "timeout",
                        "job_id": self.job_id,
                        "message": f"CimPal job {self.job_id} is still running after the task's timeout; "
                        "it keeps running on the server",
                    }
                )
                return
            yield TriggerEvent({"status": "error", "job_id": self.job_id, "message": str(error), "permanent": False})
            return
        except CimPalError as error:
            yield TriggerEvent(
                {
                    "status": "error",
                    "job_id": self.job_id,
                    "message": str(error),
                    "permanent": _logic.is_permanent(error),
                }
            )
            return
        except ValueError as error:  # a broken connection definition
            yield TriggerEvent({"status": "error", "job_id": self.job_id, "message": str(error), "permanent": True})
            return
        yield TriggerEvent({"status": "final", "job_id": self.job_id, "job_status": str(job.status)})
