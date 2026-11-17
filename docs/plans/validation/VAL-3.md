<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# VAL-3 — CLI, serve, MCP and run

| Field | Value |
| --- | --- |
| Status | In review (branch local, not pushed; PR after VAL-2) |
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

- [x] `validate --workflow profiles --models <dir> --profiles-library <dir>[,<dir>] --output <dir>` runs the VAL-2 validator (decision V-1). New options, each with a config key: `--profiles-library` (`profilesLibrary`, array), `--boundary` (`boundaryFolders`, array), `--boundary-shapes-pattern`, `--common-shapes` (`commonShapes`, array), `--profile-namespace` (`profileNamespaces`, array), `--allow-unmapped` (`allowUnmapped`), and config-only `groups`. No `--group-by`: explicit `groups` in a config file replace the folder grouping (D2). Shared options keep their meaning; options that do not apply (`--mapping-csv`, `--constraint-files`, `--data-files`, `--previous-comparison`, `--incremental-timestamps`) are rejected with exit 2 when given with this workflow
- [x] `--dry-run` prints the plan: groups, reported and context files, shapes per file, skipped files, gaps, without validating
- [x] `--format json`: stdout is only the JSON. It has the totals the other workflows have, plus `byGroup`, `skipped`, `gaps` and `complete`; per-shape detail and `--samples` work as for `combined`; `--summary-file` writes the same document
- [x] Exit codes: 0 clean and complete, 1 violations (or `--violations-exit-code`), 2 bad input including unmapped profiles without `--allow-unmapped`, 3 internal error. Decide and document what a run with `complete: false` and no violations returns (recommended: 1, so an incomplete run can never look clean)
- [x] `PathGuard.FIELDS` has the new path keys: `profilesLibrary` (READ, checked element by element), `commonShapes` (READ_IF_EXISTS: a library file name or a path). `groups` is refused in requests instead (D1). Under `serve`/`mcp`/`run` a library, shape or data path outside the allowed roots is refused; regression tests with UNC, mixed separators and `..`
- [x] `CommandSchemas` (so the MCP tool schema and the OpenAPI document) describes the new workflow and keys; `CommandSchemasTest`, `JsonSchemaSmokeTest`, `CommandContractTest` and the OpenAPI equality test pass; the Airflow `tests/test_logic.py` check that parses `PathGuard.java` passes
- [x] Tests in `ValidateProfilesWorkflowTest`: flags, config file with explicit groups, dry run, JSON shape against `validate.schema.json`, exit codes, an MCP tool call, a `run` pipeline step, PathGuard refusals. **Not done:** a real `/v1` job (D4); `PackagedJarIT` lists no workflows
- [x] `serve` with uploaded inputs (DEP-7 workspaces): `docs/cli/serve.md` says how the library is supplied (a path under an allowed root). **The upload case is not tested** (D4)
- [x] Docs and the config template updated; a *Change log* row in `docs/PROJECT.md` with `skill: yes`

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
| 2026-11-17 | Plan approved, with explicit `groups` refused in serve, mcp and run. D8 of VAL-2 (a document without a profile is a listed skip, not incomplete) stays as it is: no answer yet. | Maintainer |
| 2026-11-17 | D1: `groups` holds paths inside objects, which `PathGuard`, its Airflow mirror and the serve heap estimate do not walk. It is read from configuration files on the command line only (`loadGroupsConfig`, kept apart from `loadConfig` so the request schemas, which list what `loadConfig` reads, do not offer it), and `PathGuard.check` refuses a `validate` request that has it, for serve, mcp and run alike. Core checks the group paths under the policy anyway (VAL-2). | Claude Code / maintainer |
| 2026-11-17 | D2: no `--group-by` flag. Grouping is by folder unless a config file gives `groups`. | Claude Code |
| 2026-11-17 | D3: exit codes. 0 only when every group checked everything and found nothing; an **incomplete** run exits 1 and `--violations-exit-code` never changes that 1 (as a mapping row with an error); findings or a partial run exit with `--violations-exit-code` (default 1); bad input 2 (also an unknown profile without `--allow-unmapped`, no document that can be validated, inconsistent groups); internal error 3. | Claude Code |
| 2026-11-17 | D4: serve was not tested with a real `/v1` job. Its path into the command is the same as mcp's and run's (`PathGuard.check`, then `validate --config` in process under the policy), and both of those are tested; `JobApiTest` uses fake runners. | Claude Code |
| 2026-11-17 | D5: options of the other workflows are refused with `profiles`, and the profile options are refused with the other workflows (exit 2), rather than ignored. | Claude Code |
| 2026-11-17 | Security review (security-reviewer agent, read-only): no High or Medium. Fixed: skipped-document and gap lines on stderr and in the dry run are now sanitised (Low ×2; the skip records were already sanitised when created in VAL-2, so the new test passed before the fix too). Added, as the review suggested: the `groups` refusal through mcp and through a `run` step, a network path as `commonShapes`, and network or absolute `--boundary` values that touch nothing. Not changed (Info): the command's catch-all prints the raw message and stack trace (existing, all workflows); `--dry-run --format json` prints text, as for every workflow. | Claude Code |
| 2026-11-17 | D6: the Python client models were regenerated with the pinned generator (`openapi-python-client==0.26.1`, installed in a private environment); `regenerate.py --check` passes. Only `validate_config.py` changed. | Claude Code |

## Notes and results

**Implementation (2026-11-17).** CLI: `ValidateCommand` (the `profiles` workflow: options, config keys, `loadGroupsConfig`, `parseGroups`, dry-run plan, `runProfilesWorkflow`, JSON and text output, exit codes), `PathGuard` (`profilesLibrary`, `commonShapes`, the `groups` refusal), `CommandSchemas`; `openapi/cimpal-v1.json` regenerated (`-Dopenapi.update=true`); `clients/python` model `validate_config.py` regenerated; `clients/airflow` `PATH_FIELDS`. Test resources: `cli-json/validate.schema.json` covers the new fields. New template `configs/validate-profiles.json`. Serve, mcp and run needed no code change: no production code outside `ValidateCommand` names a workflow.

**Test counts (`mvn -B verify -DexcludedGroups=gui`, Windows).** Before (VAL-2 branch): Core 790, Main 78, CLI 685 (10 skipped) + 3 + 37 ITs (21 skipped). After: Core 790, Main 78, CLI 697 (10 skipped; 12 in `ValidateProfilesWorkflowTest`) + 3 + 37 ITs (21 skipped); build green. Airflow `tests/test_logic.py` (which parses `PathGuard.java`): 29 passed, 1 skipped. Python client: `regenerate.py --check` passes; its tests were not run (the test dependency `respx` is not installed here). Coverage floors not raised.

**Open.** A first run on real data (an application-profiles-library checkout with submodules and ReliCapGrid's instance files). A `/v1` job with an upload (D4). VAL-2 D8 (documents without a profile) still waits for the maintainer.
