<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# CimPal — Project Reference Document

**Last updated:** 2026-10-05  
**Update rule:** Edit this file at the end of every implementation session. Sections that change most often: *Implementation status*, *Next steps*, *Known issues*.

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
| `eu.griddigit.cimpal.core.utils.SHACLValidator` + `eu.griddigit.cimpal.core.models.SHACLValidationOptions` | Core | Builder-style facade for validating **one** dataset (files and/or a Jena model) against **one** set of shapes → `SHACLValidationReport`. Used by the GUI's *Validate selected files together* workflow (2026-10-05, see below), and once per distinct model by `ShaclRuleTester`, which loads the shapes once through the package-private `SHACLValidator.loadShapeFiles` and passes them as `shapesModel`. |
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

The CLI has no equivalent yet. Covered by `SHACLValidatorTest`, `SHACLValidationReportTest`, `ShapeArchiveTest`, `ZipBudgetTest` and `ShapeSourceTest`.

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
test summary and uploads surefire reports. The JavaFX test `MainGuiFxmlLoadTest` is tagged `gui`
(exclude with `-DexcludedGroups=gui`). A third job, "Docker image", is described below.

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

**Docker image** (added by CI-3, `docs/plans/CI-3.md`):
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

**Deployment track** (`docs/plans/deployment/`, DEP-1 to DEP-10): container, sizing, CLI automation
options, an async `/v1` API, a Python SDK and an Airflow provider for external users. It supersedes
the ordering of the *REST API — discovery and implementation plan* section below.

**Next steps:** merge SEC-1 ([PR #44](https://github.com/griddigit-ci/CimPal/pull/44)), then SEC-2, following the
phase order in `docs/plans/README.md`. Enabling branch protection with the two CI checks as
required is a maintainer action. CI-3 is in review ([PR #51](https://github.com/griddigit-ci/CimPal/pull/51)): its
"Docker image" job needs a first green run, and after the first release that pushes the image the
GHCR package must be made public. DEP-1 is done. DEP-2's first part was merged with PR #54; the
sizing guide and the security-review fixes follow in a second PR. Next on the deployment track is
DEP-3 (CLI automation options).
The SHACL rule test rewrite ([PR #53](https://github.com/griddigit-ci/CimPal/pull/53), branch
`feature/shacl-rule-tester`) needs a review; whether the rule test should come back to the CLI/MCP,
with SEC-2 path checks and a JSON summary, is open. TEST-3 (`aca6238`) adds a golden test,
`ShaclAutoTesterTest`, for the `ShaclAutoTester` that PR #53 deletes: whichever merges second drops
it, as `ShaclRuleTesterTest` covers the replacement.

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

All 13 commands in a single fat JAR (`CimPal-CLI/target/CimPal-CLI.jar`), no external runtime dependencies beyond JRE 25. Each release also publishes that JAR as the Docker image `ghcr.io/griddigit-ci/cimpal` (see `docs/cli/docker.md`).

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

### `serve` uses single-threaded executor

Commands run on one worker thread (`ServeServer`), with a bounded queue and a pool of HTTP handler threads in front of it. This avoids races on `ValidationTools` static flags (`exportTurtleValidationReports`, `DEBUG`). A single validation run already saturates CPU via its own internal worker pool, so sequential requests is the right trade-off for local developer use. Any concurrent REST API implementation must address this differently — see REST API section below.

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
│   │   ├── validate-mapping-cgmes30.json
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

**Findings reported by TEST-2, fixed by SEC-5** (details in `docs/plans/TEST-2.md` and `SEC-5.md`):
- An unresolvable or refused `owl:imports` now fails the row or validation instead of warning.
- All CLI CSV output uses `CsvCells.escape`.
- Zip limits are counted while reading, with a per-entry cap, and also apply in `modelLoadPerFiles`.
- `requirePublicHost` refuses IPv6 unique-local and carrier-grade NAT addresses.

**Timestamped rows with several TTLs likely double-count shared imports (found 2026-10-05, unverified).** `ValidationTools.loadParsedShapesWithImports(Collection<Path>, …)` loads each `;`-separated root's import closure separately. That is the pattern that made `SHACLValidator` report the findings of a shared import's anonymous shapes twice. The fix is to load them with the multi-root `loadShapesWithImports`, as `SHACLValidator` now does; it needs a regression test first.

**Open points from the combined-validation security review (2026-10-05):**
- `ValidationExcelWriter` writes string cells without formula-injection neutralisation. ZIP entry names now reach its Constraint file and XML files cells. String cells don't execute when the XLSX opens. Neutralising them changes every workflow's reports, so the maintainer deferred it to a separate change.
- Data ZIPs read `.xml` entries only. Other RDF files in a data ZIP are skipped without a warning, as top-level entries always were. A shapes ZIP, in turn, reads `.ttl` and `.rdf`, and skips `.xml` (it may be instance data) without a warning. The warnings for other skipped RDF files appear in the GUI's finish dialog and the Output pane, but not in the workbook.
- Entry names with C1 controls or bidi/format characters (U+202E and the like) pass `LogSanitizer` and `safeZipEntryName`; they can't forge log lines but can spoof how a name displays. Widening `LogSanitizer`'s class would change every log, so it was left as it is.
- External entities in RDF/XML are not resolved by Jena (pinned by `ShapeArchiveTest.externalEntitiesInRdfEntriesAreNotResolved`).
- `SHACLValidator` has no `PathPolicy` check on data files, and probes a shape file with `Files.isRegularFile` before `ShapeArchive.read` checks the policy. Add both before wiring it into `serve`/`mcp`/`run`.

**`MainGuiFxmlLoadTest` sometimes times out in the Claude Code desktop environment** (on unmodified `HEAD` too, 2026-10-05), although the JavaFX toolkit starts in a plain JVM there; it passed in 3 s in a full `verify` later the same day. CI runs it; if it times out locally, use `-DexcludedGroups=gui`.

**Test coverage is sparse.** Baseline 2026-09-29 (JaCoCo line/branch): Core 28.4% / 18.2%, Main 4.7% / 1.4%, CLI 3.0% / 2.2%. CLI tests only cover `--help`, `convert` and a JSON Schema smoke test. The ratchet stops coverage from dropping, and TEST-2 to TEST-4 are meant to raise it. **Run `mvn clean verify` before `Update-CoverageBaseline.ps1`.** JaCoCo appends to `jacoco.exec` across builds, so a build without `clean` also counts tests from earlier builds, even of other branches. `19e257f` raised the Core floor to 0.4138 that way (stale TEST-3 test runs); CI measures 0.346, so `devel` went red. DEP-1 lowered it to the clean measurement (2026-10-05); merging the TEST-3 Core tests should raise it again. Before any further Core refactoring, add characterisation tests that capture the current output.

**Timestamped report name is 30 minutes off (observed, unverified).** In `MappingValidatorTest`, the input `IGM_Test_EQ_20260101T0000Z.xml` produces `validation_report_IGM_Test_2026-01-01T00_30_00Z.xlsx`. The snapshot pins this behaviour. Check whether it is intended (a half-hour slot?) when TEST-3 covers timestamped validation.

**`ValidateCommand`'s mapping/timestamped workflows now delegate to `MappingValidator` (2026-09-25).** They previously called `ValidationTools.validateByMapping`/`validateByTimestampedMapping` directly, built independently of (and two days before) the `MappingValidator`/`SHACLValidator` builder API added on 2026-09-23. Refactored so the CLI stops duplicating orchestration that now has a reusable home; verified with a real smoke-test run (synthetic model + SHACL shape, both text and `--format json --samples` modes) — flags, exit codes, JSON schema, and Excel/Turtle report output are unchanged. (`validate --workflow manual` was removed on 2026-10-05; see *SHACL rule test* above.) No automated regression test exists for this yet (see "Test coverage is sparse" above); the smoke-test fixtures used to verify this were not committed.

**`rdfs2shacl` namespace extraction may miss non-standard profiles.** Auto-extracting nsPrefix/nsUri from `owl:Ontology` works for standard CimSyntaxGen RDFS. Non-standard profiles need explicit config overrides (`--shapes-namespace-prefix`, `--shapes-namespace-uri`).

**The rule test writes into the test suite.** Each model's `<model>_report.xlsx` (and `.ttl`) goes beside it and the results workbook into the suite folder, as the SHACL tester always did; a suite under OneDrive syncs every run's reports. Clearing *Save each model's validation report beside it* leaves only the results workbook.

**`validate --samples` requires `--export-turtle`.** Per-shape detail is extracted by parsing the `*__report.ttl` files after validation. These are auto-enabled when `--samples > 0` in JSON mode, but they remain on disk as a side effect. This is by design (the AI Assistant also uses them) but should be documented clearly.

**`mcp` has no timeout, queue or memory limit (G4, open).** SEC-1 bounded `serve` only.

**`run` step `config` key is ignored (found in SEC-2).** No command reads a `config` key, so a step's `config` file has no effect. `run.md` documents this; it needs a fix.

**`manifest` can't run through `serve`, `mcp` or `run`** (no `--config` option), although `mcp` advertises the tool.

**`sparql` reads `.zip` models without the archive limits** in `ModelFactory`. A large zip through `serve`/`mcp` can exhaust memory. Candidate for TEST-2 or a follow-up.

**An unresolvable `owl:imports` is only a warning.** The row is validated with those shapes missing. Network imports now fail the row, but the general case needs fixing (TEST-2).

**`mcp`/`serve` with Claude Desktop need `--root`.** After SEC-2, the CimPal MCP server only accepts paths under its working directory unless `--root` is given. Update `claude_desktop_config.json`; see `CimPal-CLI/configs/claude-desktop-config.json`.

**A `serve` command that times out keeps running.** The caller gets 504, but the command isn't interrupted (that could leave truncated reports), so the worker stays busy until it finishes. `/health` then reports `"status":"stalled"`. Restart the server if a command hangs.

**`serve` limits connection time, not connection count.** A local process that keeps opening slow connections can still slow the server down. Each one is closed after 60 s (`sun.net.httpserver.maxReqTime`).

**`serve` serialises all requests.** The single-threaded executor prevents concurrent validation runs. For team use (multiple users sharing one server) this is a bottleneck. Addressed in the REST API plan below.

**The Docker image has no Python.** Only the `APACHE_JENA` engine works in the container; `PYSHACL`, `PYSHACL_OXIGRAPH` and `RUST_SHACL` need a local install.

**`CimPal-CLI.jar` is locked while the MCP server runs.** When Claude Desktop has the CimPal MCP server running (`claude-desktop-config.json`), Windows locks `CimPal-CLI/target/CimPal-CLI.jar`, and `mvn package`/`verify` fails at CimPal-CLI with "Could not create modular JAR file". Quit Claude Desktop, or stop the `CimPal-CLI.jar mcp` processes, before a full build. A longer-term fix could have Desktop run a copied JAR instead of the build output, or the Docker image (`claude-desktop-config-docker.json`), which locks nothing on the host.

**`MainController` static state bag.** Several GUI tabs still share state through static fields. Do not add new static fields. The pattern of delegating from Main to Core (introduced in Phase 5) is the correct long-term direction.

---

## REST API — discovery and implementation plan

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
