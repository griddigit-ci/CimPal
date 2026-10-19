# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""Checking the signatures of CimPal's completion callbacks (DEP-14)."""

from __future__ import annotations

import json

import httpx
import pytest
import respx

from cimpal_client import CimPal, InvalidCallbackError, verify_callback

from .conftest import BASE, job_json

SECRET = "callback-secret-0123456789abcdef0123456789"
BODY = b'{"event":"job.finished","jobId":"x"}'
T = 1760000000
#: Computed once, and checked by the server's CallbackTest too, so both sides sign alike.
VECTOR = "f86cb11876d3c92366db706f4ddbd2d6717bd012809dad1050f5cf404de37555"


def test_a_correct_signature_gives_the_body() -> None:
    headers = {"Content-Type": "application/json", "cimpal-signature": f"t={T},v1={VECTOR}"}
    assert verify_callback(SECRET, headers, BODY, now=T + 10) == {"event": "job.finished", "jobId": "x"}
    assert verify_callback(SECRET.encode(), headers, BODY, now=T)["jobId"] == "x"


@pytest.mark.parametrize(
    ("headers", "body", "now", "message"),
    [
        ({}, BODY, T, "No CimPal-Signature"),
        ({"CimPal-Signature": "garbage"}, BODY, T, "Malformed"),
        ({"CimPal-Signature": f"t={T},v1=" + "é" * 64}, BODY, T, "Malformed"),
        ({"CimPal-Signature": f"t={T},v1=abc"}, BODY, T, "Malformed"),
        ({"CimPal-Signature": f"t={T},v1={VECTOR}"}, BODY + b" ", T, "doesn't match"),
        ({"CimPal-Signature": f"t={T},v1={'0' * 64}"}, BODY, T, "doesn't match"),
        ({"CimPal-Signature": f"t={T},v1={VECTOR}"}, BODY, T + 3600, "too old"),
        ({"CimPal-Signature": f"t={T},v1={VECTOR}"}, BODY, T - 3600, "from the future"),
    ],
)
def test_anything_else_is_refused(headers: dict[str, str], body: bytes, now: float, message: str) -> None:
    with pytest.raises(InvalidCallbackError, match=message):
        verify_callback(SECRET, headers, body, now=now)


def test_a_signature_from_another_secret_is_refused() -> None:
    with pytest.raises(InvalidCallbackError):
        verify_callback("another-secret-0123456789abcdef0123456", {"CimPal-Signature": f"t={T},v1={VECTOR}"}, BODY,
                        now=T)


@respx.mock(base_url=BASE)
def test_submit_asks_for_a_callback(respx_mock: respx.MockRouter, cp: CimPal) -> None:
    route = respx_mock.post("/v1/jobs").respond(202, json=job_json())
    cp.submit("validate", {}, callback_url="https://hooks.example.com/cimpal/done")
    assert json.loads(route.calls.last.request.content)["callback"] == {"url": "https://hooks.example.com/cimpal/done"}
    cp.submit("validate", {})
    assert "callback" not in json.loads(route.calls.last.request.content)
    assert isinstance(route.calls.last.response, httpx.Response)
