<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# TEST-6 — GUI smoke tests

| Field | Value |
| --- | --- |
| Status | Not started |
| Phase | 4 |
| Depends on | TEST-1 |
| Size | M |
| Branch | `feature/test-6-...` |

## Goal

Every GUI tab loads and runs its main action headlessly, and GUI-only logic is listed for extraction to Core.

## Scope

- TestFX + Monocle in CimPal-Main
- ci.yml (smoke subset) and nightly.yml (full)

## Acceptance criteria (status checklist)

- [ ] One smoke test per tab in CimPalGui.fxml
- [ ] Runs headless on ubuntu-latest
- [ ] GUI-only logic listed below as extraction candidates with estimated size

## Instructions for Claude Code

Start a session with: `Execute docs/plans/TEST-6.md` (in plan mode).

```text
Add TestFX with Monocle (headless) to CimPal-Main. For every tab in CimPalGui.fxml: load it, fill inputs with fixture paths, trigger the main action, and assert the result appears (table rows, output file, status label) without an exception. Keep these as smoke tests: the logic should already be covered in Core. Where a tab's logic still lives only in Main (task wizard tasks, RDFS union, visualisation, datatype mapping, export RDFS descriptions), list it in this file as a candidate for extraction to Core, with an estimated size. Make the suite run headless on ubuntu-latest in ci.yml (smoke subset) and in full in nightly.
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
