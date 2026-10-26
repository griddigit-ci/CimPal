<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-9 — Python SDK `cimpal-client`

| Field | Value |
| --- | --- |
| Status | Done ([PR #70](https://github.com/griddigit-ci/CimPal/pull/70)) |
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

- [x] Generated layer reproducible from `cimpal-v1.json`; CI check for staleness — `scripts/regenerate.py --check`
- [x] Facade methods above, sync and async, fully typed (`py.typed`, mypy strict clean) — the generated layer is excluded from `--strict` (see notes)
- [x] Unit tests with mocked HTTP (respx) for every method and every error mapping, including `Retry-After`
- [x] Integration tests: pytest fixture starts `java -jar CimPal-CLI.jar serve` on a free port with `CIMPAL_API_TOKEN` and temp roots; upload → submit → wait → result → download on the synthetic fixture; skipped cleanly when Java or the JAR is absent
- [x] Version of the package = CimPal version; client sends `User-Agent: cimpal-client/<version>` and warns when the server's `apiVersion` is incompatible
- [x] Wheel and sdist build in CI; publishing to PyPI is a maintainer step, documented in `clients/python/README.md`
- [x] Licence header on every file (D-11), licence metadata in `pyproject.toml` — except the empty `py.typed` marker, whose content PEP 561 reserves
- [x] `docs/guide/python-sdk.md` with install, auth, the example above, errors, timeouts

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
| 2026-10-13 | D-9: `clients/python/` in this repo; D-10: `cimpal-client` / `cimpal_client` | Maintainer |
| 2026-10-13 | D-11: **Apache-2.0** for `cimpal-client`. It holds no engine code and talks to the EUPL server over HTTP; an in-process wrapper of the engine (e.g. JPype) would stay EUPL | Maintainer |
| 2026-10-13 | The facade sends its own httpx requests and returns the generated models: the generator skips the `application/zip` routes (upload, archive), and one request path keeps errors, retries and streaming the same everywhere. A test checks that every facade route is in the OpenAPI document and every document route (but the spec itself) is in the facade | Claude Code |
| 2026-10-13 | The package version is read from `<cimpal.version>` in the root `pom.xml` by hatchling, so the release script (which only changes the poms) needs no change; an sdist carries the version in PKG-INFO | Claude Code |
| 2026-10-13 | Retries: 503 and requests that never reached the server; other transport errors only for GET, so `submit` is never sent twice. Retry-After is honoured up to 5 minutes | Claude Code |
| 2026-10-13 | `LocalCimPal` (CLI subprocess) left out: the nice-to-have of this WP; the CLI's `--summary-file` already gives the same document | Claude Code |

## Notes and results

### Results

- **Unit tests:** 117, against a mocked `/v1` (respx). They cover the sync and async facade, every error status, retries and `Retry-After`, token sources, safe output paths, upload zipping without following links, and the security-review regressions (`test_hardening.py`).
- **Integration tests:** 3, against `java -jar CimPal-CLI.jar serve`:
  - upload, submit, wait, result, file and archive download, log and list, sync and async;
  - a path outside the allowed folders is refused (403 at submit).
- **Checks:** ruff and `mypy --strict` are clean. The wheel and sdist build, and the wheel built from the sdist keeps the version.
- **Not run locally:** Python 3.10–3.12 run only in CI; locally only 3.13 was available.
- **Java:** no Java change, so `mvn -B verify` is unaffected.

### Security review (2026-10-13)

No High findings. Fixed, each with a regression test in `tests/test_hardening.py`:
- **Medium:** a 3xx was treated as success, since redirects aren't followed. An `http`→`https` redirect could make `result()` return the redirect page, which reads as a clean result. Now any 3xx raises and names the target.
- **Medium:** the token went over plain `http://` to any host. Now that is refused, except to loopback or with `allow_insecure_http=True`.
- **Medium:** a Windows junction inside an uploaded folder was followed. Now every file must resolve inside the folder (tested with a real junction on Windows).
- **Low:**
  - `Retry-After: NaN`/`inf` is ignored;
  - a non-JSON result is accepted only as `text/plain`/`application/json`, so an HTML page raises;
  - a cancelled async upload deletes its temporary ZIP;
  - server text in errors has control characters replaced and is capped at 1000 characters, and error bodies are read up to 64 KiB;
  - a 503 to a POST is retried only when it is CimPal's (problem details or `Retry-After`);
  - downloads go to a temporary file and replace the target only when complete;
  - the generated client's token is kept out of `repr()`;
  - `poll_interval` is validated.
- **Info, also fixed:**
  - a token that can't be a header is refused without echoing it;
  - a `base_url` with a query or fragment is refused;
  - dot segments are refused in `download_file`;
  - Windows-reserved names and trailing dots or spaces are refused in output names;
  - `regenerate.py` runs only the generator installed next to its Python;
  - hatchling is pinned.

Open: CI installs the dev tools with exact pins, but their transitive dependencies are unpinned and unhashed (CI has read-only permissions and no secrets). A hashed lock file would close that.

### Findings

- **The generator can't handle zip routes:** `openapi-python-client` 0.26.1 doesn't support `application/zip`, so `POST /v1/uploads` and the archive's 200 response are missing from `_generated`. The facade covers both.
- **Text results labelled as JSON:** a job whose command prints text instead of JSON (e.g. `convert`) gets that text as its `/v1` result, served as `application/json`. The SDK returns it as `{"output": text}`. The server should wrap it like `mcp` does; this is a separate task.
- **Stale spec text:** the OpenAPI document's description still says the server runs one command at a time (written before DEP-8).

### Open

- `LocalCimPal` (run the CLI as a subprocess without a server): left out, as the WP allows.
- Publishing to PyPI: automated with each release (trusted publishing, maintainer approval; branch `feature/pypi-publishing`). The one-time PyPI and GitHub setup is the maintainer's.
- **Install from git:** the guide's "install from git" line has not been tried outside this repository.
