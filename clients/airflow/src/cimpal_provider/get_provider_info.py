# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the Apache License, Version 2.0.
# SPDX-License-Identifier: Apache-2.0
"""What Airflow's providers manager reads (entry point ``apache_airflow_provider``): the ``cimpal``
connection type with its form, and the provider's hooks, operators, triggers and sensors."""

from __future__ import annotations

from typing import Any


def get_provider_info() -> dict[str, Any]:
    return {
        "package-name": "airflow-provider-cimpal",
        "name": "CimPal",
        "description": "Run CimPal (CIM/CGMES validation, SHACL and RDF tooling) jobs on a CimPal server.",
        "integrations": [
            {
                "integration-name": "CimPal",
                "external-doc-url": "https://github.com/griddigit-ci/CimPal/blob/devel/docs/guide/airflow.md",
                "tags": ["software"],
            }
        ],
        "hooks": [{"integration-name": "CimPal", "python-modules": ["cimpal_provider.hooks.cimpal"]}],
        "operators": [{"integration-name": "CimPal", "python-modules": ["cimpal_provider.operators.cimpal"]}],
        "sensors": [{"integration-name": "CimPal", "python-modules": ["cimpal_provider.sensors.cimpal"]}],
        "triggers": [{"integration-name": "CimPal", "python-modules": ["cimpal_provider.triggers.cimpal"]}],
        "connection-types": [
            {
                "connection-type": "cimpal",
                "hook-class-name": "cimpal_provider.hooks.cimpal.CimPalHook",
                "hook-name": "CimPal",
                "ui-field-behaviour": {
                    "hidden-fields": ["login"],
                    "relabeling": {"schema": "Scheme", "password": "API token"},
                    "placeholders": {
                        "host": "cimpal.example.com, or a full URL such as https://cimpal.example.com",
                        "schema": "https (default) or http",
                        "port": "443 for https, 7474 for a local serve",
                        "password": "The serve bearer token (CIMPAL_API_TOKEN)",
                    },
                },
                "conn-fields": {
                    "verify": {
                        "label": "Verify TLS",
                        "description": "true (default), false, or the path of a CA bundle on the workers",
                        "schema": {"type": ["string", "null"]},
                    },
                    "timeout": {
                        "label": "Request timeout (s)",
                        "description": "Seconds per HTTP request (default 60); a job's own time is set on the task",
                        "schema": {"type": ["number", "null"]},
                    },
                    "retries": {
                        "label": "Retries",
                        "description": "Retries of a busy (503) or unreachable server (default 3)",
                        "schema": {"type": ["integer", "null"]},
                    },
                    "poll_interval": {
                        "label": "Poll interval (s)",
                        "description": "Longest pause between job status checks (default 30)",
                        "schema": {"type": ["number", "null"]},
                    },
                    "allow_insecure_http": {
                        "label": "Allow plain HTTP",
                        "description": "Send the token over http:// to another host (trusted networks only)",
                        "schema": {"type": ["boolean", "null"]},
                    },
                },
            }
        ],
    }
