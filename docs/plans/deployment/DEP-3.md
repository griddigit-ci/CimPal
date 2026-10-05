<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-3 — CLI automation options and Airflow container pattern

| Field | Value |
| --- | --- |
| Status | Not started |
| Phase | D0 |
| Depends on | DEP-1 |
| Size | M |
| Branch | `feature/dep-3-airflow-container` |
| Requirements | R5, R6 |
| Decisions needed | D-12 Airflow versions (for the example pins) |

## Goal

Make integration path A work well today: Airflow starts CimPal as a container (or a plain process) per task, gets a small JSON summary through XCom, and decides what to do with violations. Two small CLI options make this clean; the rest is examples, tests and docs.

## Why the CLI options

- `KubernetesPodOperator` with `do_xcom_push=True` reads XCom from the file `/airflow/xcom/return.json` inside the pod, not from stdout. So the JSON result must be writable to a file → `--summary-file`.
- Any non-zero exit fails an Airflow task. Exit 1 means "violations found", which is a result, not a failure. Pipelines that want to branch on the result need exit 0 with `hasViolations` in XCom → `--violations-exit-code`.

## Scope

- CLI: `--summary-file <path>` and `--violations-exit-code <0..255>` on the JSON-capable commands (`validate`, `sparql`, `compare`, `compare-instances`)
- `integrations/airflow/` (new top-level folder): `examples/` DAGs, `examples/data/` small synthetic fixture set and config, `tests/` DAG integrity tests, `requirements-test.txt`, `README.md`
- `.github/workflows/integrations.yml` (new, path-filtered to `integrations/**`): DAG integrity tests on Ubuntu
- `docs/guide/airflow.md` (new; DEP-4 links it), `docs/cli/validate.md` and the other three command pages

## Acceptance criteria (status checklist)

- [ ] `--summary-file`: writes exactly the JSON document that `--format json` prints, atomically (temp file + move), creating parent folders; works with or without `--format json` on stdout; path checked by `PathGuard` when used through `serve`/`mcp`/`run`
- [ ] `--violations-exit-code N` (default 1): replaces only the "violations/differences found" exit; 2 and 3 unchanged; validated range; tests for each command
- [ ] Example DAG `cimpal_kpo.py`: `KubernetesPodOperator` running the DEP-1 image with a PVC or hostPath for `/data`, `resources` from the sizing guide, `--summary-file /airflow/xcom/return.json --violations-exit-code 0`, `do_xcom_push=True`, then `@task.branch` on `hasViolations`
- [ ] Example DAG `cimpal_docker.py`: `DockerOperator` with a mounted folder; a follow-up task reads the summary file and pushes it to XCom
- [ ] Example DAG `cimpal_bash.py`: `BashOperator` for workers that have JRE 25 and the JAR
- [ ] XCom payload stays small: the example pushes `{conforms, hasViolations, totals, report}` only, never full reports
- [ ] `integrations/airflow/tests/test_dag_integrity.py`: all example DAGs import without errors under the pinned Airflow version (`DagBag` with no import errors); runs in `integrations.yml`
- [ ] End-to-end check on a local Kubernetes (kind or Docker Desktop) with the Airflow Helm chart or `airflow standalone`, described step by step in `integrations/airflow/README.md`; result recorded in notes (maintainer may run it)
- [ ] `docs/guide/airflow.md`: path A explained, exit-code table and how to map it to task states, XCom, volumes and S3 (copy S3 → volume in an upstream task), sizing link

## Instructions for Claude Code

Start a session with: `Execute docs/plans/deployment/DEP-3.md` (in plan mode).

```text
Implement R5 and R6 and the Airflow container examples (docs/plans/deployment/README.md, path A).
1. CLI options, tests first (in-process picocli tests per command):
   - --summary-file <path>: write the same JSON document the command prints in --format json mode, atomically (write to <path>.tmp then Files.move ATOMIC_MOVE, fallback REPLACE_EXISTING), create parent dirs. Add the key to the path-key lists of PathGuard so serve/mcp/run check it as a write path. Accept "summaryFile" in config JSON.
   - --violations-exit-code <int 0..255>, default 1, config key "violationsExitCode". Only the "violations/differences found" exit is remapped. Check how each of the four commands decides exit 1 today and keep that logic.
   Update docs/cli/validate.md, sparql.md, compare.md, compare-instances.md, and the JSON Schemas / MCP tool schemas if config keys are listed there.
2. integrations/airflow/:
   - examples/cimpal_kpo.py, cimpal_docker.py, cimpal_bash.py as described in the acceptance list. Use the TaskFlow API, Airflow 3.x imports (decision D-12), and provider packages apache-airflow-providers-cncf-kubernetes and apache-airflow-providers-docker. Image name from decision D-1, configurable through an Airflow Variable.
   - examples/data/: a small synthetic EQ/SSH model, shapes and a validate config (generate with TestModels; check the files in).
   - tests/test_dag_integrity.py + requirements-test.txt with pinned versions; README.md with setup and the end-to-end walkthrough.
   - License header as Python comments on every .py file.
3. .github/workflows/integrations.yml: on PRs touching integrations/** or CimPal-CLI/**, set up Python 3.12, install requirements-test.txt (use Airflow's constraints file), run pytest. Actions pinned by SHA like ci.yml after CI-2, else by tag.
4. docs/guide/airflow.md (path A). DEP-4 will link it from the guide index.
Run /security-review (new write path through summary-file).
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
