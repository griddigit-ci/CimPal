# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""The provider's Airflow-free logic (runs on every platform)."""

from __future__ import annotations

import importlib.util
import json
import re
from pathlib import Path

import pytest
from cimpal_client import AuthError, BusyError, ForbiddenError, Job, ServerError

from cimpal_provider import _logic
from cimpal_provider.get_provider_info import get_provider_info

from .conftest import job_json

REPO = Path(__file__).resolve().parents[3]
PATH_GUARD = REPO / "CimPal-CLI/src/main/java/eu/griddigit/CimPal/cli/command/PathGuard.java"

# ---- the connection -------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("host", "port", "schema", "expected"),
    [
        ("cimpal.example.com", None, None, "https://cimpal.example.com"),
        ("cimpal.example.com", 8443, "https", "https://cimpal.example.com:8443"),
        ("localhost", 7474, "http", "http://localhost:7474"),
        ("https://cimpal.example.com/", None, None, "https://cimpal.example.com"),
        ("https://cimpal.example.com/api", 8443, None, "https://cimpal.example.com:8443/api"),
        ("http://localhost:7474", 9999, None, "http://localhost:7474"),
    ],
)
def test_base_url(host: str, port: int | None, schema: str | None, expected: str) -> None:
    assert _logic.base_url(host, port, schema) == expected


@pytest.mark.parametrize(("host", "schema"), [(None, None), ("", None), ("h", "ftp")])
def test_a_connection_without_a_usable_host_or_scheme_is_refused(host: str | None, schema: str | None) -> None:
    with pytest.raises(ValueError):
        _logic.base_url(host, None, schema)


def test_client_settings_take_the_token_from_the_password_and_the_extras_in_both_forms() -> None:
    settings = _logic.client_settings(
        "cimpal.example.com",
        None,
        None,
        "secret-token",
        {"verify": "false", "extra__cimpal__timeout": "12.5", "retries": 5, "allow_insecure_http": True},
    )
    assert settings == {
        "base_url": "https://cimpal.example.com",
        "token": "secret-token",
        "verify": False,
        "timeout": 12.5,
        "retries": 5,
        "allow_insecure_http": True,
    }
    assert _logic.client_settings("h", None, None, "t", {"verify": "/etc/ssl/ca.pem"})["verify"] == "/etc/ssl/ca.pem"


def test_a_flag_that_is_not_a_boolean_is_refused() -> None:
    with pytest.raises(ValueError, match="allow_insecure_http"):
        _logic.client_settings("h", None, None, "t", {"allow_insecure_http": "maybe"})


def test_poll_interval_explicit_then_extra_then_default() -> None:
    assert _logic.poll_interval({"poll_interval": "7"}, None) == 7.0
    assert _logic.poll_interval({"poll_interval": "7"}, 3) == 3.0
    assert _logic.poll_interval({}, None) == _logic.DEFAULT_POLL_INTERVAL


# ---- shared-volume paths -------------------------------------------------------------------------


def test_relative_paths_resolve_against_the_input_path() -> None:
    config = {
        "workflow": "combined",
        "constraintFiles": ["constraints/EQ.ttl", "/abs/SSH.ttl"],
        "dataFiles": ["models"],
        "outputDir": "reports",
        "datatypeMap": "CGMES30NC25",  # a preset name, not a path
        "xmlBase": "http://iec.ch/TC57/CIM100",
    }
    resolved = _logic.resolve_paths("validate", config, "/shared/igm/2026-10-13/")
    assert resolved == {
        "workflow": "combined",
        "constraintFiles": ["/shared/igm/2026-10-13/constraints/EQ.ttl", "/abs/SSH.ttl"],
        "dataFiles": ["/shared/igm/2026-10-13/models"],
        "outputDir": "/shared/igm/2026-10-13/reports",
        "datatypeMap": "CGMES30NC25",
        "xmlBase": "http://iec.ch/TC57/CIM100",
    }
    assert config["dataFiles"] == ["models"], "the task's config is not changed"


def test_windows_paths_and_comma_lists() -> None:
    resolved = _logic.resolve_paths("sparql", {"models": "a.xml, D:\\data\\b.xml", "query": "q.rq"}, "S:\\shared")
    assert resolved["models"] == "S:\\shared/a.xml,D:\\data\\b.xml"
    assert resolved["query"] == "q.rq"  # may be an inline query: left alone


def test_an_unknown_command_is_refused() -> None:
    with pytest.raises(ValueError, match="Unknown CimPal command"):
        _logic.resolve_paths("serve", {}, "/x")


def test_the_file_options_are_those_of_cimpals_path_guard() -> None:
    """PATH_FIELDS mirrors PathGuard.FIELDS in the CLI; a new file option there must be added here."""
    if not PATH_GUARD.is_file():
        pytest.skip("not in the CimPal repository")
    source = PATH_GUARD.read_text(encoding="utf-8")
    table = source[source.index("FIELDS = Map.ofEntries(") : source.index("STEP_CONFIG")]
    expected: dict[str, dict[str, str]] = {}
    for command, body in re.findall(r'Map\.entry\("([^"]+)", List\.of\((.*?)\)\)', table, re.S):
        expected[command] = dict(re.findall(r'new Field\("([^"]+)", "[^"]+", Kind\.(\w+)', body))
    assert expected == _logic.PATH_FIELDS
    assert set(expected) == set(_logic.COMMANDS)


# ---- outcome ------------------------------------------------------------------------------------


def test_the_summary_is_small_and_keeps_what_a_branch_needs() -> None:
    job = Job.from_dict(job_json("succeeded", exit_code=1, has_violations=True))
    result = {"totals": {"violations": 3}, "report": "/out/r.xlsx", "shapes": [{"big": "x" * 1000}]}
    summary = _logic.summary(job, result, ["r.xlsx"])
    assert summary == {
        "jobId": job_json()["jobId"],
        "command": "validate",
        "status": "succeeded",
        "exitCode": 1,
        "hasViolations": True,
        "totals": {"violations": 3},
        "report": "/out/r.xlsx",
        "outputFiles": ["r.xlsx"],
    }
    assert len(json.dumps(summary)) < 500


def test_failure_messages_carry_the_servers_words() -> None:
    failed = Job.from_dict(job_json("failed", exit_code=2, error="Bad input (exit code 2); see the result"))
    message = _logic.failure_message(failed, {"error": "--data-files is required."})
    assert "failed" in message and "exit code 2" in message and "--data-files is required." in message
    timed_out = Job.from_dict(job_json("timed_out"))
    assert "keeps running there" in _logic.failure_message(timed_out, None)
    violated = Job.from_dict(job_json("succeeded", exit_code=1, has_violations=True))
    assert '{"violations": 2}' in _logic.violations_message(violated, {"totals": {"violations": 2}})


def test_errors_another_try_cant_fix_are_permanent() -> None:
    assert _logic.is_permanent(AuthError("x", status=401))
    assert _logic.is_permanent(ForbiddenError("x", status=403))
    assert not _logic.is_permanent(BusyError("x", status=503))
    assert not _logic.is_permanent(ServerError("x", status=500))


def test_the_label_fits_the_servers_limit() -> None:
    assert _logic.default_label("dag", "task", "run") == "airflow:dag/task/run"
    assert len(_logic.default_label("d" * 300, "t", "r")) == 200


# ---- provider info --------------------------------------------------------------------------------


def test_the_provider_info_registers_the_connection_type_and_matches_airflows_schema() -> None:
    info = get_provider_info()
    [connection] = info["connection-types"]
    assert connection["connection-type"] == "cimpal"
    assert connection["hook-class-name"] == "cimpal_provider.hooks.cimpal.CimPalHook"
    assert set(connection["conn-fields"]) == {"verify", "timeout", "retries", "poll_interval", "allow_insecure_http"}
    spec = importlib.util.find_spec("airflow")
    if spec is None or not spec.submodule_search_locations:
        pytest.skip("Airflow is not installed")
    schema_file = Path(next(iter(spec.submodule_search_locations))) / "provider_info.schema.json"
    jsonschema = pytest.importorskip("jsonschema")
    jsonschema.validate(info, json.loads(schema_file.read_text(encoding="utf-8")))


# ---- security review (DEP-10) ---------------------------------------------------------------------


def test_a_connection_without_a_token_never_uses_one_from_the_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("CIMPAL_API_TOKEN", "ambient-secret-0123456789abcdef0123456789")
    for password in (None, ""):
        with pytest.raises(ValueError, match="no API token"):
            _logic.client_settings("https://attacker.example", None, None, password, {})


@pytest.mark.parametrize("value", ["0", "-5", "0.5", "inf", "nan"])
def test_a_poll_interval_that_would_hammer_or_park_is_refused(value: str) -> None:
    with pytest.raises(ValueError, match="poll_interval"):
        _logic.poll_interval({"poll_interval": value}, None)


def test_server_text_is_cleaned_and_capped_for_airflows_logs() -> None:
    text = _logic.clean("line\r\n[INFO] forged \x1b[31m" + "x" * 5000)
    assert "\r" not in text and "\n" not in text and "\x1b" not in text
    assert len(text) == _logic.MAX_TEXT + 1
    failed = Job.from_dict(job_json("failed", exit_code=2))
    assert "\n" not in _logic.failure_message(failed, {"error": "bad\nforged"})


def test_dot_segments_are_left_to_the_servers_path_check() -> None:
    """resolve_paths only joins; the server's allowed folders (PathGuard) are the boundary."""
    resolved = _logic.resolve_paths("convert", {"input": "../other/x.xml"}, "/data/in")
    assert resolved["input"] == "/data/in/../other/x.xml"
