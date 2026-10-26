<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# CimPal — Project Reference Document

**Last updated:** 2026-10-22 (TEST-4: CLI contract, packaged JAR and MCP protocol tests; MCP JSON-RPC error codes; `serve` refuses a missing root; refused uploads are answered before the connection closes); 2026-10-21 (SEC-3: MCP output hygiene and the Python interpreter; TEST-6: GUI smoke tests and the `GuiDialogs` seam; TEST-3 convert/sparql tests; ID mapping, `id-map`)  
**Update rule:** Edit this file after every bigger implementation (CLAUDE.md, *After every implementation*). Sections that change most often: *Change log*, *Implementation status*, *Next steps*, *Known issues*.

---

## How to use this document

This document serves two audiences simultaneously:

**For humans:** a single place to understand what CimPal is, what has been built, why decisions were made, and what comes next. Read it before starting any new work.

**For AI assistants:** a full context briefing so work can resume in any new conversation without re-discovering what was already learned. If you are an AI reading this, treat it as ground truth about the current state — then verify key details against the actual code before writing anything.

When AI picks up work: read this file first, then `docs/cli/index.md` for the CLI status, then the specific source file most relevant to the task. Do not re-run the full codebase discovery — it has been done and is summarized below.

---

## Project at a glance

**CimPal** is a Java 25 desktop application (JavaFX) built by gridDigIt for CIM/CGMES semantic tooling. Used by power system engineers working with CIM models, SHACL constraints, and CGMES profiles.

Core capabilities:
- SHACL validation of CGMES instance data against constraint sets
- RDFS-to-SHACL shape generation from CIM profiles
- RDF format conversion (XML ↔ Turtle ↔ JSON-LD)
- SPARQL querying of RDF instance data
- Profile version comparison (RDFS diff, SHACL diff)
- Excel-driven shape authoring
- CGMES instance data generation and manipulation
- CGMES manifest generation

**The CLI project** added a headless entry point to all operations for CI pipelines, scripts, and as the foundation of the automated validation agent (Part B).

---

## Architecture

### Build system

Maven multi-module reactor. Java 25. Four modules:

```
CimPal (parent pom)
├── CimPal-CustomWriter    — custom Jena RDF/XML serializers (no GUI)
├── CimPal-Core            — all reusable RDF/SHACL logic (no GUI, no JavaFX)
├── CimPal-Main            — JavaFX GUI application (depends on Core + CustomWriter)
└── CimPal-CLI             — headless fat-JAR entry point (depends on Core + CustomWriter)
```

Key dependency versions:
- Apache Jena 6.2.0 (primary RDF library — arq, tdb2, shacl, cmds)
- TopBraid SHACL API 1.5.0 (used alongside Jena for shape processing)
- JavaFX 25.0.3 (Main module only)
- Apache POI 5.5.1 (Excel I/O — Core and Main)
- Jackson 3.2.1 (`tools.jackson.core` groupId — Main and CLI)
- picocli 4.7.6 (CLI only)
- commons-lang3 3.20.0 + commons-math3 3.6.1 (Core — added in Phase 4)
- SLF4J 2.0.18 (logging)
- JUnit Jupiter 5.13.1 (tests)

Main and CLI are fat JARs: `maven-shade-plugin` (`shadedArtifactAttached=false`) overwrites `CimPal.jar` / `CimPal-CLI.jar` with the shaded jar, and launch4j wraps `CimPal.jar` into `CimPal.exe`. Both modules set `maven-jar-plugin` `forceCreation` (2026-10-19). Without it, a build without `clean` kept the previous fat jar as "up to date" and shaded it again, so the old Core classes inside it won. Seen after merging PR #78: the rebuilt GUI still had the old comparison wording, and `original-CimPal.jar` was 212 MB instead of 2.3 MB. Release CI builds from a fresh checkout and was not affected.

### Module dependency graph

```
CustomWriter ← Core ← Main
                  ↖ CLI
```

Both Main and CLI are fat JARs (shade plugin). CLI bundles Core + CustomWriter + Jena + picocli + Jackson.

### Package naming — CRITICAL

All packages follow `eu.griddigit.CimPal.*` with capital C in CimPal. The fat JAR's ZIP entries are case-sensitive. A package/directory case mismatch compiles on Windows but fails at runtime with `ClassNotFoundException`.

- Core: `eu.griddigit.cimpal.core.*` (lowercase — pre-existing, works on Windows)
- Main: `eu.griddigit.cimpal.main.*`
- CLI commands: `eu.griddigit.CimPal.cli.command.*` (capital C — must match directory)
- Legacy entry: `eu.griddigit.CimPal.generators.*`

---

## Key classes by function

### Validation

| Class | Module | What it does |
|---|---|---|
| `eu.griddigit.cimpal.core.utils.ValidationTools` | Core | Main validation engine. `validateByMapping()`, `validateByTimestampedMapping()`. ~6000 lines. Zero GUI imports. Prefer `MappingValidator` for new callers — see below. |
| `eu.griddigit.cimpal.core.utils.MappingValidator` + `eu.griddigit.cimpal.core.models.MappingValidationOptions` | Core | Builder-style facade over `validateByMapping`/`validateByTimestampedMapping` (added 2026-09-23). `MappingValidationOptions.builder()...timestamped(true/false).build()`, then `new MappingValidator(options).validate()` → `MappingValidationSummary`. The GUI's SHACL Validation tab and the CLI's `validate --workflow mapping/timestamped` both go through this now (CLI refactored 2026-09-25). |
| `eu.griddigit.cimpal.core.models.MappingValidationSummary` | Core | Record: `reports` (List<Path> — one entry for plain mapping, several for timestamped), `conforming`, `violations`, `errors`. Same `hasViolations()`/`totalRows()` semantics as the older `ValidationRunSummary`/`ValidationTimestampedRunSummary`. |
| `eu.griddigit.cimpal.core.utils.SHACLValidator` + `eu.griddigit.cimpal.core.models.SHACLValidationOptions` | Core | Builder-style facade for validating **one** dataset (files and/or a Jena model) against **one** set of shapes → `SHACLValidationReport`. Used by the GUI's *Validate selected files together* workflow (2026-10-05, see below) and the CLI's `validate --workflow combined` (2026-10-07), and once per distinct model by `ShaclRuleTester`, which loads the shapes once through the package-private `SHACLValidator.loadShapeFiles` and passes them as `shapesModel`. |
| `eu.griddigit.cimpal.core.models.SHACLValidationReport` | Core | `SHACLValidator`'s result. `getResultsByConstraintFile()` breaks the results down by the constraint file that declares each result's source shape. `writeExcel(file)` / `writeExcelTo(dir)` write the mapping-report workbook with one validation row per constraint file; `writeTurtle(file)` writes the engine's `sh:ValidationReport`. |
| `eu.griddigit.cimpal.core.utils.ShapeArchive` | Core | Package-private. The `.ttl`/`.rdf` entries of a shapes ZIP, held in memory (256 MiB budget) as `ShapeSource`s. Each is parsed with the base `<archive URI>/<entry>`, so relative `owl:imports` resolve inside the archive as in a folder (`ValidationTools.resolveImport` answers them from memory, ahead of the network-path refusal). |
| `eu.griddigit.cimpal.core.presets.MappingValidationOptionsPresets` / `SHACLValidationOptionsPresets` | Core | CGMES 3.0 / 2.4.15 starting points for the two builders above. |
| `eu.griddigit.cimpal.core.utils.DatatypeMapPreset` | Core | Enum: `NONE`, `CGMES24_NC22`, `CGMES30_NC24`, `CGMES30_NC25`. `.load()` reads the matching bundled `.properties` file. |
| `eu.griddigit.cimpal.core.utils.ShaclRuleTester` + `eu.griddigit.cimpal.core.models.ShaclRuleTestOptions` | Core | The SHACL rule test (replaced `shacl_tools.ShaclAutoTester` 2026-10-05, see below): tests the **rules** of a constraint set against a suite of `<rule sh:name>/Conform` and `/NonConform` model archives → `ShaclRuleTestReport` (verdict per rule, outcome per model, notes) and `rule_test_results_<timestamp>.xlsx` in the suite folder (`ShaclRuleTestWorkbook`, package-private). A tool of its own, not part of the validation API; GUI only. |
| `eu.griddigit.cimpal.core.utils.ValidationEngine` | Core | Enum: APACHE_JENA, PYSHACL, PYSHACL_OXIGRAPH, RUST_SHACL |
| `eu.griddigit.cimpal.core.utils.CompleteDatatypeMapLoader` | Core | Loads CGMES datatype maps from bundled classpath resources or .properties files. |
| `eu.griddigit.cimpal.core.interfaces.ShaclRuleTesterCallback` | Core | Callback for `ShaclRuleTester`: `updateProgress(double)` and `appendOutput(String)`, called from worker threads. |
| `ValidationTools.ValidationRunSummary` | Core | Record: `reportPath`, `conforming`, `violations`, `errors`. Still used internally by `ValidationTools` and by `MappingValidator`, which unwraps it into `MappingValidationSummary`. |
| `ValidationTools.ValidationTimestampedRunSummary` | Core | Record: `reports` (List<Path>), `conforming`, `violations`, `errors`. Same relationship to `MappingValidationSummary` as above. |

**Combined validation (added 2026-10-05).** The SHACL Validation tab has a fourth workflow, *Validate selected files together*. It merges the selected instance data files (`.xml`, or ZIPs of them, nested ZIPs included) into one data graph and validates it against all selected constraint files (`.ttl`/`.rdf`, or ZIPs of them) as one shapes graph, through `SHACLValidator`. It writes one `validation_report__<timestamp>.xlsx` (plus an optional `.ttl`) to the output folder, with one validation row per constraint file. A file with no findings is listed as conforming, unless the run was partial or the engine reported non-conformance without extractable results. When a result can't be placed in a file (an ambiguous shape label, or Python-engine blank nodes) the report falls back to one row. What a shape finds counts under the file that declares it, even if another file added the constraint. Related Core changes:
- `ValidationTools.loadShapesWithImports(List<ShapeSource>, …, BiConsumer onDocument)` loads several roots as **one** closure. Before, `SHACLValidator` loaded each root separately, so a shared import was parsed once per root and the findings of its anonymous shapes were reported twice.
- `ModelFactory.forEachZipEntry` streams archive entries, nested ones included, within one `ZipBudget`. It drains every nested entry through the budget, because `ZipInputStream` inflates skipped entries. `safeZipEntryName` refuses absolute names, `:`, `..` that climbs out, and control characters.
- A data or shapes ZIP with nothing to read fails instead of contributing nothing. Other RDF files in a shapes ZIP are named in the report warnings, which the GUI shows in its finish dialog.

The CLI runs it as `validate --workflow combined` (added 2026-10-07), with `--constraint-files` and `--data-files` (config keys `constraintFiles`, `dataFiles`) and the template `configs/validate-combined.json`. Its JSON summary keeps `cimpal-validate-summary/1`: `totals` count the workbook's rows, one per constraint file, and `results`, `byConstraintFile`, `partial` and `warnings` are added. An input that can't be read (a parse error, which now names the file, a missing `owl:imports`, an archive without matching files) ends the run with exit 2 and writes nothing, and so does a run that would check nothing (`SHACLValidationReport.checkedNothing()`: no active target shape, or no data triples); a report that can't be written ends with exit 3. `SHACLValidationReport.writeReportsTo` names the workbook and the `.ttl` for the GUI and the CLI alike, and `SHACLValidationOptions.runStats` counts the data for `--stats`. Covered by `SHACLValidatorTest`, `SHACLValidationReportTest`, `ShapeArchiveTest`, `ZipBudgetTest`, `ShapeSourceTest`, and in the CLI by `ValidateCommandTest`, `PathGuardTest` and `OutOfMemoryExitTest`.

**Comparison workbook formulas (2026-10-15).** The timestamped workflow's `validation_comparison__*.xlsx` (`ValidationExcelWriter.ComparisonExcelWriter`) was asked to show how its percentages are calculated (REE feedback). Every number on its Charts sheet is now an Excel formula: dataset counts are `SUMIFS` over the run sheets, shares are `IF(SUM=0,0,x/SUM*100)` (no more `#DIV/0!`), and the compliance rates are `COUNTIFS` over a new *Compliance basis* sheet (one row per run, region and dataset). Before, the compliance rates were numbers computed in Java. Each table has an italic description row, the column headers have hover comments, and the formula results are cached at save time. Criteria are written as escaped literals, so `*`, `?` and `~` in dataset names match literally; Excel compares them case-insensitively, unlike the Java grouping. The *Compliance basis* sheet is skipped when the file is read as the previous comparison. Covered by `ValidationExcelWriterComparisonTest` and the `timestamped-*__validation_comparison` snapshots. On 2026-10-19 the workbook's "Dataset" became **"Sets of data"** (headers, chart axes, descriptions, header notes; one mapping row = model files validated against one constraint set), and the compliance tables' region columns are headed `<region> (%)`. Old files still load as the previous comparison, which is read by column index. *Regenerate comparison* (`ValidationTools.regenerateComparisonXlsx`) failed on large reports with POI's `RecordFormatException` (100 MB per part), because `readStatisticsSheet` / `readTimestampOverview` loaded the whole workbook; they now stream only the sheet they need (`ValidationExcelWriter.readSheetRows`, POI's SAX `XSSFReader`).

**SHACL rule test (moved and rewritten 2026-10-05).** The SHACL Validation tab's *Validate by manual selection* workflow (before that the *SHACL tester* tab) never validated datasets: it checks that each rule fires on its NonConform models and on none of its Conform models. It is now the fourth section of **SHACL ▸ Constraints Operations**, *Test SHACL rules against Conform / NonConform models* (`ShaclRuleTestPane.fxml`, `ShaclRuleTestController`), and the CLI's `validate --workflow manual`, `--shacl-files`, the MCP `shaclConstraintFiles` field, `validate-manual.json` and `pipeline-shape-dev.json` are gone (`--workflow manual` exits 2 with a pointer). `ShaclRuleTester` replaces `ShaclAutoTester`, `SHACLValidationLogger` and `SHACLRuleTestData`, fixing:
- Since `f547550` (2026-03-04) every run threw a `NullPointerException` at the first finding: the rule's `sh:name` was looked up from the shortened source-shape label. Rules are now matched by the source shape node.
- Since `e0c4186` (2026-02-06) reports were cached by file name, so same-named models in different folders shared one report: the full test suite has 554 archives under 82 file names but 111 distinct contents, so at least 29 models were judged on another model's report. Models are now grouped by SHA-256 of their bytes: copies are validated once (111 validations for 554 archives), different content never shares a result.
- A rule folder no shape names, or whose shapes are all deactivated, or with no models, is an *Error*, never a pass. Model archives outside `Conform`/`NonConform` are notes, not a crash (`getName(1)` on a top-level file) or a silent skip. Constraints Jena cannot parse stop the run once.
- A rule passes only when it has models on both sides and nothing in its folder was skipped (security review, 2026-10-05). Otherwise it is *Error* "Not completely tested", or stays *Fail* with the gaps appended. A folder of archives with no `Conform`/`NonConform` folder is a rule that could not be tested. `passed()` also needs no notes. Whether a rule fired is decided by the finding's source shape node; the counts are the per-model report's rows. Cell text is cut to Excel's 32,767 characters instead of aborting the run.
- Only the `.xml` entries of a model ZIP are read (nested ZIPs included), as `SHACLValidator` reads data ZIPs; the maintainer decided a model's files are never `.rdf`.
- Shapes now load with `SHACLValidator`'s import handling (remote imports, fail closed) instead of `ShapeFactory`, which skipped any non-`file:` import silently. Models load through `SHACLValidator` too, so their `.xml` entries are typed as in dataset validation.
- `ExcelTools.exportSHACLValidationToExcel` throws instead of printing to stderr; a report that cannot be written is a note.

Checked on the real suites (copies; the tester writes into the suite): *SHACLTest full* against QoCDC v4.1.4, 191 rules, 554 archives, about 8 min with a 10 GB heap: 151 pass, 22 fail, 18 cannot be tested (11 names not in v4.1.4, 3 rules without models, 4 rules with a second, different `TC2_T1_NonConform_1.zip` loose in the rule folder). 21 of the 22 failures also failed in the November 2025 log. Covered by `ShaclRuleTesterTest` (27 tests; the symbolic-link one is skipped on Windows without the privilege), `ExcelToolsTest`, `ShaclRuleTestWorkbookTest` and `ValidateCommandTest`.

**Static state in ValidationTools (thread safety concern):**  
`exportTurtleValidationReports` and `DEBUG` are `volatile boolean` statics. `DEBUG` is still a real concern for a concurrent server. `exportTurtleValidationReports` is now effectively resolved for known callers: as of 2026-09-25, `setExportTurtleValidationReports(...)` has **zero remaining callers** anywhere in the codebase (verified by repo-wide grep) — the GUI never called it, and the CLI's `ValidateCommand` was the last one, now switched to `MappingValidator`'s per-call `exportTurtleReports` builder option instead of the global switch. The setter and field still exist (the old positional `ValidationTools.validateByMapping(...)` overloads without an explicit boolean still read the static as their default, for any external caller not yet migrated to `MappingValidator`), but nothing in this repo mutates it anymore. Any concurrent HTTP server work should still keep single-threaded execution for `DEBUG`, or migrate it the same way.

### RDF conversion

| Class | Module | What it does |
|---|---|---|
| `eu.griddigit.cimpal.core.converters.RDFConverter` | Core | Format conversion. Call `convert()` then `writeConvertedModel(OutputStream)`. |
| `eu.griddigit.cimpal.core.models.RDFConvertOptions` | Core | Builder-style config. Formats: RDFXML, TURTLE, JSONLD. |

**Base URI and relative identifiers (fixed 2026-10-01).** `RDFConverter` reads every source against the configured base URI, or against the file's own `file:///` IRI when none is set. Writers get that base back. Turtle and TriG write `BASE`, RDF/XML writes `xml:base`, and identifiers such as CIMXML's `rdf:ID` and `#` references are written relative to it. Without a base URI the writer base is the single source file and no declaration is written, so those identifiers stay document-relative. Commits 38cf674 and 2e783ed had switched to reading by file URI, which turned them into `file:///C:/...` IRIs under any base, in every target format. Jena 6 relativises with one fixed rule whenever a writer has a base, and ignores the RDF/XML `relativeURIs` property. Only CimPal's CIMXML writers honour the six `relativeURIs` kinds. `relativeToBase(false)` writes full IRIs. Never pass `""` as a writer base: Jena resolves it against the working directory. Covered by `RDFConverterTest`.

### RDFS to SHACL

| Class | Module | What it does |
|---|---|---|
| `eu.griddigit.cimpal.core.converters.SHACLFromRDF` | Core | Generates SHACL shapes. Call `convert()` then `saveShapeModel(outputDir)`. |
| `eu.griddigit.cimpal.core.models.RDFtoSHACLOptions` | Core | Builder config. Key: rdfsModels (ArrayList<Model>), rdfsModelDefinitions (List<RdfsModelDefinition>). |
| `eu.griddigit.cimpal.core.models.RdfsModelDefinition` | Core | Per-profile metadata: modelName, nsPrefix, nsUri, baseUri, owlImport. |

### Comparison

| Class | Module | What it does |
|---|---|---|
| `eu.griddigit.cimpal.core.comparators.ComparisonRDFSprofile` | Core | Compares two augmented RDFS profiles. |
| `eu.griddigit.cimpal.core.comparators.ComparisonSHACLshapes` | Core | Compares any two RDF files. Universal method. |
| `eu.griddigit.cimpal.core.comparators.ComparisonRDFSprofileCIMTool` | Core | RDFS + CIMTool-style. |
| `eu.griddigit.cimpal.core.comparators.ComparisonInstanceData` | Core | CIM instance data diff (extracted from Main in Phase 4). |
| `eu.griddigit.cimpal.core.interfaces.IRDFComparator` | Core | `compare(Model a, Model b) → RDFCompareResult` |
| `eu.griddigit.cimpal.core.models.RDFCompareResult` | Core | List of `RDFCompareResultEntry`. **Warning:** `hasDifference()` returns true when entries list is EMPTY — naming is inverted. Use `getEntries().isEmpty()` for clarity. |

### ID mapping (branch `feature/id-mapping`, 2026-10-08 to 2026-10-13)

Maps the IDs of one model (model 1, e.g. two EQ files) onto another model of the same grid (model 2, e.g. one merged EQ) and writes a workbook: `Matched` (Model1_ID, Element_type, Model2_ID, Model2_2nd_name, Confidence, Model1_name, Model2_type, Method, Note), `Unmatched` (reason and candidates) and `Summary` (certain / probable / not matched percentages per model, counts per class). GUI: **Dataset ▸ ID Mapping** (`IdMappingTab.fxml`, `IdMappingController`); CLI: `id-map` (`docs/cli/id-map.md`), not exposed through `serve`/`mcp`/`run`.

| Class | Module | What it does |
|---|---|---|
| `eu.griddigit.cimpal.core.idmapping.IdMapper` + `IdMappingOptions` | Core | Builder-style entry point: `new IdMapper(options, log).run()` → `IdMappingResult` (`writeExcel(path)`, `getPfOverview()`). |
| `QueryLibrary` + `resources/idmapping/queries/IdMap01..08_*.rq` | Core | All CIM knowledge is in these SPARQL SELECT queries (roles of classes incl. `breaker`, containment, parameters, dependents with a key, references); the Java matcher only sees their result tables (`GridData`). `--query-folder` overrides single files; `SERVICE` is refused. |
| `CimModelLoader` | Core | Reads RDF/XML, Turtle and N-Triples files or ZIPs (via `ModelFactory.forEachZipEntry`, now public), rewriting cim16 and ucaiug namespaces to CIM100. JSON-LD is refused (remote `@context`). |
| `Network`, `LogicalLine` | Core | Node → substation resolution; substations joined by a transformer or switch grouped into one station; junction substations (only line ends, switches, ≤ 1 busbar section, fewer breakers than line ends: PowerFactory `Term_` points) collapsed into the line; series elements chained through line-interior nodes into logical lines. |
| `SubstationLineMatcher` | Core | Stations by name, lines between matched stations, stations from matched lines and from matched neighbours; lines by parameters only as proposals that two lines must confirm. |
| `TopologyMatcher` + `Refinement` | Core | Seeded colour refinement inside each matched station pair and along matched lines, deepest agreement first; name-contradiction veto; class changes only at full depth; a pair is certain only if a graph anchored by trusted matches alone pins it down; partly confirmed line ends are matched last. |
| `MatchContext.promote()` | Core | After matching: a station whose lines all match one to one and whose inside is ≥ 90 % confirmed becomes certain, then the matches that waited for it. |
| `DerivedMatcher` | Core | Same IDs; terminals, nodes, transformer ends, containers by vote, dependents under matched owners, referenced objects by vote, others by unique name; certainty follows the source. |

Precision first: every rule needs a unique answer on both sides, and a match is `certain` only when the evidence leaves no other reading (`Names.similar` lets a station code such as `AGANYGAL` confirm `Agannygal`). Checked on Relicap with `ModelRewriter` (Core test package: re-IDs a model with ground truth, scrambles or abbreviates names, splits stations, adds `Term_` junctions, removes switches, scales parameters): no certain match was ever wrong; identical copy 99.4 % certain; all changes combined 46.6 % certain, 43.7 % probable (1 wrong). Tests: `IdMapperTest`, `NamesTest`, `CimModelLoaderTest`, `IdMapCommandTest`.

### SPARQL

| Class | Module | What it does |
|---|---|---|
| `eu.griddigit.cimpal.core.utils.SparqlTools` | Core | `executeSparqlQuery(query, model) → QueryResults`. `exportResultsToExcel(results, file)`. |
| `eu.griddigit.cimpal.core.utils.ModelFactory` | Core | `loadCombinedModelForSparql(files, xmlBase)` — loads and merges model files. |

### Instance data generation (extracted from Main in Phase 5)

| Class | Module | What it does |
|---|---|---|
| `eu.griddigit.cimpal.core.generators.InstanceDataBuilder` | Core | `buildFromXls(xmlBase, xlsFile, stripPrefixes, exportExtensions) → BuildResult`. Pure model building from Excel, no JavaFX. |
| `eu.griddigit.cimpal.core.generators.InstanceDataWriter` | Core | `write(Model, Map<String,Object>, OutputStream)`. Pure RDF/XML serialisation, no JavaFX. |
| `eu.griddigit.cimpal.core.generators.ManifestGenerator` | Core | CGMES manifest generation. |

### SHACL tools (extracted from Main in Phase 4)

| Class | Module | What it does |
|---|---|---|
| `eu.griddigit.cimpal.core.shacl_tools.ShaclFromXls` | Core | Excel → SHACL. Map-based overload (CLI); Preferences-based overload delegates to it (GUI). |
| `eu.griddigit.cimpal.core.shacl_tools.ShapeDataBuilder` | Core | `constructShapeData(model, rdfNs, concreteNs)` extracted from Main.ShaclTools. |
| `eu.griddigit.cimpal.core.shacl_tools.ShaclOrganizer` | Core | `splitShaclPerXlsInput(inputXLSdata, List<Model>, Path)` extracted from Main.ShaclTools. |

### Main core (GUI-coupled, wired to Core)

| Class | Module | What it does |
|---|---|---|
| `eu.griddigit.cimpal.main.core.ShaclTools` | Main | Large GUI-coupled class. `splitShaclPerXlsInput` now delegates to `ShaclOrganizer`. |
| `eu.griddigit.cimpal.main.core.ModelManipulationFactory` | Main | Instance data manipulation. `generateDataFromXlsV2` now delegates to `InstanceDataBuilder`. |
| `eu.griddigit.cimpal.main.core.InstanceDataFactory` | Main | Save instance data. `saveInstanceData` now delegates to `InstanceDataWriter` for serialisation. |
| `eu.griddigit.cimpal.main.application.MainController` | Main | Global static state bag. Do not add new static fields. Many tabs use it as a clipboard. |
| `eu.griddigit.cimpal.main.gui.GuiDialogs` | Main | The one place a native file dialog or modal alert is shown (open, openMultiple, save, directory, alert, confirm). `ModelFactory.fileChooserCustom`/`folderChooserCustom`/`fileSaveCustom` and `GUIhelper`'s dialogs go through it. Its backend is swapped only by the GUI smoke tests (TEST-6). |

---

## Development workflow — plans and Claude Code workspace

Added 2026-09-25 by work package A1. Work is planned in Claude Desktop and handed to Claude Code as
work packages in `docs/plans/` (see `docs/plans/README.md` for order, status and open decisions).
The shared Claude Code setup is committed under `.claude/`:
- **`settings.json`:**
  - Allows `mvn` and routine git.
  - `git push` asks for approval every time.
  - Denies `gh release`, `New-ReleaseTag.ps1`, tag and force pushes, and reading token, env,
    credential and certificate files.
  - Its Stop hook, `.claude/hooks/test-changed-modules.sh`, runs `mvn -pl <module> -am test` for
    the modules with changed Java files at the end of each turn. It skips when nothing changed
    since the last green run.
- **Rules:** `rules/security.md`, path-scoped to the security-sensitive classes, and
  `rules/testing.md` for `src/test`.
- **Skills:** `/add-regression-test`, `/security-check-change`, `/end-session`, `/wp-footer`.
- **Agents:** `security-reviewer` (read-only) and `test-writer`.

**CI** (added by CI-1): `.github/workflows/ci.yml` runs `mvn -B verify` on windows-latest and
ubuntu-latest (under Xvfb) for every push and PR to `devel` and `master`. It publishes a per-module
test summary and uploads surefire reports. The JavaFX tests are tagged `gui`; a plain `verify` (CI
too) runs only those also tagged `smoke` (every tab loads; the Main pom's default `excludedGroups`
is `gui & !smoke`). The full set, with each tab's main action, runs nightly (`nightly.yml`, job
"GUI tests (full set)") or locally with `-Pgui-all`; `-DexcludedGroups=gui` skips them all on a
headless machine and then also skips Main's coverage ratchet (profile `no-gui-tests`), since Main's
coverage comes mostly from them. A third job, "Docker image", is described below.

**GUI smoke tests** (added by TEST-6): one `<Controller>SmokeTest` per tab of `CimPalGui.fxml`
under `CimPal-Main/src/test/java/.../application/controllers/`, plus `MainGuiFxmlLoadTest` for the
whole window. Plain JUnit, no TestFX/Monocle: `@GuiSmoke` (tag `gui`, `FxTestSupport` extension,
60 s timeout, same thread) starts the toolkit once, swaps `ScriptedDialogs` in as the `GuiDialogs`
backend, puts an in-memory `Preferences` tree into `MainController.prefs`, and restores every
public static of `MainController`, `System.out`/`err` and the backend afterwards. A file dialog the
test did not script, or a window left open, fails the test. Fixtures are in
`src/test/resources/fixtures/gui-smoke/`.

**Test harness** (added by TEST-1):
- Shared test helpers are in Core's `eu.griddigit.cimpal.core.testsupport`: `TestModels`,
  `Fixtures`, `Snapshots`, `Normalizer` and `StubHttpServer`. They are published as the Core
  `test-jar`, which CLI and Main tests depend on.
- Golden files are under `src/test/resources/snapshots/<feature>/` and are rewritten only with
  `-Dsnapshot.update=true`. `MappingValidatorTest` is the reference example.
- CLI tests (JUnit, AssertJ, JSON Schema validator) run on the classpath.
- JaCoCo runs in every module, and the `CimPal-Coverage` pom module builds the aggregate report.
  `verify` fails when a module drops below the floors in its `coverage-baseline.properties`
  (baseline − 0.5 pp), which `scripts/Update-CoverageBaseline.ps1` maintains.
- CI adds a coverage table per module to the job summary, and the Ubuntu leg uploads the
  `coverage-report` artifact.

**`serve` hardening** (added by SEC-1, closing G1 and G6, and G4 for `serve`):
- `ServeCommand` is now a thin picocli wrapper. `ServeServer` holds the HTTP pipeline, and
  `ServeSecurity` holds the token handling and request checks.
- Every start writes a new 256-bit bearer token to a user-only file: `%LOCALAPPDATA%\CimPal\serve.token`
  on Windows, `~/.cimpal/serve.token` elsewhere. `--token-file` changes the path, and
  `CIMPAL_API_TOKEN` supplies the token instead. Every endpoint except `GET /health` needs it.
- Requests are refused when the Host is not loopback with the right port, when an Origin is not in
  `--allow-origin`, when the path doesn't match exactly, or when a POST body isn't
  `application/json` or is over `--max-body-bytes` (1 MB).
- A bounded queue (`--queue-size` 4, 503 when full) and `--request-timeout` (30 min, 504) limit
  the load. A non-loopback `--host` needs `--allow-remote`.
- `run` steps are an allowlist of the ten `serve` commands. `serve`, `mcp` and `run` can't be
  steps, and a nested `run` is refused.
- `run`, `serve` and `mcp` build their in-process command lines with `CimPalCli.inProcess()`,
  which turns off picocli `@file` expansion.
- Log lines use the shared Core `LogSanitizer`.
- Breaking change: `serve` callers must now send the token header and the `Content-Type` header.

**Allowed roots and no SPARQL SERVICE** (added by SEC-2, closing G2 and G3):
- File paths in `serve`, `mcp` and `run` input must lie under `--root`, `--read-root` or `--write-root`. The defaults are the working directory, plus the pipeline folder for `run`; a home folder or drive root is never an implicit default.
- `PathGuard` (CLI) checks every path key of each command and rewrites it as the checked absolute path. Core `PathPolicy` resolves real paths and refuses traversal, links and junctions that lead outside, UNC paths in any separator mix, device names and NTFS streams.
- An active policy also covers paths Core resolves itself: mapping CSV cells, `owl:imports`, timestamped folder walks, and organizer output. (The manual folder walk went with the CLI's manual workflow on 2026-10-05.)
- Existing output files named in a request need `"overwrite": true`.
- Network `owl:imports` are refused everywhere.
- SPARQL `SERVICE` is refused (`SparqlServicePolicy`) and switched off globally in the CLI, the GUI and `ValidationTools`. Shapes with `SERVICE` are refused before they go to the Python engines, and the Python worker disables rdflib `SERVICE`. Jena 6.2 runs `SERVICE` by default (finding in `SEC-2.md`).
- `serve` now passes `--format json` only to the four commands that support it. Before, the other six endpoints always failed with "Unknown option".

**Characterisation tests, validation group** (TEST-3, written 2026-10-02, merged into devel 2026-10-09):
- `SHACLValidatorTest` and `MappingValidatorTest` pin the current output of `SHACLValidator` and `MappingValidator` (mapping and timestamped) as golden files under `snapshots/shacl-validator` and `snapshots/mapping-validation`.
- They cover CGMES 2.4 and 3.0 presets, empty and malformed input, row errors, result limits, ZIP input and a previous comparison workbook.
- `Snapshots` also normalises sheet names, and has `assertExcelEqualsIgnoringRowOrder`.
- The `ShaclAutoTesterTest` written alongside was dropped, because PR #53 replaced `ShaclAutoTester`, and `ShaclRuleTesterTest` covers the replacement.

**Characterisation tests, rdfs2shacl + excel2shacl + organize** (TEST-3, written 2026-10-02, merged into devel 2026-10-09):
- `SHACLFromRDFTest`, `ShaclFromXlsTest` and `ShaclOrganizerTest` pin the current output as golden files under `snapshots/rdfs2shacl/`, `excel2shacl/` and `organize/`.
- The RDFS inputs are two synthetic profiles in `fixtures/shacl-generation/` (RDFS 2020 with CIM100, RDFS 2019 with CIM16).
- The Core test-jar now also carries `fixtures/**`, which `RdfsToShaclCommandTest` in the CLI uses.

**Characterisation tests, convert + sparql** (TEST-3, written 2026-10-21, branch `feature/test-3-convert-sparql`):
- `RDFConverterTest` (+33 golden tests next to its 10 base-URI tests) pins `RDFConverter` for CGMES 2.4 and 3.0 instance data to Turtle, JSON-LD (legacy and 1.1), RDF/XML plain/abbrev/pretty, CIMXML (unsorted, sorted, by prefix, as instance data) and RDFS CIMXML; the mixed-syntax union; the detailed union (deletion stereotype, package fix, configured URIs); inheritance only/list; add OWL; strip prefixes; ontology headers; `removeDuplicateShDeclareBlankNodes`; empty, malformed and missing input. Snapshots in `snapshots/convert/`: Turtle/JSON-LD by isomorphism, RDF/XML and CIMXML also as text.
- `SparqlToolsTest` (new, 11) pins SELECT results on CGMES 2.4 and 3.0, value rendering, empty results, the two Excel exports and the refusals. Snapshots in `snapshots/sparql/`.
- New fixtures in `fixtures/convert/`: an RDFS 2020 delta in Turtle and an SSH file in JSON-LD (inline context).

**Docker image** (added by CI-3, `docs/plans/ci/CI-3.md`):
- `CimPal-CLI/docker/Dockerfile` copies the built `CimPal-CLI.jar` onto `eclipse-temurin:25-jre-noble`
  (pinned by digest). Maven never runs inside Docker.
- The image runs as `10001:10001` (`cimpal`) in `/data`, which is also the default root for `mcp`
  and `serve`. Any other UID gets a private temporary home. `entrypoint.sh` keeps the base image's
  CA-certificate hook off stdout.
- `release.yml` has a second job, on Ubuntu. It downloads the `CimPal-CLI.jar` just published and
  checks it against the hash the build job recorded, then smoke-tests the image. It then pushes
  `ghcr.io/griddigit-ci/cimpal:<tag>` (and `:latest` when it is the newest release) for
  `linux/amd64` and `linux/arm64`, with SBOM and provenance attestations.
- `ci.yml` builds both platforms and runs `scripts/Test-DockerImage.ps1` on every push and PR.
- The CLI version now comes from the pom (`CliVersion`, a filtered resource), so `--version`,
  `serve` `/health`, the `mcp` `serverInfo` and the image tag agree.
- Usage: `docs/cli/docker.md`. In a container `serve` needs `--host 0.0.0.0 --allow-remote`, the
  token from `CIMPAL_API_TOKEN`, and the same port inside and outside on the host loopback (Host check).
- DEP-1 (`docs/plans/deployment/DEP-1.md`) added the container runtime defaults. The JVM runs with
  `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`, overridable through `JAVA_OPTS`, and an
  out-of-memory run exits 3. VM output goes to stderr. With `--read-only --tmpfs /tmp` every UID gets
  its home in `/tmp`, and without a writable `/tmp` the entrypoint exits 2. The image also carries
  OCI version and revision labels (build arguments), the example configs and the EUPL PDF.
  `Test-DockerImage.ps1` runs validation, `mcp` and `serve` on a read-only root filesystem.

**Resource statistics and sizing** (DEP-2, `docs/plans/deployment/DEP-2.md`):
- `--stats` (and config key `stats`) on `validate`, `sparql`, `compare`, `compare-instances`: a last `stats` field in the JSON object (or a `[STATS]` line on stderr) with wall/phase/CPU time, heap-pool peaks, GC, peak RSS (Linux), triples and bytes loaded. Core `core.stats.RunStats` (no static state), passed via `MappingValidationOptions.runStats`.
- Out of memory always ends with exit 3: `OutOfMemoryRethrow` in `ValidationTools` stops a worker OOM from becoming a failed row; `CimPalCli.main`, `serve` and `mcp` halt via `OutOfMemoryExit`.
- `scripts/bench/` (Python, stdlib): seeded synthetic model generator and benchmark runner (`--find-min-heap`, `--core-sweep`, `--verify`, `--budget` for TEST-5). Results and rule of thumb in `docs/guide/sizing.md` (0.6 GB heap per million triples, 2–4 cores).

**Automation options and Airflow** (DEP-3, `docs/plans/deployment/DEP-3.md`):
- `--summary-file` (config `summaryFile`) on `validate`, `sparql`, `compare`, `compare-instances` writes the JSON document `--format json` prints to a file as well, built once and written atomically (`core.utils.AtomicFiles`); `PathGuard` checks it as a write path under serve/mcp/run.
- `--violations-exit-code` (config `violationsExitCode`, 0..255, default 1) replaces only the "violations/differences found" exit; ignored under serve/mcp/run (an active `PathPolicy`), which keep their own status logic. Shared code in `command/AutomationOptions`.
- `integrations/airflow/`: example DAGs for KubernetesPodOperator, DockerOperator and BashOperator (Airflow 3.3.2), example data from `gen_models.py`, DAG tests; `.github/workflows/integrations.yml` runs them and the Bash DAG end to end. Guide: `docs/guide/airflow.md`.

**External user guide** (DEP-4, `docs/plans/deployment/DEP-4.md`): `docs/guide/` is the task-oriented layer on top of `docs/cli/`.
- Pages: index, capability statement, install, quickstart, deployment modes, sizing, Airflow, security, configuration, troubleshooting, versioning and support (draft, for maintainer review), glossary.
- Linked from the root `README.md`. `scripts/check_doc_links.py` checks the links in `.github/workflows/docs.yml`.

**MCP output hygiene and the Python interpreter** (SEC-3, `docs/plans/security/SEC-3.md`, gaps G5 and G9):
- Every `mcp` tool result passes through `McpCommand.forAgent`: each string value and field name of the
  command's JSON (or of the envelope for commands without JSON output, and of refused-path errors) goes
  through Core `UntrustedText.forAgent` (C0/C1 controls, NEL/LS/PS, invisible format characters such as
  bidi overrides and tag characters, variation selectors and Hangul fillers → U+241E, cut at 300 chars with
  `...`, no split surrogate pairs). A second content item carries a fixed untrusted-data notice;
  `content[0].text` is still the result JSON. JSON-RPC error messages are capped the same way.
- `sparql`, `compare`, `compare-instances` and `run` escape every control character in their JSON
  output (they share `ValidateCommand.jsonStr`); a literal with an ESC character made `sparql --format json`
  print invalid JSON before.
- The Python engines' interpreter: `--python-executable` on `validate`, `serve` and `mcp` (CLI mixin
  `PythonOptions`), `-Dcimpal.python.executable` or `CIMPAL_PYTHON_EXECUTABLE`, in that order, always an
  absolute path of an existing local file, checked after normalising and returned as checked; UNC and
  device paths are refused before any file system call (Core `PythonExecutable`; otherwise exit 2). No
  config key, request field or tool argument sets it. Without one, CimPal finds `python` / `py` in the
  absolute `PATH` folders itself (`PythonShaclValidator.searchDirs`/`findProgram`, so Windows never
  searches the working directory), probes it with a 10 s timeout, and runs the absolute `sys.executable`
  it prints. One `[ENGINE]` stderr line names the interpreter and its source.
  The worker script lives in a private `Files.createTempDirectory` folder, which is also the working
  directory of the probe and the worker, and is removed after the run (also after a failure). Python's
  stderr is read in fixed-size chunks (at most 64 KiB kept) and reaches exception messages only as its
  last 500 chars through `LogSanitizer.forLog`, also when Python stopped reading its input (broken pipe).
  `mcp`'s own stderr lines quote parser messages through `forLog`.

**Deployment track** (`docs/plans/deployment/`, DEP-1 to DEP-10): container, sizing, CLI automation
options, an async `/v1` API, a Python SDK and an Airflow provider for external users. It supersedes
the ordering of the *REST API — discovery and implementation plan* section below.

**Next steps:** merge SEC-1 ([PR #44](https://github.com/griddigit-ci/CimPal/pull/44)), then SEC-2, following the
phase order in `docs/plans/README.md`. Enabling branch protection with the two CI checks as
required is a maintainer action. CI-3 is in review ([PR #51](https://github.com/griddigit-ci/CimPal/pull/51)): its
"Docker image" job needs a first green run, and after the first release that pushes the image the
GHCR package must be made public. DEP-1 and DEP-2 are done and released in 2026.10.6.1. DEP-3
(automation options and Airflow examples) is merged (PR #60) and goes out with the next release; the
KubernetesPodOperator and DockerOperator end-to-end walkthrough is for the maintainer. DEP-4 (external
user guide, `docs/guide/`) is merged (PR #61); its support policy awaits maintainer review. DEP-5
(async `/v1` job API and OpenAPI spec) is merged (PR #62), and DEP-6 (service deployment: proxy names,
token files, probes, SIGTERM drain, JSON logs, metrics, Compose and Kubernetes examples) is merged
(PR #64). DEP-7 (file exchange: uploads, job workspaces, downloads) is merged (PR #66), and so are
TEST-3's validation and SHACL generation groups (PR #65) and `validate --workflow combined` with
folders (PR #67). DEP-8 (`serve --workers N`, concurrent commands in one JVM, decision D-8 yes) is
merged (PR #69), and so is DEP-9 (Python SDK `cimpal-client` in `clients/python/`, Apache-2.0 per D-11,
PR #70). DEP-10 (Airflow provider `airflow-provider-cimpal` in `clients/airflow/`, Airflow 3.2+ per D-12)
is merged (PR #71); PyPI publishing for both Python packages is in PR #72. Next: phase D3, making the REST
service (option 2) a shared production service: `docs/plans/deployment/OPTION-2-ROADMAP.md` lists the gaps
and the order (a release first, then DEP-13 client identity, DEP-11 durable job store, DEP-14 idempotency and
webhooks, DEP-16 load tests, DEP-12 shared queue across replicas, DEP-15 job isolation). DEP-13 (named tokens,
job and upload ownership, per-client quotas, D-17) is merged (PR #75). DEP-11 (durable job store in PostgreSQL,
D-14, D-18) is merged (PR #76); its PostgreSQL ITs run only where Docker is available (CI). DEP-14
(idempotent submits and uploads, signed completion callbacks, D-19) and DEP-16 (load tests, SLOs, alert rules;
tooling done, the full capacity runs still open, they need Docker on the measuring laptop) are merged (PR #79,
DEP-16 via #81). DEP-12 (shared queue across replicas) is in progress. SEC-3 (MCP output hygiene, the
Python interpreter by absolute path) is in review on `feature/sec-3-mcp-hygiene`; SEC-4 (re-attestation)
can follow once SEC-1 to SEC-3 are merged.
TEST-3's convert and sparql groups are merged (PR #84); the comparisons, gen-instances + manifest and kgcl
groups are next. CimPal-CustomWriter has no coverage ratchet baseline yet (follow-up).
TEST-4 (CLI contract, packaged JAR and MCP protocol tests, with the MCP JSON-RPC and `serve` missing-root
fixes) is ready for a PR on `feature/test-4-mcp-serve-run-tests`; its suspected bugs (`sparql` syntax error
and `gen-instances` non-template exit 3, the `manifest` catalog identifier) are follow-ups (see *Known issues*).
The SHACL rule test rewrite ([PR #53](https://github.com/griddigit-ci/CimPal/pull/53), branch
`feature/shacl-rule-tester`) needs a review; whether the rule test should come back to the CLI/MCP,
with SEC-2 path checks and a JSON summary, is open.

---

## Implementation status — all commands done

### Commands implemented (Phases 2–9)

| Phase | Commands | Notes |
|---|---|---|
| 2 | `validate`, `sparql`, `manifest` | Core is already clean; zero refactoring needed |
| 3 | `convert`, `rdfs2shacl`, `compare` | Core is clean; namespace auto-extraction for rdfs2shacl |
| 4 | `compare-instances`, `excel2shacl`, `organize` | Required Core extractions; commons-lang3/math3 added |
| 5 | `gen-instances` | Required InstanceDataFactory/Builder extraction |
| 6 | `run` | Pipeline runner: temp config file → picocli in-process |
| 7 | `validate --samples` | Per-shape JSON breakdown via TTL report parsing |
| 8 | `serve` | Local HTTP daemon (JDK HttpServer, localhost:7474), bearer token, Host/Origin checks, bounded queue |
| 9 | `mcp` | MCP 2024-11-05 stdio server, 10 typed tools |

`id-map` (2026-10-13, branch `feature/id-mapping`) is the 14th: ID mapping between two models of the same grid, see *ID mapping* above.

All 14 commands in a single fat JAR (`CimPal-CLI/target/CimPal-CLI.jar`), no external runtime dependencies beyond JRE 25. Since TEST-4 the contract of every command is tested in-process (`CommandContractTest`, `ConfigFileEquivalenceTest`, per-command tests, JSON Schemas in `CimPal-CLI/src/test/resources/fixtures/cli-json/`) and from the shaded JAR (`PackagedJarIT`, failsafe). Each release also publishes that JAR as the Docker image `ghcr.io/griddigit-ci/cimpal` (see `docs/cli/docker.md`).

### Python SDK (`clients/python/`, DEP-9)

`cimpal-client` (import `cimpal_client`, Python 3.10+, **Apache-2.0**, unlike the EUPL engine) calls the `/v1` API. Its version is `<cimpal.version>` from the root `pom.xml`, read by hatchling at build time.
- `_generated/`: generated from `cimpal-v1.json` by a pinned `openapi-python-client` (`scripts/regenerate.py`; CI runs `--check`). The generator can't do `application/zip`, so uploads and the archive download are missing there.
- `CimPal` / `AsyncCimPal` (facade): their own httpx requests, returning the generated models. One place for problem-details errors, retries (503 and never-sent requests; other transport errors only for GET, so a job is never submitted twice) and streaming downloads (output names checked to stay in the target folder).
- Tests: respx unit tests, and integration tests that start `java -jar CimPal-CLI.jar serve`. CI: the `python-sdk*` jobs in `integrations.yml`.
- Publishing: `release.yml` builds both Python packages through the reusable `python-dists.yml`: the tagged commit must be on master and the pom version must equal the tag; the toolchain is hash-locked (`.github/python-build-requirements.txt`, builds without isolation); `twine check --strict` and the provider's pin are checked. After the upload, the job checks that PyPI holds exactly the built files. It publishes them in its `pypi` job with PyPI trusted publishing, after a maintainer approves the `pypi` environment. `testpypi.yml` is a manual rehearsal with `.dev<run>` versions. Both trusted publishers must be registered on (Test)PyPI once (`clients/python/README.md`, Releasing).

### Airflow provider (`clients/airflow/`, DEP-10)

`airflow-provider-cimpal` (import `cimpal_provider`, **Apache-2.0**, Airflow 3.2+) is built on `cimpal-client`, which it requires at exactly its own version (a hatch build hook pins it).
- **Contents:**
  - the `cimpal` connection type, declared in `get_provider_info()` (`conn-fields`, `ui-field-behaviour`, read by Airflow 3.2+);
  - `CimPalHook`;
  - `CimPalJobOperator` and `CimPalValidateOperator` (polling or deferrable; small XCom; `fail_on_violations`; `on_kill`);
  - `CimPalJobTrigger` (an absolute deadline, so a restarted triggerer doesn't extend it);
  - `CimPalJobSensor`.
- **Errors:** 401/403/400/404/410 and job exit 2 raise `AirflowFailException` (no retries); busy, timeouts and exit 3 raise `AirflowException`.
- **`_logic.py`:** everything that needs no Airflow, tested on Windows too. Its `PATH_FIELDS` mirrors `PathGuard.FIELDS`, and `tests/test_logic.py` parses `PathGuard.java` to keep the two equal.
- **Windows:** Airflow doesn't run there (no `fork`), so the Airflow tests run only in CI. That is the `airflow-provider` job on Airflow 3.2.2 and 3.3.2, including `dag.test()` (polling and deferrable) against a real `serve`.

### How `serve` and `mcp` execute commands (shared mechanism)

Both `ServeCommand` and `McpCommand` use the same in-process execution pattern:
1. Write the request body / tool arguments to a temp JSON file
2. Call `new CommandLine(new CimPalCli()).execute(args)` in-process with `--config <tempfile>`
3. Capture `System.out` during execution to a `ByteArrayOutputStream`
4. Return the captured output as the HTTP response body / MCP content block
5. Delete the temp file in a `finally` block

This means **all existing CLI validation, option parsing, and config loading is reused automatically** — no duplicated logic in the server layers.

### Phase 7 detail — per-shape JSON breakdown

When `validate --format json --samples N` (default N=3 in JSON mode):
- `ValidationTools.setExportTurtleValidationReports(true)` is called before the run
- After validation, `*__report.ttl` files in `outputDir` are parsed with Jena
- TTL format: all values stored as plain string literals (via `addLiteral`) — not URI resources
- Aggregated per (sourceShape, sourceConstraintComponent): count + bounded focus nodes
- Included in JSON as `"shapes": [...]`, sorted by count descending
- Source: `ValidateCommand.extractShapeGroups()` — all logic in the CLI, zero Core changes

---

## Change log

One row per bigger implementation, newest first (CLAUDE.md, *After every implementation*). `Skill` = yes when the change affects how CimPal is run or explained; the cimpal skill (`Claude_contents\CimPal\cimpal-skill`) and the Desktop project notes are refreshed from these rows. Rows before 2026-10-20 were seeded from the WP files.

| Date | WP / PR | What changed for users | Docs | Skill |
|---|---|---|---|---|
| 2026-10-22 | TEST-4 | `mcp` JSON-RPC errors follow the spec: a message without `method` is -32600 (was -32601); a failing handler is -32603 with a generic message (was -32700 "Parse error" with id null); notifications (no `id`) are never answered and never run a tool (a `tools/call` notification used to run it); `"id": null` and non-object messages (batches) are -32600; client responses are ignored; invalid JSON (also trailing content) stays -32700 with id null; a stack overflow in a tool (deeply nested input) is -32603 instead of ending the server; `--debug` lines are sanitised. `serve` with a `--root`/`--read-root`/`--write-root` that doesn't exist now exits 2 at start (it used to create it and start). The `--format json` documents of `validate`, `sparql`, `compare`, `compare-instances` and `run` have published JSON Schemas (`CimPal-CLI/src/test/resources/fixtures/cli-json/`). New tests: `CommandContractTest`, `ConfigFileEquivalenceTest`, `PackagedJarIT` (the shaded JAR, in `mvn verify` on both CI OSes), per-command contract tests, an MCP `tools/call` per tool. `manifest` on Linux/macOS: an absolute `--access-url` or `--dir` (the default) now gives only its last folder name in `dcat:accessURL`, as documented and as on Windows; before, the whole local path (e.g. `file:/srv/.../models/EQ.xml`) went into the manifest. | `docs/cli/mcp.md`, `docs/cli/serve.md`, `docs/cli/README.md`, TEST-4, TEST-3 matrix, plans README | yes |
| 2026-10-22 | Fix (refused uploads) | `serve`: an upload refused before its body is read because the work area is full (507) now reads and drops up to 16 MiB of the rest of the body before answering, so the client receives the error. Before, the connection was closed with the body unread, and on Linux the reset could lose the answer ("header parser received no bytes"); this made `FileExchangeApiTest.theWorkAreaBudgetIsEnforced` fail now and then on Ubuntu CI. Larger or chunked bodies, and every 413, still get `Connection: close` without being read (security review: never read a body already refused as too large). The drain is bounded by `--request-read-time` (tested with a stalled body) | cli/serve.md, PROJECT.md | no |
| 2026-10-21 | SEC-3 | `mcp` tool results: every string value is cut at 300 characters (ending in `...`) and control characters and line breaks show as `␞`; a second content item says the result is untrusted data (`content[0].text` is still the JSON). New `--python-executable PATH` on `validate`, `serve` and `mcp` (also `CIMPAL_PYTHON_EXECUTABLE`, `-Dcimpal.python.executable`): an absolute path of an existing local file (UNC and device paths refused), else exit 2; never a config key or tool argument. Without it, the `python`/`py -3` found on PATH is resolved to its absolute `sys.executable`, and a `[ENGINE]` stderr line names the interpreter. The worker script runs from a private temp folder removed afterwards; Python stderr in errors is cut to its last 500 chars on one line. `sparql`, `compare`, `compare-instances` and `run` JSON output now escapes every control character (an ESC in a literal made invalid JSON) | cli/mcp.md, cli/validate.md, cli/serve.md, guide/security.md, PROJECT.md, plans | yes |
| 2026-10-21 | TEST-6 | No behaviour change for users. GUI smoke tests: every tab (13, incl. ID Mapping) loads, and Convert, RDF Comparison, Dataset Comparison, SHACL Validation, rule test and SPARQL run their main action headlessly; new `GuiDialogs` seam for file dialogs and alerts. Developers: `mvn verify` runs the `smoke` subset, `-Pgui-all` the full `gui` set (nightly job), `-DexcludedGroups=gui` skips them and Main's coverage ratchet | PROJECT.md, CLAUDE.md, plans/testing/TEST-6.md, plans/README.md, ci.yml, nightly.yml | no |
| 2026-10-21 | TEST-3 (convert + sparql) | No behaviour change: golden-master tests for `RDFConverter` and `SparqlTools` (snapshots `convert/`, `sparql/`), two suspected bugs recorded as `@Disabled` tests (C1 orphan enum member labels in the detailed union, C2 JSON-LD "ordered" ignored) | PROJECT.md, plans/testing/TEST-3.md, plans/README.md | no |
| 2026-10-21 | ID mapping (branch `feature/id-mapping`) | New `id-map` command and GUI tab **Dataset ▸ ID Mapping**: maps the IDs of model 1 onto model 2 of the same grid and writes Matched (Model1_ID, Element_type, Model2_ID, Model2_2nd_name, Confidence, …), Unmatched and Summary sheets with certain / probable / not matched percentages; SPARQL query library in `CimPal-Core/src/main/resources/idmapping/queries`; config template `configs/id-map.json`; not exposed through serve/mcp/run | cli/id-map.md, cli/index.md, configs/id-map.json | yes |
| 2026-10-20 | DOC-2 | `manifest --config` (keys `dir`, `files`, `accessUrl`, `output`), so `manifest` works through `mcp`, `serve` and `run`; MCP tool schemas and the OpenAPI document list every key each command reads, plus `overwrite` where a command writes a file (a test keeps them equal to the commands' config loaders); `validate` takes `incrementalTimestamps` from a config and `--dry-run` shows it; through serve/mcp/run, `manifest`'s files, scanned files and default output are checked against the allowed roots (security review), and file names in `dcat:accessURL` are percent-encoded. Docs: the mapping CSV format is commas between columns and `;` inside them (the `|` example was wrong), all 13 commands and every template listed, compare's `N/A` wording, the MCP agent loop without the manual workflow, a new root README feature list | cli/README.md, cli/index.md, cli/manifest.md, cli/mcp.md, cli/validate.md, cli/compare.md, cli/serve.md, cli/ci-pipeline.md, README.md, configs/manifest.json, configs/validate-mapping-cgmes30.json | yes |
| 2026-10-20 | Desktop (no code) | Change log introduced; drift found while running CLI 2026.10.6.1 recorded under *Known issues*; DOC-2 written | PROJECT.md, CLAUDE.md, plans | yes |
| 2026-10-19 | DEP-16 (PR #81 → #79) | `/metrics` adds `cimpal_http_responses_total{code}`, `cimpal_http_request_duration_seconds{kind}`, `cimpal_job_queue_wait_seconds`, `cimpal_queue_size`, `cimpal_stalled`, `cimpal_work_used_bytes`/`_max_bytes`; service levels (99.5 % availability, API p95 < 200 ms, queue wait p95 < 5 min); Prometheus alert rules in `deploy/monitoring/`; load tool `scripts/bench/load/`; preliminary service capacity figures | cli/serve.md, guide/service.md, guide/sizing.md, deploy/monitoring | yes |
| 2026-10-19 | DEP-14 (PR #79) | `Idempotency-Key` on `POST /v1/jobs` and `/v1/uploads` (`--idempotency-ttl`); opt-in HMAC-signed completion callbacks (`callback.url`, `--callback-secrets`, `callbackAllow` in `--client-quotas`); SDK `verify_callback()`, automatic keys in SDK and Airflow provider | cli/serve.md, guide/python-sdk.md, guide/airflow.md, guide/security.md | yes |
| ≤ 2026-10-19 | DEP-13 (PR #75) | Named clients `name:role:token` in `--token-from-file`; per-client ownership of jobs/uploads (404 for others); `--client-quotas` (maxQueued, maxRunning, maxUploadBytes, maxWorkBytes, requestsPerMinute → 429/413/507); admin-only `/shutdown`, `/metrics`, `?owner=` | cli/serve.md, guide/service.md | yes |
| 2026-10-19 | DEP-11 (PR #76) | `--job-store jdbc` PostgreSQL (`--job-store-url/-user/-password-file/-pool`, `CIMPAL_JOB_STORE*`); jobs, results, logs, uploads survive restarts; a job that was running ends `failed` with the error "Interrupted: …" unless `--requeue-interrupted` queues it again; one instance per database | cli/serve.md, guide/configuration.md | yes |
| ≤ 2026-10-19 | DEP-10 (PR #71, #72) | `airflow-provider-cimpal` (Airflow 3.2+): connection type, hook, `CimPalJobOperator`, `CimPalValidateOperator`, trigger, sensor; PyPI publishing | guide/airflow.md | yes |
| ≤ 2026-10-19 | DEP-9 (PR #70) | Python SDK `cimpal-client` (`CimPal`, `AsyncCimPal`: upload, submit, wait, result, log, cancel, downloads) generated from the OpenAPI document | guide/python-sdk.md | yes |
| ≤ 2026-10-19 | DEP-8 (PR #69) | `serve --workers N` (≤64) with heap admission `--max-concurrent-heap-bytes`; per-job path policy, caches and output | cli/serve.md, guide/sizing.md | yes |
| ≤ 2026-10-19 | DEP-7 (PR #66) | File exchange: `POST /v1/uploads` (ZIP), `inputUpload` jobs, `/v1/jobs/{id}/files`, `/archive`; `--work-root`, `--no-file-exchange`, `--max-upload-bytes`, `--upload-ttl`, `--work-max-bytes`, `--request-read-time` | cli/serve.md | yes |
| ≤ 2026-10-19 | DEP-6 (PR #64) | Service deployment: `--allowed-host`, `--token-from-file` with reload, `/ready`, SIGTERM drain `--shutdown-grace`, `--log-format json`, `--metrics`, Compose/Kubernetes examples in `deploy/` | guide/service.md, cli/serve.md | yes |
| ≤ 2026-10-19 | DEP-5 (PR #62) | Async `/v1` job API (`POST/GET /v1/jobs`, result, log, cancel), OpenAPI 3.1 at `/v1/openapi.json`, RFC 9457 errors, `--job-timeout`, `--max-jobs`, `--job-ttl`, `--job-log-lines`, `--max-result-bytes` | cli/serve.md | yes |

---

## Technical decisions

### JSON not YAML for config files

JSON chosen because: no extra dependency, Jackson 3 tree-model works without reflection/`opens`, `_note_*` keys provide documentation inline. YAML can be added later via `jackson-dataformat-yaml`.

### `_comment` and `_note_*` as documentation keys

The Jackson `readTree()` + `.path("key")` pattern silently ignores unrecognized keys. Any key starting with `_` can be used freely in config files for documentation.

### Exit codes

0 = success/no violations, 1 = violations found (not an error), 2 = bad input, 3 = internal error. CI scripts must distinguish 1 from 2+. Exit 1 is a validation result, not a tool failure.

### stdout/stderr split for JSON mode

`--format json` redirects `System.out` to `System.err` before the command runs (ValidationTools prints progress to System.out). JSON summary is printed after the run to the real stdout. This keeps JSON clean for piping.

### `hasDifference()` is inverted (known gotcha)

`RDFCompareResult.hasDifference()` returns `true` when entries list is EMPTY. Use `result.getEntries().isEmpty()` explicitly. This is in the original source; do not try to fix it without updating all callers.

### `serve` job API (`/v1`, DEP-5)

`POST /v1/jobs` queues a command on the same workers and the same queue slots as the
synchronous endpoints, and answers 202 with a job id (random UUID). Classes in
`eu.griddigit.CimPal.cli.command`:
- `JobManager`: the in-memory store, with TTL and `--max-jobs` eviction and `--job-timeout`.
- `Job`: synchronized state transitions, and the result, kept up to `--max-result-bytes`.
- `JobLog`: a ring buffer of progress lines.
- `StderrTee`: the job's `System.err`, bound to the job's threads through `CliStreams` (DEP-8): it
  copies their lines into the job's log and passes them on. Lines from the server's own threads are
  skipped.
- `Problem`: RFC 9457 problem+json bodies.
- `CommandSchemas`: the per-command config schemas, shared by `mcp` and the OpenAPI document.

The OpenAPI 3.1 document is `CimPal-CLI/src/main/resources/openapi/cimpal-v1.json`, served at
`/v1/openapi.json`. `OpenApiSpecTest` checks that it is valid, that its routes equal
`ServeServer.V1_ROUTES`, and that its config schemas equal `CommandSchemas`; regenerate the schemas
with `-Dopenapi.update=true`. `JobApiTest` checks that every documented response is exercised.

### `serve` as a service (DEP-6)

What makes `serve` deployable behind a proxy (`docs/guide/service.md`, `deploy/`):
- **Host check:** `--allowed-host` adds public names (`ServeSecurity.isAllowedHost`, `allowedHostEntry`).
- **Tokens:** a `TokenSource` holds them: a fixed token, or `FileTokens` for `--token-from-file` /
  `CIMPAL_API_TOKEN_FILE`. That file is read-only to `serve`, holds up to two tokens, and is
  reloaded lazily by an `InstantSource`; on a failed reload the tokens read last stay in force.
- **Probes:** `/ready` reports readiness.
- **Metrics:** `/metrics` (with `--metrics`) is Prometheus text, written by hand in `ServeMetrics`.
- **Logs:** everything goes through `ServeLog`, as text or as JSON lines. In JSON mode,
  `System.err` is wrapped, so the commands' own lines become `output` events.
- **SIGTERM:** a `sun.misc.Signal` handler (`ServeSignals`, `requires jdk.unsupported`) runs the
  `/shutdown` path. `ServeCommand` waits up to `--shutdown-grace` and exits 3 if a command is
  still running.
- **Tests:** `ServeSignalIT` (failsafe, POSIX only) forks a JVM with `ServeSignalItMain` to test
  the drain. Failsafe needs `package`, so it doesn't run on this Windows machine while Claude
  Desktop locks the JAR.

### `serve` file exchange (DEP-7)

Uploads and downloads for callers without a shared file system (`docs/cli/serve.md`, *File exchange*):
- **Extraction:** Core's `ZipExtractor` extracts an untrusted ZIP to a new folder.
  - Names: `safeZipEntryName`, plus device names, trailing dots and blanks, and case-insensitive duplicates.
  - Size: `ZipBudget` limits and a 100:1 ratio check.
  - Writes: `CREATE_NEW`/`NOFOLLOW_LINKS` with a real-path check of each folder; the folder is removed on any failure.
- **Path isolation:** `PathPolicy.Builder.exclude` refuses a folder inside the roots, unless a root itself lies inside it (the more specific root wins).
  - The shared policy of `serve` excludes `--work-root`.
  - The policy of an upload job (`RootOptions.jobPolicy`) reads its upload and the `--read-root`s, writes its `out/`, and excludes the rest of the work area.
- **`WorkArea`** (CLI) owns `<work-root>/uploads` and `jobs/<jobId>/out`.
  - It holds the in-memory metadata, the disk budget (reserved before streaming) and the upload TTL (not while a job uses the upload).
  - A workspace is deleted when `JobManager` evicts its job (`onRemoved`). Both folders are emptied at start.
- **ServeServer:**
  - `POST /v1/uploads` is the only non-JSON POST.
  - `inputUpload` gets a workspace prepared under a pre-allocated job id before the path check (`PathGuard.placeOutputs` puts relative or missing outputs under `out/`).
  - The download path is split from the raw URI and each segment decoded on its own.
  - Runners and checks get the workspace through default-method overloads, so existing callers are unchanged.
- **Request time:** `--request-read-time` sets `sun.net.httpserver.maxReqTime` (JVM-wide, read once).

### `serve` workers (DEP-8)

Commands run on `--workers` threads (`ServeServer`, default 1), with a bounded queue and a pool of HTTP handler threads in front of it. What a command touches is per run, and the threads it starts inherit it (`InheritableThreadLocal`, closed when the run ends):
- `PathPolicy.runWith`: the path policy. `serve` holds `PathPolicy.requireContext()`, so a check on a thread without a policy (or with an ended one) is refused rather than unrestricted.
- `RunScope.runIn` (Core): the remote-import and XML download caches of `ValidationTools`; without a scope (GUI, plain CLI) the process-wide caches are used. Downloads go to a unique temp file per call, under a per-URL lock.
- `CliStreams.bind` (CLI): `System.out`/`System.err` are routing streams installed once; a bound thread writes to its job's buffer and log. It replaced every `System.setOut`/`setErr` swap in `serve`, `mcp`, `run` and `validate --format json`. The label of the binding is the job id in the JSON log.
- `AdmissionGate`: a command starts only while the estimated heap (10× input size, at least 64 MiB, `ServeCommand.estimateHeap`) of the running ones plus its own fits `--max-concurrent-heap-bytes`; FIFO; a command alone always starts.
- `MutableStaticsTest` fails on any new non-final static field in Core or the CLI that isn't on its reviewed list, and on any static field holding threads (executor, thread, timer, `HttpClient`): such threads would outlive the job that created them and carry its streams and path policy into another job (DEP-13); `ConcurrencyIT` (failsafe) compares 24 mixed jobs under four workers with one.

### `serve` job store (DEP-11)

`ServeServer` keeps `/v1` jobs in a `JobStore`: `MemoryJobStore` (the default, the former `JobManager`) or `JdbcJobStore` (`--job-store jdbc`, PostgreSQL, D-14). The store is the source of truth for a job's status; `JobScheduler` (in memory, per process) still decides which queued job runs next.
- **Transitions** are single conditional `UPDATE`s (`… WHERE id = ? AND status = 'queued'`); every SQL text is fixed and prepared (`JdbcJobStoreTest.everyStatementIsPreparedFromFixedText`).
- **Schema:** `db/V1__jobs.sql`, applied by `JdbcMigrator` (`cimpal_schema_version`; a newer schema is refused). Tables `cimpal_jobs` (with `owner`, `config`, `upload_id`, and `node`/`lease_until` for DEP-12), `cimpal_job_log`, `cimpal_uploads`. Plain SQL that H2 in PostgreSQL mode also runs (the unit tests).
- **Connections:** `JdbcPool`, a small fixed pool; the password only ever goes to the driver (`--job-store-password-file` or `CIMPAL_JOB_STORE_PASSWORD[_FILE]`, never the command line; a URL with `password=` is refused).
- **Restart (D-18):** `recover()` fails running jobs as "Interrupted" (or requeues them with `--requeue-interrupted`) and returns the queued ones, which `ServeServer.requeue` hands to the workers. `WorkArea` is not emptied in durable mode: `restore()` matches upload and job folders with the database (deletes what has no record, forgets records without folders, answers 410 for lost outputs). A durable `close()` leaves queued jobs queued.
- **Failures:** `JobStore.Unavailable` → 503 + `Retry-After` (`ServeServer.route`/`storeUnavailable`), `/ready` 503 while `usable()` is false, `cimpal_job_store_errors_total`.
- **Logs** go to an in-memory ring while a job runs (read from there) and to the database in batches of 64 and at the end.
- **Tests:** `JobApiTest`, `FileExchangeApiTest` and `ClientIsolationTest` run on both stores (`@ParameterizedClass` over `TestStores`, H2); `JdbcJobStoreTest`, `JobStoreRestartTest`, `JobStoreOutageTest`; `JdbcJobStorePostgresIT` and `JobStoreRestartPostgresIT` repeat the store and restart tests on PostgreSQL 17 via Testcontainers (skipped without Docker).

### `serve` idempotency and callbacks (DEP-14)

- **`Idempotency-Key`** on `POST /v1/jobs` and `/v1/uploads` (`ServeServer.submitOnce`, `receiveUpload`): an `IdempotencyStore` per `JobStore` (memory, or the `cimpal_idempotency` table, schema V2) maps (client, key) to the request's fingerprint (SHA-256 of method, path and body or upload bytes) and the job or upload id, reserved *before* the job is made. A repeat gets that job (202) or upload (201); another request with the key 422; one still in progress 409 + `Retry-After: 1`; a key whose job exists but wasn't completed (a crash in between) gives that job; a refused request gives its key back; a reservation older than `IdempotencyStore.STALE` (10 min) is free. `--idempotency-ttl` (24 h).
- **Clients:** the SDK sends a fresh key per `submit()`/`upload()` call (or `idempotency_key=`) and then retries those POSTs like a GET (`RetryPolicy` with `idempotent`), including 409 + Retry-After. The Airflow provider's key is a hash of DAG, task, run, try and map index (per try, as decided).
- **Callbacks (D-19):** `Callbacks` validates `callback.url` at submit (https, no user or fragment, under the client's `callbackAllow` prefixes from `--client-quotas`, a signing secret from `--callback-secrets`, public addresses only via Core's `EgressPolicy`, which `ValidationTools` now uses too). A worker thread arms callbacks of jobs that became final, delivers due ones (signed `CimPal-Signature: t=…,v1=HMAC-SHA256(secret, t.body)`, `Redirect.NEVER`, the address check again before every attempt), and retries 10 s doubling to 1 h for 24 h. `CallbackStore` (memory, or `cimpal_callbacks`) records attempts; `GET /v1/jobs/{id}` shows `callback`; `cimpal_callbacks_total{outcome}`. The SDK's `verify_callback()` checks signatures; a shared test vector keeps both sides in step.
- **Tests:** `IdempotencyTest` and `CallbackTest` (both stores), additions to `ClientIsolationTest`, `JobStoreRestartTest`, `ClientQuotasTest`; SDK `test_callbacks.py` and retry tests; provider key tests.

### `serve` load tests and SLOs (DEP-16)

- **Load tool** (`scripts/bench/load/`): `run.py` drives `/v1` with `AsyncCimPal` (open Poisson or closed load; scenarios small/medium validation, SPARQL, upload round trip), probes `GET /v1/health` and `/v1/jobs/{id}`, samples `/metrics`, and writes CSVs, `summary.json` and `report.md`; `--start-server` launches the JAR pinned to N CPUs. `sweep.py` runs the capacity experiments and publishes to `docs/guide/sizing-data/`.
- **Metrics added to `ServeMetrics`:** `cimpal_http_responses_total{code}` (429/503 apart), `cimpal_http_request_duration_seconds{kind=api|transfer|command}` (in `ServeServer.handle`), `cimpal_job_queue_wait_seconds`, `cimpal_queue_size`, `cimpal_stalled`, `cimpal_work_used_bytes`/`_max_bytes`.
- **SLOs** (`docs/guide/service.md`, Service levels) and **alert rules** (`deploy/monitoring/`, promtool-tested in `integrations.yml`). **Nightly** (`nightly.yml`): a one-minute load smoke with `--fail-on-slo`.

### `serve` clients (DEP-13)

Every token stands for a client (`TokenSource.Client`: a name and admin or not). A fixed token, `CIMPAL_API_TOKEN` and a bare token line are client `default`, an admin, so single-token setups behave as before. A token file line `name:role:token` names a client (D-17).
- **Ownership:** `Job.owner` and `WorkArea.Upload.owner`. `ServeServer.requireClient` authenticates every request and stores the name on the exchange for the log. Every `/v1/jobs/{id}…` and `/v1/uploads/{id}` route answers the same 404 for another client's object as for a missing one; `WorkArea.prepare(jobId, uploadId, owner)` refuses another client's upload. `/shutdown` and `/metrics` need an admin; `GET /v1/jobs` lists the caller's jobs, or any client's for an admin (`?owner=`).
- **Quotas:** `ClientQuotas` (`--client-quotas` JSON, read at start): `maxQueued` (429), `maxRunning`, `maxUploadBytes` (413), `maxWorkBytes` (507, counted per owner in `WorkArea`), `requestsPerMinute` (429, token bucket). Defaults are the server's flags, and `maxQueued` has none of its own, so without a file nothing changes.
- **Fairness:** `JobScheduler`. Each submit queues one interchangeable *turn* on the workers; a turn runs the oldest queued job whose owner is under `maxRunning`, or parks without holding a worker, and a finished job re-queues one parked turn. Decided: oldest eligible, not round-robin.
- **Attribution:** JSON logs carry `client`; `cimpal_jobs_finished_total` and `cimpal_requests_total` gain a `client` label, and `cimpal_quota_refused_total` counts 429s. Tests: `ClientIsolationTest` (the cross-client 404 matrix, quotas, and a scan of logs and metrics for tokens), `JobSchedulerTest`, `ClientQuotasTest`, `TokenSourceTest`.

### GUI dialogs seam and smoke tests (TEST-6)

- **No TestFX/Monocle** (D1): plain JUnit with `FxTestSupport` (start the toolkit once, run work on the FX thread with a 30 s timeout, look nodes up by fx:id after a CSS pass, wait for windows on a listener and for workers on the alert they raise). The tests click the real buttons (`ButtonBase.fire()`), so a renamed `onAction` or fx:id fails them.
- **`GuiDialogs`** (D2, the only production change): a static facade with a swappable `Backend` (`SYSTEM` by default; `useBackendForTesting`/`resetBackend` are package-private, so only `FxTestSupport` can swap it and nothing else can install a backend that auto-approves `confirm`, which fails closed on no button or CANCEL). `openMultiple` returns null for an empty selection, as the real dialog does on cancel. It is a static field outside `MainController`. Callers keep building their `FileChooser`/`Alert`; only the `show…` call moves. `SparqlQueryTabController` also stopped calling `getScene().getWindow()` on a tab that has no scene.
- **Scope** (D3): action tests for Convert, RDF Comparison, Dataset Comparison, SHACL Validation (combined workflow), the rule test (Constraints Operations) and SPARQL; load and initial state for Visualisation, RDFS Union, AI Assistant (no network: remote knowledge is off in the in-memory preferences), RDFS to SHACL, Generate, Task Wizard and ID Mapping.
- **Tags** (D4): `gui` on every class, `smoke` on the load tests. The Main pom's `excludedGroups` property defaults to `gui & !smoke`, `-Pgui-all` sets it to a tag nobody uses, and an exact `-DexcludedGroups=gui` activates profile `no-gui-tests`, which skips Main's `coverage-ratchet` check (without GUI tests Main measures 4.3 % lines against a 7 % floor).

---

## Key file locations

```
CimPal/
├── docs/
│   ├── PROJECT.md                   ← THIS FILE
│   └── cli/
│       ├── index.md                 ← CLI command status + doc table of contents
│       ├── README.md                ← CLI overview, exit codes, config format
│       ├── validate.md              ← validate reference (--samples, per-shape JSON)
│       ├── sparql.md, manifest.md, convert.md, rdfs2shacl.md
│       ├── compare.md, compare-instances.md
│       ├── excel2shacl.md, organize.md, gen-instances.md
│       ├── run.md                   ← pipeline runner reference
│       ├── serve.md                 ← HTTP daemon reference
│       ├── mcp.md                   ← MCP server reference
│       ├── docker.md                ← running the CLI from the Docker image
│       └── ci-pipeline.md           ← CI command collection (fill in paths)
│
├── CimPal-CLI/
│   ├── pom.xml
│   ├── configs/
│   │   ├── validate-mapping-cgmes30.json, validate-combined.json
│   │   ├── validate-timestamped.json, sparql-query.json
│   │   ├── convert.json, rdfs2shacl.json, compare.json
│   │   ├── compare-instances.json, excel2shacl.json, organize.json
│   │   ├── gen-instances.json
│   │   ├── pipeline-full-validation.json
│   │   ├── pipeline-profile-migration.json
│   │   ├── claude-desktop-config.json  ← MCP config for Claude Desktop
│   │   └── claude-desktop-config-docker.json  ← same, running the Docker image
│   ├── docker/                      ← Dockerfile, Dockerfile.dockerignore, entrypoint.sh (CI-3)
│   └── src/main/java/
│       ├── module-info.java             ← requires: picocli, jackson, poi, jdk.httpserver
│       └── eu/griddigit/CimPal/
│           ├── generators/ManifestService.java   ← legacy, preserved
│           └── cli/
│               ├── CimPalCli.java       ← root @Command
│               ├── CliVersion.java      ← version from the pom (filtered cimpal-cli-version.properties)
│               ├── ExitCode.java        ← 0/1/2/3 constants
│               └── command/
│                   ├── ValidateCommand.java    ← extractShapeGroups() for Phase 7
│                   ├── SparqlCommand.java, ManifestCommand.java
│                   ├── ConvertCommand.java, RdfsToShaclCommand.java
│                   ├── CompareCommand.java, CompareInstancesCommand.java
│                   ├── ExcelToShaclCommand.java, OrganizeCommand.java
│                   ├── GenInstancesCommand.java
│                   ├── RunCommand.java          ← pipeline runner
│                   ├── ServeCommand.java        ← HTTP daemon (JDK HttpServer)
│                   └── McpCommand.java          ← MCP 2024-11-05 stdio server
│
├── CimPal-Core/src/main/java/eu/griddigit/cimpal/core/
│   ├── converters/RDFConverter.java, SHACLFromRDF.java
│   ├── comparators/ComparisonRDFSprofile.java, ComparisonSHACLshapes.java,
│   │              ComparisonRDFSprofileCIMTool.java, ComparisonInstanceData.java
│   ├── models/RDFConvertOptions.java, RDFtoSHACLOptions.java, RdfsModelDefinition.java,
│   │         RDFCompareResult.java, RDFCompareResultEntry.java, SHACLValidationResult.java
│   ├── utils/ValidationTools.java (~6000 lines), ValidationEngine.java,
│   │        SparqlTools.java, ModelFactory.java, CompleteDatatypeMapLoader.java,
│   │        ExcelTools.java (importXLSX overloads added in Phase 4),
│   │        ShaclRuleTester.java, ShaclRuleTestWorkbook.java (the SHACL rule test)
│   ├── shacl_tools/ShaclFromXls.java, ShapeDataBuilder.java, ShaclOrganizer.java
│   └── generators/ManifestGenerator.java, InstanceDataBuilder.java, InstanceDataWriter.java
│
└── CimPal-Main/src/main/java/eu/griddigit/cimpal/main/
    ├── application/MainController.java (static state bag — do not add to)
    └── core/ShaclTools.java (delegates to ShaclOrganizer),
         ModelManipulationFactory.java (delegates to InstanceDataBuilder),
         InstanceDataFactory.java (delegates to InstanceDataWriter)
```

---

## Known issues and limitations

**Other early refusals can still lose their answer on Linux (seen 2026-10-22).** Only a 507 upload refusal reads the unsent body before answering. A 400 (malformed `Idempotency-Key`, invalid `Content-Length`), 413 or 415 sent before a large body is read still closes the connection with data unread, and the client may see a connection reset instead of the status. The status itself is right; reusing `drainOrClose` on those paths would fix it.

**`manifest` access URLs.** `--access-url` only shapes `dcat:accessURL` as `file:<folder>/<file>`: a relative path is used as the folder, a `file:` URI or absolute folder gives its last folder name, and an `https://` URL is ignored (`ManifestGenerator.compactAccessUrl`). Documented in `docs/cli/manifest.md`; whether an http(s) base should be supported is open.

**`serve` job store limits (DEP-11).** One instance per database until DEP-12 (two `serve` on one database would each run the queued jobs they loaded at start). After a crash the last (up to 63) log lines of a running job may be missing. A job that couldn't start because the database failed stays queued in the database and runs only after the next start. The PostgreSQL ITs only run where Docker is available (CI on Ubuntu; not on the maintainer's Windows machine yet).

**`serve` clients share the folders (DEP-13).** Ownership separates `/v1` jobs, uploads and their outputs. In shared-folder mode (a job without `inputUpload`) every client reads and writes the same `--root` folders, so clients that must not see each other's files should use uploads only, or separate instances. Quotas are read at start only (the token file reloads, `--client-quotas` doesn't). OIDC/JWT tokens are a possible follow-up.

**`AdmissionGateTest.aCommandThatDoesNotFitWaitsAndSmallerOnesBehindItDoNotOvertake` is flaky (seen 2026-10-15).** Each waiting thread appends to the order list only after `acquire` returns, so the second thread admitted can append first. The gate itself is FIFO; the test needs to record the order inside the gate (or wait for each ticket in turn).

**Findings reported by TEST-2, fixed by SEC-5** (details in `docs/plans/testing/TEST-2.md` and `SEC-5.md`):
- An unresolvable or refused `owl:imports` now fails the row or validation instead of warning.
- All CLI CSV output uses `CsvCells.escape`.
- Zip limits are counted while reading, with a per-entry cap, and also apply in `modelLoadPerFiles`.
- `requirePublicHost` refuses IPv6 unique-local and carrier-grade NAT addresses.

**Timestamped rows with several TTLs double-counted shared imports (fixed 2026-10-06).** `ValidationTools.loadParsedShapesWithImports(Collection<Path>, …)` loaded each `;`-separated root's import closure separately, so a file that two roots import was parsed twice. Its blank nodes, such as anonymous property shapes, got a second identity, and each of their findings was reported twice, in full and incremental mode alike. The report workbook showed duplicate result rows that differ only in the blank-node *Source* label, and doubled *All*/*Violations* counts. The run summary counts rows, so it was not affected. The method now loads the row's roots as one closure with the multi-root `loadShapesWithImports`, keeping its `SHAPES_COMBINATION:` cache key and sorted root order. Reports from earlier runs of such rows overstate those findings. Covered by `TimestampedValidationGraphTest`.

**Open points from the combined-validation security review (2026-10-05):**
- `ValidationExcelWriter` writes string cells without formula-injection neutralisation. ZIP entry names now reach its Constraint file and XML files cells. String cells don't execute when the XLSX opens. Neutralising them changes every workflow's reports, so the maintainer deferred it to a separate change.
- Data ZIPs read `.xml` entries only. Other RDF files in a data ZIP are skipped without a warning, as top-level entries always were. A shapes ZIP, in turn, reads `.ttl` and `.rdf`, and skips `.xml` (it may be instance data) without a warning. The warnings for other skipped RDF files appear in the GUI's finish dialog and the Output pane, but not in the workbook.
- Entry names with C1 controls or bidi/format characters (U+202E and the like) pass `LogSanitizer` and `safeZipEntryName`; they can't forge log lines but can spoof how a name displays. Widening `LogSanitizer`'s class would change every log, so it was left as it is.
- External entities in RDF/XML are not resolved by Jena (pinned by `ShapeArchiveTest.externalEntitiesInRdfEntriesAreNotResolved`).
- Fixed 2026-10-07, when the CLI's combined workflow wired `SHACLValidator` into `serve`/`mcp`/`run`: it now checks every shape and data file against an active `PathPolicy` before touching it, and `PathGuard` checks `constraintFiles` and `dataFiles` element by element. An `owl:imports` candidate outside the roots is no longer probed: `PathPolicy.mayReadIfActive` tests it without touching the file system, so an import outside the roots fails the same way whether or not the file exists.
- Open from the CLI combined-workflow review (2026-10-07): Jena's JSON-LD reader may fetch a remote `@context` outside the egress gate (`.jsonld` inputs to `convert`, `sparql` and now `validate --workflow combined`); to verify and, if so, block while a `PathPolicy` is active. The `clearRemoteCaches()` call at the start of a combined run has no test: the egress gate only allows GitHub hosts, so no local stub can serve an import.

**SHACL generation suspected bugs (TEST-3, not fixed).** Each one has an `@Disabled` failing test; the details are in `docs/plans/testing/TEST-3.md`:
- `SHACLFromRDF` references an undeclared `…CardinalityIO` group.
- The `rdfs2shacl` `--io-uri` default puts the IdentifiedObject shapes in the CIM namespace.
- excel2shacl writes length limits as `"32.0"^^xsd:integer`.
- An unreadable workbook gives empty shapes instead of an error.

**Convert suspected bugs (TEST-3, not fixed).** Each one has an `@Disabled` failing test in `RDFConverterTest`; details in `docs/plans/testing/TEST-3.md`:
- C1: the detailed union removes only the `rdf:type` of a deleted enumeration's typed members, so their labels stay as orphans.
- C2: the JSON-LD "ordered" option (GUI) is passed to Jena but does not change the output.

**`SparqlTools.loadModelFromXML` reads files against the CIM16 schema URI** (`http://iec.ch/TC57/2013/CIM-schema-cim16`), so `rdf:ID` objects become `cim:_id` IRIs in the CIM namespace (CGMES 3.0 files too), and results show them as `cim:_t1`. Pinned by `SparqlToolsTest`; maintainer to confirm it is intended.

**GUI tests may time out in the Claude Code desktop environment** (`MainGuiFxmlLoadTest`, seen 2026-10-05 on unmodified `HEAD`). On 2026-10-21 all 20 GUI tests passed there repeatedly (TEST-6). Ubuntu CI under Xvfb is the authoritative run; if they time out locally, use `-DexcludedGroups=gui`.

**Dialogs not yet behind `GuiDialogs` (TEST-6).** Only the dialogs on the tested paths were routed: `ModelFactory`'s choosers, `GUIhelper`'s alerts, and the direct alerts and choosers of the RDF Comparison, Dataset Comparison and SPARQL tabs. About 95 other direct `showAndWait`/`show*Dialog` calls remain (most in `MainController`, `RDFStoSHACLController`, `ExcelToSHACLController`, `ShaclTools`, the wizard pages and the PSS/E compare), plus the SPARQL tab's workspace picker and Git commit-message dialog (not `Alert`s). A smoke test that reaches one of them fails after 30 s naming the open window, rather than hanging. Route them when their tabs get action tests.

**Main's `ModelFactory.unzip` has no ZIP limits (found in the TEST-6 security review, follow-up security fix).** `CimPal-Main` `util/ModelFactory.unzip` (used by the Dataset Comparison tab) reads every entry with no entry-count or total-uncompressed-size limit, unlike Core's `ModelFactory`/`ZipBudget` (`MAX_ZIP_ENTRIES`, `MAX_TOTAL_UNCOMPRESSED_BYTES`), and for a folder entry it only shows an alert and carries on. A crafted archive opened in the GUI can exhaust memory. Fix: route it through Core's ZIP reading or `ZipBudget`.

**GUI tests touch the user's real preferences in two places (TEST-6).** `MainController.initialize` (the whole-window test) and `RDFStoSHACLController.initialize` open `Preferences.userRoot().node("CimPal")` themselves and write the defaults if it is missing; the smoke tests restore `MainController.prefs` afterwards but cannot keep those two from reading or creating the node. `RDFStoSHACLController` also keeps its own static `prefs` field.

**Test coverage is sparse.** Baseline 2026-09-29 (JaCoCo line/branch): Core 28.4% / 18.2%, Main 4.7% / 1.4%, CLI 3.0% / 2.2%. With the TEST-3 validation and SHACL generation groups merged (2026-10-09), Core is at 52.3% / 37.9%; the convert and sparql groups raise it to 58.1% / 43.9% (2026-10-21). Main: 7.8% / 4.6% before TEST-6; with the smoke subset (what `verify` and CI run) 8.0% / 4.8% (floor 0.0753 / 0.0426), with the full GUI set (`-Pgui-all`) 11.5% / 7.7%; the ratchet floor follows the smoke subset. CLI: 84.7% / 70.9% after TEST-4 (2026-10-22; 79.3% / 65.4% before), floor 0.7971 / 0.6593 (measured − 5 pp, as Ubuntu measures lower); every command has contract tests and runs from the shaded JAR in `PackagedJarIT`. The ratchet stops coverage from dropping, and TEST-2 to TEST-4 are meant to raise it. **Run `mvn clean verify` before `Update-CoverageBaseline.ps1`.** JaCoCo appends to `jacoco.exec` across builds, so a build without `clean` also counts tests from earlier builds, even of other branches. `19e257f` raised the Core floor to 0.4138 that way (stale TEST-3 test runs); CI measures 0.346, so `devel` went red. DEP-1 lowered it to the clean measurement (2026-10-05); merging the TEST-3 Core tests should raise it again. Before any further Core refactoring, add characterisation tests that capture the current output.

**Timestamped report times are snapped to minute 30 of the hour (by design, maintainer to confirm).** `ValidationTools.normalizeTimestampToReportTime` sets every report time to `HH:30:00Z`, so `IGM_Test_EQ_20260101T0000Z.xml` produces `validation_report_IGM_Test_2026-01-01T00_30_00Z.xlsx`. It looks like the market-time-unit convention (the middle of the hour). The TEST-3 snapshots pin it.

**Single-dataset workbook row order varies between runs.** `SHACLValidationReport.writeExcel` writes results in the engine's result order, which isn't stable. The TEST-3 snapshots compare those rows sorted (`Snapshots.assertExcelEqualsIgnoringRowOrder`).

**`ValidateCommand`'s mapping/timestamped workflows now delegate to `MappingValidator` (2026-09-25).** They previously called `ValidationTools.validateByMapping`/`validateByTimestampedMapping` directly, built independently of (and two days before) the `MappingValidator`/`SHACLValidator` builder API added on 2026-09-23. Refactored so the CLI stops duplicating orchestration that now has a reusable home; verified with a real smoke-test run (synthetic model + SHACL shape, both text and `--format json --samples` modes) — flags, exit codes, JSON schema, and Excel/Turtle report output are unchanged. (`validate --workflow manual` was removed on 2026-10-05; see *SHACL rule test* above.) No automated regression test exists for this yet (see "Test coverage is sparse" above); the smoke-test fixtures used to verify this were not committed.

**`rdfs2shacl` namespace extraction may miss non-standard profiles.** Auto-extracting nsPrefix/nsUri from `owl:Ontology` works for standard CimSyntaxGen RDFS. Non-standard profiles need explicit config overrides (`--shapes-namespace-prefix`, `--shapes-namespace-uri`).

**The rule test writes into the test suite.** Each model's `<model>_report.xlsx` (and `.ttl`) goes beside it and the results workbook into the suite folder, as the SHACL tester always did; a suite under OneDrive syncs every run's reports. Clearing *Save each model's validation report beside it* leaves only the results workbook.

**`validate --samples` requires `--export-turtle`.** Per-shape detail is extracted by parsing the `*__report.ttl` files after validation. These are auto-enabled when `--samples > 0` in JSON mode, but they remain on disk as a side effect. This is by design (the AI Assistant also uses them) but should be documented clearly.

**`mcp` has no timeout, queue or memory limit (G4, open).** SEC-1 bounded `serve` only.

**Contract gaps found by TEST-4 (not fixed; `@Disabled` failing tests where pinned).** Details in `docs/plans/testing/TEST-4.md`:
- `sparql` exits 3 with a stack trace for a query with a syntax error; it should be 2 (`SparqlCommandTest`).
- `gen-instances` exits 3 for a workbook that is not a generation template; it should be 2 (`GenInstancesCommandTest`). A malformed SHACL file for `organize` or RDFS file for `excel2shacl` is also exit 3.
- `manifest` writes the catalog's `dct:identifier` as the local name of its `urn:uuid:` IRI, so a UUID starting with digits loses them.
- `mcp` sends `notifications/initialized` to the client after `initialize` (in MCP that notification goes from client to server).
- With `serve --log-format json`, a missing-root error at start is a plain text line, not a JSON log object.
- No natural exit-3 trigger is tested for `convert`, `rdfs2shacl`, `manifest`, `id-map`, `serve` and `mcp` (D3: no production hooks).
- Unknown config keys (typos) are silently ignored, like `_` keys (D6); typo detection is a possible later feature.
- A non-OOM `Error` other than a stack overflow still ends `mcp`; whether a stack overflow in a `serve` job is contained was not examined.

**`run` step `config` key is ignored (found in SEC-2).** No command reads a `config` key, so a step's `config` file has no effect. `run.md` documents this; it needs a fix.

**`mcp` results are capped per string (SEC-3).** A value longer than 300 characters, a file path included, reaches the agent cut and ending in `...`; the full value is in the command's output files (or run the CLI). `serve` payloads are not capped or rewritten (decision D6): its clients are programs that parse the JSON, and its `/v1` results are documented byte-for-byte. An agent that reads `serve` results should treat them as untrusted data too. The cap is per value only (part of G5, decided in SEC-3): there is no cap on a result's total size or on how many elements an array has, so a query or validation with many rows still puts all of them (each value capped) into the agent's context. Use `LIMIT`, `samples` or `maxResults` to bound it.

**The Python interpreter can be changed only at start (SEC-3).** `--python-executable`, `CIMPAL_PYTHON_EXECUTABLE` and `-Dcimpal.python.executable` are read when `validate`, `serve` or `mcp` starts; there is deliberately no config key or per-request field. The interpreter's own `import` path (site-packages, `PYTHONPATH` in the environment) is not restricted. The `[ENGINE]` line names the interpreter's absolute path and goes to stderr, so under `serve` it is in the job log that `GET /v1/jobs/{id}/log` returns to the job's client (accepted in the SEC-3 review: Low, the server's Python install path).

**`mcp`/`serve` with Claude Desktop need `--root`.** After SEC-2, the CimPal MCP server only accepts paths under its working directory unless `--root` is given. Update `claude_desktop_config.json`; see `CimPal-CLI/configs/claude-desktop-config.json`.

**A `serve` command that times out keeps running.** The caller gets 504, but the command isn't interrupted (that could leave truncated reports), so the worker stays busy until it finishes. `/health` then reports `"status":"stalled"`. Restart the server if a command hangs.

**`serve` limits connection time, not connection count.** A local process that keeps opening slow connections can still slow the server down. Each one is closed after 60 s (`sun.net.httpserver.maxReqTime`).

**`serve --workers` shares one JVM.** An out-of-memory error ends every running command (exit 3), and `--stats` CPU, heap and GC figures cover all running commands. Replicas with one worker each stay the recommended production shape. Common-pool threads (`parallelStream` in `ModelFactory`) don't inherit a run's policy or output binding: their path checks are refused under `serve`, `mcp` and `run`, and their output goes to the process streams. Today they only parse in-memory streams.

**The Docker image has no Python.** Only the `APACHE_JENA` engine works in the container; `PYSHACL`, `PYSHACL_OXIGRAPH` and `RUST_SHACL` need a local install.

**`CimPal-CLI.jar` is locked while the MCP server runs.** When Claude Desktop has the CimPal MCP server running (`claude-desktop-config.json`), Windows locks `CimPal-CLI/target/CimPal-CLI.jar`, and `mvn package`/`verify` fails at CimPal-CLI with "Could not create modular JAR file". Quit Claude Desktop, or stop the `CimPal-CLI.jar mcp` processes, before a full build. A longer-term fix could have Desktop run a copied JAR instead of the build output, or the Docker image (`claude-desktop-config-docker.json`), which locks nothing on the host.

**ID mapping limits (2026-10-13).** DC topology (DC busbars, DC line segments and their terminals) is not matched; converters, their DC terminals, DC nodes and converter units are. Two identical elements at one place with the same name are reported, not guessed. First real run (EirGrid + SONI 2026 EQ against the 2020 merged EIRGRIDSONI EQ, before the junction, station and confidence changes): 6.5 % mapped; the causes were PowerFactory `Term_` junctions holding a busbar section (not collapsed then), stations split per voltage level in the merged model, and six years between the models. Not re-run on the real data since; read the Summary overview and model statistics first.

**`MainController` static state bag.** Several GUI tabs still share state through static fields. Do not add new static fields. The pattern of delegating from Main to Core (introduced in Phase 5) is the correct long-term direction.

---

## REST API — discovery and implementation plan

> **Superseded by the deployment track.** The asynchronous job API was built in DEP-5
> (`docs/plans/deployment/DEP-5.md`) and made deployable in DEP-6; see *`serve` job API* and
> *`serve` as a service* above and `docs/cli/serve.md`. The
> remaining service work is in DEP-6 to DEP-10. The analysis below is kept for background.

### Context

After the CLI, MCP, and HTTP daemon were implemented, a request came in to expose CimPal via a proper REST API — specifically for team use, CI integration, and consumption by non-AI tools. This section documents the discovery analysis so an implementor can proceed without re-deriving the requirements.

### What already exists (`serve` command)

The `serve` command (`ServeCommand.java`) is already a basic REST API:
- `POST /<command>` with JSON body → JSON response
- `GET /health`, `GET /commands`, `POST /shutdown`
- Zero extra dependencies (JDK `com.sun.net.httpserver`)
- Binds to `localhost:7474` by default

The gap is that `serve` is a **local developer tool**, not a **team or production service**. The specific limitations:

| Limitation | Impact |
|---|---|
| localhost-only by default | Cannot be accessed by other machines on the network |
| Single-threaded executor | One request at a time; second caller waits |
| Local bearer token only (SEC-1) | One token per server start, no users or roles |
| Synchronous only | Long validation (5–30 min) blocks the HTTP connection |
| No OpenAPI spec | No self-documentation, no client code generation |
| No result storage | No history, no trend queries across runs |
| No versioned endpoints | No stability guarantees for API consumers |

### MCP vs REST API — when each is appropriate

**Use MCP (`cimpal mcp`)** when:
- The caller is a Claude agent making deliberate decisions
- You want typed tool schemas to guide AI reasoning
- No HTTP involved — stdio transport only
- Single session, sequential tool calls

**Use REST API** when:
- Caller is a script, CI pipeline, dashboard, or other service
- Multiple concurrent callers (different engineers, different pipelines)
- Caller is not an AI (monitoring tool, web app, shell script)
- Network accessibility is needed (not just localhost)
- Authentication is needed
- Historical results need to be queried

**Key insight:** MCP is for the AI reasoning loop. REST is for everything else. They serve different purposes and can coexist — the same CimPal instance could serve both.

### Capabilities to implement (ordered by value/effort)

**Tier 1 — High value, low effort (extend `serve`)**

1. **Network binding + basic auth**  
   - Change bind address: `--host 0.0.0.0` (already supported, just needs documenting + token middleware)  
   - Add bearer token check in a middleware wrapper around all handlers  
   - Token configured via `--token <value>` flag or `CIMPAL_API_TOKEN` env var  
   - If no token configured: localhost-only (current behavior); if token configured: any host

2. **Concurrent request handling**  
   - Replace `Executors.newSingleThreadExecutor()` with `Executors.newFixedThreadPool(N)`  
   - **Thread safety blocker**: `ValidationTools` has static volatile booleans (`exportTurtleValidationReports`, `DEBUG`). These are set per-request and must not race. Solutions:
     - Option A: Keep validation single-threaded (validation pool) but allow concurrent reads (sparql, compare)
     - Option B: Move the static flags to ThreadLocal — requires modifying ValidationTools
     - Option C: Accept the race for non-validation endpoints; serialise only validate calls via a separate lock
   - Recommended: Option A first (separate executor for write-heavy vs read-only commands)

3. **OpenAPI 3.0 specification endpoint**  
   - `GET /openapi.json` returns the full OpenAPI spec  
   - Can be hand-authored once and served as a static file embedded in the JAR  
   - Enables Swagger UI, auto-generated client code, API contract testing  
   - All request/response schemas are already defined (same as MCP tool schemas in `McpCommand.java`)

**Tier 2 — High value, medium effort (new features)**

4. **Asynchronous job execution**  
   - `POST /validate` → `{"jobId": "abc123", "status": "running", "startedAt": "..."}`  
   - `GET /jobs/{id}` → `{"status": "running"|"done"|"failed", "result": {...}}`  
   - `DELETE /jobs/{id}` → cancel (best-effort)  
   - Storage: in-memory `ConcurrentHashMap<String, JobResult>` for V1 (cleared on restart)  
   - Job IDs: UUID v4  
   - Required because validation can take 5–30 minutes; HTTP clients time out

5. **Progress streaming (Server-Sent Events)**  
   - `GET /jobs/{id}/events` → SSE stream of progress events  
   - `ValidationTools` already emits `System.out.println("[INFO] ...")` progress messages  
   - Capturing these and forwarding as SSE requires a callback mechanism into ValidationTools  
   - Simplest approach: capture stdout during async execution, buffer last N lines, serve via polling `GET /jobs/{id}/log?offset=N`

6. **Result history**  
   - Store job results in SQLite (zero infrastructure, one file)  
   - `GET /history?limit=20&command=validate` → paginated list of past runs  
   - Enables trend queries: "show violation counts for the last 10 validation runs"  
   - Each entry: jobId, command, timestamp, exitCode, summary JSON, input hashes  
   - SQLite via JDBC (`org.xerial:sqlite-jdbc`) — one new dependency, ~500KB

**Tier 3 — Lower priority (polish and production readiness)**

7. **Webhook callbacks**  
   - `POST /validate` body includes `"callbackUrl": "https://ci.example.com/notify/..."}`  
   - When done, POST the result to the callback URL  
   - Enables CI/CD integration without polling

8. **Multi-tenant config profiles**  
   - Named profiles per team/project: `POST /validate?profile=cgmes30-nc25`  
   - Profile sets default datatypeMap, xmlBase, constraintsRoot, etc.  
   - Profiles configured in a server config file at startup

9. **Rate limiting and request queuing**  
   - Prevent memory exhaustion from simultaneous large validation runs  
   - Bounded queue: if > N requests queued, return 503

### Implementation approach

**Option A — Extend existing `ServeCommand`** (recommended for Tiers 1–2)
- Keep `com.sun.net.httpserver` (zero new dependencies)
- Add concurrency, auth, and async jobs inside `ServeCommand.java`
- The in-process command execution pattern is already working and tested
- Risk: `com.sun.net.httpserver` is basic — no middleware, no streaming support out of the box

**Option B — Replace with Javalin** (recommended if OpenAPI, SSE, or production-grade needed)
- Javalin: ~500KB, built on Jetty, first-class JSON/SSE/OpenAPI support
- Add `io.javalin:javalin:6.x` to CLI pom
- Reuse the same in-process command execution pattern
- Javalin has OpenAPI plugin (`io.javalin:javalin-openapi`) for auto-generated spec
- Server startup: `Javalin.create(config -> { config.plugins.enableOpenApi(...); }).start(port)`

**Option C — Separate module** (for clean separation from CLI)
- New Maven module `CimPal-REST` depending on Core (not CLI)
- Calls Core classes directly instead of through picocli
- More work, but cleaner architecture for long-term maintenance
- Required if the REST API needs to diverge significantly from the CLI interface

### What does NOT change

All Core classes work the same. The REST API layer is purely a new front-end on top of the existing in-process execution mechanism. No changes to:
- `ValidationTools`, `SHACLFromRDF`, `RDFConverter`, any comparator
- The Core module's pom.xml
- The GUI (CimPal-Main)
- The CLI commands themselves (picocli interface stays)

### Request/response schema design

The REST API request bodies and responses should be identical to the MCP tool schemas (already defined in `McpCommand.java`) and the existing `serve` command behavior. The MCP tool definitions in `McpCommand.buildTools()` are the authoritative source for input schemas — reuse them for OpenAPI `requestBody` definitions.

Response format: same as `--format json` output from each command. For async jobs, wrap in:
```json
{
  "jobId": "abc123",
  "status": "done",
  "command": "validate",
  "startedAt": "2026-09-20T14:30:00Z",
  "completedAt": "2026-09-20T14:42:15Z",
  "exitCode": 1,
  "result": { /* same as --format json output */ }
}
```

### Files to create/modify for REST API

| File | Action |
|---|---|
| `CimPal-CLI/src/main/java/eu/griddigit/CimPal/cli/command/ServeCommand.java` | Extend or replace with REST API implementation |
| `CimPal-CLI/pom.xml` | Add Javalin (Option B) or SQLite-JDBC (for history) |
| `CimPal-CLI/src/main/java/module-info.java` | Add `requires` for new dependencies |
| `CimPal-CLI/configs/server-config.json` | New: server startup config (port, token, profiles) |
| `docs/cli/rest-api.md` | New: REST API reference with all endpoints |
| `docs/PROJECT.md` | Update this section after implementation |

---

## Remaining work — Part B (Validation Agent)

The CLI + MCP foundation is complete. The agent itself (Part B of the original plan):
- Uses MCP tools through Claude's tool-call mechanism
- Loop: `validate` (get shape groups) → `sparql` (inspect focus nodes) → diagnose cause → edit SHACL or RDFS → `gen_instances` (fixtures) → `validate --workflow mapping` (full run). The fixture test that `validate --workflow manual` offered is the GUI's rule test since 2026-10-05; an agent would need a CLI/MCP form of `ShaclRuleTester` with SEC-2 path checks first.
- Enterprise Architect UML stays read-only; agent only modifies SHACL, RDFS, and mapping tables
- Each change arrives as a git branch with a commit message tracing it to the violation
- Stopping conditions: zero violations, no improvement over 2 iterations, iteration cap

The agent's write scope, stopping conditions, and branch/PR workflow are defined in the original plan document. The technical foundation for all of this is now in place.
