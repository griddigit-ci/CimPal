# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""Shared test data, and whether Airflow can be imported here (it runs on Linux and macOS only)."""

from __future__ import annotations

import os
from typing import Any


def _airflow_importable() -> bool:
    # A failed import can leave half-initialised Airflow modules behind, and a second attempt then
    # seems to succeed; Airflow's own needs (fork) decide first.
    if not hasattr(os, "register_at_fork"):
        return False
    try:
        from airflow.sdk import BaseHook, BaseOperator  # noqa: F401
        from airflow.triggers.base import BaseTrigger  # noqa: F401
    except Exception:  # ImportError, or the AttributeError and others of an unsupported platform
        return False
    return True


AIRFLOW = _airflow_importable()
AIRFLOW_REASON = "needs Apache Airflow, which runs on Linux and macOS only"

BASE = "https://cimpal.test"
TOKEN = "test-token-0123456789abcdef0123456789abcdef"
JOB_ID = "6f1c2b9e-3c1d-4a5b-9e8f-0a1b2c3d4e5f"
UPLOAD_ID = "0b6d3f2a-8c4e-4d1a-b2c3-d4e5f6a7b8c9"


def job_json(
    status: str = "queued",
    exit_code: int | None = None,
    has_violations: bool | None = None,
    error: str | None = None,
    command: str = "validate",
) -> dict[str, Any]:
    finished = status not in ("queued", "running")
    return {
        "jobId": JOB_ID,
        "command": command,
        "label": None,
        "status": status,
        "createdAt": "2026-10-13T08:00:00Z",
        "startedAt": None if status == "queued" else "2026-10-13T08:00:01Z",
        "finishedAt": "2026-10-13T08:00:05Z" if finished else None,
        "exitCode": exit_code,
        "hasViolations": has_violations,
        "error": error,
        "links": {"self": f"/v1/jobs/{JOB_ID}", "result": f"/v1/jobs/{JOB_ID}/result", "log": f"/v1/jobs/{JOB_ID}/log"},
    }


def health_json() -> dict[str, Any]:
    return {
        "status": "ok",
        "version": "CimPal CLI 2026.10.6.1",
        "apiVersion": "1",
        "busy": False,
        "stalled": False,
        "queued": 0,
        "running": 0,
        "workers": 1,
    }


def upload_json() -> dict[str, Any]:
    return {
        "uploadId": UPLOAD_ID,
        "files": [{"name": "models/data.xml", "size": 10}],
        "totalBytes": 10,
        "createdAt": "2026-10-13T08:00:00Z",
        "expiresAt": "2026-10-14T08:00:00Z",
    }


def log_json(lines: list[str] | None = None) -> dict[str, Any]:
    lines = lines or ["[INFO] Loading 1 model file(s)..."]
    return {"jobId": JOB_ID, "offset": 0, "nextOffset": len(lines), "truncated": False, "lines": lines}


def problem(status: int, detail: str) -> dict[str, Any]:
    return {"type": "about:blank", "title": "Error", "status": status, "detail": detail}
