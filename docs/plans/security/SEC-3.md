<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# SEC-3 — MCP output hygiene and external engines

| Field | Value |
| --- | --- |
| Status | Done ([PR #86](https://github.com/griddigit-ci/CimPal/pull/86)) |
| Phase | 3 |
| Depends on | SEC-2 |
| Size | S |
| Branch | `feature/sec-3-mcp-hygiene` |

## Goal

Close G5 (untrusted text flowing into agent context) and G9 (external engines resolved from PATH).

## Scope

- `McpCommand`, `ServeCommand` result shaping
- `PythonShaclValidator`, RUST_SHACL path
- docs/cli/mcp.md, validate.md

## Acceptance criteria (status checklist)

- [x] Values copied from input data into tool results are length-capped, control characters stripped, and placed in labelled data fields (every string of an `mcp` result, 300 chars, U+241E; the result JSON stays `content[0]`, a fixed untrusted-data notice is `content[1]`)
- [x] mcp.md warns that results contain untrusted data and that write tools should stay behind client approval
- [x] External engine executable is configurable by absolute path and logged at start; no shell string building (`--python-executable`, `CIMPAL_PYTHON_EXECUTABLE`, `-Dcimpal.python.executable`; no config key, see D1)
- [x] Tests with an injection-style literal and with a fake executable

## Instructions for Claude Code

Start a session with: `Execute docs/plans/security/SEC-3.md` (in plan mode).

```text
G5: in McpCommand (and serve), cap the size of any value copied from input data into tool results (IRIs, literals, messages; e.g. 300 chars), strip control characters, and put sample values in clearly labelled data fields. Add a note to docs/cli/mcp.md that tool results contain untrusted data and that write tools (convert, gen_instances, organize, rdfs_to_shacl, excel_to_shacl, manifest) should stay behind approval in the MCP client. Add a test with a model whose literal contains an instruction-like string and CR/LF: the output is truncated/escaped.
G9: in PythonShaclValidator and the RUST_SHACL path, allow configuring the absolute executable path (start-up flag + environment variable; no config key, decision D1), log the resolved executable at start, never build a shell command string, and pass the worker script from a private temp dir. Tests with a fake executable.
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
| 2026-10-21 | D1: the interpreter is set by `--python-executable` on `validate`, `serve` and `mcp` (at start), `CIMPAL_PYTHON_EXECUTABLE`, and the existing `-Dcimpal.python.executable`. No per-request config key: it would let `mcp`/`serve` callers run any program. The WP text "flag + config key" is changed accordingly. | Maintainer (plan approval) |
| 2026-10-21 | D2: a relative or missing configured interpreter is refused: exit 2 with a clear message (flag; also the env/property at `serve`/`mcp` start), an `IOException` when Core resolves it. | Maintainer |
| 2026-10-21 | D3: the PATH fallback stays for CLI and GUI, but the probe prints `sys.executable` and that absolute path is what runs; one `[ENGINE]` stderr line names the interpreter and its source. | Maintainer |
| 2026-10-21 | D4: each string value (and field name) in an `mcp` result is capped at 300 chars plus `...`; control characters and NEL/LS/PS become U+241E; no cap on array lengths. | Maintainer |
| 2026-10-21 | D5: a second MCP content item carries a fixed untrusted-data notice; `content[0].text` stays the same JSON. | Maintainer |
| 2026-10-21 | D6: `serve` payloads are unchanged: its clients are programs that parse the JSON and `/v1` results are documented as the commands' output; documented in serve.md, guide/security.md and PROJECT.md *Known issues*. | Maintainer |
| 2026-10-21 | Found while testing: `sparql`, `compare`, `compare-instances` and `run` built JSON strings without escaping control characters (an ESC in a literal made `sparql --format json` invalid JSON, so `mcp` fell back to a text envelope). They now share `ValidateCommand.jsonStr`. Fixed in this WP because G5 relies on parsing the result. | Claude Code |
| 2026-10-21 | Security review (security-reviewer agent), all Low: fixed (1) `UntrustedText` also replaces C1 controls and invisible format characters (`\p{Cc}\p{Cf}`), while `LogSanitizer` keeps its narrower class; (2) the PATH probe and the worker run with the private temp folder as working directory, so a `pyshacl.py` in CimPal's working directory can't be imported by `python -c`; (3) clean-up of the worker folder can't be skipped by an interrupt or a failed delete. Accepted and recorded in PROJECT.md *Known issues*: the `[ENGINE]` line (interpreter path) reaches `serve` job logs; Windows' `CreateProcess` searches the working directory for a bare `python` (fixed in review 2). Not changed (Info): two field names with the same 300-char prefix would collapse. | Claude Code |
| 2026-10-21 | Security review 2 (coordinator): ten findings fixed or covered, see *Security review 2* below. Kept unchanged on purpose: every exception in `mcp`'s read loop still answers JSON-RPC `-32700 Parse error` (TEST-4 fixes the protocol errors); the cap is per value only, with no cap on total result size or element count (recorded in PROJECT.md *Known issues*); `serve` payloads (D6). | Coordinator |

## Notes and results

**Implementation (2026-10-21).** Core: `UntrustedText` (new), `PythonExecutable` (new), `PythonShaclValidator` (`resolveExecutable`, probe of `sys.executable`, private worker folder, capped stderr), `LogSanitizer.neutralise`. CLI: `PythonOptions` mixin on `validate`, `serve`, `mcp`; `McpCommand.callTool` parses the command output and passes every string through `forAgent`, adds the notice item, caps JSON-RPC error messages; `jsonStr` shared by sparql/compare/compare-instances/run. Docs: cli/mcp.md, cli/validate.md, cli/serve.md, guide/security.md, PROJECT.md.

**Test counts (`mvn -B verify`, Windows).** Before (69321d1): Core 484 (8 skipped), Main 65, CLI 477 (8 skipped) + 2 (serve-read-timeout) + 22 ITs (21 skipped). After the first pass: Core 511 (8 skipped), Main 65, CLI 486 (8 skipped) + 2 + 22 ITs (21 skipped). After merging devel (fac5828, TEST-3 convert/SPARQL tests) and security review 2 (`mvn -B clean verify`, Windows): Core 609 (11 skipped; 21 of them from review 2), Main 65, CLI 494 (8 skipped; 2 from review 2) + 2 + 22 ITs (21 skipped).

**Failing before, passing after** (each run first against the unchanged code):
- `PythonShaclValidatorTest` (fake interpreter: a `.cmd` on Windows, a shell script elsewhere, so it runs on both): `aRelativeConfiguredExecutableIsRefused` (before: `python` from PATH ran, no error), `aMissingConfiguredExecutableIsRefused` (before: "Cannot run program", not a clear message), `theWorkerRunsFromAPrivateFolderThatIsRemovedAfterwards` (before: worker in the shared temp folder, kept until JVM exit), `stderrInTheErrorIsCappedAndCannotForgeLines` (before: 14 KB of stderr with CR/LF/ESC in the message). After the security review: `thePathProbeRunsInThePrivateFolderNotTheWorkingDirectory` failed on the first fix's code. The resolution tests (`anAbsoluteConfigured…`, `theEnvironmentVariable…`, `thePropertyComesBefore…`, `aPathProbeRuns…`, `noUsablePathProbe…`) need the new API and failed by not compiling.
- `UntrustedTextTest`: did not compile before (new class); `c1ControlsAndInvisibleFormatCharactersAreReplaced` (4 cases) failed on the first fix's code.
- `McpCommandTest.anInstructionLikeLiteralReachesTheAgentCappedAndOnOneLine` (1,000-char literal with "Ignore previous instructions… SYSTEM:", CR/LF, ESC; before: full value, raw ESC, no notice) and `aRefusedPathNamedInTheErrorIsCappedAndOnOneLine` (before: 500+ char message, no notice); `anAbsolutePythonExecutableIsAccepted` (before: unknown option, exit 2).
- `ValidateCommandTest.anAbsolutePythonExecutableIsAccepted` (before: exit 2). `aRelativePythonExecutableIsBadInput` (mcp and validate) passed before too, because picocli answered the unknown option with exit 2; they pin the new check. `ServeCommandTest.aRelativePythonExecutableIsBadInputAtStart` also asserts the "absolute path" message, which the old code lacks. `McpCommandTest.aRelativeInterpreterInTheSystemPropertyIsBadInputAtStart` and `aToolCallCannotChooseTheInterpreter` were added after the review.

**Security review 2.** Each test was run with its fix reverted in the source (by edit, then restored) and failed, then passed with the fix:

| # | Severity | Finding | Test | Before (fix reverted) | Fix |
| --- | --- | --- | --- | --- | --- |
| 1 | Medium | `PythonExecutable.check` probed UNC and device paths (`\\host\share\…`, `//host/…`, `\/host/…`, `\\?\…`, `\\.\…`) | `PythonExecutableTest.uncAndDevicePathsAreRefused` (5 cases, no files created; hosts in `.invalid`) | 5 failures: "does not exist" after a file system (network) lookup | `PathPolicy.isUncOrDevice` before any `Path`/`Files` call; docs mcp.md, serve.md, validate.md |
| 2 | Low | Checked one path (symlink then `..`), returned another (lexical `normalize()`) | `PythonExecutableTest.theCheckedPathIsTheReturnedPath` | Skipped on this Windows machine (no symlink privilege); runs on Ubuntu CI | Normalise first, check and return the same value |
| 3 | Low | Windows `CreateProcess` searched the JVM's working directory for bare `python`/`py` | `PythonShaclValidatorTest.aBareCandidateIsLookedUpInTheSearchPathByCimPal`, `pathEntriesThatNameTheWorkingDirectoryAreLeftOut` | "No Python interpreter" (the OS lookup didn't use the given search path); `searchDirs` is new API | `searchDirs` (absolute `PATH` entries only) and `findProgram` (with `PATHEXT` on Windows); the probe starts an absolute file |
| 4 | Low | Probe had no timeout | `aPathCandidateThatHangsIsSkippedAfterTheProbeTimeout` (fake sleeps 60 s) | "execution timed out after 30000 ms" | Read on a virtual thread, `waitFor(10 s)`, kill the process tree, skip |
| 5 | Low | Broken pipe hid Python's error | `anInterpreterThatStopsReadingReportsItsStderrNotTheBrokenPipe` (fake prints `ModuleNotFoundError` and exits 1 unread; 200,000-triple model) | message without `ModuleNotFoundError` | Catch the write failure, wait, join stderr, rethrow with `stderrForMessage` |
| 6 | Low | stderr capped only after a full line | `stderrIsReadInChunksSoTextBeforeAFailureIsKept` (2 MB without newline, then the stream fails); `megabytesOfStderrWithoutALineBreakGiveABoundedMessage` | first: text lost (empty buffer); second passed before too | `copy` reads 8 KiB char chunks with the same 64 KiB trim |
| 7 | Low | `[MCP parse error]` / `[MCP fatal]` stderr lines unsanitised | `McpCommandTest.aMessageThatCannotBeParsedIsLoggedOnOneSanitisedLine` (ESC and BEL in the input) | logged line held the raw ESC | `LogSanitizer.forLog` |
| 8 | Low | Variation selectors and Hangul fillers passed `UntrustedText` | `UntrustedTextTest.variationSelectorsAndHangulFillersAreReplaced` (8 cases) | 8 failures | Added U+FE00-FE0F, U+E0100-E01EF, U+115F, U+1160, U+3164, U+FFA0 |
| 9 | Missing test | Fail closed; hermetic tool-call test | `aReportFollowedByAFailingExitIsAnErrorNotAPass`; `McpCommandTest.aToolCallCannotChooseTheInterpreter` now uses two fake interpreters (the configured one writes a marker and runs; the tool argument's never runs) | Pinned behaviour (passed before) | — |
| 10 | Missing test | JSON-RPC error message cap | `McpCommandTest.aJsonRpcErrorMessageIsCappedAndOnOneLine` | Pinned behaviour (passed before) | — |

**Coverage floors.** The CLI floor had never been raised by the earlier WPs (0.2847 line). Measured 79.0 % line / 65.4 % branch on Windows; set to 0.74 / 0.60, 5 pp under that rather than the usual 0.5 pp, because Ubuntu CI runs a different set of OS-dependent tests (symlinks, POSIX fake interpreter, Docker ITs). Raise it to the Ubuntu measurement minus 0.5 pp in a later change. Core keeps devel's higher floor (TEST-3, PR #84).

**Open:** fake-interpreter tests rely on `.cmd` handling on Windows and `/bin/sh` elsewhere (CI Ubuntu runs the POSIX branch, which also checks `drwx------` on the worker folder). No `serve`-side capping (D6).
