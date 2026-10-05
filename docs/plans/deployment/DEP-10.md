<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-10 — Airflow provider

| Field | Value |
| --- | --- |
| Status | Not started |
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

- [ ] Provider registers the `cimpal` connection type (visible in the Airflow UI) through `get_provider_info()`
- [ ] Hook, operators, trigger, sensor as in the table; templated fields: `config`, `input_path`, `upload_from`, `download_outputs_to`
- [ ] Deferrable path: trigger `serialize()` round-trip test; operator resumes with `execute_complete` and pushes the small XCom only
- [ ] `fail_on_violations=True` raises `AirflowException` with the totals; `False` succeeds and leaves branching to the DAG
- [ ] Failures map cleanly: 401/403 → non-retryable failure with a clear message; 503 → retried by the SDK; job `failed` → task failure with the server's problem detail
- [ ] Unit tests with a mocked SDK; DAG integrity test for example DAGs; integration test with Airflow `dag.test()` against a real `serve` from the JAR (skipped when absent)
- [ ] Compatible with the Airflow versions chosen in D-12 (CI matrix)
- [ ] `docs/guide/airflow.md` path C section; comparison table of paths A/B/C (when to use which)

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

## Notes and results

(Claude Code: record findings, baseline numbers and open items here.)
