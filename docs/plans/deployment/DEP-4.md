<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-4 — External user guide, first edition

| Field | Value |
| --- | --- |
| Status | In progress (maintainer review of the support policy pending) |
| Phase | D0 |
| Depends on | DEP-1, DEP-2, DEP-3 |
| Size | M |
| Branch | `feature/dep-4-user-guide` |
| Requirements | R16 |
| Decisions needed | — (support policy wording is the maintainer's; draft it and mark it for review) |

## Goal

A guide that an external organisation can follow to understand, install, size, secure and integrate CimPal without talking to gridDigIt. It answers the Level 2 and Level 3 questions in `docs/plans/deployment/README.md` and contains a short capability statement that can be handed to procurement or IT.

`docs/cli/*` stays the per-command reference. `docs/guide/*` is the task-oriented layer on top and links into it.

## Structure of `docs/guide/`

| Page | Content |
| --- | --- |
| `index.md` | What CimPal is, who it is for, the three ways to run it (CLI/container, server, AI/MCP), where to start |
| `capabilities.md` | One-page capability statement: functions, deployment modes table (form, prerequisites, network exposure, auth, concurrency, state, output), resource summary from the sizing guide, integration options, security posture, licence, support |
| `install.md` | JAR + JRE 25 (Windows, Linux), container image, GUI exe; verifying the download |
| `quickstart.md` | Validate a sample model in 5 minutes, with the JAR and with Docker; reading the JSON summary and the Excel report |
| `deployment-modes.md` | CLI vs container vs `serve` vs `mcp`: when to use which, current limits, what's planned |
| `sizing.md` | From DEP-2 |
| `airflow.md` | From DEP-3 (path A); later extended by DEP-5/DEP-10 |
| `security.md` | Operator view: what CimPal reads/writes, allowed roots, tokens, network exposure, egress (`owl:imports`, SPARQL `SERVICE`), running as non-root, link to `SECURITY.md` when SEC-4 adds it |
| `configuration.md` | Config files, `_note_*` keys, exit codes, JSON output, environment variables, JVM options; links to `docs/cli/*` |
| `troubleshooting.md` | OOM, Windows JAR lock, token errors (401/403/415), paths outside roots, slow runs, `owl:imports` failures |
| `versioning-and-support.md` | Version scheme `YYYY.MM.DD.N`, what is stable (CLI flags, config keys, exit codes, JSON schemas, `/v1` API), deprecation policy, supported versions, how to report bugs and vulnerabilities |
| `glossary.md` | CIM/CGMES/SHACL terms plus the deployment terms from the plan README |

## Acceptance criteria (status checklist)

- [x] All pages exist, each with the licence header, a "last reviewed" date and the CimPal version it describes. `sizing.md` and `airflow.md` (DEP-2/3) got the header line.
- [x] Every command and flag mentioned exists: checked against the `--help` output of the current `devel` CLI (see notes).
- [ ] Quickstart verified from a clean folder:
  - with the CLI on Windows/PowerShell: done (see notes);
  - Docker and bash: written to match `docs/cli/docker.md`, but not run here (no Docker in this environment); for the maintainer.
- [x] The capability statement fits on two printed pages (853 words) and makes no unbacked claims. Each security claim maps to a test, the attestation or a CLI doc; the resource numbers come from `sizing.md`.
- [x] Planned features (service API, deployment, file exchange, concurrency, SDK, provider) are marked "Planned" with their WP ids, in `deployment-modes.md` and `capabilities.md`.
- [x] `README.md` links to `docs/guide/index.md` (new "Documentation" section), and `docs/cli/index.md` links to the guide.
- [ ] Support-policy text drafted and marked `<!-- maintainer review -->` in `versioning-and-support.md`; the maintainer's review is still to be recorded below.
- [x] The Markdown link check passes for `docs/guide/**`, `docs/cli/**` and `README.md`: `scripts/check_doc_links.py` in `.github/workflows/docs.yml`.

## Instructions for Claude Code

Start a session with: `Execute docs/plans/deployment/DEP-4.md` (in plan mode).

```text
Write the first edition of the external user guide in docs/guide/ (structure in this file).
Audience: engineers and IT staff at TSOs/RSCs who have never seen the code. Plain language, short sentences, task-oriented, examples for PowerShell and bash.
Sources: docs/cli/*, docs/PROJECT.md, docs/plans/deployment/README.md (current state, glossary), DEP-1/2/3 results (docker.md, sizing.md, airflow.md). Do not invent behaviour; verify every command against the built JAR (`java -jar CimPal-CLI/target/CimPal-CLI.jar <cmd> --help`) and record the check in this file's notes.
capabilities.md is the Level 2 / Level 3 description: deployment modes table, resource summary with numbers from sizing.md, integration options (path A available now, B and C planned with WP ids), security posture (SEC-1/SEC-2 features), licence.
versioning-and-support.md: draft and mark for maintainer review.
Add a markdown link check (e.g. a small script in scripts/ or lychee in integrations.yml) for docs/guide/**.
Link the guide from README.md and docs/cli/index.md.
No code changes.
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
| 2026-10-06 | Each page says "Describes CimPal 2026.10.6.1 and later". Features that are on `devel` but not in 2026.10.6.1 (`--summary-file`, `--violations-exit-code`) are marked as needing the next release. | Claude Code; approved plan |
| 2026-10-06 | Command check against `--help` from the `devel` build on its Maven classpath, because `CimPal-CLI.jar` was locked by the local MCP server. Same code as the JAR. | Claude Code; approved plan |
| 2026-10-06 | The link check is a small stdlib script (`scripts/check_doc_links.py`) in a path-filtered workflow (`docs.yml`), not lychee: no new tool, and it checks heading anchors. External URLs are not fetched. | Claude Code; approved plan |
| 2026-10-06 | Support contact: the README's existing support address (cimpal@griddigit.eu), plus GitHub private vulnerability reporting for security issues. Both are marked for maintainer confirmation; no address was invented. | Claude Code |

## Notes and results

- **Command check (2026-10-06):**
  - Captured `--help` for the top level and all 13 commands from `devel` `fe64eb9`. That build reports `CimPal CLI 2026.9.10.1`: `devel` never received the release version bump, which only lives on `master`.
  - All 21 CimPal flags in `docs/guide/**` occur in the help texts. The other 16 flags in the guide belong to Docker (`--memory`, `--cpus`, `--network`, `--read-only`, `--rm`, `--tmpfs`, `--user`) or `scripts/bench/run_bench.py` (`--sizes`, `--find-min-heap`, `--heap-range`, `--core-sweep`, `--cores`, `--warmup`, `--runs`, `--out`, `--docker`). All 13 command names match the top-level help.
- **Quickstart run** (Windows, PowerShell, a fresh copy of `integrations/airflow/examples/data` in `%TEMP%`):
  - `validate --config run.json --format json` exited 1 with `totals {conforming 0, violations 1, errors 0, total 1}` and `hasViolations: true`, and wrote `out/validation_report__<date>_<time>.xlsx`.
  - The workbook has the sheets "Validation results", "Validation statistics", "StatisticsConstraint" and "Charts".
  - With `--samples 3`: 5 shape groups with 2 findings each (10 in total), matching the generator's manifest.
- **Link check:** 31 files, 0 broken links.
- **Open:**
  - the maintainer reviews `versioning-and-support.md` (stability promises, deprecation period, supported versions, response time, contacts) and records it here;
  - the Docker and bash quickstart are run once on Linux;
  - `devel` should get `master`'s version bump (the guide doesn't depend on it, but `--version` on `devel` is behind);
  - pages to extend as DEP-5..10 land: deployment modes, capabilities, airflow.
