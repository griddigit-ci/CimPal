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
| 0 | [A1](workspace/A1.md) | Claude Code workspace setup | — | S | Done ([PR #39](https://github.com/griddigit-ci/CimPal/pull/39)) |
| 0 | [DOC-1](workspace/DOC-1.md) | Plans folder restructure | — | S | Done (branch `docs/option2-roadmap`) |
| 0 | [CI-1](ci/CI-1.md) | PR build and test | — | S | Done ([PR #40](https://github.com/griddigit-ci/CimPal/pull/40)) |
| 1 | [TEST-1](testing/TEST-1.md) | Test harness | CI-1 | M | Done ([PR #43](https://github.com/griddigit-ci/CimPal/pull/43)) |
| 1 | [SEC-1](security/SEC-1.md) | Harden serve | TEST-1 | M | In review ([PR #44](https://github.com/griddigit-ci/CimPal/pull/44)) |
| 1 | [SEC-2](security/SEC-2.md) | Allowed roots and SPARQL SERVICE | TEST-1 | M | In review ([PR #45](https://github.com/griddigit-ci/CimPal/pull/45), stacked on SEC-1) |
| 2 | [DOC-2](workspace/DOC-2.md) | Docs/CLI consistency fixes found on 2026-10-20 | — | S | In review |
| 2 | [TEST-2](testing/TEST-2.md) | Regression tests for past security findings | TEST-1 | M | In review ([PR #46](https://github.com/griddigit-ci/CimPal/pull/46); 4 findings reported) |
| 2 | [SEC-5](security/SEC-5.md) | Fix the findings reported by TEST-2 | TEST-2, SEC-2 | S | In review (PR #49) |
| 2 | [TEST-3](testing/TEST-3.md) | Characterisation (golden-master) tests | TEST-1 | L (one session per feature group) | In progress (validation and SHACL generation groups merged; convert and sparql groups on `feature/test-3-convert-sparql`; comparisons, gen-instances + manifest and kgcl open) |
| 2 | [TEST-4](testing/TEST-4.md) | CLI contract, packaged JAR and protocol tests | TEST-1 | M | Not started |
| 3 | [SEC-3](security/SEC-3.md) | MCP output hygiene and external engines | SEC-2 | S | In review (branch `feature/sec-3-mcp-hygiene`, not pushed) |
| 3 | [CI-2](ci/CI-2.md) | Supply chain and release integrity | CI-1 | M | Not started |
| 3 | [CI-3](ci/CI-3.md) | Docker image | CI-1, SEC-1 | M | In review ([PR #51](https://github.com/griddigit-ci/CimPal/pull/51)) |
| 3 | [SEC-4](security/SEC-4.md) | Governance and re-attestation | SEC-1, SEC-2, SEC-3 | S (maintainer signs) | Not started |
| 4 | [TEST-5](testing/TEST-5.md) | Nightly deep tests | TEST-3 | M | Not started |
| 4 | [TEST-6](testing/TEST-6.md) | GUI smoke tests | TEST-1 | M | In review (`feature/test-6-gui-smoke`) |

## Deployment track

Plans for external users: container, sizing, the async `/v1` API, a Python SDK and an Airflow provider, then (phase D3) making the REST service a shared production service. The rationale, requirements (R1–R22) and decisions D-1 to D-19 are in [`deployment/README.md`](deployment/README.md); phase D3's gaps and order are in [`deployment/OPTION-2-ROADMAP.md`](deployment/OPTION-2-ROADMAP.md).

| Phase | ID | Work package | Depends on | Size | Status |
| --- | --- | --- | --- | --- | --- |
| D0 | [DEP-1](deployment/DEP-1.md) | Container image (delta on CI-3) | CI-1, CI-3 | S | Done ([PR #52](https://github.com/griddigit-ci/CimPal/pull/52)) |
| D0 | [DEP-2](deployment/DEP-2.md) | Resource statistics, benchmark and sizing guide | DEP-1 (soft) | M | Done ([PR #54](https://github.com/griddigit-ci/CimPal/pull/54), [#55](https://github.com/griddigit-ci/CimPal/pull/55); released 2026.10.6.1) |
| D0 | [DEP-3](deployment/DEP-3.md) | CLI automation options and Airflow container pattern | DEP-1 | M | Done ([PR #60](https://github.com/griddigit-ci/CimPal/pull/60); manual Kubernetes walkthrough open) |
| D0 | [DEP-4](deployment/DEP-4.md) | External user guide, first edition | DEP-1, DEP-2, DEP-3 | M | Done (support policy reviewed 2026-10-22; Docker quickstart run open) |
| D1 | [DEP-5](deployment/DEP-5.md) | Async job API (`/v1`) and OpenAPI spec | SEC-1, SEC-2 | L | Done (PR #62) |
| D1 | [DEP-6](deployment/DEP-6.md) | Service deployment: proxy, tokens, probes, SIGTERM, logs, manifests | DEP-5, DEP-1 | M | Done (PR #64) |
| D1 | [DEP-7](deployment/DEP-7.md) | File exchange: job workspaces, upload and download | DEP-5 | M–L | Done (PR #66) |
| D1 | [DEP-8](deployment/DEP-8.md) | Concurrent workers in one JVM (optional) | DEP-5, D-8 | L | Done (PR #69) |
| D2 | [DEP-9](deployment/DEP-9.md) | Python SDK `cimpal-client` | DEP-5, DEP-7 | M | Done (PR #70) |
| D2 | [DEP-10](deployment/DEP-10.md) | Airflow provider | DEP-9, DEP-3 | M | Done (PR #71; PyPI publishing in PR #72) |
| D3 | [DEP-11](deployment/DEP-11.md) | Durable job store (PostgreSQL) | DEP-5, DEP-7, DEP-8; D-14, D-18 | L | In review (PR #76) |
| D3 | [DEP-12](deployment/DEP-12.md) | Shared queue across replicas (horizontal scale-out) | DEP-11; D-15, D-16 | L | Not started |
| D3 | [DEP-13](deployment/DEP-13.md) | Client identity, job ownership and quotas | DEP-6; D-17 | M | Done ([PR #75](https://github.com/griddigit-ci/CimPal/pull/75)) |
| D3 | [DEP-14](deployment/DEP-14.md) | Idempotent submission and completion webhooks | DEP-11 (soft), DEP-13 (soft); D-19 | M | In review (stacked on DEP-11) |
| D3 | [DEP-15](deployment/DEP-15.md) | Job isolation: one child process per job | DEP-8 | M–L | Not started |
| D3 | [DEP-16](deployment/DEP-16.md) | Load tests, capacity model and SLOs | DEP-2, DEP-9 | M | In progress (tooling done; full runs open) |

Recommended order for phase D3 (see the roadmap): a release first, then DEP-13 → DEP-11 → DEP-14 → DEP-16 → DEP-12 → DEP-15.

## Security gaps referenced by the WPs

The open gaps G1–G9 and the WPs that close them are listed in [`security/README.md`](security/README.md).

## Open decisions (maintainer)

- [x] Token file location for `serve`: `%LOCALAPPDATA%\CimPal\serve.token` on Windows, `~/.cimpal/serve.token` elsewhere, user-only (decided 2026-09-30, SEC-1)
- [ ] Default allowed root for `mcp` (proposed: working directory, plus repeatable `--root`)
- [ ] Code-signing certificate for `CimPal.exe`
- [ ] Which ENTSO-E conformity models may be used in nightly scale tests (licence)
- [ ] Attestation signers (engineering owner, approver)
- [ ] Make the GHCR package `cimpal` public after the first release that pushes it (CI-3)
- [ ] Phase D3 decisions D-14 to D-19 (store, queue, shared files, client identity, interrupted jobs, webhooks): recommendations in `deployment/OPTION-2-ROADMAP.md` §5

## Standard footer (for every WP session)

- Read CLAUDE.md, docs/PROJECT.md and this plan file first. Do not redo full codebase discovery.
- Start in plan mode: propose the plan, list the files you will touch, wait for approval.
- Work on branch feature/<wp-id>-<short-name> off devel. Do not push, tag or open a PR unless asked.
- Follow CLAUDE.md conventions (license header, package case, JPMS requires, logic in Core).
- Tests first where possible. Finish with `mvn -B verify` green and report the test count before and after.
- Run /security-review on the diff if the change touches I/O, network, processes, parsing or serve/mcp/run.
- At the end: tick this file's checklist, add to its decisions log, update docs/PROJECT.md (date, status, technical decisions, known issues, next steps, and a new *Change log* row) and docs/cli/*, docs/guide/*, config templates and MCP/OpenAPI schemas if behaviour changed (CLAUDE.md, *After every implementation*). Summarise what changed and what is left open.
