Release Scripts

New-ReleaseTag.ps1

Manual, on-demand script — nothing triggers it automatically. Run it yourself from a terminal (e.g. IntelliJ's built-in terminal) at the repo root whenever you're ready to cut a release:

    ./scripts/New-ReleaseTag.ps1

This is what starts the release process: it bumps versions, commits, tags, and pushes. The pushed tag is what triggers `.github/workflows/release.yml` on GitHub's side to build and publish the release (CimPal.exe, CimPal.jar, CimPal-CLI.jar).

What it does, step by step
1. Refuses to run if the working tree isn't clean (avoids releasing with uncommitted changes mixed in).
2. Reads the current version from `<cimpal.version>` in the root pom.xml.
3. Computes the next date-based version (YYYY.MM.DD.N), bumping N if a release already happened today (same-day hotfix).
4. Replaces that version string across all 5 pom.xml files (root, CimPal-Core, CimPal-Main, CimPal-CustomWriter, CimPal-CLI) - keeping the <parent><version> refs and the cimpal.version property in sync.
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