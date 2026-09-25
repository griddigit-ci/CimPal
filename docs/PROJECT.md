<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# CimPal — Project Reference Document

**Last updated:** 2026-09-25  
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
| `eu.griddigit.cimpal.core.utils.SHACLValidator` + `eu.griddigit.cimpal.core.models.SHACLValidationOptions` | Core | Builder-style facade for validating **one** dataset (files and/or a Jena model) against **one** set of shapes → `SHACLValidationReport`. Not a fit for "batch-test many independent model archives against shared shapes, one report each" — that's still `ShaclAutoTester`'s job (see below); this is for single-dataset/programmatic use. |
| `eu.griddigit.cimpal.core.presets.MappingValidationOptionsPresets` / `SHACLValidationOptionsPresets` | Core | CGMES 3.0 / 2.4.15 starting points for the two builders above. |
| `eu.griddigit.cimpal.core.utils.DatatypeMapPreset` | Core | Enum: `NONE`, `CGMES24_NC22`, `CGMES30_NC24`, `CGMES30_NC25`. `.load()` reads the matching bundled `.properties` file. |
| `eu.griddigit.cimpal.core.shacl_tools.ShaclAutoTester` | Core | Manual validation: SHACL files + model archives → Excel reports per archive. Deliberately untouched by the `MappingValidator`/`SHACLValidator` builder API (no equivalent "one report per archive" abstraction exists yet) — the CLI's `validate --workflow manual` still calls this directly. |
| `eu.griddigit.cimpal.core.utils.ValidationEngine` | Core | Enum: APACHE_JENA, PYSHACL, PYSHACL_OXIGRAPH, RUST_SHACL |
| `eu.griddigit.cimpal.core.utils.CompleteDatatypeMapLoader` | Core | Loads CGMES datatype maps from bundled classpath resources or .properties files. |
| `eu.griddigit.cimpal.core.interfaces.ShaclAutoTesterCallback` | Core | Callback: `updateProgress(double)` and `appendOutput(String)`. |
| `ValidationTools.ValidationRunSummary` | Core | Record: `reportPath`, `conforming`, `violations`, `errors`. Still used internally by `ValidationTools` and by `MappingValidator`, which unwraps it into `MappingValidationSummary`. |
| `ValidationTools.ValidationTimestampedRunSummary` | Core | Record: `reports` (List<Path>), `conforming`, `violations`, `errors`. Same relationship to `MappingValidationSummary` as above. |

**Static state in ValidationTools (thread safety concern):**  
`exportTurtleValidationReports` and `DEBUG` are `volatile boolean` statics. `DEBUG` is still a real concern for a concurrent server. `exportTurtleValidationReports` is now effectively resolved for known callers: as of 2026-09-25, `setExportTurtleValidationReports(...)` has **zero remaining callers** anywhere in the codebase (verified by repo-wide grep) — the GUI never called it, and the CLI's `ValidateCommand` was the last one, now switched to `MappingValidator`'s per-call `exportTurtleReports` builder option instead of the global switch. The setter and field still exist (the old positional `ValidationTools.validateByMapping(...)` overloads without an explicit boolean still read the static as their default, for any external caller not yet migrated to `MappingValidator`), but nothing in this repo mutates it anymore. Any concurrent HTTP server work should still keep single-threaded execution for `DEBUG`, or migrate it the same way.

### RDF conversion

| Class | Module | What it does |
|---|---|---|
| `eu.griddigit.cimpal.core.converters.RDFConverter` | Core | Format conversion. Call `convert()` then `writeConvertedModel(OutputStream)`. |
| `eu.griddigit.cimpal.core.models.RDFConvertOptions` | Core | Builder-style config. Formats: RDFXML, TURTLE, JSONLD. |

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

**Next steps:** CI-1 (PR build and test), then TEST-1 (test harness), following the phase order in
`docs/plans/README.md`.

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
| 8 | `serve` | Local HTTP daemon (JDK HttpServer, localhost:7474) |
| 9 | `mcp` | MCP 2024-11-05 stdio server, 10 typed tools |

All 13 commands in a single fat JAR (`CimPal-CLI/target/CimPal-CLI.jar`), no external runtime dependencies beyond JRE 25.

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

Avoids races on `ValidationTools` static flags (`exportTurtleValidationReports`, `DEBUG`). A single validation run already saturates CPU via its own internal worker pool, so sequential requests is the right trade-off for local developer use. Any concurrent REST API implementation must address this differently — see REST API section below.

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
│       ├── ci-pipeline.md           ← CI command collection (fill in paths)
│       └── shape-dev-loop.md        ← shape development workflow
│
├── CimPal-CLI/
│   ├── pom.xml
│   ├── configs/
│   │   ├── validate-mapping-cgmes30.json, validate-manual.json
│   │   ├── validate-timestamped.json, sparql-query.json
│   │   ├── convert.json, rdfs2shacl.json, compare.json
│   │   ├── compare-instances.json, excel2shacl.json, organize.json
│   │   ├── gen-instances.json
│   │   ├── pipeline-full-validation.json, pipeline-shape-dev.json
│   │   ├── pipeline-profile-migration.json
│   │   └── claude-desktop-config.json  ← MCP config for Claude Desktop
│   └── src/main/java/
│       ├── module-info.java             ← requires: picocli, jackson, poi, jdk.httpserver
│       └── eu/griddigit/CimPal/
│           ├── generators/ManifestService.java   ← legacy, preserved
│           └── cli/
│               ├── CimPalCli.java       ← root @Command
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
│   │        ExcelTools.java (importXLSX overloads added in Phase 4)
│   ├── shacl_tools/ShaclAutoTester.java, ShaclFromXls.java,
│   │              ShapeDataBuilder.java, ShaclOrganizer.java
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

**Test coverage is sparse.** Core has 5 test files covering ~3% of production code. CLI commands have no automated tests. Before any further Core refactoring, add characterisation tests capturing current output.

**`ValidateCommand`'s mapping/timestamped workflows now delegate to `MappingValidator` (2026-09-25).** They previously called `ValidationTools.validateByMapping`/`validateByTimestampedMapping` directly, built independently of (and two days before) the `MappingValidator`/`SHACLValidator` builder API added on 2026-09-23. Refactored so the CLI stops duplicating orchestration that now has a reusable home; verified with a real smoke-test run (synthetic model + SHACL shape, both text and `--format json --samples` modes) — flags, exit codes, JSON schema, and Excel/Turtle report output are unchanged. `validate --workflow manual` was deliberately left calling `ShaclAutoTester` directly — see the `ShaclAutoTester` row above for why. No automated regression test exists for this yet (see "Test coverage is sparse" above); the smoke-test fixtures used to verify this were not committed.

**`rdfs2shacl` namespace extraction may miss non-standard profiles.** Auto-extracting nsPrefix/nsUri from `owl:Ontology` works for standard CimSyntaxGen RDFS. Non-standard profiles need explicit config overrides (`--shapes-namespace-prefix`, `--shapes-namespace-uri`).

**`ShaclAutoTester` progress is terse.** The callback emits raw percentage; a richer format (model N of M: pass/fail) would require changes to the callback interface.

**`validate --samples` requires `--export-turtle`.** Per-shape detail is extracted by parsing the `*__report.ttl` files after validation. These are auto-enabled when `--samples > 0` in JSON mode, but they remain on disk as a side effect. This is by design (the AI Assistant also uses them) but should be documented clearly.

**`serve` serialises all requests.** The single-threaded executor prevents concurrent validation runs. For team use (multiple users sharing one server) this is a bottleneck. Addressed in the REST API plan below.

**`CimPal-CLI.jar` is locked while the MCP server runs.** When Claude Desktop has the CimPal MCP server running (`claude-desktop-config.json`), Windows locks `CimPal-CLI/target/CimPal-CLI.jar`, and `mvn package`/`verify` fails at CimPal-CLI with "Could not create modular JAR file". Quit Claude Desktop, or stop the `CimPal-CLI.jar mcp` processes, before a full build. A longer-term fix could have Desktop run a copied JAR instead of the build output.

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
| No authentication | Anyone on the machine can call it |
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
- Loop: `validate` (get shape groups) → `sparql` (inspect focus nodes) → diagnose cause → edit SHACL or RDFS → `gen_instances` + `validate --workflow manual` (fixture test) → `validate --workflow mapping` (full run)
- Enterprise Architect UML stays read-only; agent only modifies SHACL, RDFS, and mapping tables
- Each change arrives as a git branch with a commit message tracing it to the violation
- Stopping conditions: zero violations, no improvement over 2 iterations, iteration cap

The agent's write scope, stopping conditions, and branch/PR workflow are defined in the original plan document. The technical foundation for all of this is now in place.
