# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""Errors of the CimPal client. Each carries the server's problem details (RFC 9457) when it sent any."""

from __future__ import annotations

import builtins


class CimPalError(Exception):
    """A request to CimPal failed.

    ``status`` is the HTTP status (``None`` when no response arrived); ``title``, ``detail``,
    ``type`` and ``job_id`` come from the server's ``application/problem+json`` body.
    """

    def __init__(
        self,
        message: str,
        *,
        status: int | None = None,
        title: str | None = None,
        detail: str | None = None,
        type: str | None = None,  # noqa: A002 (the RFC 9457 field name)
        job_id: str | None = None,
    ) -> None:
        super().__init__(message)
        self.status = status
        self.title = title
        self.detail = detail
        self.type = type
        self.job_id = job_id


class BadRequestError(CimPalError):
    """400, 413 or 415: the request itself is wrong (bad config, body too large, wrong content type)."""


class AuthError(CimPalError):
    """401: no token, or a token the server doesn't accept."""


class ForbiddenError(CimPalError):
    """403: a path outside the server's allowed folders, or a refused Host or Origin."""


class NotFoundError(CimPalError):
    """404: no such job, upload or file (finished jobs are dropped after ``--job-ttl``)."""


class ConflictError(CimPalError):
    """409: the job is in the wrong state, e.g. its result is asked for while it runs."""


class GoneError(CimPalError):
    """410: the job's result was larger than ``--max-result-bytes`` and was not kept."""


class BusyError(CimPalError):
    """503: the queue is full or the server is shutting down; or 429: this client's quota is used up
    (too many queued jobs, or requests per minute). ``retry_after`` is in seconds."""

    def __init__(
        self,
        message: str,
        *,
        retry_after: float | None = None,
        status: int | None = None,
        title: str | None = None,
        detail: str | None = None,
        type: str | None = None,  # noqa: A002
        job_id: str | None = None,
    ) -> None:
        super().__init__(message, status=status, title=title, detail=detail, type=type, job_id=job_id)
        self.retry_after = retry_after


class TimeoutError(CimPalError, builtins.TimeoutError):  # noqa: A001 (deliberately also a builtin TimeoutError)
    """504, or :meth:`CimPal.wait` ran out of time. A running job is not cancelled by it."""


class ServerError(CimPalError):
    """5xx other than 503 and 504 (507: the server's work area is full), or an unexpected answer."""


class CompatibilityWarning(UserWarning):
    """The server speaks an API version this client was not written for."""
