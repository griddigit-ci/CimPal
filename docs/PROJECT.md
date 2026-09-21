# CimPal — Project Reference Document

**Last updated:** 2026-09-20 (Phase 5 complete)  
**Update rule:** Edit this file at the end of every implementation session. Sections that change most often: *Implementation status*, *Next steps*, *Known issues*.

---

## How to use this document

This document serves two audiences simultaneously:

**For humans:** a single place to understand what CimPal is, what has been built, why decisions were made, and what comes next. Read it before starting any new work.

**For AI assistants:** a full context briefing so work can resume in any new conversation without re-discovering what was already learned. If you are an AI reading this, treat it as ground truth about the current state — then verify key details against the actual code before writing anything.

When AI picks up work here: read this file first, then read `docs/cli/index.md` for the CLI status, then read the specific source file most relevant to the task. Do not re-run the full codebase discovery — it has been done and is summarized below.

---

## Project at a glance

**CimPal** is a Java 25 desktop application (JavaFX) built by gridDigIt for CIM/CGMES semantic tooling. It is used by power system engineers who work with CIM models, SHACL constraints, and CGMES profiles.

Its core capabilities:
- SHACL validation of CGMES instance data against constraint sets
- RDFS-to-SHACL shape generation from CIM profiles
- RDF format conversion (XML ↔ Turtle ↔ JSON-LD)
- SPARQL querying of RDF instance data
- Profile version comparison (RDFS diff, SHACL diff)
- Excel-driven shape authoring
- CGMES instance data generation and manipulation
- CGMES manifest generation

**The CLI project** adds a headless entry point to all of these operations so they can run in CI pipelines, scripts, and as the foundation of a future automated validation agent.

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
- Apache POI 5.5.1 (Excel I/O — available in Core and Main)
- Jackson 3.2.1 (`tools.jackson.core` groupId — in Main and CLI)
- picocli 4.7.6 (CLI only)
- SLF4J 2.0.18 (logging)
- JUnit Jupiter 5.13.1 (tests)

### Module dependency graph

```
CustomWriter ← Core ← Main
                  ↖ CLI
```

Both Main and CLI are fat JARs (shade plugin). CLI bundles Core + CustomWriter + Jena + picocli + Jackson.

### Package naming convention

All packages follow `eu.griddigit.CimPal.*` with capital C in CimPal. This must be maintained for JAR class lookup to work correctly on case-sensitive systems.

- Core: `eu.griddigit.cimpal.core.*` (lowercase — pre-existing convention, works on Windows)
- Main: `eu.griddigit.cimpal.main.*`
- CLI: `eu.griddigit.CimPal.cli.*` and `eu.griddigit.CimPal.generators.*`

**Important:** New CLI classes must go in directories matching their package declaration. The fat JAR's ZIP entries are case-sensitive. A mismatch (lowercase package in uppercase directory) compiles on Windows but fails at runtime with ClassNotFoundException.

---

## Key classes by function

### Validation

| Class | Module | What it does |
|---|---|---|
| `eu.griddigit.cimpal.core.utils.ValidationTools` | Core | The main validation engine. Static methods `validateByMapping()` and `validateByTimestampedMapping()`. ~6000 lines. Zero GUI imports. |
| `eu.griddigit.cimpal.core.shacl_tools.ShaclAutoTester` | Core | Manual validation: given SHACL files + model archives, validates each archive and writes Excel reports. |
| `eu.griddigit.cimpal.core.utils.ValidationEngine` | Core | Enum: APACHE_JENA, PYSHACL, PYSHACL_OXIGRAPH, RUST_SHACL |
| `eu.griddigit.cimpal.core.utils.CompleteDatatypeMapLoader` | Core | Loads CGMES datatype maps from bundled classpath resources or user-supplied .properties files. |
| `eu.griddigit.cimpal.core.interfaces.ShaclAutoTesterCallback` | Core | Callback interface for `ShaclAutoTester` — `updateProgress(double)` and `appendOutput(String)`. |
| `eu.griddigit.cimpal.core.utils.ValidationTools.ValidationRunSummary` | Core | Record: `reportPath`, `conforming`, `violations`, `errors` |
| `eu.griddigit.cimpal.core.utils.ValidationTools.ValidationTimestampedRunSummary` | Core | Record: `reports` (List<Path>), `conforming`, `violations`, `errors` |

### RDF conversion

| Class | Module | What it does |
|---|---|---|
| `eu.griddigit.cimpal.core.converters.RDFConverter` | Core | Format conversion. Call `convert()` then `writeConvertedModel(OutputStream)`. |
| `eu.griddigit.cimpal.core.models.RDFConvertOptions` | Core | Builder-style config for RDFConverter. Formats: RDFXML, TURTLE, JSONLD. |
| `eu.griddigit.cimpal.core.models.RDFConvertOptionsPresets` | Core | Pre-built option sets for common conversions. |

### RDFS to SHACL

| Class | Module | What it does |
|---|---|---|
| `eu.griddigit.cimpal.core.converters.SHACLFromRDF` | Core | Generates SHACL shapes from RDFS profiles. Call `convert()` then `saveShapeModel(outputDir)`. |
| `eu.griddigit.cimpal.core.models.RDFtoSHACLOptions` | Core | Builder-style config. Key fields: rdfsModels (ArrayList<Model>), rdfsModelDefinitions (List<RdfsModelDefinition>), many boolean flags. |
| `eu.griddigit.cimpal.core.models.RdfsModelDefinition` | Core | Per-profile metadata: modelName, nsPrefix, nsUri, baseUri, owlImport. |

### Comparison

| Class | Module | What it does |
|---|---|---|
| `eu.griddigit.cimpal.core.comparators.ComparisonRDFSprofile` | Core | Compares two augmented RDFS profiles (CimSyntaxGen format). |
| `eu.griddigit.cimpal.core.comparators.ComparisonSHACLshapes` | Core | Compares any two RDF files (RDFS or SHACL). Universal method. |
| `eu.griddigit.cimpal.core.comparators.ComparisonRDFSprofileCIMTool` | Core | Compares augmented RDFS with CIMTool-style profile. |
| `eu.griddigit.cimpal.core.interfaces.IRDFComparator` | Core | Interface: `compare(Model a, Model b) → RDFCompareResult` |
| `eu.griddigit.cimpal.core.models.RDFCompareResult` | Core | List of `RDFCompareResultEntry`. Note: `hasDifference()` returns true when the list is EMPTY (naming is counterintuitive — use `getEntries().isEmpty()` for clarity). |
| `eu.griddigit.cimpal.core.models.RDFCompareResultEntry` | Core | One diff: item URI, rdfType, property, valueModelA, valueModelB |

### SPARQL

| Class | Module | What it does |
|---|---|---|
| `eu.griddigit.cimpal.core.utils.SparqlTools` | Core | `executeSparqlQuery(query, model) → QueryResults`. `exportResultsToExcel(results, file)`. |
| `eu.griddigit.cimpal.core.utils.ModelFactory` | Core | `loadCombinedModelForSparql(files, xmlBase)` — loads and merges model files. |

### Excel / SHACL organizer (in Main, not yet extracted)

| Class | Module | Status |
|---|---|---|
| `eu.griddigit.cimpal.core.shacl_tools.ShaclFromXls` | Core | Excel-to-SHACL. Needs `Preferences`/static refactor for CLI. |
| `eu.griddigit.cimpal.main.core.ShaclTools` | Main | SHACL organizer. Needs move to Core. |
| `eu.griddigit.cimpal.main.core.ModelManipulationFactory` | Main | Instance data generation. Needs move to Core. |

---

## The CLI plan — original goals vs. discovery findings

### Original plan goals (from the brief)

1. Every CimPal operation runnable headlessly, reproducibly, on any machine
2. picocli for argument parsing; fat JAR for distribution
3. `--config <file>` (YAML or JSON) on every command
4. `--format json` on every result-producing command; keep existing xlsx
5. Summary-first JSON: header, totals, per-shape groups, bounded samples
6. Distinct exit codes: 0=ok, 1=violations, 2=bad-input, 3=error
7. Deterministic, canonically ordered output
8. `--dry-run` on anything that writes
9. Pipeline mechanism (reuse Task Wizard if suitable)
10. Prewritten configs for recurring workflows

### Key discovery findings that changed the plan

**Task Wizard is NOT a pipeline engine.** It is a CGMES instance data manipulation wizard (multiply models, inject MRIDs, generate test data). Its tasks are `ITask` with a JavaFX-observable `SelectedTask` parent — not reusable. There is no file-based task format. The pipeline format for the CLI will be new YAML. *This is the biggest plan deviation.*

**The Core module is already cleanly separated.** `ValidationTools`, `SHACLFromRDF`, `RDFConverter`, all comparators — zero JavaFX imports. The CLI can call them directly with no refactoring.

**`MainController` is a static state bag.** Several tabs (ExcelToSHACL, RDFStoSHACL, InstanceDataComparison, SHACLOrganizer) share state through static fields on `MainController`. These tabs need small refactors to work headlessly. This affects Phase 4 commands but not Phases 2 and 3.

**Validation is already highly capable.** Remote SHACL fetching from GitHub URLs, ETag caching, three Python alternative engines, memory-aware worker sizing, result sampling, Turtle report export — all already implemented in Core. The CLI just wraps it.

**JSON format deviation from the plan.** The plan specified per-shape groups with bounded violation samples in the JSON output. Implementing this requires reading the Excel report back, which is complex. Phase 2 delivers run metadata + totals + report path. Per-shape breakdown is Phase 5.

**Config format:** Implemented as JSON (not YAML) to avoid the `jackson-dataformat-yaml` dependency. JSON with `_comment` / `_note_*` annotations serves the same purpose.

**Existing CLI module:** `CimPal-CLI` already exists with `ManifestService` and a shade-plugin fat-JAR setup. New commands extend this module; `ManifestService.main()` preserved for backward compat.

---

## Implementation status

### Done — Phase 2

| Command | Status | Core class |
|---|---|---|
| `validate` | ✅ Complete | `ValidationTools`, `ShaclAutoTester` |
| `sparql` | ✅ Complete | `SparqlTools`, `ModelFactory` |
| `manifest` | ✅ Complete | `ManifestGenerator` |

**CLI infrastructure:** picocli root `CimPalCli`, `ExitCode` constants, Jackson 3 tree-model config loading, relative path resolution, `--dry-run`, `--format json` with stdout redirect, `_note_*` annotated config templates.

**Documentation:** `docs/cli/` — README, validate, sparql, manifest references + ci-pipeline and shape-dev-loop how-tos.

### Done — Phase 3

| Command | Status | Core class |
|---|---|---|
| `convert` | ✅ Complete | `RDFConverter`, `RDFConvertOptions` |
| `rdfs2shacl` | ✅ Complete | `SHACLFromRDF`, `RDFtoSHACLOptions`, `RdfsModelDefinition` |
| `compare` | ✅ Complete | `ComparisonRDFSprofile`, `ComparisonSHACLshapes`, `ComparisonRDFSprofileCIMTool` |

**Documentation:** `docs/cli/` — convert, rdfs2shacl, compare references; ci-pipeline updated with Steps 7–10.

### Done — Phase 4

| Command | Status | What was extracted to Core |
|---|---|---|
| `compare-instances` | ✅ Complete | `ComparisonInstanceData` (+ helpers from `CompareFactory`) → `core.comparators`; commons-lang3 + commons-math3 added to Core |
| `excel2shacl` | ✅ Complete | New `ShaclFromXls.generateShaclFromXls(Map<String,String>, ...)` overload; `ShapeDataBuilder.constructShapeData()` → `core.shacl_tools`; `ExcelTools` overloads added to Core |
| `organize` | ✅ Complete | `ShaclOrganizer.splitShaclPerXlsInput(inputXLSdata, List<Model>, Path)` → `core.shacl_tools`; folder-picker + MainController statics replaced with explicit parameters |

**What was NOT extracted (deferred to Phase 5):**
- `gen-instances` — `generateDataFromXlsV2` model-building logic is in `Main.ModelManipulationFactory`; the save step uses `InstanceDataFactory.saveInstanceData` which has JavaFX imports that prevent loading in a headless JVM even when the dialog code path is not reached. Needs `InstanceDataFactory` extracted to Core first.

### Done — Phase 5 (restructuring + gen-instances)

**Restructuring (single source of truth):**
- `ShaclFromXls.generateShaclFromXls(Preferences, ...)` now delegates to the Map-based overload — one implementation, two entry points. GUI unchanged.
- `Main.ShaclTools.splitShaclPerXlsInput` now delegates to `Core.ShaclOrganizer.splitShaclPerXlsInput` — GUI picks the folder dialog, Core does the logic.

**New Core classes extracted from Main:**
- `core.generators.InstanceDataWriter.write(Model, Map, OutputStream)` — pure RDF serialisation with `CustomRDFFormat`, no JavaFX import
- `core.generators.InstanceDataBuilder.buildFromXls(xmlBase, xlsFile, stripPrefixes, exportExtensions) → BuildResult` — model-building from Excel template, no MainController or JavaFX

**Main wired to Core:**
- `Main.InstanceDataFactory.saveInstanceData` now delegates to `InstanceDataWriter.write` for the actual serialisation
- `Main.ModelManipulationFactory.generateDataFromXlsV2` now delegates to `InstanceDataBuilder.buildFromXls` then `InstanceDataFactory.saveInstanceData`

**CLI command:** `gen-instances` — generates CGMES RDF/XML from CimPal Excel templates

### Done — Phase 6 (pipeline `run` command)

**`run <pipeline.json>`** — executes a declarative JSON pipeline of CimPal commands sequentially.

Design: each pipeline step is a JSON object with a `command` key plus the same option keys the command's config file accepts. The step JSON is written to a temp file and passed as `--config` to the subcommand — this reuses all existing config loading logic in each command automatically.

Key features:
- `stopOnError` (default: true) / `stopOnViolations` (default: false) — configurable at pipeline and per-step level
- `--dry-run` to preview steps without running
- `--format json` for machine-readable pipeline result summary
- `config` key in each step to reference a base config file (inline step keys override it)
- Paths in `config` resolved relative to pipeline file; inline paths should be absolute

**Three prewritten pipeline templates:**
- `pipeline-full-validation.json` — convert to Turtle then validate
- `pipeline-shape-dev.json` — generate test fixtures then validate with shapes
- `pipeline-profile-migration.json` — diff profile versions then generate fresh shapes

### Done — Phase 7 (per-shape JSON breakdown for `validate`)

**`--samples <n>`** flag added to `validate`. When `--format json` and `--samples > 0` (default 3 in JSON mode):
- Turtle report files are auto-enabled (`setExportTurtleValidationReports(true)`) before the run
- After validation, all `*__report.ttl` files in `outputDir` are parsed with Jena
- Results aggregated per (sourceShape, sourceConstraintComponent): count + bounded sample of focus nodes
- Shape groups sorted by count descending, included in JSON as `"shapes": [...]`

No changes to `ValidationTools` — all logic is in `ValidateCommand.extractShapeGroups()`. The TTL files written by `saveValidationReportTurtle` store all values as string literals, so the Jena model API is used directly (no SPARQL needed).

**JSON schema addition:**
```json
"shapes": [
  {"shapeId": "...", "constraint": "sh:MinCount...", "path": "cim:...", "count": 15,
   "sampleFocusNodes": ["http://...#node1", "http://...#node2", "http://...#node3"]}
]
```

### Done — Phase 8 (warm HTTP daemon)

**`cimpal serve [--port 7474] [--host localhost]`** — starts a local HTTP server using the built-in JDK `com.sun.net.httpserver` (zero extra dependencies, available in every JRE).

**API:** `POST /<command>` with request body = same JSON as the command's config file. Response = the command's `--format json` stdout output. `GET /health`, `GET /commands`, `POST /shutdown` utility endpoints.

**Thread safety:** single-threaded executor — requests serialised to avoid races on `ValidationTools` static flags. A single validation run saturates CPU via its own internal worker pool, so serialisation is the right trade-off for local use.

**Zero Core changes** — the serve command just writes the request body to a temp config file and calls the picocli command in-process (same mechanism as `run`).

**Port 7474** (default). Server binds to `localhost` only — not accessible from the network without an explicit `--host 0.0.0.0` override.

### Done — Phase 9 (MCP server)

**`cimpal mcp`** — MCP 2024-11-05 stdio server. 10 typed tools (validate, sparql, compare, compare_instances, convert, rdfs_to_shacl, organize, excel_to_shacl, gen_instances, manifest).

**Protocol:** newline-delimited JSON-RPC 2.0 on stdin/stdout (standard MCP stdio transport). Handles `initialize`, `notifications/initialized`, `tools/list`, `tools/call`, `ping`.

**Tool call execution:** same in-process mechanism as `serve` — writes arguments to temp config file, calls picocli command, captures stdout, returns as MCP content block. Commands supporting `--format json` automatically use it; others return a minimal `{"exitCode":N,"status":"..."}` envelope.

**Claude Desktop config** at `CimPal-CLI/configs/claude-desktop-config.json` — copy the `mcpServers.cimpal` block into `claude_desktop_config.json`.

**Zero extra dependencies** — `--debug` flag logs message traffic to stderr.

### CLI complete — all planned commands implemented

All 13 commands (`validate`, `sparql`, `manifest`, `convert`, `rdfs2shacl`, `compare`, `compare-instances`, `excel2shacl`, `organize`, `gen-instances`, `run`, `serve`, `mcp`) build successfully from a single fat JAR with no external runtime dependencies beyond JRE 25.

### Remaining work — Part B (Validation Agent)

The CLI is the foundation the agent builds on. The agent itself:
- Uses the MCP tools through Claude's tool-call mechanism
- Calls `validate` → reads shape groups → calls `sparql` to inspect focus nodes → edits SHACL → calls `gen_instances` + `validate` for fixture testing → promotes to full mapping run
- Enterprise Architect UML stays read-only; agent only modifies SHACL, RDFS, and mapping tables
- Each change arrives as a git branch with a commit message tracing it to the violation

### Phase 8 — Warm daemon

Long-running HTTP service (Javalin/Undertow) wrapping Core classes. Zero Core changes needed. Add a `serve` command to CimPal-CLI that starts the HTTP server on a configurable port.

### Phase 6 — Warm daemon

Long-running HTTP service (Javalin/Undertow) wrapping Core classes. Zero Core changes needed — the structure already supports it.

### Phase 5 — Per-shape JSON breakdown

Enhance `--format json` on `validate` to include per-shape violation groups with bounded focus-node samples. Requires reading the Excel report back or accumulating data in `ValidationTools` during the run.

### Phase 6 — Pipeline / `run` command

YAML pipeline format chaining: `convert → organize → validate → report`. Designed fresh (Task Wizard format is not reusable).

### Phase 7 — Warm daemon

Long-running HTTP service wrapping the same Core classes. Already structured for this — no Core changes needed. Just a Javalin/Undertow front-end.

---

## Technical decisions

### JSON not YAML for config files

The plan said YAML or JSON. JSON was chosen because:
- No extra dependency (`jackson-dataformat-yaml` + SnakeYAML)
- Jackson 3 tree-model API works without reflection/module `opens` directives
- JSON is simpler for AI to generate accurately
- `_note_*` keys provide documentation inline without YAML's comment support

YAML can be added later by adding `jackson-dataformat-yaml` to the CLI pom and detecting by file extension in `ConfigLoader`.

### `_comment` and `_note_*` as documentation keys

The Jackson `readTree()` + `.path("key")` pattern reads only named keys; unrecognized keys are silently ignored. `_comment` (file-level description) and `_note_<fieldname>` (field-level explanation) are the convention used throughout. Any key starting with `_` can be used freely.

### Package naming — capital C

All new CLI classes use `eu.griddigit.CimPal.cli.*` (capital C). This matches the directory path on disk (`CimPal\cli\`) and the existing `ManifestService` convention (`eu.griddigit.CimPal.generators`). Earlier attempt at lowercase package caused `ClassNotFoundException` at runtime because JAR ZIP entries are case-sensitive.

### Exit code 1 means violations, not tool failure

Exit code 1 is a validation result (violations found), not a tool error. CI scripts must distinguish between codes 1 and 2+. The validate docs explain this explicitly.

### stdout / stderr split for JSON mode

When `--format json` is used on `validate`, progress output from `ValidationTools` (which goes to `System.out`) is redirected to `System.err` before running. The JSON summary is printed to stdout after the run completes. This lets CI scripts pipe stdout to a file and still see progress on the terminal.

### `hasDifference()` on `RDFCompareResult` is inverted

`RDFCompareResult.hasDifference()` returns `true` when the entries list is EMPTY (no differences found). The method name implies the opposite. When working with compare results, use `result.getEntries().isEmpty()` for clarity.

### `ShaclAutoTester.setValidationOptions(workers, maxResults)` API

For the manual workflow, pass explicit workers rather than 0 when auto-sizing from memory. The `ShaclAutoTester` does not do memory-aware auto-sizing internally (unlike `ValidationTools`). For CLI: if workers == 0 (auto), use `Runtime.getRuntime().availableProcessors()` as the fallback.

---

## How to build

```bash
# Build everything (GUI + CLI)
mvn package -DskipTests

# Build CLI fat-JAR only (much faster)
mvn package -pl CimPal-CLI -am -DskipTests

# The built JAR is at:
CimPal-CLI/target/CimPal-CLI.jar

# Verify it runs:
java -jar CimPal-CLI/target/CimPal-CLI.jar --help
```

### Requirements

- JDK 25 (exactly — the modules are compiled with `--release 25`)
- Apache Maven 3.9+
- Internet access for first build (Maven downloads dependencies)

### Module-info and the fat JAR

`CimPal-CLI/src/main/java/module-info.java` declares `module CimPal.CLI`. The shade plugin strips `module-info.class` from the fat JAR so it runs in the unnamed module (no JPMS enforcement at runtime). The `module-info.java` is only used during compilation.

---

## Key file locations

```
CimPal/
├── docs/
│   ├── PROJECT.md               ← THIS FILE — update after every session
│   └── cli/
│       ├── index.md             ← CLI doc table of contents
│       ├── README.md            ← CLI overview, exit codes, config format
│       ├── validate.md          ← validate command reference
│       ├── sparql.md            ← sparql command reference
│       ├── manifest.md          ← manifest reference
│       ├── ci-pipeline.md       ← CI command collection (fill in paths)
│       └── shape-dev-loop.md    ← shape development workflow
│
├── CimPal-CLI/
│   ├── pom.xml                  ← dependencies: picocli, jackson, slf4j
│   ├── configs/
│   │   ├── validate-mapping-cgmes30.json   ← annotated template
│   │   ├── validate-manual.json            ← annotated template
│   │   ├── validate-timestamped.json       ← annotated template
│   │   └── sparql-query.json               ← annotated template
│   └── src/main/java/
│       ├── module-info.java
│       └── eu/griddigit/CimPal/
│           ├── generators/ManifestService.java   ← legacy entry point (preserved)
│           └── cli/
│               ├── CimPalCli.java               ← root @Command, main()
│               ├── ExitCode.java                ← 0/1/2/3 constants
│               └── command/
│                   ├── ValidateCommand.java      ← validate subcommand
│                   ├── SparqlCommand.java        ← sparql subcommand
│                   ├── ManifestCommand.java      ← manifest subcommand
│                   ├── ConvertCommand.java       ← [Phase 3]
│                   ├── RdfsToShaclCommand.java   ← [Phase 3]
│                   └── CompareCommand.java       ← [Phase 3]
│
├── CimPal-Core/src/main/java/eu/griddigit/cimpal/core/
│   ├── converters/
│   │   ├── RDFConverter.java        ← RDF format conversion
│   │   └── SHACLFromRDF.java        ← RDFS → SHACL generation
│   ├── comparators/
│   │   ├── ComparisonRDFSprofile.java
│   │   ├── ComparisonSHACLshapes.java
│   │   └── ComparisonRDFSprofileCIMTool.java
│   ├── models/
│   │   ├── RDFConvertOptions.java
│   │   ├── RDFtoSHACLOptions.java
│   │   ├── RdfsModelDefinition.java
│   │   ├── RDFCompareResult.java
│   │   └── RDFCompareResultEntry.java
│   ├── utils/
│   │   ├── ValidationTools.java     ← ~6000 lines, main validation engine
│   │   ├── ValidationEngine.java    ← enum
│   │   ├── SparqlTools.java
│   │   ├── ModelFactory.java
│   │   └── CompleteDatatypeMapLoader.java
│   ├── shacl_tools/
│   │   ├── ShaclAutoTester.java
│   │   ├── ShaclFromXls.java        ← Map overload (CLI); Preferences overload delegates to it (GUI)
│   │   ├── ShapeDataBuilder.java    ← constructShapeData() extracted from Main.ShaclTools
│   │   └── ShaclOrganizer.java      ← splitShaclPerXlsInput() extracted from Main.ShaclTools
│   └── generators/
│       ├── ManifestGenerator.java
│       ├── InstanceDataWriter.java  ← write(Model, Map, OutputStream) — pure serialisation, no JavaFX
│       └── InstanceDataBuilder.java ← buildFromXls() → BuildResult(model, saveProperties, filename)
│
└── CimPal-Main/src/main/java/eu/griddigit/cimpal/main/
    ├── application/
    │   ├── MainController.java      ← global static state bag (do not add to)
    │   └── controllers/
    │       ├── ValidationByMappingController.java
    │       ├── RDFConvertController.java
    │       ├── RDFStoSHACLController.java
    │       └── ... (other tabs)
    └── core/
        ├── ShaclTools.java          ← [Phase 4] needs move to Core
        └── ModelManipulationFactory.java  ← [Phase 4] needs move to Core
```

---

## Next steps — Phase 3 detail

Implement `convert`, `rdfs2shacl`, and `compare` commands. All three call Core classes directly with no refactoring.

### `convert` command

Core: `RDFConverter(RDFConvertOptions options).convert()` then `writeConvertedModel(OutputStream)`.

Minimum viable config:
```json
{
  "input": "path/to/source.xml",
  "output": "path/to/target.ttl",
  "sourceFormat": "RDFXML",
  "targetFormat": "TURTLE",
  "xmlBase": "http://iec.ch/TC57/CIM100"
}
```

Also support multi-file union: `"inputFiles": ["a.xml", "b.xml"]` → produces merged output.

Output format auto-detected from file extension if `targetFormat` not given.

### `rdfs2shacl` command

Core: `SHACLFromRDF(RDFtoSHACLOptions options).convert()` then `saveShapeModel(outputDir)`.

Tricky part: building `RDFtoSHACLOptions` requires:
- `rdfsModels` (ArrayList<Model>) — load each .rdf file with `RDFDataMgr.read()`
- `rdfsModelDefinitions` (List<RdfsModelDefinition>) — one per file; derive modelName from filename, extract nsPrefix/nsUri from owl:Ontology in the model
- `cimsNamespace` — the CIMS extensions namespace: `http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#` (bundled default, rarely changed)
- `iOprefix` / `iOuri` — IdentifiedObject prefix/URI (for shapes URI construction, can default to empty)

Config minimum:
```json
{
  "rdfsFiles": ["path/to/EQ.rdf", "path/to/TP.rdf"],
  "outputDir": "path/to/shapes/",
  "rdfsFormat": "2020"
}
```

### `compare` command

Core: instantiate the right `IRDFComparator` based on `--compare-type`, call `compare(modelA, modelB)`, output `RDFCompareResultEntry` list.

**Note on `hasDifference()`:** The method returns `true` when `entries.isEmpty()` (no differences). Use `result.getEntries().isEmpty()` for clear semantics.

Output: text table (item | type | property | fileA | fileB), JSON, CSV, or Excel.

Exit codes: 0 = identical, 1 = differences found, 2 = bad input, 3 = error.

---

## Known issues and limitations

**Per-shape JSON breakdown not yet implemented.** `--format json` on `validate` gives run metadata + totals only. Per-shape violation groups with bounded samples (as specified in the original plan) are deferred to Phase 5.

**`rdfs2shacl` namespace extraction may miss some profile structures.** Auto-extracting nsPrefix and nsUri from the owl:Ontology declaration works for standard CimSyntaxGen augmented RDFS. Non-standard profiles may need explicit config overrides.

**`ShaclAutoTester` progress output goes to stderr as raw percentage.** The callback API is functional but terse. A richer progress format (e.g. "model X of N: pass/fail") would require changes to the callback interface.

**Windows console encoding.** Some Unicode characters (em-dash etc.) display as `?` in the Windows console. Use ASCII-safe characters in CLI output strings.

**Test coverage is sparse.** The Core module has 5 test files covering ~3% of production code. CLI commands have no automated tests. Before any refactoring in Phase 4 (moving Main.core classes to Core), add characterisation tests that capture current output.

**`ManifestService.main()` backward compat caveat.** The old `--dir` / `--files` flags passed to the new main class (`CimPalCli`) will show help instead of running the manifest. Users must migrate to `manifest --dir ...`. The legacy `ManifestService` class still exists but is no longer the main class.
