<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# TEST-5 — Nightly deep tests

| Field | Value |
| --- | --- |
| Status | Not started |
| Phase | 4 |
| Depends on | TEST-3 |
| Size | M |
| Branch | `feature/test-5-...` |

## Goal

Find what PR tests can't: crashes on malformed input, engine disagreements, performance regressions, and weak tests.

## Scope

- Jazzer fuzz targets
- Engine differential test
- Scale tests on real CGMES models
- PIT mutation testing
- `nightly.yml`

## Acceptance criteria (status checklist)

- [ ] Fuzz targets for archives, RDF/XML + Turtle loading, Excel import, config JSON, SHACL loading; 10 min each nightly; crashes saved and turned into regression tests
- [ ] Jena vs pySHACL differential test, skipped cleanly when Python is absent
- [ ] Scale test with time and heap budgets; timings stored as artifacts
- [ ] Weekly PIT report for validation and egress packages

## Instructions for Claude Code

Start a session with: `Execute docs/plans/TEST-5.md` (in plan mode).

```text
1. Jazzer (jazzer-junit) fuzz targets for: zip/archive extraction, RDF/XML and Turtle model loading through ModelFactory, Excel import (ExcelTools), config JSON parsing, SHACL shape loading. Each target asserts: no uncaught exception other than the documented input-error types, no file written outside @TempDir, finishes within the time limit. Seed corpora from the fixtures. Run 10 minutes per target in nightly.yml; save crashes as artifacts and turn each into a regression test.
2. Engine differential: parameterised test running the same fixtures through APACHE_JENA and PYSHACL; compare conforms and per-shape violation counts; skip cleanly if Python/pySHACL is absent; nightly installs them.
3. Scale: nightly job downloads the agreed ENTSO-E conformity models (URL list in this file, not committed), runs validate mapping and rdfs2shacl, asserts time and -Xmx budgets, and records the timings as an artifact.
4. PIT mutation testing on the validation and egress packages, weekly, report only.
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
