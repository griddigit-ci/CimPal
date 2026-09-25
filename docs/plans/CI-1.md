<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# CI-1 — PR build and test

| Field | Value |
| --- | --- |
| Status | Not started |
| Phase | 0 |
| Depends on | — |
| Size | S |
| Branch | `feature/ci-1-...` |

## Goal

Run the full build and all tests on every push and PR to `devel` and `master`, on Windows and Linux.

## Scope

- New `.github/workflows/ci.yml`
- Fixes needed to make the build and tests pass on `ubuntu-latest`

## Acceptance criteria (status checklist)

- [ ] ci.yml runs on push/PR to devel and master, matrix windows-latest + ubuntu-latest, JDK 25
- [ ] `mvn -B verify` green on both OSes; test reports uploaded as artifacts
- [ ] Top-level `permissions: contents: read`; all actions pinned by full commit SHA
- [ ] No test skipped to make Linux pass; any Linux-only failures fixed at the root cause and listed here

## Instructions for Claude Code

Start a session with: `Execute docs/plans/CI-1.md` (in plan mode).

```text
Add .github/workflows/ci.yml: on push and pull_request to devel and master. Matrix windows-latest and ubuntu-latest, Temurin JDK 25, Maven cache. Steps: mvn -B verify; upload surefire/failsafe reports as artifacts; publish a test summary. Set top-level `permissions: contents: read`. Pin every action by full commit SHA with a version comment.
The Ubuntu leg is important: it catches the package/directory case mismatch that only fails on case-sensitive filesystems. If the build or tests fail on Linux, fix the root cause (do not skip tests) and list what you fixed.
Make sure CimPal-Main builds headless on Linux (JavaFX tests must not need a display yet; tag display-dependent tests so they can be excluded until TEST-6).
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
