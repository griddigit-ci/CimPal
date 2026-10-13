# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""The shared helpers, and the facade kept in line with the OpenAPI document and the CimPal version."""

from __future__ import annotations

import ast
import json
import re
from pathlib import Path

import pytest

import cimpal_client
from cimpal_client import _core, errors

REPO = Path(__file__).resolve().parents[3]
SPEC = REPO / "CimPal-CLI" / "src" / "main" / "resources" / "openapi" / "cimpal-v1.json"
POM = REPO / "pom.xml"


def test_retry_after_seconds_dates_and_caps() -> None:
    assert _core.parse_retry_after("12") == 12.0
    assert _core.parse_retry_after("-3") == 0.0
    assert _core.parse_retry_after("100000") == _core.MAX_RETRY_AFTER
    assert _core.parse_retry_after("Wed, 21 Oct 2026 07:28:00 GMT", now=1792567670.0) == 10.0
    assert _core.parse_retry_after("soon") is None
    assert _core.parse_retry_after(None) is None


def test_backoff_doubles_up_to_its_cap() -> None:
    policy = _core.RetryPolicy(retries=5, backoff=1.0, max_backoff=5.0)
    assert [policy.delay(a) for a in range(5)] == [1.0, 2.0, 4.0, 5.0, 5.0]
    assert policy.delay(0, retry_after=9.0) == 9.0
    with pytest.raises(ValueError):
        _core.RetryPolicy(retries=-1)


def test_a_token_file_without_a_token_is_an_auth_error(tmp_path: Path) -> None:
    empty = tmp_path / "token"
    empty.write_text("# nothing yet\n", encoding="utf-8")
    with pytest.raises(errors.AuthError, match="no token"):
        _core.resolve_token(None, empty, environ={})
    assert _core.resolve_token(None, None, environ={}) is None


def test_secret_never_shows() -> None:
    secret = _core.Secret("abc")
    assert "abc" not in repr(secret) and "abc" not in str(secret) and "abc" not in f"{secret}"


def test_file_paths_are_encoded_per_segment() -> None:
    assert _core.file_url_path("j", "a b/c#d.ttl") == "/v1/jobs/j/files/a%20b/c%23d.ttl"


def test_every_facade_route_is_in_the_openapi_document() -> None:
    if not SPEC.is_file():
        pytest.skip("not in the CimPal repository")
    spec_routes = {
        (method.upper(), re.sub(r"\{[^}]+\}", "{}", path))
        for path, ops in json.loads(SPEC.read_text(encoding="utf-8"))["paths"].items()
        for method in ops
        if method != "parameters"
    }
    used = set()
    source = Path(cimpal_client.__file__).with_name("client.py").read_text(encoding="utf-8")
    for node in ast.walk(ast.parse(source)):
        if isinstance(node, ast.Call) and getattr(node.func, "attr", "") == "_send" and len(node.args) >= 2:
            method = node.args[0]
            path = node.args[1]
            if isinstance(method, ast.Constant):
                used.add((method.value, _template(path)))
    used.add(("GET", "/v1/jobs/{}/files/{}"))  # built by _core.file_url_path
    used.discard(("GET", "?"))
    assert used <= spec_routes, used - spec_routes
    missing = spec_routes - used - {("GET", "/v1/openapi.json")}
    assert not missing, f"routes the facade doesn't offer: {missing}"


def _template(node: ast.expr) -> str:
    if isinstance(node, ast.Constant):
        return str(node.value)
    if isinstance(node, ast.JoinedStr):
        return "".join(str(v.value) if isinstance(v, ast.Constant) else "{}" for v in node.values)
    return "?"


def test_the_package_version_is_the_cimpal_version() -> None:
    if not POM.is_file():
        pytest.skip("not in the CimPal repository")
    expected = re.search(r"<cimpal\.version>([^<]+)</cimpal\.version>", POM.read_text(encoding="utf-8"))
    assert expected is not None
    assert cimpal_client.__version__ == expected.group(1)
