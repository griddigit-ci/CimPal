<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# TEST-4 — CLI contract, packaged JAR and protocol tests

| Field | Value |
| --- | --- |
| Status | Not started |
| Phase | 2 |
| Depends on | TEST-1 |
| Size | M |
| Branch | `feature/test-4-...` |

## Goal

Prove each of the 13 CLI commands honours its contract (exit codes, JSON output, config handling), that the shaded JAR really works, and that serve/mcp match their documented schemas.

## Scope

- CLI tests (in-process and failsafe)
- JSON Schemas under `CimPal-CLI/src/test/resources/schemas/`
- docs/cli/README.md (schemas as published contract)

## Acceptance criteria (status checklist)

- [ ] One contract test class per command (13)
- [ ] Packaged-JAR smoke test per command on both CI OSes
- [ ] serve: every endpoint, happy and error paths; mcp: initialize, tools/list (10 tools, schemas match), tools/call per tool, malformed JSON-RPC
- [ ] L3/L5 columns of the TEST-3 matrix ticked

## Instructions for Claude Code

Start a session with: `Execute docs/plans/TEST-4.md` (in plan mode).

```text
1. CLI contract (in-process picocli), one test class per command, all 13: --help exits 0; missing required input exits 2 with a message on stderr; violations exit 1; forced internal error exits 3; in --format json stdout is exactly one JSON document and validates against a JSON Schema checked into src/test/resources/schemas/; config keys starting with _ are ignored; config file and CLI flags give identical results.
2. Packaged smoke (maven-failsafe, after shade): run `java -jar CimPal-CLI.jar <cmd>` as a real process for each command on fixtures; assert exit code and outputs. This runs on both CI OSes.
3. Protocol: serve (every endpoint, happy + error paths, with the SEC-1 token) and mcp (spawn `mcp` over stdio: initialize, tools/list returns 10 tools whose inputSchema matches the checked-in schema, tools/call for each tool on fixtures, malformed JSON-RPC returns a proper error).
The JSON Schemas become the published contract: document them in docs/cli/README.md. Tick the L3/L5 columns in docs/plans/TEST-3.md.
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

## Notes and results

(Claude Code: record findings, baseline numbers and open items here.)
