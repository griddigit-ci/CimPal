# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""Python client for the CimPal ``serve`` job API (``/v1``).

:class:`CimPal` (synchronous) and :class:`AsyncCimPal` submit CimPal commands as jobs, wait for
them, and fetch their results and output files. The low-level client generated from the OpenAPI
document is in :mod:`cimpal_client._generated`.
"""

from __future__ import annotations

from ._generated.models.file_list import FileList
from ._generated.models.health import Health
from ._generated.models.job import Job
from ._generated.models.job_log import JobLog
from ._generated.models.job_status import JobStatus
from ._generated.models.upload import Upload
from ._version import __version__
from .aio import AsyncCimPal
from .callbacks import InvalidCallbackError, verify_callback
from .client import CimPal
from .errors import (
    AuthError,
    BadRequestError,
    BusyError,
    CimPalError,
    CompatibilityWarning,
    ConflictError,
    ForbiddenError,
    GoneError,
    NotFoundError,
    ServerError,
    TimeoutError,  # noqa: A004 (also a builtin TimeoutError subclass, see errors.py)
)

__all__ = [
    "AsyncCimPal",
    "AuthError",
    "BadRequestError",
    "BusyError",
    "CimPal",
    "CimPalError",
    "CompatibilityWarning",
    "ConflictError",
    "FileList",
    "ForbiddenError",
    "GoneError",
    "Health",
    "InvalidCallbackError",
    "Job",
    "JobLog",
    "JobStatus",
    "NotFoundError",
    "ServerError",
    "TimeoutError",
    "Upload",
    "__version__",
    "verify_callback",
]
