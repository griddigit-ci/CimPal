<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# SEC-5 — Fix the findings reported by TEST-2

| Field | Value |
| --- | --- |
| Status | In review (PR #49) |
| Phase | 2 |
| Depends on | TEST-2, SEC-2 |
| Size | S |
| Branch | `feature/sec-5-test2-findings` |

Added 2026-10-02 by Claude Code, on the maintainer's instruction to fix the TEST-2 findings before TEST-3, so the characterisation tests don't pin the wrong behaviour.

## Goal

Close the four findings TEST-2 reported in `docs/plans/TEST-2.md`, where a remediation in `SECURITY-SELF-ATTESTATION.md` §4 was incomplete or has regressed in code added since.

## Scope

- `ValidationTools` (owl:imports handling, `requirePublicHost`)
- `ModelFactory` (archive budget)
- CLI `SparqlCommand`, `CompareCommand` (CSV output)

## Acceptance criteria (status checklist)

- [x] A refused or unresolvable `owl:imports` fails the row (an error, never conforming); `F2_refusedImportFailsTheRowInsteadOfPassing` is enabled and passes
- [x] CLI CSV output (`sparql`, `compare`, file and stdout) neutralises cells starting with `=` `+` `-` `@` TAB CR, using one shared escape
- [x] Archive bytes are counted while an entry is read (per-entry and total limits), and `modelLoadPerFiles` uses the budget
- [x] `requirePublicHost` also refuses IPv6 unique-local (`fc00::/7`) and carrier-grade NAT (`100.64.0.0/10`) addresses
- [x] Regression tests for every item; `mvn -B verify` green

## Decisions log

| Date | Decision | By |
| --- | --- | --- |
| 2026-10-02 | Fix the four TEST-2 findings in a separate WP before TEST-3. | Maintainer |
| 2026-10-02 | An unresolvable or refused `owl:imports` fails the shapes load (`loadShapesWithImports` throws, naming up to five imports), so every caller fails closed: mapping and timestamped rows become errors, and `SHACLValidator.validate` throws instead of warning. This is a deliberate behaviour change: configurations that had silently broken imports now fail. | Claude Code |
| 2026-10-02 | One CSV cell escape, Core `CsvCells.escape`, used by `ComparisonCsvWriter` and by the CLI `sparql`, `compare` and `compare-instances` writers. The third CLI writer, `compare-instances`, was found while fixing. The GUI `ExportSHACLInformation` already neutralises and is unchanged. | Claude Code |
| 2026-10-02 | Archive limits are counted while an entry is read, with a new per-entry cap (1 GiB) beside the 2 GiB total and the 10 000 entries. The budget is thread-safe and also applied in `modelLoadPerFiles`. | Claude Code |
| 2026-10-02 | Also fixed: `sparql` treated inline query text as a path, which fails on Windows for `?` and `*` (`InvalidPathException`). That made inline queries unusable on Windows and let a SEC-2 test pass for the wrong reason; the test now asserts the actual refusal message. | Claude Code |

## Notes and results

### Results 2026-10-02

All four TEST-2 findings are fixed, each with regression tests:
- **Finding 1 (High, refused import gave a clean pass):** `F2_refusedImportFailsTheRowInsteadOfPassing` is enabled and passes. `ShapeSourceTest` (3) and `SHACLValidatorTest.unresolvableImportFailsTheValidation` were updated from "counted as unresolvable" or "warning" to "fails, naming the import"; each still checks that nothing is fetched.
- **Finding 2 (CSV):** `CsvCellsTest` (8, including a source check that every CLI writer delegates) and `SparqlCommandTest.csvOutputNeutralisesFormulaCells` (stdout and file).
- **Finding 3 (zip):** `ZipBudgetTest` (6). It covers per-entry and total limits while reading, the `modelLoadPerFiles` size and entry limits, and that archives within the limits still load.
- **Finding 4 (address ranges):** 4 more refused and 4 accepted addresses in `SecurityRegressionTest`.

Each fix was reverted in turn, and its tests failed every time.

