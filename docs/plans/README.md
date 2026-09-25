<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
# Work plans

This folder is the handoff between planning (Claude Desktop, CimPal Project) and implementation (Claude Code).

- Full plan with rationale, threat model and test methodology: [CimPal — Security & Testing Plan](https://claude.ai/code/artifact/19c44ed1-1c4d-48ab-be25-072492b6ffd5) (claude.ai doc).
- One file per work package (WP). Each has goal, scope, acceptance criteria as a checklist, the Claude Code instructions, a decisions log and a notes section.
- Claude Code: start a session with `Execute docs/plans/<ID>.md` in plan mode. Tick the checklist and update the Status row as you go. Keep the plan file and `docs/PROJECT.md` in sync at the end of the session.
- New WPs are written in Desktop and committed here before implementation starts.

## Status values

`Not started` · `In progress` · `In review` (PR open) · `Done` · `Blocked (<reason>)`

## Work packages

Order: CI and test harness first, then the two High server gaps, then broad test coverage, then supply chain and governance.

| Phase | ID | Work package | Depends on | Size | Status |
| --- | --- | --- | --- | --- | --- |
| 0 | [A1](A1.md) | Claude Code workspace setup | — | S | Done (branch `feature/a1-claude-workspace`, not merged) |
| 0 | [CI-1](CI-1.md) | PR build and test | — | S | Not started |
| 1 | [TEST-1](TEST-1.md) | Test harness | CI-1 | M | Not started |
| 1 | [SEC-1](SEC-1.md) | Harden serve | TEST-1 | M | Not started |
| 1 | [SEC-2](SEC-2.md) | Allowed roots and SPARQL SERVICE | TEST-1 | M | Not started |
| 2 | [TEST-2](TEST-2.md) | Regression tests for past security findings | TEST-1 | M | Not started |
| 2 | [TEST-3](TEST-3.md) | Characterisation (golden-master) tests | TEST-1 | L (one session per feature group) | Not started |
| 2 | [TEST-4](TEST-4.md) | CLI contract, packaged JAR and protocol tests | TEST-1 | M | Not started |
| 3 | [SEC-3](SEC-3.md) | MCP output hygiene and external engines | SEC-2 | S | Not started |
| 3 | [CI-2](CI-2.md) | Supply chain and release integrity | CI-1 | M | Not started |
| 3 | [SEC-4](SEC-4.md) | Governance and re-attestation | SEC-1, SEC-2, SEC-3 | S (maintainer signs) | Not started |
| 4 | [TEST-5](TEST-5.md) | Nightly deep tests | TEST-3 | M | Not started |
| 4 | [TEST-6](TEST-6.md) | GUI smoke tests | TEST-1 | M | Not started |

## Security gaps referenced by the WPs

| ID | Gap | Severity (estimate) | WP |
| --- | --- | --- | --- |
| G1 | `serve`: no auth, no Host/Origin/Content-Type check, unbounded body, open `/shutdown` | High | SEC-1 |
| G2 | `serve`/`mcp`/`run` accept any path; no allowed roots | High | SEC-2 |
| G3 | User SPARQL `SERVICE` may bypass the egress allowlist | Medium (verify) | SEC-2 |
| G4 | No timeouts, queue limits or memory guard in `serve`/`mcp` | Medium | SEC-1 |
| G5 | MCP results carry untrusted text into the agent's context | Medium | SEC-3 |
| G6 | `run` can nest `serve`/`mcp`/`run` | Low | SEC-1 |
| G7 | CI only on tags; tag-pinned actions; no Dependabot; SBOM not published; unsigned exe | Medium | CI-1, CI-2 |
| G8 | Attestation stale and unsigned; no `SECURITY.md` | Medium (governance) | SEC-4 |
| G9 | External SHACL engines resolved from `PATH` | Low (verify) | SEC-3 |

## Open decisions (maintainer)

- [ ] Token file location for `serve` (proposed `%LOCALAPPDATA%\CimPal\serve.token`, user-only ACL)
- [ ] Default allowed root for `mcp` (proposed: working directory, plus repeatable `--root`)
- [ ] Code-signing certificate for `CimPal.exe`
- [ ] Which ENTSO-E conformity models may be used in nightly scale tests (licence)
- [ ] Attestation signers (engineering owner, approver)

## Standard footer (for every WP session)

- Read CLAUDE.md, docs/PROJECT.md and this plan file first. Do not redo full codebase discovery.
- Start in plan mode: propose the plan, list the files you will touch, wait for approval.
- Work on branch feature/<wp-id>-<short-name> off devel. Do not push, tag or open a PR unless asked.
- Follow CLAUDE.md conventions (license header, package case, JPMS requires, logic in Core).
- Tests first where possible. Finish with `mvn -B verify` green and report the test count before and after.
- Run /security-review on the diff if the change touches I/O, network, processes, parsing or serve/mcp/run.
- At the end: tick this file's checklist, add to its decisions log, update docs/PROJECT.md (date, status, known issues, next steps) and docs/cli/* if CLI behaviour changed. Summarise what changed and what is left open.
