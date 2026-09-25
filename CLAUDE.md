# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Read first

- `docs/PROJECT.md` — the living project reference (architecture, key classes, decisions, known issues, next steps). Treat it as the briefing; verify against the code before editing.
- `docs/cli/index.md` — CLI command status and per-command docs.
- Do not redo a full codebase discovery; start from those two and the source file relevant to the task.

## What CimPal is

Java 25 / Maven multi-module toolset by gridDigIt for CIM/CGMES semantic work: SHACL validation, RDFS→SHACL generation, RDF conversion, SPARQL, profile/instance comparison, Excel-driven shape authoring, CGMES instance generation and manifests. Licensed EUPL-1.2-or-later.

```
CustomWriter ← Core ← Main   (JavaFX GUI, fat JAR + launch4j exe)
                  ↖ CLI      (picocli fat JAR: validate, sparql, manifest, convert, rdfs2shacl,
                              compare, compare-instances, excel2shacl, organize, gen-instances,
                              run, serve, mcp)
```

- **CimPal-Core** — all reusable RDF/SHACL logic. No JavaFX, no GUI imports, ever.
- **CimPal-Main** — JavaFX GUI (FXML in `src/main/resources/fxml`, bundled RDFS/SHACL in `resources`). Uses PowSyBl.
- **CimPal-CLI** — headless entry point `eu.griddigit.CimPal.cli.CimPalCli`; example configs in `CimPal-CLI/configs/`.
- **CimPal-CustomWriter** — custom Jena RDF/XML serializers.

Key libs: Apache Jena 6.2.0, TopBraid SHACL 1.5.0, JavaFX 25, Apache POI, Jackson **3** (`tools.jackson.core` groupId — not `com.fasterxml`), picocli, JUnit Jupiter.

## Commands

Run from the repo root (Windows; Maven 3.9 and JDK 25 on PATH).

```bash
mvn -B package                                   # full build (what the release CI runs)
mvn -B package -DskipTests                       # faster full build
mvn -B -pl CimPal-Core -am test                  # Core unit tests (main test suite)
mvn -B -pl CimPal-Core -am test -Dtest=SHACLValidatorTest            # single test class
mvn -B -pl CimPal-CLI -am package -DskipTests    # → CimPal-CLI/target/CimPal-CLI.jar
mvn -B -pl CimPal-Main -am package -DskipTests   # → CimPal-Main/target/CimPal.jar + CimPal.exe
mvn -pl CimPal-Main exec:java                    # launch the GUI (after `mvn install -DskipTests`, so sibling modules resolve)
mvn -Psecurity-scan verify                       # OWASP dependency check (slow; fails on CVSS ≥ 7)
java -jar CimPal-CLI/target/CimPal-CLI.jar <subcommand> --help
```

Core tests run on the classpath (`useModulePath=false`) so they can reach package-private members — don't add test-only `opens` to `module-info.java`.

## Conventions

- **License header** on every new Java file (and on docs/config where the existing files have it):
  ```java
  /*
   * Copyright (c) 2020-2026 gridDigIt Kft.
   * Licensed under the EUPL-1.2-or-later.
   * SPDX-License-Identifier: EUPL-1.2+
   */
  ```
- **Package case must match the directory exactly.** Core/Main use `eu.griddigit.cimpal.*` (lowercase); CLI uses `eu.griddigit.CimPal.cli.*` / `eu.griddigit.CimPal.generators.*` (capital C). A mismatch compiles on Windows but fails at runtime from the fat JAR with `ClassNotFoundException`. Follow the module's existing package.
- **JPMS modules**: each module has a `module-info.java`. Adding a dependency or JDK API means adding the matching `requires` (e.g. `java.net.http` in Core, `jdk.httpserver` in CLI).
- **New logic goes in Core**, then is wired into Main and/or CLI. The direction is Main → delegates to Core (e.g. `ShaclTools` → `ShaclOrganizer`).
- **For validation, use the builder APIs** — `MappingValidator` + `MappingValidationOptions`, `SHACLValidator` + `SHACLValidationOptions` — rather than calling `ValidationTools` (~6000 lines) directly.
- **Do not add static fields to `MainController`** (legacy static state bag).
- GUI input fields get the "?" help icon: `GUIhelper.installHelpTooltip(...)`, following `ValidationByMappingController`.
- CLI: exit codes `0` ok, `1` violations found (a result, not a failure), `2` bad input, `3` internal error (`ExitCode`). In `--format json` mode, stdout must contain only the JSON; progress goes to stderr. Config files are JSON, and keys starting with `_` are documentation and ignored.
- Match the surrounding code's comment density and Javadoc style; keep diffs focused.

## Gotchas

- `RDFCompareResult.hasDifference()` is **inverted** (true when there are no entries). Use `getEntries().isEmpty()`; don't "fix" it without updating every caller.
- `ValidationTools` has static flags (`exportTurtleValidationReports`, `DEBUG`), which is why `serve` uses a single-threaded executor. Anything concurrent must deal with this.
- Never call Jena `TypeMapper.reset()`, and prefer `getSafeTypeByName()`. A reset once emptied the datatype map, typed every literal as `xsd:string`, and silently broke SHACL rules that use typed literals.
- A failed remote `owl:imports` fetch must surface as an error, never as a silent pass (a false-clean validation is the worst outcome here).
- `validate --samples` depends on the `*__report.ttl` files written by `--export-turtle`.

## Testing

Coverage is sparse: a handful of Core tests, a few in Main, none in CLI. Before refactoring Core behaviour, add characterisation tests that capture the current output. Build small synthetic RDF/SHACL fixtures under `@TempDir`, as `MappingValidatorTest` does, and don't depend on files outside the repo.

## Git and releases

- Day-to-day work happens on `devel` (or feature branches off it, PR'd into `devel`). `master` is the release branch.
- Don't commit, push, or open PRs unless asked.
- **Never run `scripts/New-ReleaseTag.ps1`** or push version tags unless explicitly asked. A pushed `YYYY.MM.DD.N` tag triggers `.github/workflows/release.yml`, which publishes a GitHub release.
- Versions live in all five `pom.xml` files plus `<cimpal.version>`; only the release script changes them.

## End of session

- Update `docs/PROJECT.md` (date, *Implementation status*, *Known issues*, *Next steps*) when the change affects them.
- When a CLI command's flags or behaviour change, update its page under `docs/cli/`.
