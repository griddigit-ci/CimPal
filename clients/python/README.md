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

Both Python packages, this one and [`airflow-provider-cimpal`](../airflow/README.md), go to PyPI with every CimPal release. No token is stored anywhere: the release workflow publishes through PyPI's *trusted publishing* (GitHub OIDC), and a maintainer approves each upload.

**What a release does** (`.github/workflows/release.yml`, after `scripts/New-ReleaseTag.ps1` pushes the tag):
1. **`python-dists`** (`python-dists.yml`) builds both packages from the tagged commit and checks them:
   - the tagged commit must be on `master`;
   - the pom version must equal the tag;
   - the build tools come from a hash-locked list (`.github/python-build-requirements.txt`) and build without isolation, so nothing unpinned makes the wheels;
   - `twine check --strict` passes;
   - the provider pins `cimpal-client==<version>`.
2. **`pypi`** waits in the GitHub environment `pypi` until a maintainer clicks *Review deployments › Approve*. Before approving, check that the run belongs to the release you just cut. The job then uploads `cimpal-client`, then the provider, with signed provenance (attestations), and checks that PyPI holds byte for byte the files that were built. An upload can't be replaced, only yanked, so approve only a release you mean to ship.
3. **If it fails half-way,** re-run only the failed job: it reuses the same built files, versions already on PyPI are skipped, and the byte check still applies. Don't use *Re-run all jobs*: it rebuilds the files, and the byte check would then fail against what is already on PyPI.

**The TestPyPI rehearsal** (Actions › *TestPyPI rehearsal* › Run workflow, from `devel` or a branch):
- builds both packages as `<pom version>.dev<run number>`;
- publishes them to test.pypi.org after the `testpypi` approval;
- installs them back to check the versions and the pin.

Run it before the first real release, and after changing the packaging.

**One-time setup** (maintainers; needs the PyPI account and the repository admin):
1. **PyPI:** on pypi.org (an account with 2FA, preferably the gridDigIt organization), go to *Your projects › Publishing › Add a new pending publisher*. Add one for `cimpal-client` and one for `airflow-provider-cimpal`, each with:
   - owner `griddigit-ci`, repository `CimPal`;
   - workflow `release.yml`, environment `pypi`.

   **A pending publisher does not reserve a name:** the first upload does. If someone registers a name first, the pending publisher stops working, so cut the first release soon after this setup.
2. **TestPyPI:** the same on test.pypi.org, with workflow `testpypi.yml` and environment `testpypi`.
3. **GitHub, release tags** (*Settings › Rules › Rulesets*, target tags `20*`): only the maintainers may create, update or delete them. PyPI trusts the workflow file of whatever commit is tagged, so whoever can push a release tag could otherwise publish their own workflow.
4. **GitHub, environments** (*Settings › Environments*):
   - `pypi`: the maintainer(s) as required reviewers, *Prevent self-review* on, *Allow administrators to bypass* off, deployment refs limited to tags `20*`;
   - `testpypi`: required reviewers and *Prevent self-review* as well, with deployment branches limited to `devel`.

**By hand,** if the workflow can't be used: build from the release tag with `python -m build` in both folders. Then run `twine upload`, `cimpal-client` first, with a project-scoped API token.
