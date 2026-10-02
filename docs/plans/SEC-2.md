<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# SEC-2 — Allowed roots and SPARQL SERVICE

| Field | Value |
| --- | --- |
| Status | In review (PR pending, stacked on SEC-1) |
| Phase | 1 |
| Depends on | TEST-1 |
| Size | M |
| Branch | `feature/sec-2-allowed-roots` |

## Goal

Close G2 (unrestricted file paths in serve/mcp/run) and G3 (SPARQL SERVICE bypassing the egress allowlist).

## Scope

- New Core `PathPolicy`
- `ServeCommand`, `McpCommand`, `RunCommand`
- `SparqlTools` and every caller that executes user SPARQL
- docs/cli/serve.md, mcp.md, run.md, sparql.md

## Acceptance criteria (status checklist)

- [x] Every path-like field from serve/mcp/run input is checked against read/write roots before the command runs
- [x] Traversal, outside-root absolute paths, symlink/junction escapes and UNC paths are rejected
- [x] Existing outputs are not overwritten without `"overwrite": true`
- [x] Direct CLI use is unchanged
- [x] A SPARQL query with SERVICE is refused, and the stub server receives zero requests
- [x] Finding on Jena 6.2 ARQ's default SERVICE behaviour recorded below

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
| 2026-10-01 | Default roots: `serve` and `mcp` use the working directory, as the plan proposes. That was an open decision; it is adopted here and the maintainer should confirm it. `run` uses the working directory plus the pipeline file's folder, because the pipeline templates point at data next to them. `--root`, `--read-root`, `--write-root` and `--allow-unc` are added to all three. `CimPal-CLI/configs/claude-desktop-config.json` now passes `--root`, because Claude Desktop's working directory is not the data folder. | Claude Code (maintainer to confirm) |
| 2026-10-01 | The path check runs at the entry points through a per-command registry of config keys (`PathGuard.FIELDS`, kept in sync with each command's file-type options by a test). It rewrites each value to the checked absolute path. Paths that Core resolves itself are checked through a process-wide active policy (`PathPolicy.runWith`), which `serve`, `mcp` and `run` set while a command runs. That is possible because they run one command at a time. With no active policy, as in direct CLI and GUI use, nothing changes. | Claude Code |
| 2026-10-01 | A refused path inside a validation is a row error, never a silent pass. At the entry point, a refused path is 403 (`serve`), an `isError` result (`mcp`), or exit 2 for that step (`run`). | Claude Code |
| 2026-10-01 | SPARQL `SERVICE` is refused, not routed through the egress policy: CimPal has no federated use for it. The query is checked before it runs, SERVICE is turned off per execution in `SparqlTools`, and it is turned off globally in the CLI and the GUI. The global switch also covers SHACL-SPARQL constraints and GUI updates. | Claude Code |
| 2026-10-01 | `serve` now passes `--format json` only to the four commands that have it. Before, the other six endpoints always failed with "Unknown option". `sparql` reports a refused query as exit 2 (bad input) instead of 3. | Claude Code |
| 2026-10-01 | Security review round: UNC detection covers any mix of two leading separators (`\/host`). Network paths are refused before any file-system probe, in `PathGuard` and in the Core hooks. Network `owl:imports` (`file://host/...`, `//host/...`) are refused everywhere, also in direct CLI and GUI use, and fail the row: just opening them can send the user's Windows credentials to that host. This is the one deliberate change to direct use. | Claude Code |
| 2026-10-01 | SERVICE in external engines: shapes whose `sh:select`/`sh:ask`/`sh:construct`/`sh:update` text contains SERVICE are refused before they go to pySHACL or Rust SHACL. The Python worker also disables rdflib's SERVICE evaluation and remote graph loading. The global Jena switch is also set in `ValidationTools` and `CimPalCli` static initialisers, so embedded and in-process use is covered. | Claude Code |
| 2026-10-01 | The overwrite rule covers output files named in the request. Files a command creates inside its output folder (reports, generated shapes, organizer output) are replaced, as on every validate run. `serve` checks paths again just before a queued command runs. A working directory that is the home folder or a drive root is never an implicit default root. | Claude Code |

## Notes and results

### Results 2026-10-01

**Tests:** 175 before SEC-2 (Core 92, Main 9, CLI 72 + 2), 269 after (Core 158, Main 9, CLI 100 + 2). `mvn -B clean verify` is green on Windows. The new tests are:
- **Core:** `PathPolicyTest` (29), `SparqlServicePolicyTest` (20), `ValidationPathPolicyTest` (17).
- **CLI:** `PathGuardTest` (17), `AllowedRootsTest` (6: serve, mcp and run end to end with a real `convert`), `RootOptionsTest` (4), `SparqlCommandTest` (1).

The symlink case skips on Windows without the symlink privilege; the junction case runs there. Each safeguard was mutation-checked: removing it fails its tests.

**Coverage:** Core line 28.4% → 29.7% and branch 18.2% → 20.1%. CLI line 22.6% → 29.0% and branch 16.7% → 21.6%. The floors were raised.

### Finding: Jena 6.2 ARQ and SERVICE

**Jena 6.2 ARQ executes SERVICE by default.** A probe ran `SELECT * WHERE { SERVICE <http://127.0.0.1:<port>/sparql> { ?s ?p ?o } }` through `QueryExecutionFactory.create(query, model)` against `StubHttpServer`. The stub received one `GET /sparql` request (`User-Agent: ApacheJena/6.2.0`). So any user query, or any `sh:sparql` constraint in user-supplied shapes, could reach any host, including loopback and private addresses, and bypass the egress allowlist (`ALLOWED_REMOTE_HOSTS`, `requirePublicHost`). With `Service.httpServiceAllowed` set to false, the same query fails and the stub receives nothing (`SparqlServicePolicyTest`).

### Security review

`/security-check-change` and the `security-reviewer` agent found:
- **3 High.**
  - Mixed-separator UNC (`\/host/share`) passed the check, and the preset-or-path fields probed it, which leaks NTLM.
  - Core probed `owl:imports` and mapping cells (`file://host/...`) before the policy check.
  - pySHACL and Rust SHACL run `SERVICE` outside Jena's control.
- **3 Medium.**
  - A relative preset-or-path value was resolved against the working directory outside the roots.
  - Files written inside output folders contradicted the documented overwrite rule.
  - A queued `serve` request was checked only when accepted, not again before it ran.
- **7 Low or design.**
  - The manual-workflow folder walk was not checked.
  - The SERVICE pre-check missed SELECT, GROUP BY, HAVING and ORDER BY expressions and LATERAL.
  - The global switch was set only in `main`.
  - The comma split applied to every path key.
  - The home folder could be an implicit default root.
  - `manifest`'s default output (latent).
  - A misplaced Javadoc.

All were fixed with regression tests, except the latent `manifest` default output, which is recorded below. Each fix was mutation-checked.

### Other findings (not fixed here)

- **An unresolvable `owl:imports` is only a warning.** When an import can't be resolved or is refused by the egress allowlist, the row is still validated, with those shapes missing. That can look like a clean result. The new network refusal throws instead. The general case belongs in TEST-2 (attestation findings about fail-closed imports).
- **`manifest`'s default output.** It writes `manifest.ttl` next to the input folder when no `output` is given. That is not reachable today (no `--config`), but it must be registered once `manifest` gets config support.

- **Step `config` key ignored.** No command reads a `config` key, so a `run` step's `config` file has no effect, even though `run.md` documented it. `run.md` now says so; this needs a fix in a later WP.
- **`manifest` can't run through `serve`, `mcp` or `run`.** It has no `--config` option, although `mcp` advertises a `manifest` tool.
- **`sparql` zip limits.** `sparql` opens `.zip` models through `ModelFactory` without the archive budget (`MAX_ZIP_ENTRIES`, `MAX_TOTAL_UNCOMPRESSED_BYTES`). A large zip sent through `serve`/`mcp` can exhaust memory. This is a candidate for TEST-2 or a follow-up WP.
- **Relative paths used to resolve against the temp directory.** Before SEC-2, relative paths in `serve`/`mcp`/`run` input resolved against the system temp directory, where the temp config is written. They now resolve against the root or the pipeline folder.

