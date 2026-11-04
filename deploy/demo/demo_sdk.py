# Copyright (c) 2020-2026 gridDigIt Kft.
# Licensed under the EUPL-1.2-or-later.
# SPDX-License-Identifier: EUPL-1.2+
"""The client demo with the Python SDK (docs/guide/client-demo.md).

Run from deploy/docker-compose after setup.sh and "docker compose up -d":

    python ../demo/demo_sdk.py                                   # TLS proxy, its CA in caddy-root.crt
    python ../demo/demo_sdk.py http://localhost:7474             # plain HTTP on this machine
"""
import sys
from pathlib import Path

from cimpal_client import CimPal

base = sys.argv[1] if len(sys.argv) > 1 else "https://localhost:8443"
token = Path("secrets/token").read_text().splitlines()[0].strip()
# The demo proxy signs with its own local CA; trust exactly that one instead of switching checks off.
verify = "caddy-root.crt" if base.startswith("https://localhost") else True

with CimPal(base, token=token, verify=verify) as cp:
    print("server:", cp.health().version)
    upload = cp.upload("inputs")  # a folder is zipped on the fly
    job = cp.submit("validate", {
        "workflow": "mapping",
        "mappingCsv": "mapping.csv",
        "modelsDir": "models",
        "constraintsRoot": "constraints",
        "xmlBase": "http://example.com/data",
    }, input_upload=upload, label="client demo (SDK)")
    print("submitted:", job.job_id)
    job = cp.wait(job, timeout=600)
    summary = cp.result(job)
    print("status:", job.status, "| violations found:", summary["hasViolations"], "| totals:", summary["totals"])
    cp.download_outputs(job, "reports/")
    print("downloaded to reports/:", sorted(p.name for p in Path("reports").iterdir()))
