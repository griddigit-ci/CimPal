<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DOC-2 — Docs/CLI consistency fixes (found 2026-10-20)

| Field | Value |
| --- | --- |
| Status | Not started |
| Phase | 2 (any time; small) |
| Depends on | — (rebase on devel after DEP-11/DEP-14 are merged if they touch the same docs) |
| Size | S |
| Branch | `feature/doc-2-consistency` |

## Goal

Make the docs and the machine-readable contracts match what CLI 2026.10.6.1 actually does, fix the two small CLI/MCP gaps found while building the cimpal skill, and make the "update the docs after every implementation" rule (CLAUDE.md, *After every implementation*; PROJECT.md *Change log*) part of the routine.

The findings were verified by Claude Desktop on 2026-10-20 by running the released `CimPal-CLI.jar` (2026.10.6.1) under OpenJDK 25 on Linux; they are listed in `docs/PROJECT.md` → *Known issues* → "Doc and behaviour drift found 2026-10-20".

## Scope

Code (small, CLI module only):
1. **`manifest` config support**: add `--config` (keys `dir`, `files`, `accessUrl`, `output`) so `manifest` works through `mcp`, `serve` (`POST /manifest`, `/v1/jobs`) and `run`, consistent with the other commands; or, if the maintainer prefers, remove `manifest` from the MCP tools and the serve/job command lists. Default: add `--config`.
2. **MCP tool schemas**: generate each tool's `inputSchema` from `CommandSchemas` (the source the OpenAPI document already uses) so every accepted key is listed (`stats`, `summaryFile`, `overwrite`, `format`, `constraintFiles`/`dataFiles`, `previousComparison`, `incrementalTimestamps`, …). Descriptions stay short.
3. **`validate --dry-run`**: print `incrementalTimestamps`.

Docs:
4. `docs/cli/mcp.md`: rewrite *The validation agent loop* step 5 (no `manual` workflow; fixtures via `gen-instances` + combined validation or the GUI rule test); add that `manifest` now accepts a config (or isn't a tool).
5. `docs/cli/README.md`: *Available commands* lists all 13 commands; *Template configs* lists all templates (or links to `index.md`); `--stats` phases include `report` (and any other phase the code emits — list them from `StatsJson` callers).
6. `docs/cli/compare.md`: document `N/A` / "New property: …" output of `--compare-type shacl` next to `(not present)` for `rdfs`.
7. `docs/cli/validate.md`: mapping CSV columns may be separated by `|` or comma; several constraint files in column 2 with `;`; document `--incremental-timestamps` in the flags table.
8. `docs/cli/serve.md`: `/health` example without a stale version number (e.g. `"version":"CimPal CLI <version>"`).
9. Root `README.md`: replace the old GUI feature list with a short current one (validation workflows, shape authoring, conversion, comparison, SPARQL, CLI/Docker/serve/MCP, Python SDK, Airflow) linking `docs/guide/capabilities.md`.
10. `docs/PROJECT.md`: remove the fixed items from the drift list; add a *Change log* row.

## Acceptance criteria (status checklist)

- [ ] `manifest` runs via `mcp` (`tools/call` returns exit 0 and the manifest path) and via `POST /manifest`; test in the CLI module
- [ ] MCP `tools/list` schemas equal the `CommandSchemas` keys; a test fails if they diverge (like `OpenApiSpecTest`)
- [ ] `validate --dry-run` prints `incrementalTimestamps`
- [ ] Doc items 4–9 done; `scripts/check_doc_links.py` green
- [ ] PROJECT.md drift list updated and a *Change log* row added (`skill: yes`)
- [ ] `mvn -B verify` green; test count before/after in Notes

## Instructions for Claude Code

Start a session with: `Execute docs/plans/workspace/DOC-2.md` (in plan mode).

```text
Execute docs/plans/workspace/DOC-2.md. Read CLAUDE.md, docs/PROJECT.md (Known issues → "Doc and behaviour drift found 2026-10-20") and this file first.
1. Branch feature/doc-2-consistency off devel. Record the baseline test count.
2. Tests first:
   - ManifestCommand: a test that runs `manifest --config <json>` with dir/files/accessUrl/output keys and checks the written manifest; an McpCommand test that tools/call "manifest" returns exitCode 0.
   - McpToolSchemaTest: tools/list inputSchema properties per tool == CommandSchemas keys for that command.
   - ValidateCommand dry-run output contains incrementalTimestamps.
3. Implement: --config for ManifestCommand (same config loading/precedence as the other commands; relative paths resolve against the config file; PathGuard fields for dir/files/output so serve/mcp/run check them); McpCommand builds inputSchema from CommandSchemas; dry-run prints incrementalTimestamps. Update CommandSchemas/openapi (-Dopenapi.update=true) for manifest.
4. Docs: items 4–9 of this file. Keep wording style of the existing pages.
5. Run /security-check-change (manifest now reachable via serve/mcp/run: path checks).
6. mvn -B verify. Then follow CLAUDE.md "After every implementation": PROJECT.md (Last updated, Known issues drift list, Change log row with skill: yes), this file's checklist, docs/plans/README.md status.
```

Standard footer (applies to every work package):

- Read CLAUDE.md, docs/PROJECT.md and this plan file first. Do not redo full codebase discovery.
- Start in plan mode: propose the plan, list the files you will touch, wait for approval.
- Work on branch feature/<wp-id>-<short-name> off devel. Do not push, tag or open a PR unless asked.
- Follow CLAUDE.md conventions (license header, package case, JPMS requires, logic in Core).
- Tests first where possible. Finish with `mvn -B verify` green and report the test count before and after.
- Run /security-review on the diff if the change touches I/O, network, processes, parsing or serve/mcp/run.
- At the end: tick this file's checklist, add to its decisions log, update docs/PROJECT.md (date, status, technical decisions, known issues, next steps, and a new *Change log* row) and docs/cli/*, docs/guide/*, config templates and MCP/OpenAPI schemas if behaviour changed (CLAUDE.md, *After every implementation*). Summarise what changed and what is left open.

## Decisions log

| Date | Decision | By |
| --- | --- | --- |
| 2026-10-20 | Default for `manifest`: add `--config` rather than drop it from MCP/serve (maintainer may override) | Claude Desktop (proposal) |

## Notes and results

(Claude Code: record findings, baseline numbers and open items here.)
