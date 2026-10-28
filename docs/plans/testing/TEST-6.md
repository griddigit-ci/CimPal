<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# TEST-6 — GUI smoke tests

| Field | Value |
| --- | --- |
| Status | Done ([PR #85](https://github.com/griddigit-ci/CimPal/pull/85)) |
| Phase | 4 |
| Depends on | TEST-1 |
| Size | M |
| Branch | `feature/test-6-gui-smoke` |

## Goal

Every GUI tab loads and runs its main action headlessly, and GUI-only logic is listed for extraction to Core.

## Scope

- GUI smoke tests in CimPal-Main (plain JUnit and `FxTestSupport`; TestFX + Monocle was the
  original idea, dropped by decision D1 below)
- ci.yml (smoke subset) and nightly.yml (full)

## Acceptance criteria (status checklist)

- [x] One smoke test per tab in CimPalGui.fxml (13 tabs, incl. ID Mapping merged from devel)
- [x] Runs headless on ubuntu-latest (under Xvfb: CI runs the smoke subset, nightly the full set;
      to confirm on the first CI and nightly run of the PR)
- [x] GUI-only logic listed below as extraction candidates with estimated size

## Instructions for Claude Code

Start a session with: `Execute docs/plans/testing/TEST-6.md` (in plan mode).

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
| 2026-10-21 | D1: no TestFX/Monocle. Plain JUnit with a shared `FxTestSupport` (toolkit started once, FX-thread work with a timeout, latches instead of sleeps), `@GuiSmoke` per class. | Maintainer (approved plan) |
| 2026-10-21 | D2: production change `eu.griddigit.cimpal.main.gui.GuiDialogs` (open, openMultiple, save, directory, alert, confirm) with a test-only backend; `fileChooserCustom`, the save and folder choosers and the alerts on the tested paths go through it. | Maintainer (approved plan) |
| 2026-10-21 | D3: full action tests for Convert, RDF Comparison, Dataset Comparison, SHACL Validation, the rule test and SPARQL; load and initial state for Visualisation, RDFS Union, AI Assistant (never touches the network), RDFS to SHACL, Generate, Task Wizard, and ID Mapping (added after devel gained the tab). | Maintainer (approved plan; ID Mapping by coordinator) |
| 2026-10-21 | D4: tag `smoke` runs in ci.yml (default `excludedGroups` of the Main pom is `gui & !smoke`); the full `gui` set in a new nightly.yml job (`-Pgui-all`, `xvfb-run -a`, pinned actions). `-DexcludedGroups=gui` also skips Main's coverage ratchet (profile `no-gui-tests`), because it does break without the GUI tests (4.3 % lines against a 7.1 % floor). | Maintainer (approved plan) |

## Notes and results

### What was added

- `CimPal-Main/src/main/java/eu/griddigit/cimpal/main/gui/GuiDialogs.java` (new) and its callers:
  `util/ModelFactory` (open/multiple/folder/save choosers and the zip-folder alert), `gui/GUIhelper`
  (info/warning/error and `showUserFriendlyError`), `RDFComparisonController` and
  `InstanceDataComparisonController` (their alerts), `SparqlQueryTabController` (choosers, alerts,
  confirmations; owner window is null-safe now).
- Test support in `src/test/java/eu/griddigit/cimpal/main/gui/`: `FxTestSupport` (JUnit extension
  and helpers), `GuiSmoke` (composed annotation: tag `gui`, extension, same thread, 60 s),
  `ScriptedDialogs` (the test backend: queued answers, recorded alerts, `awaitAlert`),
  `InMemoryPreferences`.
- 13 `<Controller>SmokeTest` classes (one per tab) and a rewritten `MainGuiFxmlLoadTest` that checks
  every tab's id, title and content and that no tab-loading error dialog was raised. Fixtures in
  `src/test/resources/fixtures/gui-smoke/`; the validation and rule-test inputs come from
  `TestModels`.
- `CimPal-Main/pom.xml`: `excludedGroups` default, profiles `gui-all` and `no-gui-tests`.
  `.github/workflows/ci.yml` (comment only: a plain verify now runs the smoke subset) and
  `nightly.yml` (job "GUI tests (full set)").

### Counts and coverage

Before (origin/devel 69321d1, `mvn -B verify`, Windows): Core 484 (8 skipped), Main 65, CLI 477
(8 skipped) + 2 + 22 IT (21 skipped). Main coverage 7.78 % lines / 4.58 % branches.

After (merged with devel f8459e7+, `mvn -B clean verify`, Windows, green): Core 561 (10 skipped;
the increase is devel's TEST-3 tests), Main 85 (65 + 13 tab smoke tests + 5 `GuiDialogsTest` + 2
`ModelFactoryDialogsSmokeTest`; the 7 action tests run with `-Pgui-all`, 92 in all), CLI 483 (8 skipped) + 2 + 22 IT (21 skipped). Main 8.04 % lines /
4.76 % branches; floor raised to 0.0753 / 0.0426 with `Update-CoverageBaseline.ps1 -Modules
CimPal-Main`. Main, clean builds before the devel merge: default (smoke) 77 tests,
8.00 % / 4.79 %; `-Pgui-all` 83 tests, 11.54 % / 7.66 %; `-DexcludedGroups=gui` 64 tests,
4.35 % / 3.42 %.

### Deliberate breaks (each confirmed red, then reverted)

| Break | Red tests |
| --- | --- |
| Round A, one per tab FXML: renamed `onAction` (Convert reset, SHACL Validation reset, RDFS to SHACL reset, Task Wizard back) or an fx:id read in `initialize` (RDF Comparison, Dataset Comparison, rule-test pane, SPARQL, Visualisation, RDFS Union, AI Assistant, Generate) | All 19 GUI tests: every tab's smoke tests (LoadException), and `MainGuiFxmlLoadTest` listing all 12 "tab could not be loaded" error dialogs |
| ID Mapping: fx:id `cbSecondName` renamed (after the devel merge) | `IdMappingControllerSmokeTest`, `MainGuiFxmlLoadTest` ("ID Mapping tab error") |
| Convert writes nothing (stream closed without converting) | `convertsRdfXmlToTurtle`: expected 4 triples, was 0 |
| RDF Comparison drops the result rows (`initData(stage, null, …)`) | `RDFComparisonControllerSmokeTest.comparisonShowsDifferenceRows`: 0 rows |
| Dataset Comparison drops the result rows | `InstanceDataComparisonControllerSmokeTest.comparisonShowsDifferenceRows`: 0 rows |
| SHACL Validation writes its report to another folder | `combinedValidationWritesReport`: no workbook in the output folder |
| SPARQL returns empty bindings (`results.rows.clear()`) | `selectQueryShowsResultRows`: expected ["Line 1", "Line 2"], was [] |
| Rule test verdict inverted | `ruleTestPassesAndWritesWorkbook`: "Rule test finished" instead of "Rule test passed" |
| An un-seamed `Alert.showAndWait()` in Convert's Browse handler | `convertsRdfXmlToTurtle` fails after 30 s ("Timed out … waiting for work on the FX thread") instead of hanging |

### Security review fixes (2026-10-21, two Low findings, test first)

| Finding | Test | Before the fix | After |
| --- | --- | --- | --- |
| `GuiDialogs.useBackendForTesting`/`resetBackend` were public, so any code could install a backend that answers every `confirm` (SPARQL repair, Git commit) with OK. Now package-private; `currentBackend()` (package-private) lets a test check the reset. | `GuiDialogsTest` (untagged, runs everywhere): methods not public; reset restores `SYSTEM`; `confirm` is false for no button and for CANCEL, true only for OK | `onlyThisPackageCanSwapTheBackend` failed ("useBackendForTesting is public"); the other four passed (confirm already failed closed; they pin it) | 5/5 pass |
| `ScriptedDialogs.answerOpen()` with no files gave `openMultiple` an empty list, where the real dialog returns null on cancel, so `ModelFactory.fileChooserCustom(false, …)` threw `NoSuchElementException` at `fileL.getFirst()`. `GuiDialogs.openMultiple` now returns null for an empty selection, whatever the backend. | `ModelFactoryDialogsSmokeTest` (tags `gui`, `smoke`): multi and single selection closed without files select nothing | `multipleSelectionClosedWithoutFilesSelectsNothing` failed with `NoSuchElementException` | 2/2 pass |

Recorded, not fixed here: Main's `ModelFactory.unzip` has no ZIP entry/size limits (PROJECT.md,
*Known issues*).

### Local run

On the maintainer's Windows desktop, run from Claude Code, all GUI tests passed every time
(2026-10-21; the whole set takes about 20 s). The earlier `MainGuiFxmlLoadTest` timeouts did not
recur. Ubuntu CI under Xvfb remains the authoritative run.

### Extraction candidates (GUI-only logic still in Main)

| Logic | Where | Size | Note |
| --- | --- | --- | --- |
| Task wizard tasks (rename, multiply, multiply-and-connect, CGM/IGM from config workbook, add/regenerate mRID and rdf:ID, non-conform datatypes, header edits, delete required properties, generate model) | `application/tasks/*` (~3,100 lines) | L | `WizardContext` (716) is GUI state and stays. Tasks already implement `ITask`; a Core `tasks` package plus a CLI command is the natural target. |
| Visualisation: scanning, filtering (incl. SPARQL filter), grouping and paging of the RDF browser | `RDFVisualisationController` (3,345 lines, logic roughly 1,000–1,500) | L | `RdfGraphView` (504) and `PowsyblBridge` (332) are UI/PowSyBl and stay in Main. |
| Dataset Comparison | `main/core/ComparisonInstanceData` (901) + `util/CompareFactory` (467) | M | Core already has its own `comparators.ComparisonInstanceData` (used by `compare-instances`); switch the tab to it and delete the Main copy after a characterisation test shows they agree. |
| Datatype mapping | `main/core/DataTypeMaping` (366) and `application/datagenerator/DataTypeMaping` (137) | M | Two copies; Core has `DatatypeMapPreset`s. |
| Export RDFS descriptions | `main/core/ExportRDFSdescriptions` (427) | S–M | Pure Jena + POI. |
| SHACL information export and instance-data template | `main/core/ExportSHACLInformation` (1,268), `ExportInstanceDataTemplate` (915) | M each | Excel writers, no GUI needed. |
| RDFS Union | none | – | Already Core (`RDFConverter`); the controller only reads the form. |

### Open items

- About 95 direct dialog calls are not yet behind `GuiDialogs` (PROJECT.md, *Known issues*).
- `MainController.initialize` and `RDFStoSHACLController.initialize` still open the real user
  preferences node (PROJECT.md, *Known issues*).
- The first CI and nightly runs of the PR must confirm the Xvfb leg.
