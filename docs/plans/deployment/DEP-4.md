<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-4 — External user guide, first edition

| Field | Value |
| --- | --- |
| Status | Not started |
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

- [ ] All pages above exist, each with the licence header, a "last reviewed" date and the CimPal version they describe
- [ ] Every command and flag mentioned exists (checked against `--help` output of the current JAR; list the check in notes)
- [ ] Quickstart verified from a clean folder with the JAR and with the DEP-1 image; commands copy-paste-able for PowerShell and bash
- [ ] Capability statement fits on two printed pages and has no claims that aren't backed by a test, a benchmark or a doc
- [ ] Pages that describe planned features (service mode, SDK, provider) mark them clearly as planned, with the WP id
- [ ] `README.md` (repo root) links to `docs/guide/index.md`; `docs/cli/index.md` links to the guide
- [ ] Support-policy text drafted and marked `<!-- maintainer review -->`; maintainer review recorded in the decisions log
- [ ] Markdown link check passes for `docs/guide/**` (script or CI step)

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

## Notes and results

(Claude Code: record findings, baseline numbers and open items here.)
