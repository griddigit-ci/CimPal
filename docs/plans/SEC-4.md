<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# SEC-4 — Governance and re-attestation

| Field | Value |
| --- | --- |
| Status | Not started |
| Phase | 3 |
| Depends on | SEC-1, SEC-2, SEC-3 |
| Size | S (maintainer signs) |
| Branch | `feature/sec-4-...` |

## Goal

Close G8: a disclosure policy and an attestation that matches the current architecture.

## Scope

- New `SECURITY.md`
- `SECURITY-SELF-ATTESTATION.md` → v1.2

## Acceptance criteria (status checklist)

- [ ] SECURITY.md with supported versions, private reporting, response targets, disclosure policy
- [ ] Attestation v1.2: AI-provenance banner kept; serve/mcp/run in scope; G1–G9 in §4 with status and linked tests; §4.6 cites CI runs; §5.3 updated
- [ ] Owner/approver fields left for the maintainer; a pre-signing checklist produced

## Instructions for Claude Code

Start a session with: `Execute docs/plans/SEC-4.md` (in plan mode).

```text
1. Create SECURITY.md: supported versions, how to report privately (GitHub private vulnerability reporting + a security contact address I will confirm), response targets by severity, disclosure policy.
2. Update SECURITY-SELF-ATTESTATION.md to v1.2: keep the AI-provenance banner; bring §2.1/§3.3 in line with reality (serve, mcp, run exist; network listener and agent interface are in scope); add findings G1–G9 in §4 with their current status and linked tests; update §4.6 with CI evidence (links to workflow runs); update §5.3 statuses. Leave owner/approver fields for me.
3. Produce a short checklist of what I must personally verify before signing §6, and put it in this file.
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
