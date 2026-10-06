<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-3 — CLI automation options and Airflow container pattern

| Field | Value |
| --- | --- |
| Status | Done ([PR #60](https://github.com/griddigit-ci/CimPal/pull/60); the manual Kubernetes walkthrough is open) |
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

- [x] `--summary-file`:
  - writes exactly the JSON document that `--format json` prints, atomically (temp file in the same folder, then a move), and creates parent folders;
  - works with or without `--format json` on stdout, and with `--output`;
  - the path is checked by `PathGuard` (WRITE_FILE) when used through `serve`/`mcp`/`run`, and again by `AtomicFiles`.
- [x] `--violations-exit-code N` (default 1):
  - replaces only the "violations/differences found" exit; 2 and 3 are unchanged;
  - *(changed)* a `validate` run with a failed row keeps exit 1;
  - the range is validated, and a non-integer config value is bad input;
  - tested on all four commands.
- [x] Example DAG `cimpal_kpo.py`:
  - `KubernetesPodOperator` with the DEP-1 image (pinned release tag) and a PVC at `/data`;
  - `resources` from the sizing guide;
  - `--summary-file /airflow/xcom/return.json --violations-exit-code 0`, `do_xcom_push=True`;
  - `@task.branch` on `hasViolations`;
  - pod security "restricted".
- [x] Example DAG `cimpal_docker.py`: `DockerOperator` with a mounted folder; a follow-up task reads the summary file and pushes it to XCom.
- [x] Example DAG `cimpal_bash.py`: `BashOperator` for workers with JRE 25 and the JAR; values reach the shell only as environment variables.
- [x] The XCom payload stays small: the examples push `{conforms, hasViolations, totals, report}` only.
- [x] `integrations/airflow/tests/test_dag_integrity.py`: all example DAGs import without errors under Airflow 3.3.2, plus structure, security and end-to-end tests (the Bash DAG runs the CLI). Green in `integrations.yml` on PR #60, after two fixes for Airflow 3.3: `DagBag` has no `include_examples`, and `dag.test()` needs the DAGs serialized (`airflow dags reserialize`).
- [ ] End-to-end check on a local Kubernetes: described step by step in `integrations/airflow/README.md`; for the maintainer (no Docker or Kubernetes in this session).
- [x] `docs/guide/airflow.md`:
  - path A;
  - the exit-code table with task states;
  - XCom, one run at a time;
  - volumes and S3;
  - sizing and security.

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
| 2026-10-06 | D-12: Airflow 3 only. Tests are pinned to 3.3.2 and installed with the official constraints file (cncf-kubernetes 10.22.0, docker 4.5.9, standard 1.19.0). | Claude Code; recommendation in the approved plan |
| 2026-10-06 | `serve`, `mcp` and `run` ignore `violationsExitCode`: under an active `PathPolicy` the remap is always 1, so their HTTP status, `isError` and step status keep their meaning. This is decided in `AutomationOptions.violationsExitCode`, not by stripping request keys, so it also holds for any other way the key arrives. | Claude Code; approved plan |
| 2026-10-06 | CI runs the DAG integrity tests and the Bash DAG end to end (`dag.test()` against the built JAR). The KPO and Docker end-to-end checks stay manual. | Claude Code; approved plan |
| 2026-10-06 | The example data is generated by `scripts/bench/gen_models.py` (2k triples, seed 1, violation rate 0.1 → 10 known violations) rather than by `TestModels`. | Claude Code; approved plan |
| 2026-10-06 | `validate --samples` defaults to 3 whenever the JSON document is produced (`--format json` or `--summary-file`), so the file matches what JSON mode prints. The examples set `"samples": 0` to keep XCom small. | Claude Code |
| 2026-10-06 | From the security review: row **errors** keep exit 1 under `--violations-exit-code`, because `MappingValidationSummary.hasViolations()` includes errors and an error must never become a pass. | Claude Code |
| 2026-10-06 | From the security review: the Bash example has no trigger-time param; values go to the shell as environment variables. All examples use `max_active_runs=1` (shared output and summary), pinned image tags, strict `compact()`, and the KPO pod meets Pod Security "restricted". | Claude Code |

## Notes and results

- **Local verification:**
  - Core 338 tests (was 333; `AtomicFilesTest` adds 5, plus 2 more after the review, one skipped on Windows).
  - CLI: `AutomationOptionsTest` 19 tests; the full suite passed before the review fixes.
  - A manual run of `validate --config integrations/airflow/examples/data/run.json --summary-file … --violations-exit-code 0` gave exit 0 and `hasViolations: true` with `totals.violations: 1` (rows, not SHACL findings).
  - The Python files compile. Airflow itself doesn't run on Windows, so the DAG tests run only in CI.
- **Security review** (`security-reviewer`). Fixed here:
  - High: shell injection through the Bash DAG's trigger-time `data_dir` param.
  - Mediums: arbitrary `data_dir`; row errors remapped to exit 0 (a false clean); concurrent runs sharing the summary.
  - Lows: `AtomicFiles` with a folder or root target; the temp file re-checked before the move (parent swapped for a link); 0600 permissions; unsanitised stderr; lenient `violationsExitCode` parsing; KPO pod hardening; `:latest` image defaults; `compact()` defaulting to clean.
  - Left open:
    - `pip install` without `--require-hashes` in `integrations.yml`, for CI-2 (G7);
    - `serve`/`mcp` tests for the ignored remap: not observable there, because exit 0 and 1 both map to 200 and `isError: false`.
- **Known, unchanged:** a `run` step's `config` key is not read by the commands (`docs/cli/run.md`, found in SEC-2).
- **Open:**
  - `integrations.yml` green on GitHub;
  - the maintainer's KPO and Docker walkthrough;
  - DEP-4 links `docs/guide/airflow.md`;
  - DEP-10 (Airflow provider) builds on these DAGs.
