# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""The I/O-free parts that :class:`CimPal` and :class:`AsyncCimPal` share."""

from __future__ import annotations

import email.utils
import ipaddress
import json
import math
import os
import re
import tempfile
import time
import uuid
import warnings
import zipfile
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any
from urllib.parse import quote, urlsplit

import httpx

from . import errors
from ._generated.models.job import Job
from ._generated.models.job_status import JobStatus
from ._version import __version__

TOKEN_ENV = "CIMPAL_API_TOKEN"  # noqa: S105 (the name of the variable, not a token)
TOKEN_FILE_ENV = "CIMPAL_API_TOKEN_FILE"  # noqa: S105
SUPPORTED_API_VERSIONS = frozenset({"1"})
DEFAULT_TIMEOUT = httpx.Timeout(60.0, connect=10.0)
#: Longest wait a server's Retry-After can ask for before a retry.
MAX_RETRY_AFTER = 300.0
#: Statuses after which a job doesn't change any more (a timed-out job may still get its result later).
FINAL_STATUSES = frozenset({JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED, JobStatus.TIMED_OUT})
CHUNK = 1 << 16

JobRef = Job | str | uuid.UUID


class Secret:
    """A token that never shows in ``repr()``, ``str()`` or tracebacks of the client."""

    __slots__ = ("_value",)

    def __init__(self, value: str) -> None:
        self._value = value

    def reveal(self) -> str:
        return self._value

    def __repr__(self) -> str:
        return "Secret('***')"

    __str__ = __repr__


def resolve_token(
    token: str | None, token_file: str | os.PathLike[str] | None, environ: Mapping[str, str] | None = None
) -> Secret | None:
    """The token from the argument, ``token_file``, ``CIMPAL_API_TOKEN`` or ``CIMPAL_API_TOKEN_FILE``, in that order."""
    env = os.environ if environ is None else environ
    if token:
        return _checked(token.strip())
    if token_file is None and env.get(TOKEN_ENV):
        return _checked(env[TOKEN_ENV].strip())
    path = token_file if token_file is not None else env.get(TOKEN_FILE_ENV)
    if path:
        # The server's token file may list several tokens, one per line; the first one is used.
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                return _checked(line)
        raise errors.AuthError(f"The token file {path} holds no token")
    return None


def _checked(value: str) -> Secret:
    """Refuses a token that can't go into a header, without echoing it."""
    if not value or not all(33 <= ord(c) <= 126 for c in value):
        raise errors.AuthError("The token must be printable ASCII without spaces")
    return Secret(value)


def normalize_base_url(url: str, *, token: Secret | None = None, allow_insecure_http: bool = False) -> str:
    """Checks ``url``. The token is sent over plain ``http://`` only to this machine, unless allowed."""
    parts = urlsplit(url)
    if parts.scheme not in ("http", "https") or not parts.netloc:
        raise ValueError(f"base_url must be an http:// or https:// URL, not {url!r}")
    if parts.username or parts.password:
        raise ValueError("base_url must not hold credentials; pass the token instead")
    if parts.query or parts.fragment or "?" in url or "#" in url:
        raise ValueError("base_url must not have a query or a fragment")
    if parts.scheme == "http" and token is not None and not allow_insecure_http and not _loopback(parts.hostname):
        raise ValueError(
            "base_url is plain http:// to another machine: the token would cross the network unencrypted. "
            "Use https:// (a TLS proxy in front of serve), or pass allow_insecure_http=True on a trusted network."
        )
    return url.rstrip("/")


def _loopback(host: str | None) -> bool:
    if host is None:
        return False
    if host.lower() == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def headers(token: Secret | None) -> dict[str, str]:
    result = {"User-Agent": f"cimpal-client/{__version__}", "Accept": "application/json"}
    if token is not None:
        result["Authorization"] = "Bearer " + token.reveal()
    return result


def job_id(job: JobRef) -> str:
    """The id of a job given as a :class:`Job`, a UUID or a string (checked to be a UUID)."""
    if isinstance(job, Job):
        return str(job.job_id)
    return str(uuid.UUID(str(job)))


def upload_id(upload: Any) -> str:
    value = getattr(upload, "upload_id", upload)
    return str(uuid.UUID(str(value)))


def check_api_version(api_version: str) -> None:
    if api_version not in SUPPORTED_API_VERSIONS:
        warnings.warn(
            f"The CimPal server speaks API version {api_version!r}; cimpal-client {__version__} was written for "
            f"{sorted(SUPPORTED_API_VERSIONS)}. Requests may fail.",
            errors.CompatibilityWarning,
            stacklevel=3,
        )


# ---- errors -------------------------------------------------------------------------------------


def parse_retry_after(value: str | None, now: float | None = None) -> float | None:
    """Seconds from a Retry-After header (delta-seconds or an HTTP date), at most :data:`MAX_RETRY_AFTER`."""
    if not value:
        return None
    value = value.strip()
    try:
        seconds = float(value)
    except ValueError:
        try:
            when = email.utils.parsedate_to_datetime(value)
        except (TypeError, ValueError):
            return None
        seconds = when.timestamp() - (time.time() if now is None else now)
    if not math.isfinite(seconds):
        return None
    return min(max(seconds, 0.0), MAX_RETRY_AFTER)


#: Longest server text kept in an error (detail, title, reason).
MAX_ERROR_TEXT = 1000
_CONTROL = re.compile(r"[\x00-\x1f\x7f-\x9f]")


def clean(text: str) -> str:
    """Server text fit for an exception message and logs: control characters replaced, length capped."""
    text = _CONTROL.sub("\ufffd", text)
    return text if len(text) <= MAX_ERROR_TEXT else text[:MAX_ERROR_TEXT] + "\u2026"


def error_from_response(response: httpx.Response) -> errors.CimPalError:
    """The exception for an error response, with the fields of its problem details."""
    fields: dict[str, Any] = {}
    try:
        body = response.json()
    except (json.JSONDecodeError, UnicodeDecodeError, ValueError):
        body = None
    if isinstance(body, dict):
        for key, name in (("title", "title"), ("detail", "detail"), ("type", "type"), ("jobId", "job_id")):
            if isinstance(body.get(key), (str, int)):
                fields[name] = clean(str(body[key]))
        if "detail" not in fields and isinstance(body.get("error"), str):
            fields["detail"] = clean(body["error"])  # the older endpoints' {"error": ...}
    status = response.status_code
    reason = fields.get("detail") or fields.get("title") or clean(response.reason_phrase or "") or "error"
    message = f"{response.request.method} {response.request.url.path}: {status} {reason}"
    if 300 <= status < 400:
        # Not followed: the token must not go elsewhere, and a redirect page is no result.
        location = clean(response.headers.get("Location", ""))
        return errors.CimPalError(
            f"{message}; the server redirects to {location or 'another URL'}, which is not followed. "
            "Use the final URL (e.g. https://) as base_url.",
            status=status,
        )
    if status == 503:
        return errors.BusyError(
            message, retry_after=parse_retry_after(response.headers.get("Retry-After")), status=status, **fields
        )
    kind: type[errors.CimPalError]
    if status in (400, 413, 415, 422):
        kind = errors.BadRequestError
    elif status == 401:
        kind = errors.AuthError
    elif status == 403:
        kind = errors.ForbiddenError
    elif status == 404:
        kind = errors.NotFoundError
    elif status == 409:
        kind = errors.ConflictError
    elif status == 410:
        kind = errors.GoneError
    elif status == 504:
        kind = errors.TimeoutError
    elif status >= 500:
        kind = errors.ServerError
    else:
        kind = errors.CimPalError
    return kind(message, status=status, **fields)


def transport_error(exc: httpx.TransportError, method: str, url: str) -> errors.CimPalError:
    path = urlsplit(url).path
    if isinstance(exc, httpx.TimeoutException):
        return errors.TimeoutError(f"{method} {path}: no answer in time ({type(exc).__name__})")
    return errors.CimPalError(f"{method} {path}: could not reach the server ({type(exc).__name__})")


# ---- retries ------------------------------------------------------------------------------------


@dataclass(frozen=True)
class RetryPolicy:
    """Retries of 503 answers and of requests that never reached the server.

    A request that may have reached the server (a read timeout, a dropped connection) is retried
    only when it is a GET, so a job is never submitted twice.
    """

    retries: int = 3
    backoff: float = 1.0
    max_backoff: float = 30.0

    def __post_init__(self) -> None:
        if self.retries < 0 or self.backoff < 0 or self.max_backoff < 0:
            raise ValueError("retries, backoff and max_backoff must not be negative")

    def delay(self, attempt: int, retry_after: float | None = None) -> float:
        if retry_after is not None:
            return retry_after
        return float(min(self.max_backoff, self.backoff * (2**attempt)))

    @staticmethod
    def may_retry(exc: httpx.TransportError, method: str) -> bool:
        never_sent = isinstance(exc, (httpx.ConnectError, httpx.ConnectTimeout, httpx.PoolTimeout))
        return never_sent or method == "GET"

    @staticmethod
    def may_retry_busy(response: httpx.Response, method: str) -> bool:
        """A 503 to a POST is retried only when CimPal sent it (it then refused the request before
        accepting it); a proxy's 503 may come after the server got the request."""
        if method == "GET":
            return True
        content_type = response.headers.get("Content-Type", "")
        return content_type.startswith("application/problem+json") or "Retry-After" in response.headers


# ---- files --------------------------------------------------------------------------------------


@dataclass
class ZipSource:
    """What :meth:`CimPal.upload` sends: a ZIP file, and whether it is a temporary one to delete."""

    path: Path
    temporary: bool

    def size(self) -> int:
        return self.path.stat().st_size

    def cleanup(self) -> None:
        if self.temporary:
            self.path.unlink(missing_ok=True)


def zip_source(path: str | os.PathLike[str]) -> ZipSource:
    """A ``.zip`` as it is; a folder or another file zipped into a temporary file.

    In a folder only regular files are taken, with their paths relative to it. Symbolic links are
    not followed, so nothing outside the folder is sent.
    """
    source = Path(path)
    if source.is_file() and source.suffix.lower() == ".zip":
        return ZipSource(source, temporary=False)
    if not source.exists():
        raise FileNotFoundError(f"Nothing to upload at {source}")
    handle, name = tempfile.mkstemp(prefix="cimpal-upload-", suffix=".zip")
    os.close(handle)
    target = Path(name)
    try:
        with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            if source.is_file():
                archive.write(source, source.name)
            else:
                for file in sorted(_regular_files(source)):
                    archive.write(file, file.relative_to(source).as_posix())
            # Note: hidden files (.env, .git) in the folder are sent too; upload a folder of inputs only.
    except BaseException:
        target.unlink(missing_ok=True)
        raise
    return ZipSource(target, temporary=True)


def _regular_files(folder: Path) -> list[Path]:
    """Regular files under ``folder`` that really lie inside it: symbolic links, Windows junctions
    and anything else resolving elsewhere are left out."""
    inside = folder.resolve()

    def contained(path: Path) -> bool:
        return not path.is_symlink() and path.resolve().is_relative_to(inside)

    found = []
    for root, dirs, files in os.walk(folder, followlinks=False):
        base = Path(root)
        dirs[:] = [d for d in dirs if contained(base / d)]
        found += [base / f for f in files if (base / f).is_file() and contained(base / f)]
    return found


def file_url_path(job: str, path: str) -> str:
    """``/v1/jobs/{id}/files/{path}`` with each segment of ``path`` percent-encoded."""
    segments = path.split("/")
    if any(segment in ("", ".", "..") for segment in segments):
        raise ValueError(f"Not an output file path: {path!r}")
    return f"/v1/jobs/{job}/files/" + "/".join(quote(segment, safe="") for segment in segments)


_WINDOWS_RESERVED = re.compile(r"(?i)^(con|prn|aux|nul|com[0-9]|lpt[0-9])(\..*)?$")


def safe_destination(folder: Path, relative: str) -> Path:
    """Where a downloaded output goes under ``folder``; refuses names that would leave it."""
    pure = PurePosixPath(relative)
    if (
        not relative
        or pure.is_absolute()
        or "\\" in relative
        or ":" in relative
        or any(
            part in ("", ".", "..") or part.endswith((".", " ")) or _WINDOWS_RESERVED.match(part)
            for part in relative.split("/")
        )
    ):
        raise errors.ServerError(f"Refusing an output file name that would leave the target folder: {relative!r}")
    target = folder.joinpath(*pure.parts).resolve()
    if not target.is_relative_to(folder.resolve()):
        raise errors.ServerError(f"Refusing an output file name that would leave the target folder: {relative!r}")
    return target


def result_document(response: httpx.Response) -> dict[str, Any]:
    """A job's result as a dict. Text a command printed instead of JSON comes as ``{"output": text}``;
    anything else (an HTML page from a proxy) is an error, never a result without findings."""
    content_type = response.headers.get("Content-Type", "").split(";")[0].strip().lower()
    try:
        data = response.json()
    except (json.JSONDecodeError, UnicodeDecodeError, ValueError):
        if content_type in ("application/json", "text/plain"):
            return {"output": response.text}
        raise errors.ServerError(
            f"GET {response.request.url.path}: expected a JSON result, got {clean(content_type) or 'no content type'}",
            status=response.status_code,
        ) from None
    return data if isinstance(data, dict) else {"result": data}


#: The most of an error response that is read (the problem details are small).
MAX_ERROR_BODY = 64 * 1024


def check_poll_interval(poll_interval: float, max_poll_interval: float) -> None:
    if not (0 < poll_interval <= max_poll_interval):
        raise ValueError("poll_interval must be positive and at most max_poll_interval")


def submit_body(command: str, config: Mapping[str, Any] | None, input_upload: Any, label: str | None) -> dict[str, Any]:
    body: dict[str, Any] = {"command": command, "config": dict(config or {})}
    if label is not None:
        body["label"] = label
    if input_upload is not None:
        body["inputUpload"] = upload_id(input_upload)
    return body


def partial_file(target: Path) -> Path:
    """A new, empty temporary file next to ``target`` for a download to go to first."""
    target.parent.mkdir(parents=True, exist_ok=True)
    handle, name = tempfile.mkstemp(prefix="." + target.name + ".", suffix=".part", dir=target.parent)
    os.close(handle)
    return Path(name)


def _capped_response(response: httpx.Response, body: bytes) -> httpx.Response:
    return httpx.Response(response.status_code, headers=response.headers, content=body, request=response.request)


def read_error_body(response: httpx.Response) -> httpx.Response:
    """The error response with at most :data:`MAX_ERROR_BODY` bytes of its body read."""
    body = b""
    for block in response.iter_bytes(CHUNK):
        body += block
        if len(body) >= MAX_ERROR_BODY:
            break
    return _capped_response(response, body[:MAX_ERROR_BODY])


async def read_error_body_async(response: httpx.Response) -> httpx.Response:
    body = b""
    async for block in response.aiter_bytes(CHUNK):
        body += block
        if len(body) >= MAX_ERROR_BODY:
            break
    return _capped_response(response, body[:MAX_ERROR_BODY])
