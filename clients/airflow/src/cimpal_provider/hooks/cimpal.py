# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""``CimPalHook``: CimPal clients from an Airflow connection of type ``cimpal``."""

from __future__ import annotations

from typing import Any

from airflow.sdk import BaseHook
from cimpal_client import AsyncCimPal, CimPal, CimPalError

from .. import _logic


class CimPalHook(BaseHook):
    """Builds :class:`cimpal_client.CimPal` and :class:`cimpal_client.AsyncCimPal` from a connection.

    Connection fields: **Host** is the server (a host name, or a full URL such as
    ``https://cimpal.example.com``), **Schema** the scheme (``https`` by default), **Port** optional,
    **Password** the API token. Extras: ``verify``, ``timeout``, ``retries``, ``poll_interval``,
    ``allow_insecure_http``.
    """

    conn_name_attr = "cimpal_conn_id"
    default_conn_name = _logic.DEFAULT_CONN_ID
    conn_type = _logic.CONN_TYPE
    hook_name = "CimPal"

    def __init__(self, cimpal_conn_id: str = _logic.DEFAULT_CONN_ID, **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self.cimpal_conn_id = cimpal_conn_id

    @staticmethod
    def _settings(connection: Any) -> dict[str, Any]:
        return _logic.client_settings(
            connection.host, connection.port, connection.schema, connection.password, connection.extra_dejson
        )

    def get_conn(self) -> CimPal:
        """A synchronous client; close it (or use it in a ``with`` block) when done."""
        return CimPal(**self._settings(self.get_connection(self.cimpal_conn_id)))

    async def aget_conn(self) -> AsyncCimPal:
        """An asynchronous client, for triggers; the connection is read without blocking the event loop."""
        connection = await self.aget_connection(self.cimpal_conn_id)
        return AsyncCimPal(**self._settings(connection))

    def poll_interval(self, explicit: float | None = None) -> float:
        """``explicit``, else the connection's ``poll_interval`` extra, else 30 seconds."""
        if explicit is not None:
            return float(explicit)
        return _logic.poll_interval(self.get_connection(self.cimpal_conn_id).extra_dejson, None)

    def test_connection(self) -> tuple[bool, str]:
        """The "Test" button: the server answers, and accepts the token (an authenticated request)."""
        try:
            with self.get_conn() as cp:
                health = cp.health()
                cp.list_jobs(limit=1)
        except (CimPalError, ValueError) as error:
            return False, str(error)
        return True, f"Connected to {health.version} (API version {health.api_version}); the token is accepted"
