<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# TEST-1 — Test harness

| Field | Value |
| --- | --- |
| Status | In progress (handoff 2026-09-29, see Notes) |
| Phase | 1 |
| Depends on | CI-1 |
| Size | M |
| Branch | `feature/test-1-harness` |

## Goal

Shared test infrastructure so every later work package can write fast, network-free, snapshot-based tests.

## Scope

- Core test-support package published as a Maven `test-jar`
- Snapshot helper (RDF isomorphism, Excel→CSV, JSON)
- StubHttpServer for remote-import tests
- JUnit/AssertJ/JSON Schema in CLI
- JaCoCo + coverage ratchet in all modules

## Acceptance criteria (status checklist)

- [x] CLI and Main tests can use the Core test-support classes
- [ ] `-Dsnapshot.update=true` rewrites golden files; otherwise mismatches fail with a readable diff
- [ ] JaCoCo aggregate report produced by `mvn verify`; ratchet fails the build on a drop > 0.5 pp
- [ ] MappingValidatorTest converted to the new helpers as the reference example
- [ ] Baseline coverage per module recorded below

## Instructions for Claude Code

Start a session with: `Execute docs/plans/TEST-1.md` (in plan mode).

```text
Build the shared test infrastructure. No production behaviour changes.
1. In CimPal-Core add a test-support package (test scope) with: TestModels (tiny synthetic EQ/SSH models and SHACL shapes built in code), Fixtures (loads src/test/resources/fixtures/<feature>/...), Snapshot (assertIsomorphic for RDF via Jena isomorphism; assertExcelEquals by flattening sheets to CSV; assertJsonEquals ignoring timestamps and absolute paths; update mode via -Dsnapshot.update=true), and StubHttpServer (JDK HttpServer on 127.0.0.1:0 serving fixture files and recording requests, for owl:imports tests).
2. Publish it as a test-jar so CimPal-CLI and CimPal-Main can depend on it.
3. Add JUnit 5 + AssertJ + a JSON Schema validator to CimPal-CLI tests; add one trivial CLI test that runs `CimPalCli --help` in-process and asserts exit 0.
4. Add JaCoCo to all modules with an aggregate report. Add a coverage ratchet: record current line/branch coverage per module in a checked-in file and fail `verify` if it drops by more than 0.5 pp.
5. Convert MappingValidatorTest to use the new helpers as the reference example.
Report the baseline coverage numbers per module in this file.
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
| 2026-09-25 | No production changes, so `StubHttpServer` can only prove refusals for now. The egress gate refuses loopback and there is no fetch seam. Positive-path fetch tests need a test seam in `ValidationTools`, which is a production change left to TEST-2/SEC-2. | Claude Code |
| 2026-09-25 | Test-support lives in `eu.griddigit.cimpal.core.testsupport`, published as a test-jar containing only that package, with `Automatic-Module-Name: CimPal.Core.testsupport`. Without that name the jar's derived name clashed with `CimPal.Core` and module-path test compiles ignored it. | Claude Code |
| 2026-09-25 | Core test compilation adds `--add-modules jdk.httpserver --add-reads CimPal.Core=jdk.httpserver` (testCompile only), so `StubHttpServer` compiles without adding `requires jdk.httpserver` to the production module descriptor. | Claude Code |
| 2026-09-25 | CLI tests run on the classpath (`useModulePath=false`), like Core. Main stays on the module path, and its tests can use the test-jar. | Claude Code |
| 2026-09-25 | The ratchet floors live in a per-module `coverage-baseline.properties` (`coverage.line.min`, `coverage.branch.min`), not one root file. Maven can't interpolate per-module key names, and the per-module form keeps the parent config generic. | Claude Code |
| 2026-09-25 | Dropped `Fixtures.copyAll`, because a directory can't be listed portably when the fixtures sit inside a jar. `Fixtures.copy` handles one file at a time. | Claude Code |
| 2026-09-25 | Library versions: jacoco 0.8.15, assertj 3.27.7, json-schema-validator 3.0.7 (Jackson 3), properties-maven-plugin 1.3.1, maven-jar-plugin 3.4.2. JUnit stays on 5.13.1. | Claude Code |

## Notes and results

### Handoff 2026-09-29: resume here

Work stopped partway through step 4 (JaCoCo). The previous session ran from the home folder, not the repo, so CLAUDE.md, settings, rules and hooks weren't loaded. Resume in a session started in `C:\GitHub\CimPal`.

**Test baseline before TEST-1:** 73 (Core 65, Main 8, CLI 0).

**Committed on `feature/test-1-harness`:**
1. `5307610`: Core test-support: `TestModels`, `Fixtures`, `Snapshots`, `Normalizer`, `StubHttpServer`, plus self-tests (`SnapshotsTest`, `StubHttpServerTest`, `TestModelsTest`). `ShapeSourceTest.importPointingAtLocalStub_isRefusedAndNeverRequested` added. Core pom: test-jar plus test deps (assertj, Jackson 3). Core = 76 tests.
2. `cd2b119`: CLI and Main consumers: `CimPalCliTest`, `ConvertCommandTest`, `JsonSchemaSmokeTest` (+ `fixtures/cli-json/summary.schema.json`), and a `WorkspaceRdfStoreTest` case using `TestModels`/`Snapshots`. CLI = 5, Main = 9. `mvn -B -pl CimPal-CLI -am test` resolves the Core test-jar in the reactor without `install`, which the Stop hook needs.

**Uncommitted in the working tree (step 4, not yet verified end to end):**
- Root `pom.xml`: `jacoco.version`, default `coverage.line.min`/`coverage.branch.min` = 0, properties-maven-plugin reading `${project.basedir}/coverage-baseline.properties`, jacoco `prepare-agent`, `report` (verify) and `check` (verify, BUNDLE LINE/BRANCH COVEREDRATIO), and the new module `CimPal-Coverage`.
- New `CimPal-Coverage/pom.xml`: pom packaging, depends on all four modules, `report-aggregate` at verify.
- `CimPal-Core/pom.xml`: test-jar `Automatic-Module-Name` fix.
- Last `mvn -B clean verify`: CustomWriter, Core (76) and Main (9) SUCCESS. CLI failed only because Claude Desktop's MCP server was locking `CimPal-CLI.jar` (the known issue). Quit Desktop before running it.
- Core coverage measured in that run: line 0.2751 (3107/11294), branch 0.1700 (965/5678). Main, CLI and aggregate aren't measured yet.

**Remaining steps:**
1. Quit Claude Desktop, then run `mvn -B clean verify` until green, with the aggregate report at `CimPal-Coverage/target/site/jacoco-aggregate/index.html`.
2. Write `scripts/Update-CoverageBaseline.ps1`. It reads each `<module>/target/site/jacoco/jacoco.csv` and writes `<module>/coverage-baseline.properties` with `min = baseline − 0.005` floored to 4 decimals and the measured baseline as a comment. It only raises floors, unless `-AllowDecrease`. Run it for Core, Main and CLI, then commit those files.
3. Add `CimPal-Coverage\pom.xml` to the POM list in `scripts/New-ReleaseTag.ps1`, or releases will break on the version mismatch. Update the "five pom.xml" wording in CLAUDE.md to six.
4. **Ratchet proof:** raise one floor by 0.01 and confirm `verify` fails with the JaCoCo check message, then restore.
5. `ci.yml`: the Ubuntu leg uploads `CimPal-Coverage/target/site/jacoco-aggregate/` as `coverage-report`, and the summary step adds coverage per module from `jacoco.csv`.
6. **Convert `MappingValidatorTest`:**
   - Use `TestModels.THING_SHAPES` / `VIOLATING_THING_MODEL`.
   - Keep the count asserts.
   - Add `Snapshots.forFeature("mapping-validation")` with `assertIsomorphic` on the `__report.ttl` and `assertExcelEquals` on the workbook, using `Normalizer.timestamps()` and `Normalizer.paths(tempDir)`, plus a regex for any duration columns found in the output.
   - Generate with `-Dsnapshot.update=true`, review, commit.
7. **Snapshot proof:** edit one snapshot line and confirm the diff failure, then restore.
8. **Docs:**
   - CLAUDE.md Testing section: test-support, snapshot flag, ratchet script.
   - `.claude/rules/testing.md`: point to `testsupport` and `src/test/resources/snapshots/<feature>/`.
   - Record baseline coverage per module below, tick the checklist, and update the README status and `docs/PROJECT.md`.
9. Push, open a PR into `devel`, and check that both CI legs are green and that Windows and Ubuntu coverage are within 0.5 pp.
