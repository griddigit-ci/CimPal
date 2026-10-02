<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# TEST-1 — Test harness

| Field | Value |
| --- | --- |
| Status | Done ([PR #43](https://github.com/griddigit-ci/CimPal/pull/43), merged 2026-09-30) |
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
- [x] `-Dsnapshot.update=true` rewrites golden files; otherwise mismatches fail with a readable diff
- [x] JaCoCo aggregate report produced by `mvn verify`; ratchet fails the build on a drop > 0.5 pp
- [x] MappingValidatorTest converted to the new helpers as the reference example
- [x] Baseline coverage per module recorded below

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
| 2026-09-29 | Merged `devel` (`b0402a0`, timestamped mapping update) into the branch before recording baselines, so the floors match what the PR will merge. | Maintainer |
| 2026-09-29 | The ratchet `check` lives in a `coverage-ratchet` profile activated by `${basedir}/coverage-baseline.properties`. The floors must not be declared in `<properties>`. The first version declared defaults of 0 there, and Maven substituted them into the check's configuration before the file was read, so the check always passed. The ratchet proof caught it. Modules without a baseline file have no ratchet. | Claude Code |
| 2026-09-29 | Snapshot normalisation adds `Normalizer.blankNodeLabels()` and `Snapshots.normalizeLiterals(...)`. The report writes the anonymous property shape's blank-node label as a string (`sh:sourceShape`, the workbook `Source` column), which changes on every run. | Claude Code |
| 2026-09-29 | The timestamped `MappingValidatorTest` pins all 4 workbooks the run writes (per-timestamp report, group summary, overall summary, comparison), not one. The old test only asserted "not empty". | Claude Code |
| 2026-09-29 | `SnapshotsTest` restores the run's `snapshot.update` value after each test instead of clearing it. Clearing it silently turned later classes in the same update run back into compare mode. | Claude Code |
| 2026-09-29 | Stopped tracking `.idea/compiler.xml`, `encodings.xml`, `jarRepositories.xml` and `vcs.xml` with `git rm --cached`. `.idea/` was already in `.gitignore`, but these four files were committed before the rule existed, so every Maven re-import showed as a change. IntelliJ regenerates them from the poms. | Maintainer |

## Notes and results

### Results 2026-09-29

**Tests:** 73 before TEST-1 (Core 65, Main 8, CLI 0), 95 after (Core 81, Main 9, CLI 5). Core's 81 includes 4 tests that came in from `devel` with `TimestampedValidationGraphTest`. `mvn -B clean verify` is green on Windows.

**Baseline coverage** (JaCoCo, `mvn -B clean verify`, Windows, after merging `devel`):

| Module | Line | Branch | Floor line / branch |
| --- | ---: | ---: | ---: |
| CimPal-Core | 28.41% (3218/11327) | 18.20% (1036/5692) | 0.2790 / 0.1770 |
| CimPal-Main | 4.69% (979/20882) | 1.39% (141/10174) | 0.0418 / 0.0088 |
| CimPal-CLI | 3.01% (66/2193) | 2.22% (33/1486) | 0.0250 / 0.0172 |
| CimPal-CustomWriter | no tests | no tests | none |

The aggregate report is at `CimPal-Coverage/target/site/jacoco-aggregate/index.html`. Core line coverage varied by 0.0002 between two runs (0.2841 vs 0.2839), probably because the timestamped path is threaded. That is well inside the 0.5 pp margin.

**Proofs:**
- **Ratchet.** With Core `coverage.line.min` raised to 0.2890, `verify` failed with "Rule violated for bundle CimPal-Core: lines covered ratio is 0.2839, but expected minimum is 0.2890". With the floor restored, it passed.
- **Update script.** With a floor set above the measured value, `Update-CoverageBaseline.ps1` kept the higher floor and printed a warning.
- **Snapshots.** They were generated twice with `-Dsnapshot.update=true` and the two sets were identical. When one line in `mapping-run__workbook.csv` was changed, the test failed with a `-6:`/`+6:` line diff and the rerun hint. The file was then restored.

**Commits:** `f39e4f4` (merge `devel`), `72b58a8` (JaCoCo, ratchet, script, CI), `676cc20` (MappingValidatorTest and snapshot helpers), `4a1fd05` (docs), `8da826d` (untrack `.idea`), plus the end-of-session docs commit. [PR #43](https://github.com/griddigit-ci/CimPal/pull/43) into `devel` was opened on 2026-09-29.

**Observed, not changed (no production changes in TEST-1):** in the timestamped run, the input `IGM_Test_EQ_20260101T0000Z.xml` produces `validation_report_IGM_Test_2026-01-01T00_30_00Z.xlsx`, so the report's timestamp is 00:30 rather than 00:00. The snapshot pins the current behaviour. Check whether it is intended (a half-hour slot?) when TEST-3 covers timestamped validation.

**Left open:**
1. PR #43: both CI legs passed on 2026-09-29 (windows-latest, ubuntu-latest), including the coverage ratchet on Ubuntu, so Ubuntu coverage is above the Windows floors. What's left is review and merge.
2. After merge, set the status to Done here and in `docs/plans/README.md`.
