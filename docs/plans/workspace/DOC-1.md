<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DOC-1 — Plans folder restructure

| Field | Value |
| --- | --- |
| Status | Not started |
| Phase | 0 (run before the next WP) |
| Depends on | — (no open PRs as of 2026-10-02) |
| Size | S |
| Branch | `docs/doc-1-plans-restructure` |

## Goal

Group the work packages by track in subfolders of `docs/plans/`, keep git history (`git mv`), and update every file that points at a plan path, so that `Execute docs/plans/<track>/<ID>.md`, `/wp-footer <ID>` and `/end-session <ID>` all work.

Claude Desktop already wrote the new files (untracked in the working tree): `docs/plans/deployment/` (README + DEP-1..DEP-10), `docs/plans/security/README.md` (with the gaps table), `docs/plans/testing/README.md`, `docs/plans/ci/README.md`, `docs/plans/workspace/README.md` and this file. Their links already point at the final locations.

## Target layout

```
docs/plans/
  README.md              master index: tracks, ONE status table for all WPs, open decisions, standard footer
  workspace/  README.md  A1.md  DOC-1.md
  ci/         README.md  CI-1.md  CI-2.md
  security/   README.md (gaps G1–G9)  SEC-1.md … SEC-5.md
  testing/    README.md  TEST-1.md … TEST-6.md
  deployment/ README.md (plan, requirements, decisions D-1…D-13)  DEP-1.md … DEP-10.md
```

Track from WP prefix: `A`, `DOC` → `workspace`; `CI` → `ci`; `SEC` → `security`; `TEST` → `testing`; `DEP` → `deployment`. Statuses live only in the master table; track READMEs never carry status.

## Scope

- `git mv` of the 14 existing WP files
- Path and link updates inside the moved files
- `docs/plans/README.md` (master index)
- `CLAUDE.md` (*Current work*), `docs/PROJECT.md` (path references, development workflow, REST API section note)
- `.claude/skills/wp-footer/SKILL.md`, `.claude/skills/end-session/SKILL.md`, `.claude/agents/security-reviewer.md`, `.claude/rules/security.md`
- No Java, pom or workflow changes

## Acceptance criteria (status checklist)

- [ ] New Desktop files committed on this branch, not on any other branch
- [ ] The 14 WP files moved with `git mv`; `git log --follow docs/plans/security/SEC-1.md` shows the earlier history
- [ ] Every `Execute docs/plans/<ID>.md` line uses the new path
- [ ] Cross-links fixed: TEST-2 → `../security/SEC-5.md`; SEC-5 text → `docs/plans/testing/TEST-2.md`; TEST-4 text → `docs/plans/testing/TEST-3.md`; any other hit from the grep in the instructions
- [ ] Master README rewritten as described; statuses refreshed from git (merged PRs → `Done`)
- [ ] Gaps table removed from the master README (now in `security/README.md`); security-reviewer agent and rules point there
- [ ] `/wp-footer` and `/end-session` resolve `docs/plans/<track>/<ID>.md` by prefix; tried with `SEC-1` and `DEP-1` (dry read, no edits)
- [ ] `CLAUDE.md` and `docs/PROJECT.md` updated
- [ ] Link check: every relative link in `docs/plans/**` resolves, and every `docs/plans/...` path mentioned anywhere in the repo exists (script output in notes)

## Instructions for Claude Code

Start a session with: `Execute docs/plans/workspace/DOC-1.md` (in plan mode).

```text
Restructure docs/plans/ into track subfolders (layout and prefix→track map in this file). Docs only.
0. git status. The untracked Desktop files under docs/plans/{deployment,security,testing,ci,workspace}/ must end up on branch docs/doc-1-plans-restructure off devel. Make sure devel is up to date and contains the merged SEC-1, SEC-2, TEST-2, SEC-5 and any TEST-3 work; if the current feature branch has uncommitted work, stop and ask. If any Desktop file was accidentally committed on another branch, report it.
1. git mv: A1 → workspace/; CI-1, CI-2 → ci/; SEC-1..SEC-5 → security/; TEST-1..TEST-6 → testing/. Commit the moves alone first ("docs(plans): move WPs into track folders") so rename detection is clean, then do content edits in a second commit.
2. In every moved file: the "Start a session with: `Execute docs/plans/<ID>.md`" line → new path. Then grep the whole repo (excluding .git, target) for `docs/plans/` and for relative links `](<ID>.md)`; fix each hit (known: TEST-2 → ../security/SEC-5.md, SEC-5 → docs/plans/testing/TEST-2.md, TEST-4 → docs/plans/testing/TEST-3.md, PROJECT.md "docs/plans/TEST-2.md and SEC-5.md").
3. docs/plans/README.md (master index), keep the licence header and the standard footer text:
   - Intro: handoff explanation; layout tree; sessions start with `Execute docs/plans/<track>/<ID>.md`.
   - "Tracks" table: Workspace, CI, Security, Testing, Deployment → link to each track README, one-line purpose.
   - "Work packages" table: columns Phase | ID | Track | Work package | Depends on | Size | Status. All existing rows with links updated to the track paths; add DOC-1 (phase 0) and DEP-1..DEP-10 (phases D0, D1, D2; titles, dependencies and sizes from deployment/README.md; Status `Not started`, DEP-8 `Not started (optional)`). Refresh existing statuses from git: `git log --oneline devel` / merged PRs; a WP whose PR is merged becomes `Done ([PR #n](…))`.
   - Replace the "Security gaps" section with one line linking security/README.md#security-gaps-referenced-by-the-wps.
   - "Open decisions": keep the existing items; add one line "Deployment decisions D-1…D-13: see deployment/README.md#open-decisions-maintainer".
   - Ordering note: the security/testing phases as before; the deployment track runs D0 → D1 → D2 and D0 can start any time.
4. CLAUDE.md "Current work": WPs are grouped by track in docs/plans/<track>/ (workspace, ci, security, testing, deployment); start with docs/plans/README.md; sessions start with `Execute docs/plans/<track>/<ID>.md`; /wp-footer <ID> and /end-session <ID> take the bare ID.
5. Skills: in wp-footer and end-session replace `docs/plans/$ARGUMENTS.md` with "the WP file `docs/plans/<track>/$ARGUMENTS.md` (track from the ID prefix: A/DOC→workspace, CI→ci, SEC→security, TEST→testing, DEP→deployment; if unsure, glob docs/plans/*/$ARGUMENTS.md)". end-session step 3 still updates only this WP's Status cell in docs/plans/README.md.
6. .claude/agents/security-reviewer.md: gaps G1–G9 are in docs/plans/security/README.md. .claude/rules/security.md: "SEC-1 and SEC-2 in docs/plans/security/".
7. docs/PROJECT.md: development-workflow paragraph mentions the track folders and the deployment track; at the top of "REST API — discovery and implementation plan" add: "Superseded by the deployment track (docs/plans/deployment/, DEP-5 to DEP-8). Kept as background." Update Last updated.
8. Link check: a short script (scripts/check-plan-links.py, stdlib only, licence header) that checks every relative link in docs/plans/** and every `docs/plans/...` path mentioned in *.md under the repo. Run it; paste the output in Notes.
No mvn run needed for docs-only changes unless the hook triggers; say so in the summary.
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
| 2026-10-02 | Moves are done by Claude Code with `git mv` (history kept) rather than copied from Desktop, because Claude Code was active on a feature branch and Desktop cannot run git. Branch name `docs/doc-1-…` instead of `feature/…` because it is docs-only. | Maintainer |

## Notes and results

(Claude Code: record findings, baseline numbers and open items here.)
