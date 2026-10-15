# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""Shared fixtures: server answers as the CimPal /v1 API gives them, and clients that don't sleep."""

from __future__ import annotations

from collections.abc import AsyncIterator, Iterator
from typing import Any

import pytest

from cimpal_client import AsyncCimPal, CimPal

BASE = "https://cimpal.test:7474"
TOKEN = "test-token-0123456789abcdef0123456789abcdef"
JOB_ID = "6f1c2b9e-3c1d-4a5b-9e8f-0a1b2c3d4e5f"
UPLOAD_ID = "0b6d3f2a-8c4e-4d1a-b2c3-d4e5f6a7b8c9"


def job_json(status: str = "queued", **overrides: Any) -> dict[str, Any]:
    finished = status not in ("queued", "running")
    body: dict[str, Any] = {
        "jobId": JOB_ID,
        "command": "validate",
        "label": None,
        "owner": "default",
        "status": status,
        "createdAt": "2026-10-13T08:00:00Z",
        "startedAt": None if status == "queued" else "2026-10-13T08:00:01Z",
        "finishedAt": "2026-10-13T08:00:05Z" if finished else None,
        "exitCode": 1 if status == "succeeded" else None,
        "hasViolations": True if status == "succeeded" else None,
        "error": None,
        "links": {
            "self": f"/v1/jobs/{JOB_ID}",
            "result": f"/v1/jobs/{JOB_ID}/result",
            "log": f"/v1/jobs/{JOB_ID}/log",
        },
    }
    body.update(overrides)
    return body


def health_json(api_version: str = "1") -> dict[str, Any]:
    return {
        "status": "ok",
        "version": "CimPal CLI 2026.10.6.1",
        "apiVersion": api_version,
        "busy": False,
        "stalled": False,
        "queued": 0,
        "running": 0,
        "workers": 1,
    }


def upload_json() -> dict[str, Any]:
    return {
        "uploadId": UPLOAD_ID,
        "files": [{"name": "models/eq.xml", "size": 12}],
        "totalBytes": 12,
        "createdAt": "2026-10-13T08:00:00Z",
        "expiresAt": "2026-10-14T08:00:00Z",
    }


def problem(status: int, detail: str, **extra: Any) -> dict[str, Any]:
    return {"type": "about:blank", "title": "Error", "status": status, "detail": detail, **extra}


class Sleeps(list[float]):
    """The delays a client asked for, instead of sleeping."""

    def __call__(self, seconds: float) -> None:
        self.append(seconds)


@pytest.fixture
def sleeps() -> Sleeps:
    return Sleeps()


@pytest.fixture
def cp(sleeps: Sleeps) -> Iterator[CimPal]:
    client = CimPal(BASE, token=TOKEN, check_api_version=False)
    client._sleep = sleeps
    with client:
        yield client


@pytest.fixture
async def acp(sleeps: Sleeps) -> AsyncIterator[AsyncCimPal]:
    client = AsyncCimPal(BASE, token=TOKEN, check_api_version=False)

    async def sleep(seconds: float) -> None:
        sleeps(seconds)

    client._sleep = sleep
    async with client:
        yield client
