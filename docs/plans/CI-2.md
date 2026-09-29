<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# CI-2 — Supply chain and release integrity

| Field | Value |
| --- | --- |
| Status | Not started |
| Phase | 3 |
| Depends on | CI-1 |
| Size | M |
| Branch | `feature/ci-2-...` |

## Goal

Close G7: automated dependency and code scanning, pinned actions, and verifiable releases.

## Scope

- `.github/dependabot.yml`, CodeQL workflow, `nightly.yml`
- SpotBugs + FindSecBugs `static-analysis` profile
- `release.yml` changes

## Acceptance criteria (status checklist)

- [ ] Dependabot for maven and github-actions
- [ ] CodeQL for Java runs on PRs
- [ ] SpotBugs + FindSecBugs with a baseline; new High findings fail CI
- [ ] Nightly `-Psecurity-scan verify` with NVD API key from a secret
- [ ] Release gated on green CI; SBOMs and SHA256SUMS attached; actions SHA-pinned; minimal permissions
- [ ] List of manual GitHub UI steps recorded below

## Instructions for Claude Code

Start a session with: `Execute docs/plans/CI-2.md` (in plan mode).

```text
1. .github/dependabot.yml for maven (weekly, grouped minor/patch) and github-actions.
2. CodeQL workflow for java-kotlin (build-mode manual using mvn -B -DskipTests package).
3. SpotBugs + FindSecBugs in a `static-analysis` profile run by ci.yml; start with a baseline exclusion file for existing findings, fail on new High ones.
4. nightly.yml: `mvn -B -Psecurity-scan verify` (NVD API key from repo secret NVD_API_KEY), upload the report.
5. release.yml: require the tag commit to have a green ci.yml run (or re-run verify), attach target/bom.json (CycloneDX) for each module and a SHA256SUMS file; pin actions by SHA; keep permissions minimal (contents: write only on the release job).
6. Add a placeholder, disabled step for Windows code signing of CimPal.exe with instructions in this file.
Do not create secrets or change repository settings; list here what I must do in the GitHub UI (secret scanning, push protection, private vulnerability reporting, branch protection on devel/master requiring ci.yml, NVD_API_KEY secret).
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
