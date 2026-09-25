---
description: Close out a work-package session - tick the plan checklist, log decisions and results, update the status table and docs/PROJECT.md, report test counts.
argument-hint: <WP-ID, e.g. TEST-1>
disable-model-invocation: true
---
<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# End the session for work package $ARGUMENTS

1. **Tests:** run `mvn -B verify`. Record the test count per module and compare it with the baseline
   from the start of the session. If it's red, stop and report; don't mark anything done.
2. **`docs/plans/$ARGUMENTS.md`:**
   - Tick each acceptance-criteria box that is verifiably met. Leave the others unticked and say why
     in *Notes and results*.
   - Set the Status row (`In progress`, `In review` if a PR is open, `Done`, or `Blocked (<reason>)`)
     and fill in the Branch row.
   - Add a row to the *Decisions log* for each decision taken (date `YYYY-MM-DD`, decision, by
     maintainer or Claude Code).
   - Under *Notes and results*, record baseline and final test counts, findings and open items.
   - Don't edit the other sections.
3. **`docs/plans/README.md`:** update only this WP's Status cell. Tick an *Open decisions* item only
   if the maintainer decided it this session.
4. **`docs/PROJECT.md`:** update *Last updated*, then *Implementation status*, *Known issues* and
   *Next steps* where this work changes them.
5. **`docs/cli/*`:** update it if CLI flags, output or exit codes changed.
6. **Summarise for the user:** what changed (files and commits), test counts before and after,
   what is left open, and the suggested next WP from the README order. Don't push or open a PR
   unless asked.
