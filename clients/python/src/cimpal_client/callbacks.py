# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""Checking the completion callbacks a CimPal server sends (DEP-14).

A job submitted with ``callback_url`` makes the server ``POST`` its outcome to that URL when it ends,
signed with the client's callback secret::

    CimPal-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>

The body carries the job's status and links, never its result: fetch that with your token. Its
``jobId`` is what the signature covers; delivery is at least once, so receivers should act on each
``jobId`` once.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import time
from collections.abc import Mapping
from typing import Any

#: How far the signature's time may be from now, in seconds (replay protection).
DEFAULT_TOLERANCE = 300.0


class InvalidCallbackError(ValueError):
    """The callback's signature is missing, wrong or too old; don't trust its body."""


def verify_callback(
    secret: str | bytes,
    headers: Mapping[str, str],
    body: bytes,
    *,
    tolerance: float = DEFAULT_TOLERANCE,
    now: float | None = None,
) -> dict[str, Any]:
    """Checks a callback's ``CimPal-Signature`` and returns its JSON body.

    ``headers`` may be any mapping (header names are matched case-insensitively); ``body`` must be the
    raw request body, exactly as received. Raises :class:`InvalidCallbackError` when the signature is
    missing, malformed or wrong, or its time is more than ``tolerance`` seconds from ``now``.
    """
    key = secret.encode("utf-8") if isinstance(secret, str) else secret
    header = next((v for k, v in headers.items() if k.lower() == "cimpal-signature"), None)
    if not header:
        raise InvalidCallbackError("No CimPal-Signature header")
    fields = dict(part.split("=", 1) for part in header.split(",") if "=" in part)
    try:
        timestamp = int(fields["t"])
        given = fields["v1"]
    except (KeyError, ValueError):
        raise InvalidCallbackError("Malformed CimPal-Signature header") from None
    if len(given) != 64 or any(c not in "0123456789abcdef" for c in given):
        raise InvalidCallbackError("Malformed CimPal-Signature header")
    current = time.time() if now is None else now
    if abs(current - timestamp) > tolerance:
        raise InvalidCallbackError("CimPal-Signature is too old or from the future (replay?)")
    expected = hmac.new(key, f"{timestamp}.".encode() + body, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, given):
        raise InvalidCallbackError("CimPal-Signature doesn't match")
    try:
        parsed = json.loads(body)
    except (UnicodeDecodeError, ValueError):
        raise InvalidCallbackError("The callback body is not JSON") from None
    if not isinstance(parsed, dict):
        raise InvalidCallbackError("The callback body is not a JSON object")
    return parsed
