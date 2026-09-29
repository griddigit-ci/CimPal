<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# TEST-1 — Test harness

| Field | Value |
| --- | --- |
| Status | Not started |
| Phase | 1 |
| Depends on | CI-1 |
| Size | M |
| Branch | `feature/test-1-...` |

## Goal

Shared test infrastructure so every later work package can write fast, network-free, snapshot-based tests.

## Scope

- Core test-support package published as a Maven `test-jar`
- Snapshot helper (RDF isomorphism, Excel→CSV, JSON)
- StubHttpServer for remote-import tests
- JUnit/AssertJ/JSON Schema in CLI
- JaCoCo + coverage ratchet in all modules

## Acceptance criteria (status checklist)

- [ ] CLI and Main tests can use the Core test-support classes
- [ ] `-Dsnapshot.update=true` rewrites golden files; otherwise mismatches fail with a readable diff
- [ ] JaCoCo aggregate report produced by `mvn verify`; ratchet fails the build on a drop > 0.5 pp
- [ ] MappingValidatorTest converted to the new helpers as the reference example
- [ ] Baseline coverage per module recorded below

## Instructions for Claude Code

Start a session with: `Execute docs/plans/TEST-1.md` (in plan mode).

```text
Build the shared test infrastructure. No production behaviour changes.
1. In CimPal-Core add a test-support package (test scope) with: TestModels (tiny synthetic EQ/SSH models and SHACL shapes built in code), Fixtures (loads src/test/resources/fixtures/<feature>/...), Snapshot (assertIsomorphic for RDF via Jena isomorphism; assertExcelEquals by flattening sheets to CSV; assertJsonEquals ignoring timestamps and absolute paths; update mode via -Dsnapshot.update=true), and StubHttpServer (JDK HttpServer on 127.0.0.1:0 serving fixture files and recording requests, for owl:imports tests).
2. Publish it as a test-jar so CimPal-CLI and CimPal-Main can depend on it.
3. Add JUnit 5 + AssertJ + a JSON Schema validator to CimPal-CLI tests; add one trivial CLI test that runs `CimPalCli --help` in-process and asserts exit 0.
4. Add JaCoCo to all modules with an aggregate report. Add a coverage ratchet: record current line/branch coverage per module in a checked-in file and fail `verify` if it drops by more than 0.5 pp.
5. Convert MappingValidatorTest to use the new helpers as the reference example.
Report the baseline coverage numbers per module in this file.
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
