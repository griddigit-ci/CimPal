---
description: Commit, rebase onto devel, verify, and open a PR for the current work package
argument-hint: "[WP-ID, default from the branch name]"
disable-model-invocation: true
---
<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# Ship the current work package ($ARGUMENTS)

1. Run `git status`. Commit all work for this ticket with a message prefixed by the ticket ID.
2. Run `git fetch origin && git rebase origin/devel`, and resolve any conflicts.
3. Run `mvn -q verify` and fix any failures.
4. Push, then run `gh pr create --base devel --fill` (non-draft).
5. Watch CI with `gh pr checks --watch` and fix red checks until they pass.
6. Report the PR URL and the final test count.
