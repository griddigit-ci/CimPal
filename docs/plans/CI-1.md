<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# CI-1 — PR build and test

| Field | Value |
| --- | --- |
| Status | In review ([PR #40](https://github.com/griddigit-ci/CimPal/pull/40)) |
| Phase | 0 |
| Depends on | — |
| Size | S |
| Branch | `feature/ci-1-pr-build` |

## Goal

Run the full build and all tests on every push and PR to `devel` and `master`, on Windows and Linux.

## Scope

- New `.github/workflows/ci.yml`
- Fixes needed to make the build and tests pass on `ubuntu-latest`

## Acceptance criteria (status checklist)

- [x] ci.yml runs on push/PR to devel and master, matrix windows-latest + ubuntu-latest, JDK 25
- [x] `mvn -B verify` green on both OSes; test reports uploaded as artifacts
- [x] Top-level `permissions: contents: read`; all actions pinned by full commit SHA
- [x] No test skipped to make Linux pass; any Linux-only failures fixed at the root cause and listed here (none were found; see Notes)

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
| 2026-09-25 | The Linux leg runs `xvfb-run -a mvn -B verify`, so the JavaFX test runs on both OSes instead of being excluded. `MainGuiFxmlLoadTest` is tagged `@Tag("gui")` so headless machines can use `-DexcludedGroups=gui` until TEST-6. | Claude Code |
| 2026-09-25 | The test summary is an inline Python step writing to `$GITHUB_STEP_SUMMARY`, not a third-party reporter action. That avoids `checks: write` and one more supply-chain dependency. | Claude Code |
| 2026-09-25 | Actions are pinned to the latest releases: checkout v7.0.1, setup-java v6.0.1, upload-artifact v7.0.1. `persist-credentials: false` on checkout. Concurrency cancels superseded PR runs, but never pushes to `devel`/`master`. | Claude Code |
| 2026-09-25 | Pinning `release.yml` (still tag-pinned `@v4`) and Dependabot for SHA updates are left to CI-2. | Claude Code |

## Notes and results

- **First CI run:** [run 36120288629](https://github.com/griddigit-ci/CimPal/actions/runs/36120288629). Both legs were green on the first attempt.
  - **windows-latest:** 73 tests (Core 65, Main 8, CLI 0), 0 failures, 0 skipped.
  - **ubuntu-latest:** 73 tests (Core 65, Main 8, CLI 0), 0 failures, 0 skipped.
  - `MainGuiFxmlLoadTest` ran on both, under Xvfb on Ubuntu.
  - Artifacts `test-reports-windows-latest` and `test-reports-ubuntu-latest` were uploaded.
- **No Linux-only failures, so no fixes were needed.** Pre-checks found no case mismatches: 0 in package vs directory across all Java files, 0 in string resource references (`.fxml`, `.css`, `.ttl`, `.properties`, …) vs committed files, and no committed paths differing only by case. launch4j 2.7.0 builds `CimPal.exe` on Linux with its `linux64` workdir, and JavaFX Linux classifiers were already declared.
- **Local tests:** before 73, after 73 (`mvn -B verify`, Windows). `mvn -B -pl CimPal-Main -am test -DexcludedGroups=gui` runs 72, which confirms the tag works.
- **Open items:**
  - GitHub warns that `ubuntu-latest` moves to Ubuntu 26 from 2026-10-19. Watch the first run after that.
  - To make CI mandatory, enable branch protection on `devel`/`master` with the required checks "Build and test (windows-latest)" and "Build and test (ubuntu-latest)". This is a repo setting for the maintainer.
- `/security-review` wasn't run, because no application code changed. The workflow was reviewed for least privilege: `contents: read`, SHA pins, no secrets, and checkout without persisted credentials.
