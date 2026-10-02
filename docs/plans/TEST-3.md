<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# TEST-3 — Characterisation (golden-master) tests

| Field | Value |
| --- | --- |
| Status | In progress (validation group in review, PR #50; other groups not started) |
| Phase | 2 |
| Depends on | TEST-1 |
| Size | L (one session per feature group) |
| Branch | `feature/test-3-<group>`; validation: `feature/test-3-validation` (stacked on SEC-5) |

## Goal

Capture the current behaviour of every Core feature with golden-output tests, so later refactoring (especially of `ValidationTools`) is safe.

## Scope

- Tests and fixtures only; suspected bugs recorded, not fixed

## Acceptance criteria (status checklist)

- [ ] Every row of the feature matrix below has an L2 golden test
- [ ] Each test covers happy path, empty input, malformed input, and CGMES 2.4 + 3.0 where relevant
- [ ] Suspected bugs listed below, each with an `@Disabled` failing test (validation group: done)

## Feature matrix

| Feature | Core entry point | L2 golden | L3 CLI (TEST-4) | L5 serve/mcp (TEST-4) |
| --- | --- | --- | --- | --- |
| SHACL validation — mapping, timestamped | `MappingValidator` | [x] | [ ] `validate` | [ ] |
| SHACL validation — manual | `ShaclAutoTester` | [x] | [ ] `validate --workflow manual` | [ ] |
| Single-dataset validation | `SHACLValidator` | [x] (Jena engine; the Python engines need external installs) | — | — |
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

Each has an `@Disabled` test that asserts the correct behaviour and fails today (checked with `-Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition`).

| # | Where | Suspected bug | Test |
| --- | --- | --- | --- |
| 1 | `ShaclAutoTester` | Models and reports are cached by file name, not path, so two rule folders holding a `model.xml` share the first one's report and one rule is wrongly reported as not triggered. | `ShaclAutoTesterTest.sameFileNameInTwoRuleFoldersIsValidatedSeparately` |
| 2 | `ShaclAutoTester` | A triggered shape without `sh:name` makes `getProperty(..).getObject()` throw a `NullPointerException`, which ends the whole run with no log written. | `ShaclAutoTesterTest.shapeWithoutNameDoesNotAbortTheRun` |
| 3 | `ShaclAutoTester` | An unparsable model is logged twice: by the pre-validation as a "Validation Error" with no rule name, and by the rule loop as a "Model Load Error". | `ShaclAutoTesterTest.unparsableModelIsLoggedOnceUnderItsRule` |

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
| 2026-10-02 | First feature group: validation, branched off SEC-5 so the tests pin the fail-closed `owl:imports` behaviour rather than the old false pass. | Maintainer |
| 2026-10-02 | Inputs are built in code (`TestModels` and inline RDF) under `@TempDir` rather than as fixture files, following `MappingValidatorTest`; golden outputs are in `snapshots/<feature>/`. | Claude Code |
| 2026-10-02 | `SHACLValidationReport.writeExcel` rows follow the engine's unstable result order, so those snapshots compare rows sorted per sheet (new `Snapshots.assertExcelEqualsIgnoringRowOrder`). `Snapshots` now also normalises sheet names, because the empty timestamped comparison workbook names its sheet after the run time. | Claude Code |
| 2026-10-02 | The pySHACL and Rust engines are not covered: they need Python or Rust installs, which unit tests must not depend on. | Claude Code |

## Notes and results

### Validation group, 2026-10-02

- **Tests:** Core 246 → 272 (3 skipped are the suspected-bug tests above). Coverage: Core line 33.6% → 38.0%, branch 22.6% → 25.5%. `utils` 62.5% line and `shacl_tools` 27.0% line afterwards.
- **`SHACLValidatorTest`** (+7): CGMES 3.0 and 2.4 presets with a Violation/Warning/Info mix, 3.0 data with the 2.4 preset (nothing typed, so 6 violations), conforming data, an empty data file, malformed data and malformed shapes (`RiotException`).
- **`MappingValidatorTest`** (+10): conforming, violating and error rows in one mapping (malformed model, missing model, missing shapes); header-only and one-column mappings; the result limit; the cgmes24 and cgmes30 presets and no map. Timestamped: two groups and two timestamps, a ZIP input, a previous comparison workbook, and an empty folder.
- **`ShaclAutoTesterTest`** (new, 5 + 3 disabled): expected behaviour with Excel and Turtle report export, a conform model triggering the rule, a non-conform model not triggering it, a malformed model, and no models.
- **Snapshots** were generated with `-Dsnapshot.update=true`, then three normal runs passed unchanged.

Findings (current behaviour, pinned, not bugs):
- **Report times are snapped to minute 30 of the hour** (`ValidationTools.normalizeTimestampToReportTime`), which explains the "30 minutes off" note in PROJECT.md. It looks like the market-time-unit convention; the maintainer should confirm.
- **A timestamped ZIP input is one group named after the archive.** Its sub-folders are not groups (`prepareTimestampedInputGroups`).
- **`MappingValidationOptions.previousComparisonCsv` takes the previous `validation_comparison__*.xlsx`**, not a CSV. The name is misleading.
- **With the CGMES 2.4 map, CIM100 data stays untyped**, and every datatype and range check on it fails. This is a configuration mismatch, and there is no warning for it.

Left open: the other feature groups (rdfs2shacl + excel2shacl + organize, convert, comparisons, sparql, gen-instances + manifest, kgcl).
