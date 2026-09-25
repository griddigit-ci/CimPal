<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# TEST-2 — Regression tests for past security findings

| Field | Value |
| --- | --- |
| Status | Not started |
| Phase | 2 |
| Depends on | TEST-1 |
| Size | M |
| Branch | `feature/test-2-...` |

## Goal

Every remediation in SECURITY-SELF-ATTESTATION.md §4 (F1–F13, H1) has a test that fails if the fix is reverted.

## Scope

- Core and Main tests only; no production changes unless a finding turns out not to be remediated (then stop and report)

## Acceptance criteria (status checklist)

- [ ] At least one test `F<n>_<short>` per finding
- [ ] Finding → test mapping table filled in below
- [ ] Any finding found not remediated is reported, not silently fixed

## Finding → test map

| Finding | Test |
| --- | --- |
| F1 | |
| F2 | |
| F3 | |
| F4 | |
| F5 | |
| F6 | |
| F7 | |
| F8 | |
| F9 | |
| F10 | |
| F11 | |
| F12 | |
| F13 | |
| H1 | |

## Instructions for Claude Code

Start a session with: `Execute docs/plans/TEST-2.md` (in plan mode).

```text
Read SECURITY-SELF-ATTESTATION.md §4. For each finding F1–F13 and H1, write at least one test named `F<n>_<short>` that fails if the remediation is reverted. Examples: F1 credential never sent to a non-allowlisted host (StubHttpServer records headers); F2 owl:imports to loopback/private/non-allowlisted hosts refused and reported as an error, not a pass; F4 CSV cells starting with = + - @ tab CR are neutralised; F5 cache filename cannot escape the cache dir; F7 zip bomb / entry-count / total-size limits trip; F8 zip-slip entry rejected; F9 CR/LF in logged values escaped; F13 AI fetch refuses private addresses and redirects.
Before writing each test, verify the current code actually implements the remediation. If a finding is NOT remediated as described, stop and report it; do not fix silently.
Fill in the finding → test class#method table in this file.
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
