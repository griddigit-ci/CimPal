<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# TEST-4 — CLI contract, packaged JAR and protocol tests

| Field | Value |
| --- | --- |
| Status | In progress (ready for PR) |
| Phase | 2 |
| Depends on | TEST-1 |
| Size | M |
| Branch | `feature/test-4-mcp-serve-run-tests` |

## Goal

Prove each of the 13 CLI commands honours its contract (exit codes, JSON output, config handling), that the shaded JAR really works, and that serve/mcp match their documented schemas.

## Scope

- CLI tests (in-process and failsafe)
- JSON Schemas under `CimPal-CLI/src/test/resources/fixtures/cli-json/` (D2; was `schemas/`)
- docs/cli/README.md (schemas as published contract)

## Acceptance criteria (status checklist)

- [x] The contract of all 13 commands (plus `id-map`) is tested: one parameterised `CommandContractTest` for the rules every command keeps, and per-command classes for the command-specific ones (D1; was "one contract test class per command (13)")
- [x] Packaged-JAR smoke test per command on both CI OSes (`PackagedJarIT`, failsafe, in `mvn verify`)
- [x] serve: every endpoint, happy and error paths (the existing serve tests, ~130, plus serve from the packaged JAR); mcp: initialize, tools/list (10 tools, schemas match), tools/call per tool, malformed JSON-RPC
- [x] L3/L5 columns of the TEST-3 matrix ticked

## Instructions for Claude Code

Start a session with: `Execute docs/plans/testing/TEST-4.md` (in plan mode).

```text
1. CLI contract (in-process picocli), one test class per command, all 13: --help exits 0; missing required input exits 2 with a message on stderr; violations exit 1; forced internal error exits 3; in --format json stdout is exactly one JSON document and validates against a JSON Schema checked into src/test/resources/schemas/; config keys starting with _ are ignored; config file and CLI flags give identical results.
2. Packaged smoke (maven-failsafe, after shade): run `java -jar CimPal-CLI.jar <cmd>` as a real process for each command on fixtures; assert exit code and outputs. This runs on both CI OSes.
3. Protocol: serve (every endpoint, happy + error paths, with the SEC-1 token) and mcp (spawn `mcp` over stdio: initialize, tools/list returns 10 tools whose inputSchema matches the checked-in schema, tools/call for each tool on fixtures, malformed JSON-RPC returns a proper error).
The JSON Schemas become the published contract: document them in docs/cli/README.md. Tick the L3/L5 columns in docs/plans/testing/TEST-3.md.
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
| 2026-10-22 | D1: one parameterised `CommandContractTest` for the uniform rules (`--help` exits 0; missing required input exits 2 with a message on stderr; in `--format json` stdout is exactly one JSON document matching its schema, and empty on bad input), plus per-command classes for command-specific rules (`CompareCommandTest`, `CompareInstancesCommandTest`, `ExcelToShaclCommandTest`, `OrganizeCommandTest`, `GenInstancesCommandTest` new; `ValidateCommandTest`, `SparqlCommandTest`, `RunCommandTest` extended). Shared `CliRun` helper (in-process run capturing exit code, stdout, stderr; or a forked JVM). The "13 classes" criterion is reworded. | Maintainer |
| 2026-10-22 | D2: the JSON Schemas stay in `fixtures/cli-json/` (beside `stats.schema.json`); new `validate`, `sparql`, `compare`, `compare-instances` and `run` schemas; `JsonSchemaSmokeTest` generalised; documented as the published contract in `docs/cli/README.md`. | Maintainer |
| 2026-10-22 | D3: exit 3 only through natural triggers (an output or summary file whose parent folder is a file); no production hooks. Commands without one are listed below as open. | Maintainer |
| 2026-10-22 | D4: fix the MCP JSON-RPC error handling in this WP, each test written first and seen failing: no `method` → -32600 (was -32601); a handler exception → -32603 (was -32700 "Parse error" with id null); notifications (no `id`) are never answered and never run a tool (an unknown one was answered; a `tools/call` notification ran the tool); invalid JSON → -32700 with id null and unknown method → -32601 pinned. | Maintainer |
| 2026-10-22 | D5: `PackagedJarIT` runs `java -jar ${project.build.directory}/CimPal-CLI.jar` in the existing failsafe execution (after shade), with the JDK running the build, so `mvn verify` checks the shaded JAR on Windows and Ubuntu. | Maintainer |
| 2026-10-22 | D6: unknown config keys stay silently ignored (as `_` keys are); typo detection (warn on an unknown key) is a possible later feature. | Maintainer |
| 2026-10-22 | Security review fixes in this WP (test first): mcp `"id": null` is -32600 and runs nothing; a `StackOverflowError` in a tool is -32603 and the server goes on; `--debug` traffic lines are sanitised; client responses (also with id null) are never answered; `serve` refuses a missing root with exit 2 before creating anything. | Claude Code |

## Notes and results

**Test counts** (`mvn -B clean verify`, Windows): CLI unit tests 500 → 620 (default surefire execution; plus 3 in `serve-read-timeout`); CLI integration tests 22 → 37 (21 skipped: the Postgres ITs without Docker, as before; `PackagedJarIT` adds 15); Core 609 and Main 85 unchanged. The first baseline run was stopped because it was compiling in-progress test files, so the CLI baseline is computed from the final reports (test cases whose method doesn't exist on `origin/devel`: 120 added). CLI coverage 79.25 % / 65.36 % → 84.72 % / 70.94 % (line / branch); floors raised to 0.7971 / 0.6593 (measured − 5 pp, because Ubuntu measures lower).

**New test support:** `CliRun`, `CliFixtures` (one small synthetic run per command, as flags and as a config; used in-process, by mcp `tools/call` and from the JAR), `CliSchemas`.

**Regression-break log** (each break made on purpose, the tests run, then reverted; `git diff` of production files checked clean afterwards):

| Break | Caught by |
| --- | --- |
| A progress line on stdout in JSON mode (`compare`) | `CompareCommandTest` (2), `CommandContractTest.jsonModePrintsExactlyOneDocumentMatchingTheSchema[compare]`, `AutomationOptionsTest.compareSummaryFileAndExitCode` |
| `validate` returns 0 on violations (combined workflow) | `ValidateCommandTest` (3 combined tests), `CommandContractTest` json[validate], `ConfigFileEquivalenceTest`[validate] |
| One config loader reads `_comment` (`sparql` took it as the query) | `ConfigFileEquivalenceTest.keysStartingWithAnUnderscoreChangeNothing[sparql]` |
| `hasViolations` dropped from the validate JSON (mapping workflow) | `ValidateCommandTest.jsonModePrintsOnlyTheSummary…` ("required property 'hasViolations' not found"), `AutomationOptionsTest` |
| `ServicesResourceTransformer` removed from the shade config | `PackagedJarIT`: 13 of 15 fail (every command but `--help`/`--version` and the missing-input case) |
| One tool (`gen_instances`) removed from `CommandSchemas` | `CommandSchemasTest.everyCommandOfTheSchemasHasItsSourceHere`, new `McpCommandTest.toolsListOffersAToolForEveryCommandThatServeRuns` (and `PackagedJarIT`'s 10 tools) |
| mcp dispatch swallows parse errors (no answer) | `McpCommandTest.invalidJsonIsAParseErrorWithANullId` |

**Exit 3 (D3).** Natural triggers tested: an output or `--summary-file` whose parent is a file (`validate`, `sparql`, `compare`, `compare-instances`, `excel2shacl`, `organize` (output folder), `gen-instances`), and `run` with a failing step (documented: a step with exit 2 or 3 makes the pipeline exit 3). Open (no natural trigger without a hook): `convert`, `rdfs2shacl`, `manifest`, `id-map`, `serve` and `mcp` (their exit 3 is the out-of-memory path, covered by `OutOfMemoryExitTest`).

**Suspected bugs found (not fixed; `@Disabled` failing tests, as in TEST-3):**
- `sparql`: a query with a syntax error exits 3 with a stack trace, not 2 (`QueryParseException` is not an `IllegalArgumentException`) — `SparqlCommandTest.aQueryThatDoesNotParseIsBadInputWithNothingOnStdout`.
- `gen-instances`: a workbook that is not a generation template (no Config sheet) exits 3, not 2 — `GenInstancesCommandTest.aWorkbookThatIsNotATemplateIsBadInput`. Similarly a malformed SHACL file for `organize` or RDFS file for `excel2shacl` is reported as exit 3 (not pinned).
- `manifest`: the catalog's `dct:identifier` is `getLocalName()` of its `urn:uuid:` IRI, so a UUID that starts with digits loses them (e.g. `d2db252-…`). Found by `ConfigFileEquivalenceTest`; **fixed** in this WP after it made that test flaky on CI (a digit-only first group also lost its dash, so the normaliser missed it): the catalog and a dataset without its own identifier now get the whole UUID (`ManifestGeneratorIriTest`). Also fixed after a CI run: on Linux/macOS an absolute access URL or `--dir` put the whole local path into `dcat:accessURL`; it now gives the last folder name, as on Windows.
- `mcp` sends `notifications/initialized` to the client after `initialize`; in MCP that notification goes from client to server. Clients seem to ignore it; left as is.

**gen-instances fixture:** built in code (`CliFixtures.instanceTemplate`): a Config sheet (prefixes, class sheet names, header class), a `FullModel` header sheet and a `Substation` sheet in the template layout. The output names instances relative to an undeclared `xml:base`, so tests parse it with that base.

**Security review** (`security-reviewer`, checklist: UNC, traversal, symlinks, fail-open, unsanitised logs, hostile JSON-RPC). No Critical/High/Medium. Low findings, each fixed test first (test seen failing, then green):
1. `serve` created a missing `--root` (as the parent of its default `.cimpal-work`) and wrote its token file before any root check, so a mistyped root or unmounted volume started a server on an empty folder (mcp and run refuse it). Fixed: roots are checked first, exit 2. `ServeCommandTest.aRootThatDoesNotExistIsBadInputAndNothingIsCreated` (plain, mixed separators, traversal; UNC through `\\localhost`, Windows only, already refused before the fix and pinned). Found when the first `CommandContractTest` ran `serve --root <missing>` in-process: it started a server on port 7474 and hung the build, and it overwrote the default token file `%LOCALAPPDATA%\CimPal\serve.token` once (2026-10-22 15:57). The test now uses a port of its own, a token file under `@TempDir` and a timeout.
2. mcp `"id": null` requests were dispatched (MCP forbids null ids; the answer looked like a parse error's): now -32600, nothing runs.
3. mcp `--debug` wrote raw input and output lines to stderr: now sanitised like other log lines.
4. A `StackOverflowError` in a tool (deeply nested Turtle in an input file) ended the mcp server (shown by a forked test with `-Xss256k`): now -32603 for that request and the server goes on.
5. Follow-up review: a client error response with `"id": null` got a -32600 answer: responses are now recognised first and never answered.
Open (Low, not fixed): with `--log-format json`, serve's missing-root error is a plain text line (the check runs before the JSON log is set up); `BufferedReader.readLine()` in mcp has no length limit (a huge line ends the server via the out-of-memory exit, by design); other non-OOM `Error`s still end mcp.

**Other open items:** `serve` jobs and a `StackOverflowError` were not examined; typo detection for config keys (D6).
