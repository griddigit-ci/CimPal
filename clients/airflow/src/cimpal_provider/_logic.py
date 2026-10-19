# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""The parts of the provider that need no Airflow: connection settings, config paths, the XCom summary.

Kept apart so they can be tested on any platform (Airflow itself runs on Linux and macOS only).
"""

from __future__ import annotations

import hashlib
import json
import math
import re
from collections.abc import Mapping
from typing import Any

from cimpal_client import (
    AuthError,
    BadRequestError,
    CimPalError,
    ForbiddenError,
    GoneError,
    Job,
    JobStatus,
    NotFoundError,
)

CONN_TYPE = "cimpal"
DEFAULT_CONN_ID = "cimpal_default"
DEFAULT_POLL_INTERVAL = 30.0
#: The shortest poll interval a connection or task may set (many deferred tasks poll one server).
MIN_POLL_INTERVAL = 1.0
#: Longest server text (a log line, an error detail) kept in Airflow's logs and exceptions.
MAX_TEXT = 1000
COMMANDS = (
    "validate",
    "sparql",
    "convert",
    "compare",
    "compare-instances",
    "rdfs2shacl",
    "organize",
    "excel2shacl",
    "gen-instances",
    "manifest",
)

# The file options of each command and how they are used, as CimPal's PathGuard lists them
# (tests/test_logic.py keeps the two in step). READ_IF_EXISTS values may be text instead of a path
# (a datatype map preset, an inline query), so they are never rewritten.
READ, READ_LIST, READ_IF_EXISTS, WRITE_FILE, WRITE_DIR = (
    "READ",
    "READ_LIST",
    "READ_IF_EXISTS",
    "WRITE_FILE",
    "WRITE_DIR",
)
PATH_FIELDS: dict[str, dict[str, str]] = {
    "validate": {
        "mappingCsv": READ,
        "modelsDir": READ,
        "constraintsRoot": READ,
        "constraintFiles": READ,
        "dataFiles": READ,
        "outputDir": WRITE_DIR,
        "datatypeMap": READ_IF_EXISTS,
        "previousComparison": READ,
        "summaryFile": WRITE_FILE,
    },
    "sparql": {"models": READ_LIST, "query": READ_IF_EXISTS, "output": WRITE_FILE, "summaryFile": WRITE_FILE},
    "convert": {"input": READ, "inputFiles": READ, "output": WRITE_FILE},
    "compare": {"fileA": READ, "fileB": READ, "output": WRITE_FILE, "summaryFile": WRITE_FILE},
    "compare-instances": {"modelsA": READ, "modelsB": READ, "output": WRITE_FILE, "summaryFile": WRITE_FILE},
    "rdfs2shacl": {"rdfsFiles": READ, "outputDir": WRITE_DIR},
    "organize": {"shaclFiles": READ, "templateXlsx": READ, "outputDir": WRITE_DIR},
    "excel2shacl": {"rdfsFile": READ, "excelFile": READ, "output": WRITE_FILE},
    "gen-instances": {"templateXlsx": READ, "output": WRITE_FILE},
    "manifest": {"dir": READ, "files": READ, "output": WRITE_FILE},
}


# ---- the connection -------------------------------------------------------------------------------


def base_url(host: str | None, port: int | None, schema: str | None) -> str:
    """The server URL from a connection: ``host`` may be a full URL; ``schema`` is the scheme (default https)."""
    if not host:
        raise ValueError("The CimPal connection has no host")
    host = host.strip().rstrip("/")
    if "://" in host:
        url = host
    else:
        scheme = (schema or "https").strip().lower()
        if scheme not in ("http", "https"):
            raise ValueError(f"The CimPal connection's scheme must be http or https, not {scheme!r}")
        url = f"{scheme}://{host}"
    authority = url.split("://", 1)[1].split("/", 1)[0]
    if port and ":" not in authority.rsplit("]", 1)[-1]:
        scheme_part, rest = url.split("://", 1)
        url = f"{scheme_part}://{authority}:{port}" + rest[len(authority) :]
    return url


def _extra(extra: Mapping[str, Any], name: str) -> Any:
    """An extra as the connection form stores it (plain, or prefixed with ``extra__cimpal__``)."""
    if name in extra:
        return extra[name]
    return extra.get(f"extra__{CONN_TYPE}__{name}")


def _flag(value: Any, name: str) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str) and value.strip().lower() in ("true", "false", "1", "0", "yes", "no"):
        return value.strip().lower() in ("true", "1", "yes")
    raise ValueError(f"The CimPal connection's extra {name!r} must be true or false")


def client_settings(
    host: str | None, port: int | None, schema: str | None, password: str | None, extra: Mapping[str, Any]
) -> dict[str, Any]:
    """Keyword arguments for :class:`cimpal_client.CimPal` from a connection's fields.

    The password is the token. Extras: ``verify`` (true/false or a CA bundle path), ``timeout``
    (seconds per request), ``retries``, ``allow_insecure_http``.
    """
    if not password:
        # Never fall back to a CIMPAL_API_TOKEN in the worker's environment: it would go to whatever
        # host the connection names.
        raise ValueError("The CimPal connection has no API token (password)")
    settings: dict[str, Any] = {"base_url": base_url(host, port, schema), "token": password}
    verify = _extra(extra, "verify")
    if verify not in (None, ""):
        settings["verify"] = (
            _flag(verify, "verify")
            if isinstance(verify, bool) or str(verify).lower() in ("true", "false", "1", "0", "yes", "no")
            else str(verify)
        )
    timeout = _extra(extra, "timeout")
    if timeout not in (None, ""):
        settings["timeout"] = float(timeout)
    retries = _extra(extra, "retries")
    if retries not in (None, ""):
        settings["retries"] = int(retries)
    insecure = _extra(extra, "allow_insecure_http")
    if insecure not in (None, ""):
        settings["allow_insecure_http"] = _flag(insecure, "allow_insecure_http")
    return settings


def poll_interval(extra: Mapping[str, Any], explicit: float | None) -> float:
    """``explicit``, else the ``poll_interval`` extra, else 30 s; finite and at least 1 s."""
    value: Any = explicit if explicit is not None else _extra(extra, "poll_interval")
    seconds = DEFAULT_POLL_INTERVAL if value in (None, "") else float(value)
    check_poll_interval(seconds)
    return seconds


def check_poll_interval(seconds: float, minimum: float = MIN_POLL_INTERVAL) -> None:
    if not math.isfinite(seconds) or seconds < minimum:
        raise ValueError(f"poll_interval must be a number of seconds, at least {minimum}")


_CONTROL = re.compile(r"[\x00-\x1f\x7f-\x9f]")


def clean(text: str) -> str:
    """Server text fit for Airflow's logs: control characters replaced, at most MAX_TEXT characters."""
    text = _CONTROL.sub("\ufffd", text)
    return text if len(text) <= MAX_TEXT else text[:MAX_TEXT] + "\u2026"


# ---- config paths on a shared volume ---------------------------------------------------------------


def _is_relative(path: str) -> bool:
    return not (path.startswith(("/", "\\")) or (len(path) > 1 and path[1] == ":"))


def _join(base: str, path: str) -> str:
    return base.rstrip("/\\") + "/" + path if _is_relative(path) and path else path


def resolve_paths(command: str, config: Mapping[str, Any], base: str) -> dict[str, Any]:
    """``config`` with the relative paths of the command's file options made relative to ``base``.

    ``base`` is a folder as the server sees it (a shared volume). Absolute paths, and the options
    whose value may be text instead of a path (``datatypeMap``, ``query``), stay as they are.
    """
    if command not in PATH_FIELDS:
        raise ValueError(f"Unknown CimPal command {command!r}; one of {', '.join(COMMANDS)}")
    resolved = dict(config)
    for key, kind in PATH_FIELDS[command].items():
        value = resolved.get(key)
        if kind == READ_IF_EXISTS or value is None:
            continue
        if isinstance(value, list):
            resolved[key] = [_join(base, str(v)) for v in value]
        elif isinstance(value, str) and kind == READ_LIST and "," in value:
            resolved[key] = ",".join(_join(base, part.strip()) for part in value.split(","))
        elif isinstance(value, str):
            resolved[key] = _join(base, value)
    return resolved


# ---- outcome ----------------------------------------------------------------------------------------


#: Errors that another try won't fix: the task fails without Airflow retries.
PERMANENT_ERRORS: tuple[type[CimPalError], ...] = (AuthError, ForbiddenError, BadRequestError, NotFoundError, GoneError)


def is_permanent(error: CimPalError) -> bool:
    return isinstance(error, PERMANENT_ERRORS)


def summary(job: Job, result: Mapping[str, Any] | None, output_files: list[str] | None = None) -> dict[str, Any]:
    """The small XCom of a finished job: no report contents, only what the next task branches on."""
    data: dict[str, Any] = {
        "jobId": str(job.job_id),
        "command": str(job.command),
        "status": str(job.status),
        "exitCode": job.exit_code,
        "hasViolations": job.has_violations,
    }
    if result:
        for key in ("totals", "report", "partial"):
            if key in result:
                data[key] = result[key]
    if output_files is not None:
        data["outputFiles"] = output_files
    return data


def failure_message(job: Job, result: Mapping[str, Any] | None) -> str:
    """Why a job didn't succeed, in the server's words where it gave any."""
    detail = None
    if result:
        detail = result.get("error") or result.get("message")
    detail = detail or job.error
    if detail is not None:
        detail = clean(str(detail))
    reason = {
        JobStatus.FAILED: "failed",
        JobStatus.CANCELLED: "was cancelled",
        JobStatus.TIMED_OUT: "ran longer than the server's --job-timeout (it keeps running there)",
    }.get(job.status, str(job.status))
    text = f"CimPal job {job.job_id} ({job.command}) {reason}"
    if job.exit_code is not None:
        text += f", exit code {job.exit_code}"
    return text + (f": {detail}" if detail else "")


def violations_message(job: Job, result: Mapping[str, Any] | None) -> str:
    totals = (result or {}).get("totals")
    return f"CimPal job {job.job_id} ({job.command}) found violations" + (
        f": {json.dumps(totals, sort_keys=True)}" if totals else ""
    )


def idempotency_key(kind: str, dag_id: str | None, task_id: str | None, run_id: str | None, try_number: object,
                    map_index: object) -> str:
    """The ``Idempotency-Key`` of one task try's upload or submit (DEP-14).

    Within one try (the client's own retries of a lost answer) the same job or upload comes back; a new
    try of the task submits again, and so does a try Airflow restarts after a dead worker (it gets a new
    ``try_number``). Hashed, so it is always short, visible ASCII.
    """
    identity = json.dumps([kind, dag_id, task_id, run_id, try_number, map_index], default=str)
    return f"airflow-{kind}-" + hashlib.sha256(identity.encode("utf-8")).hexdigest()


def default_label(dag_id: str | None, task_id: str | None, run_id: str | None) -> str:
    """``airflow:<dag>/<task>/<run>``, at most the server's 200 characters."""
    return f"airflow:{dag_id}/{task_id}/{run_id}"[:200]
