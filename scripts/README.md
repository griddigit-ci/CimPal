<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
Release Scripts

New-ReleaseTag.ps1

Manual, on-demand script — nothing triggers it automatically. Run it yourself from a terminal (e.g. IntelliJ's built-in terminal) at the repo root whenever you're ready to cut a release:

    ./scripts/New-ReleaseTag.ps1

This is what starts the release process: it bumps versions, commits, tags, and pushes. The pushed tag is what triggers `.github/workflows/release.yml` on GitHub's side to build and publish the release (CimPal.exe, CimPal.jar, CimPal-CLI.jar).

What it does, step by step
1. Refuses to run if the working tree isn't clean (avoids releasing with uncommitted changes mixed in).
2. Reads the current version from `<cimpal.version>` in the root pom.xml.
3. Computes the next date-based version (YYYY.MM.DD.N), bumping N if a release already happened today (same-day hotfix).
4. Replaces that version string across all 6 pom.xml files (root, CimPal-Core, CimPal-Main, CimPal-CustomWriter, CimPal-CLI, CimPal-Coverage) - keeping the <parent><version> refs and the cimpal.version property in sync.
5. Runs `mvn -N validate` as a sanity check before committing anything.
6. Commits ("Bump version to X"), tags (annotated tag X), and pushes both the commit and the tag.

Flags
- -DryRun     : only prints the computed version: no files changed, nothing committed/tagged/pushed.
- -NoPush     : commits and tags locally but leaves the push to you.
- -Remote <name> : use a remote other than origin.

Examples
    ./scripts/New-ReleaseTag.ps1
    ./scripts/New-ReleaseTag.ps1 -DryRun
    ./scripts/New-ReleaseTag.ps1 -NoPush
    ./scripts/New-ReleaseTag.ps1 -Remote upstream

Update-CoverageBaseline.ps1

Records the coverage ratchet floors after a green `mvn -B verify`:

    ./scripts/Update-CoverageBaseline.ps1

For CimPal-Core, CimPal-Main and CimPal-CLI it reads `<module>/target/site/jacoco/jacoco.csv` and writes `<module>/coverage-baseline.properties` with `coverage.line.min` and `coverage.branch.min` set to the measured ratio minus 0.5 pp (floored to 4 decimals). The jacoco `check` in the parent pom fails `verify` when a module's coverage drops below its floors. Floors only go up; `-AllowDecrease` lowers them, and the commit should say why. Commit the updated properties files whenever coverage has risen.

Flags
- -Modules <names> : modules to update (default: CimPal-Core, CimPal-Main, CimPal-CLI).
- -Margin <ratio>  : allowed drop below the measured value (default 0.005 = 0.5 pp).
- -AllowDecrease   : allow a floor to go down.
