<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# SEC-2 — Allowed roots and SPARQL SERVICE

| Field | Value |
| --- | --- |
| Status | Not started |
| Phase | 1 |
| Depends on | TEST-1 |
| Size | M |
| Branch | `feature/sec-2-...` |

## Goal

Close G2 (unrestricted file paths in serve/mcp/run) and G3 (SPARQL SERVICE bypassing the egress allowlist).

## Scope

- New Core `PathPolicy`
- `ServeCommand`, `McpCommand`, `RunCommand`
- `SparqlTools` and every caller that executes user SPARQL
- docs/cli/serve.md, mcp.md, run.md, sparql.md

## Acceptance criteria (status checklist)

- [ ] Every path-like field from serve/mcp/run input is checked against read/write roots before the command runs
- [ ] Traversal, outside-root absolute paths, symlink/junction escapes and UNC paths are rejected
- [ ] Existing outputs are not overwritten without `"overwrite": true`
- [ ] Direct CLI use is unchanged
- [ ] A SPARQL query with SERVICE is refused, and the stub server receives zero requests
- [ ] Finding on Jena 6.2 ARQ's default SERVICE behaviour recorded below

## Instructions for Claude Code

Start a session with: `Execute docs/plans/SEC-2.md` (in plan mode).

```text
Implement gaps G2 and G3.
G2: add a reusable PathPolicy in Core: a set of read roots and write roots; `checkRead(Path)` and `checkWrite(Path)` resolve with toRealPath (for writes: the real path of the nearest existing parent), reject anything outside the roots, reject symlink escapes, reject Windows device names and UNC paths unless explicitly allowed. Wire it into serve, mcp and run: every path-like field in request/tool/pipeline JSON is checked before the command runs. Flags: repeatable --root (read+write), --read-root, --write-root; default root = the working directory. Existing output files are not overwritten unless the request sets "overwrite": true. Plain CLI use (direct commands) is unchanged.
G3: find every place user-supplied SPARQL is executed (SparqlTools and callers). Verify whether Jena 6.2 ARQ executes SERVICE clauses by default and record the answer in this file; whatever the default, explicitly disable remote SERVICE execution in the query context, and route any future allowed use through the existing egress policy. Test: a SERVICE query against the StubHttpServer is refused and the stub receives zero requests.
Tests for G2: traversal (..), absolute path outside root, symlink/junction escape, UNC path, overwrite refused, allowed path accepted, for each of serve / mcp / run.
Update docs/cli/serve.md, mcp.md, run.md, sparql.md.
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
