---
description: Secure-code review of the current branch's diff against CimPal's security controls (SECURITY-SELF-ATTESTATION.md §5.3 item 5). Use before finishing any change that touches archives, outbound requests, the host allowlist, processes, filesystem writes, CSV/report output, parsers, or serve/mcp/run.
argument-hint: "[base branch, default devel]"
---
<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# Security check for a change

1. **Collect the change.** Base = `$ARGUMENTS`, or `devel` if that's empty. Run
   `git diff <base>...HEAD` plus `git diff HEAD` for uncommitted work, and
   `git diff --name-only` for both.
2. **Classify it.** For each changed file, mark which §5.3 item 5 categories it touches:
   - [ ] Archive extraction (zip entry limits, nesting, path traversal)
   - [ ] Outbound requests (HTTP clients, `owl:imports`, workbook URLs, AI knowledge sources)
   - [ ] Remote-host allowlist / egress gate (`ALLOWED_REMOTE_HOSTS`, `requirePublicHost`)
   - [ ] Process execution (`ProcessBuilder`, external SHACL engines, git/python calls)
   - [ ] Filesystem writes (temp dirs, caches, output paths, user-supplied paths)
   - [ ] CSV / report emission (formula injection, sensitive data in reports)
   - [ ] Parser configuration (RDF/XML, XML external entities, Excel, JSON, SPARQL `SERVICE`)
   - [ ] Entry points `serve`, `mcp`, `run` (auth, path roots, limits, nesting)

   If nothing is ticked, report "No security-relevant change" with the file list and stop.
3. **Review.** Delegate to the `security-reviewer` agent. Pass it the full diff text, the ticked
   categories, and the instruction to check against `.claude/rules/security.md` and
   `SECURITY-SELF-ATTESTATION.md` §4 for regressions of fixed findings.
4. **Check the tests.** Every ticked category needs a regression test in the diff
   (`/add-regression-test`). List any category that doesn't have one.
5. **Report** a table of findings (severity, file:line, issue, fix) plus the missing tests. Don't
   fix anything automatically; propose the fixes and wait.
