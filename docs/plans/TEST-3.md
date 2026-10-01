<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# TEST-3 — Characterisation (golden-master) tests

| Field | Value |
| --- | --- |
| Status | Not started |
| Phase | 2 |
| Depends on | TEST-1 |
| Size | L (one session per feature group) |
| Branch | `feature/test-3-...` |

## Goal

Capture the current behaviour of every Core feature with golden-output tests, so later refactoring (especially of `ValidationTools`) is safe.

## Scope

- Tests and fixtures only; suspected bugs recorded, not fixed

## Acceptance criteria (status checklist)

- [ ] Every row of the feature matrix below has an L2 golden test
- [ ] Each test covers happy path, empty input, malformed input, and CGMES 2.4 + 3.0 where relevant
- [ ] Suspected bugs listed below, each with an `@Disabled` failing test

## Feature matrix

| Feature | Core entry point | L2 golden | L3 CLI (TEST-4) | L5 serve/mcp (TEST-4) |
| --- | --- | --- | --- | --- |
| SHACL validation — mapping, timestamped | `MappingValidator` | [ ] | [ ] `validate` | [ ] |
| SHACL validation — manual | `ShaclAutoTester` | [ ] | [ ] `validate --workflow manual` | [ ] |
| Single-dataset validation | `SHACLValidator` | [ ] | — | — |
| RDFS → SHACL (2019, 2020, closed, split datatypes) | `SHACLFromRDF` | [ ] | [ ] `rdfs2shacl` | [ ] |
| Excel → SHACL | `ShaclFromXls` | [ ] | [ ] `excel2shacl` | [ ] |
| SHACL organise | `ShaclOrganizer` | [ ] | [ ] `organize` | [ ] |
| RDF convert (XML, Turtle, JSON-LD, CIMXML, sort, union) | `RDFConverter` | [ ] | [ ] `convert` | [ ] |
| Compare RDFS / SHACL / CIMTool | `Comparison*` | [ ] | [ ] `compare` | [ ] |
| Compare instances (DL/SV/TP ignores) | `ComparisonInstanceData` | [ ] | [ ] `compare-instances` | [ ] |
| SPARQL SELECT + Excel/CSV export | `SparqlTools` | [ ] | [ ] `sparql` | [ ] |
| Instance generation from Excel | `InstanceDataBuilder/Writer` | [ ] | [ ] `gen-instances` | [ ] |
| Manifest generation | `ManifestGenerator` | [ ] | [ ] `manifest` | [ ] |
| Pipelines | `RunCommand` | — | [ ] `run` | — |
| KGCL export | `Kgcl*` | [ ] | — | — |

## Suspected bugs

(none yet)

## Instructions for Claude Code

Start a session with: `Execute docs/plans/TEST-3.md` (in plan mode).

```text
Feature group for this session: <validation | rdfs2shacl + excel2shacl + organize | convert | comparisons | sparql | gen-instances + manifest | kgcl>.
Write golden-master tests that capture CURRENT behaviour of the Core entry points for this group (see the feature matrix below). Do not change production code; if current behaviour looks wrong, record it under "Suspected bugs" in this file with a failing @Disabled test, and keep the golden test on current behaviour.
For each option combination the CLI exposes, add a small fixture pair (input + expected output) under src/test/resources/fixtures/<feature>/. Compare RDF by isomorphism, Excel via flattened CSV, JSON ignoring timestamps/paths. Cover at least: happy path, empty input, malformed input (expected error type), and one CGMES 2.4 and one CGMES 3.0 case where relevant.
Tick the matrix rows you completed and report the coverage change for the touched packages.
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
