<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-10 — Airflow provider

| Field | Value |
| --- | --- |
| Status | In review |
| Phase | D2 |
| Depends on | DEP-9, DEP-3 |
| Size | M |
| Branch | `feature/dep-10-airflow-provider` |
| Requirements | R15 |
| Decisions needed | D-10 package name, D-12 Airflow versions |

## Goal

Airflow users add CimPal to a DAG in a few lines, with a proper connection type, and long validations don't occupy a worker slot while they wait.

```python
from cimpal_provider.operators import CimPalValidateOperator

validate = CimPalValidateOperator(
    task_id="validate_igm",
    cimpal_conn_id="cimpal_default",
    config={...},                     # templated
    input_path="/shared/igm/{{ ds }}/",   # shared volume, or
    upload_from="/tmp/igm.zip",           # upload mode
    fail_on_violations=False,             # default: violations are data
    download_outputs_to="/shared/reports/{{ ds }}/",
    deferrable=True,
)
```

## Components

| Component | Behaviour |
| --- | --- |
| Connection type `cimpal` | Host/port/schema; password = token; extras: `verify` (TLS), `timeout`, `poll_interval` |
| `CimPalHook` | Builds `CimPal` / `AsyncCimPal` from the connection; `test_connection()` calls `/v1/health` plus an authenticated call |
| `CimPalJobOperator` | Generic: any of the 10 commands; submits, then waits (deferrable or polling) |
| `CimPalValidateOperator` | `validate` with the options above; returns a small XCom: `jobId`, `status`, `exitCode`, `hasViolations`, `totals`, output file names |
| `CimPalJobTrigger` | Async poll with `AsyncCimPal`; serialisable; yields success/failure/timeout events |
| `CimPalJobSensor` | Waits for an existing job id (deferrable) |
| `on_kill` | Cancels the job if still queued; logs that a running job continues on the server |

## Scope

- `clients/airflow/` with `pyproject.toml`, package `airflow-provider-cimpal` (import `cimpal_provider`), `get_provider_info()` entry point (`apache_airflow_provider`), `src/`, `tests/`, `example_dags/`
- Example DAGs for both file modes and a branching example
- `integrations.yml` job; `docs/guide/airflow.md` gets the path C section

## Acceptance criteria (status checklist)

- [x] Provider registers the `cimpal` connection type (visible in the Airflow UI) through `get_provider_info()` — declarative `conn-fields`/`ui-field-behaviour`, validated against Airflow's schema
- [x] Hook, operators, trigger, sensor as in the table; templated fields: `config`, `input_path`, `upload_from`, `download_outputs_to` (and `job_label`)
- [x] Deferrable path: trigger `serialize()` round-trip test; operator resumes with `execute_complete` and pushes the small XCom only
- [x] `fail_on_violations=True` raises `AirflowFailException` with the totals; `False` succeeds and leaves branching to the DAG
- [x] Failures map cleanly: 401/403 → non-retryable failure with a clear message; 503 → retried by the SDK; job `failed` → task failure with the server's problem detail
- [x] Unit tests with a mocked server (respx); DAG integrity test for example DAGs; integration test with Airflow `dag.test()` (polling and deferrable) against a real `serve` from the JAR (required in CI)
- [x] Compatible with the Airflow versions chosen in D-12 (CI matrix: 3.2.2 and 3.3.2)
- [x] `docs/guide/airflow.md` path C section; comparison table of paths A/B/C (when to use which)

## Instructions for Claude Code

Start a session with: `Execute docs/plans/deployment/DEP-10.md` (in plan mode).

```text
Build the Airflow provider on top of cimpal-client (R15 in docs/plans/deployment/README.md). Confirm D-10 and D-12 in plan mode or use the README recommendations and log them.
1. clients/airflow/ package (hatchling). Dependency on cimpal-client pinned to the same version (path dependency in the repo for tests).
2. get_provider_info() with connection-types and the hook class; connection form widgets for the extras.
3. Hook, operators, trigger, sensor as in this file. Tests first: unit tests with the SDK mocked; trigger serialisation; execute/execute_complete flow; on_kill; error mapping.
4. Example DAGs: shared-volume validate + branch on hasViolations; upload mode with download of reports; a DAG integrity test.
5. Integration test using dag.test() against serve started from the JAR (skip if absent).
6. integrations.yml: Airflow version matrix from D-12 with Airflow's constraints files.
7. docs/guide/airflow.md path C + A/B/C comparison; update docs/plans/deployment/README.md current-state table only if the maintainer asks.
mvn -B verify must stay green; also report pytest counts.
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
| 2026-10-13 | D-12: Airflow 3 only. The provider needs **3.2+**: declarative connection fields (`conn-fields`) and `aget_connection`; CI tests 3.2.2 and 3.3.2 | Maintainer (3.x), Claude Code (3.2 minimum) |
| 2026-10-13 | Licence Apache-2.0, like `cimpal-client` (no engine code; talks to the EUPL server through the SDK) | Maintainer |
| 2026-10-13 | `cimpal-client` pinned to exactly the provider's version by a hatch build hook (both are the CimPal version) | Claude Code |
| 2026-10-13 | Once a job is submitted, failures don't retry (a running job can't be stopped, so a retry would duplicate it); only exit 3 and failures before submission retry. The deferrable trigger keeps polling through passing errors until the deadline | Claude Code (security review) |
| 2026-10-13 | `input_path` rewrites the relative paths of the command's file options, mirroring `PathGuard.FIELDS` (a test keeps them equal); text-or-path options (`datatypeMap`, `query`) are left alone | Claude Code |
| 2026-10-13 | `label` became `job_label`: `label` is a read-only property of Airflow's operators | Claude Code |

## Notes and results

### Results

- **CI** (`airflow-provider`; Airflow 3.2.2 and 3.3.2, Python 3.12): 48 tests passed on both with none skipped before the security fixes. Those runs included `dag.test()` against a real `serve` from the JAR, polling and deferrable. ruff and `mypy --strict` are clean.
- **Locally on Windows:** only `test_logic.py` (29 tests) runs, because Airflow doesn't (no `fork`); the Airflow tests skip there. Iterating went through CI on the pushed branch, as agreed.
- **mypy** found a real bug before CI: assigning `self.label` breaks Airflow's read-only `label` property, hence `job_label`.
- **First CI run:** `dag.test()` refused a DAG defined inside the test. Airflow 3 runs only DAGs it serializes from the DAG bundle, so the end-to-end DAGs live in `tests/e2e_dags/` and CI points `AIRFLOW__CORE__DAGS_FOLDER` there.

### Security review (2026-10-13)

No High findings. Fixed, with regression tests:
- **Medium:** an empty connection password let the SDK fall back to a `CIMPAL_API_TOKEN` in the worker's environment, sending it to whatever host the connection names. Now the connection must have a token.
- **Medium:** a wait timeout, a server `timed_out` job and transient polling errors raised retryable errors, so a retry submitted the job again while the first kept running. Asking a timed-out job for its result also gave a misleading 409. Now:
  - after submission only exit 3 retries;
  - the result is fetched only for succeeded and failed jobs;
  - the trigger keeps polling through passing errors;
  - `execute_complete` fails a job that isn't final.
- **Low:**
  - the copied server log and error details are cleaned and capped;
  - `download_outputs_to` works as a relative or symlinked path;
  - the poll interval must be finite and at least 1 s;
  - `verify=false` logs a warning;
  - `test_connection` handles a missing CA bundle;
  - CI's provider install uses Airflow's constraints.
- **Documented rather than changed:**
  - don't template `upload_from`/`download_outputs_to` from run conf;
  - `input_path` joins `..` as given (the server's allowed folders are the boundary).

### Open

- **PyPI:** publishing `cimpal-client` and `airflow-provider-cimpal` is a maintainer step. Until the names are registered, install both from the same git ref; a squatter could otherwise publish `cimpal-client` at the pinned version.
- **Connection form:** the Airflow UI was not tried by hand (the provider-info schema check and the CI install stand in for it).
