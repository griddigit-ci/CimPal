<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the Apache License, Version 2.0.
  SPDX-License-Identifier: Apache-2.0
-->
# cimpal-client

A typed Python client for the job API (`/v1`) of [CimPal](https://github.com/griddigit-ci/CimPal)'s `serve` command. CimPal validates CIM/CGMES grid models against SHACL constraints and much more; this package only calls a running CimPal server over HTTP. The engine stays Java.

```python
from cimpal_client import CimPal

with CimPal("https://cimpal.example.com", token="...") as cp:
    up = cp.upload("models/")                         # zips a folder, or takes a .zip
    job = cp.submit("validate", {
        "workflow": "combined",
        "constraintFiles": ["constraints/EQ.ttl"],
        "dataFiles": ["models/IGM.zip"],
    }, input_upload=up)
    job = cp.wait(job, timeout=3600)                  # polls; honours Retry-After
    summary = cp.result(job)                          # the --format json document
    cp.download_outputs(job, "out/")                  # the Excel and Turtle reports
```

`AsyncCimPal` has the same methods for asyncio. The user guide is [docs/guide/python-sdk.md](../../docs/guide/python-sdk.md).

- Python 3.10 or later; depends on `httpx` and `attrs`.
- The package version is the CimPal version it was built with.
- Licence: Apache-2.0 (this client). CimPal itself is EUPL-1.2.
- The token goes over plain `http://` only to this machine; elsewhere use `https://` or pass `allow_insecure_http=True`.

## Development

```bash
python -m venv .venv && . .venv/bin/activate      # Windows: .venv\Scripts\activate
pip install -e ".[dev]"
ruff check src scripts tests && ruff format --check src scripts tests
mypy                                              # --strict, configured in pyproject.toml
pytest -m "not integration"                       # unit tests, mocked HTTP
pytest -m integration                             # against java -jar CimPal-CLI.jar serve
```

The integration tests start `serve` from `../../CimPal-CLI/target/CimPal-CLI.jar` (build it with `mvn -B -pl CimPal-CLI -am package -DskipTests` in the repository root), or from the JAR that `CIMPAL_JAR` names. They are skipped without Java or the JAR, unless `CIMPAL_INTEGRATION_REQUIRED=1`.

### The generated layer

`src/cimpal_client/_generated` is generated from `CimPal-CLI/src/main/resources/openapi/cimpal-v1.json` by a pinned `openapi-python-client`. After the API changes:

```bash
python scripts/regenerate.py           # or scripts/regenerate.sh, scripts/regenerate.ps1
python scripts/regenerate.py --check   # what CI runs: fails when the committed layer is stale
```

The generator can't handle `application/zip`, so uploads and the archive download exist only in the facade (`CimPal`, `AsyncCimPal`). The facade sends its own requests (one place for errors, retries and streaming) and returns the generated models.

## Releasing (maintainers)

CI builds the wheel and sdist but never publishes them. Publishing to PyPI is a manual step after a CimPal release, from the tagged commit:

```bash
cd clients/python
python -m build                        # dist/cimpal_client-<version>.tar.gz and .whl
python -m twine check dist/*
python -m twine upload dist/*          # needs the PyPI token of the cimpal-client project
```

The version comes from `<cimpal.version>` in the repository's root `pom.xml`, so build from the release tag.
