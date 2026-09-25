<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# SEC-3 — MCP output hygiene and external engines

| Field | Value |
| --- | --- |
| Status | Not started |
| Phase | 3 |
| Depends on | SEC-2 |
| Size | S |
| Branch | `feature/sec-3-...` |

## Goal

Close G5 (untrusted text flowing into agent context) and G9 (external engines resolved from PATH).

## Scope

- `McpCommand`, `ServeCommand` result shaping
- `PythonShaclValidator`, RUST_SHACL path
- docs/cli/mcp.md, validate.md

## Acceptance criteria (status checklist)

- [ ] Values copied from input data into tool results are length-capped, control characters stripped, and placed in labelled data fields
- [ ] mcp.md warns that results contain untrusted data and that write tools should stay behind client approval
- [ ] External engine executable is configurable by absolute path and logged at start; no shell string building
- [ ] Tests with an injection-style literal and with a fake executable

## Instructions for Claude Code

Start a session with: `Execute docs/plans/SEC-3.md` (in plan mode).

```text
G5: in McpCommand (and serve), cap the size of any value copied from input data into tool results (IRIs, literals, messages; e.g. 300 chars), strip control characters, and put sample values in clearly labelled data fields. Add a note to docs/cli/mcp.md that tool results contain untrusted data and that write tools (convert, gen_instances, organize, rdfs_to_shacl, excel_to_shacl, manifest) should stay behind approval in the MCP client. Add a test with a model whose literal contains an instruction-like string and CR/LF: the output is truncated/escaped.
G9: in PythonShaclValidator and the RUST_SHACL path, allow configuring the absolute executable path (flag + config key), log the resolved executable at start, never build a shell command string, and pass the worker script from a private temp dir. Tests with a fake executable.
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
