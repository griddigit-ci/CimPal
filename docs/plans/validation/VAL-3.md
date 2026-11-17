<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# VAL-3 — CLI, serve, MCP and run

| Field | Value |
| --- | --- |
| Status | Not started |
| Phase | V |
| Depends on | VAL-2 |
| Size | M |
| Branch | `feature/val-3-profiles-workflow` |

## Goal

The profile validator is usable from every integration layer: `validate --workflow profiles` on the command line, the `validate` tool of `mcp`, `/v1` jobs of `serve`, `run` pipelines, the Python SDK and the Airflow provider, with all contracts in step.

## Scope

- `ValidateCommand` (new workflow, new options), `CommandSchemas`, `PathGuard.FIELDS`, `openapi/cimpal-v1.json`, and whatever `ServeServer`, `McpCommand` and `RunCommand` need (expected: nothing beyond the schemas, since `validate` is already exposed; verify).
- `clients/python` (regenerate if the schema changed), `clients/airflow` (`_logic.py` `PATH_FIELDS` mirrors `PathGuard.FIELDS`).
- `CimPal-CLI/configs/validate-profiles.json`, `docs/cli/validate.md`, `docs/cli/index.md`, `docs/cli/mcp.md`, `docs/cli/serve.md`, `docs/guide/*` where validation is described, `docs/PROJECT.md`.

## Acceptance criteria (status checklist)

- [ ] `validate --workflow profiles --models <dir> --profiles-library <dir>[,<dir>] --output <dir>` runs the VAL-2 validator (decision V-1). New options, each with a config key: `--profiles-library` (`profilesLibrary`, array), `--group-by` (`groupBy`), `--boundary` (`boundary`, array), `--boundary-shapes-pattern`, `--common-shapes` (`commonShapes`, array), `--allow-unmapped` (`allowUnmapped`), and config-only `groups`. Shared options keep their meaning; options that do not apply (`--mapping-csv`, `--constraint-files`, `--data-files`, `--previous-comparison`, `--incremental-timestamps`) are rejected with exit 2 when given with this workflow
- [ ] `--dry-run` prints the plan: groups, reported and context files, shapes per file, skipped files, gaps, without validating
- [ ] `--format json`: stdout is only the JSON. It has the totals the other workflows have, plus `groups`, `skipped`, `gaps` and `complete`; per-shape detail and `--samples` work as for `combined`; `--summary-file` writes the same document
- [ ] Exit codes: 0 clean and complete, 1 violations (or `--violations-exit-code`), 2 bad input including unmapped profiles without `--allow-unmapped`, 3 internal error. Decide and document what a run with `complete: false` and no violations returns (recommended: 1, so an incomplete run can never look clean)
- [ ] `PathGuard.FIELDS` has the new path keys: `profilesLibrary` (READ, checked element by element), `commonShapes` (READ), and the paths inside `groups`. Under `serve`/`mcp`/`run` a library, shape or data path outside the allowed roots is refused; regression tests with UNC, mixed separators and `..`
- [ ] `CommandSchemas` (so the MCP tool schema and the OpenAPI document) describes the new workflow and keys; `CommandSchemasTest`, `JsonSchemaSmokeTest`, `CommandContractTest` and the OpenAPI equality test pass; the Airflow `tests/test_logic.py` check that parses `PathGuard.java` passes
- [ ] Tests: `ValidateCommandTest` (flags, config file, dry run, JSON shape, exit codes), `McpCommandTest` (a tool call with `workflow: profiles`, including output capping of values copied from data), `JobApiTest` (a `/v1` job), `PathGuardTest`, one `run` pipeline step, and `PackagedJarIT` if it lists workflows
- [ ] `serve` with uploaded inputs (DEP-7 workspaces): say in `docs/cli/serve.md` how the library is supplied (a path under an allowed root; uploading a whole APL checkout is not expected) and test that case
- [ ] Docs and the config template updated; a *Change log* row in `docs/PROJECT.md` with `skill: yes`

## Instructions for Claude Code

Start a session with: `Execute docs/plans/validation/VAL-3.md` (in plan mode).

```text
Read docs/plans/validation/README.md (decisions V-1 and V-4) and the notes of VAL-2. V-1 is decided: a workflow of validate, not a separate command.

1. Follow the "combined" workflow in ValidateCommand as the pattern: option declaration, loadConfig, applyDefaults, validation of required inputs, dry run, the JSON builder, exit-code mapping, StatsJson phases. Add the new workflow beside it; do not restructure the existing three.
2. Then walk the contracts in this order and keep each in step: PathGuard.FIELDS → CommandSchemas → openapi/cimpal-v1.json (regenerate the way CommandSchemasTest / PROJECT.md describe) → clients/airflow _logic.py PATH_FIELDS → clients/python generated models if they changed.
3. Check, do not assume, that serve, mcp and run need no code change: grep for the workflow names ("mapping", "timestamped", "combined") across CimPal-CLI/src and clients/.
4. Tests first for the contract parts (exit codes, JSON-only stdout, PathGuard refusals).
5. Run /security-check-change on the diff: this WP adds read paths reachable from serve and mcp.
6. Docs: docs/cli/validate.md gets a "profiles" section with a worked example against an APL checkout, the grouping rules, the list of what is skipped and why, and the two APL workarounds (boundary shapes, common shapes).
```

Standard footer: see [`../README.md`](../README.md#standard-footer-for-every-wp-session) (`/wp-footer VAL-3` at the start, `/end-session VAL-3` at the end).

## Decisions log

| Date | Decision | By |
| --- | --- | --- |

## Notes and results
