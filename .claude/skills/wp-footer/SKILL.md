---
description: Show and apply the standard work-package footer from docs/plans/README.md as a checklist at the start of a WP session.
argument-hint: <WP-ID>
disable-model-invocation: true
---
<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# Standard work-package footer ($ARGUMENTS)

Re-read the *Standard footer* section of `docs/plans/README.md`; it is the source of truth if it
differs from this list. Then work through it as a checklist and report each item's state:

- [ ] Read `CLAUDE.md`, `docs/PROJECT.md` and `docs/plans/$ARGUMENTS.md`. Don't redo full codebase
      discovery.
- [ ] Plan mode: propose the plan, list the files to touch, and wait for approval.
- [ ] Branch `feature/<wp-id>-<short-name>` off `devel`. No push, tag or PR unless asked.
- [ ] CLAUDE.md conventions: license header, package case, JPMS `requires`, logic in Core.
- [ ] Tests first where possible. Record the baseline test count now; finish with `mvn -B verify`
      green and report the count before and after.
- [ ] `/security-review` (and `/security-check-change`) if the change touches I/O, network,
      processes, parsing or `serve`/`mcp`/`run`.
- [ ] At the end, run `/end-session $ARGUMENTS`.
