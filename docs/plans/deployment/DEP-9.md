<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-9 — Python SDK `cimpal-client`

| Field | Value |
| --- | --- |
| Status | Not started |
| Phase | D2 |
| Depends on | DEP-5, DEP-7 |
| Size | M |
| Branch | `feature/dep-9-python-sdk` |
| Requirements | R14 |
| Decisions needed | D-9 location, D-10 package name, D-11 licence |

## Goal

A small, typed Python client for the CimPal `/v1` API, so Python users and the Airflow provider never hand-craft HTTP calls. The engine stays Java; this is only a client.

## Design

- Location: `clients/python/` (D-9), package `cimpal-client`, import `cimpal_client` (D-10), Python 3.10+.
- Two layers:
  - `cimpal_client._generated`: generated from `cimpal-v1.json` with `openapi-python-client` (httpx + attrs). Committed; a script regenerates it; CI fails if it is stale.
  - Hand-written facade on top:

```python
from cimpal_client import CimPal

with CimPal("https://cimpal.example.com", token="...") as cp:
    up = cp.upload("models/")                      # zips a folder or takes a .zip
    job = cp.submit("validate", config, input_upload=up.id)
    job = cp.wait(job, timeout=3600)               # polls, honours Retry-After
    summary = cp.result(job)                       # dict, same as --format json
    cp.download_outputs(job, "out/")
```

  plus `AsyncCimPal` with the same methods (needed by the Airflow trigger), `health()`, `list_jobs()`, `cancel()`, `log()`.
- Errors: `CimPalError` → `AuthError` (401), `ForbiddenError` (403, path/host), `NotFoundError`, `ConflictError`, `BusyError` (503, `retry_after`), `TimeoutError` (504 / wait timeout), `ServerError`; each carries the problem+json fields.
- Retries: 503 and connection errors with backoff (bounded, configurable); never retry `POST /v1/jobs` blindly unless the server returned 503 before accepting.
- Token from argument, `CIMPAL_API_TOKEN`, or a file path; never logged; `repr()` hides it.
- Optional `LocalCimPal(jar="CimPal-CLI.jar")` that runs the CLI as a subprocess with `--summary-file` (same `result()` shape) for users without a server. Nice to have; mark as such if skipped.

## Scope

- `clients/python/` with `pyproject.toml` (hatchling), `src/cimpal_client/`, `tests/`, `README.md`, `scripts/regenerate.sh` (+ `.ps1`)
- `.github/workflows/integrations.yml`: lint (ruff), type check (mypy), unit tests on 3.10–3.13, integration tests against the real JAR on Ubuntu, wheel build (no publish)
- `docs/guide/python-sdk.md`

## Acceptance criteria (status checklist)

- [ ] Generated layer reproducible from `cimpal-v1.json`; CI check for staleness
- [ ] Facade methods above, sync and async, fully typed (`py.typed`, mypy strict clean)
- [ ] Unit tests with mocked HTTP (respx) for every method and every error mapping, including `Retry-After`
- [ ] Integration tests: pytest fixture starts `java -jar CimPal-CLI.jar serve` on a free port with `CIMPAL_API_TOKEN` and temp roots; upload → submit → wait → result → download on the synthetic fixture; skipped cleanly when Java or the JAR is absent
- [ ] Version of the package = CimPal version; client sends `User-Agent: cimpal-client/<version>` and warns when the server's `apiVersion` is incompatible
- [ ] Wheel and sdist build in CI; publishing to PyPI is a maintainer step, documented in `clients/python/README.md`
- [ ] Licence header on every file (D-11), licence metadata in `pyproject.toml`
- [ ] `docs/guide/python-sdk.md` with install, auth, the example above, errors, timeouts

## Instructions for Claude Code

Start a session with: `Execute docs/plans/deployment/DEP-9.md` (in plan mode).

```text
Build the Python SDK for the /v1 API (R14 in docs/plans/deployment/README.md). Confirm D-9, D-10, D-11 with the maintainer in plan mode or use the README recommendations and log them.
1. clients/python/ layout with pyproject.toml (hatchling, deps: httpx, attrs; dev: pytest, pytest-asyncio, respx, mypy, ruff, openapi-python-client).
2. Generate the low-level client from CimPal-CLI/src/main/resources/openapi/cimpal-v1.json into src/cimpal_client/_generated with a pinned openapi-python-client version; regenerate scripts for bash and PowerShell; CI staleness check (regenerate and git diff --exit-code).
3. Facade CimPal / AsyncCimPal as in this file; tests first with respx.
4. Integration tests against the real JAR (fixture builds nothing; it expects CimPal-CLI/target/CimPal-CLI.jar and skips otherwise).
5. integrations.yml job: Python 3.10–3.13 matrix for unit tests; one Ubuntu job with JDK 25 that builds the JAR and runs integration tests; build wheel; no publishing.
6. docs/guide/python-sdk.md; link from docs/guide/index.md and docs/cli/serve.md.
mvn -B verify must stay green (no Java change expected). Also report pytest counts.
```

Standard footer (applies to every work package):

- Read CLAUDE.md, docs/PROJECT.md and this plan file first. Do not redo full codebase discovery.
- Start in plan mode: propose the plan, list the files you will touch, wait for approval.
- Work on branch feature/<wp-id>-<short-name> off devel. Do not push, tag or open a PR unless asked.
- Follow CLAUDE.md conventions (license header, package case, JPMS requires, logic in Core).
- Tests first where possible. Finish with `mvn -B verify` green and report the test count before and after.
- Run /security-review on the diff if the change touches I/O, network, processes, parsing or serve/mcp/run.
- At the end: tick this file's checklist, add to its decisions log, update docs/PROJECT.md (date, status, known issues, next steps) and docs/cli/* if CLI behaviour changed. Summarise what changed and what is left open.

## Decisions log

| Date | Decision | By |
| --- | --- | --- |

## Notes and results

(Claude Code: record findings, baseline numbers and open items here.)
