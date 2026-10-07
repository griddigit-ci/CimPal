<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->
Release Scripts

New-ReleaseTag.ps1

Manual, on-demand script — nothing triggers it automatically. Run it yourself from a terminal (e.g. IntelliJ's built-in terminal) at the repo root whenever you're ready to cut a release:

    ./scripts/New-ReleaseTag.ps1

This is what starts the release process: it bumps versions, commits, tags, and pushes. The pushed tag is what triggers `.github/workflows/release.yml` on GitHub's side to build and publish the release (CimPal.exe, CimPal.jar, CimPal-CLI.jar). A second job then builds the Docker image from the released CimPal-CLI.jar, smoke-tests it, and pushes `ghcr.io/griddigit-ci/cimpal:<version>` and `:latest` (linux/amd64 and linux/arm64). If that job fails, the GitHub release is already out: fix the cause and re-run only the failed job.

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

Test-DockerImage.ps1

Smoke-tests a CimPal CLI Docker image (PowerShell 7, Docker running). Build the image first:

    mvn -B -pl CimPal-CLI -am package -DskipTests
    docker build -f CimPal-CLI/docker/Dockerfile -t cimpal:dev --build-arg CIMPAL_VERSION=<cimpal.version> --build-arg GIT_SHA=$(git rev-parse HEAD) .
    ./scripts/Test-DockerImage.ps1 -Image cimpal:dev

Without the two build arguments the version label check fails (docs/cli/docker.md has the full commands).

It checks:
- the version and the numeric non-root user;
- that no port is exposed and the binaries are read-only;
- a mapping validation of the `docker-smoke` fixture into a bind mount;
- that the `~/.cimpal` cache stays private per UID;
- that a remote `owl:imports` fails closed with `--network none`;
- MCP over stdio (also with `USE_SYSTEM_CA_CERTS=1`);
- `serve` as `docs/cli/docker.md` runs it (`--allow-remote`, token from `CIMPAL_API_TOKEN`, same port on the host loopback): `/health`, 403 for a remapped port, 401 without the token, and `POST /shutdown`.

It exits 1 if any check failed. The "Docker image" job in `ci.yml` and the release run it too. Its work folder is `CimPal-CLI/target/docker-smoke`.

Flags
- -Image <name>           : the image to test (required).
- -ExpectedVersion <ver>  : the version the CLI must report (default: `<cimpal.version>` from the root pom.xml).

bench/ (Python 3.10+, standard library only)

Benchmark of `validate` by model size (DEP-2): `gen_models.py` writes synthetic CGMES-like models with a known triple count and known violations, and `run_bench.py` runs `validate --stats` over them with different heaps and core counts and writes `results.csv` and `summary.md`. The sizing guide (docs/guide/sizing.md) is built from it, and `--budget` checks time and heap budgets for nightly runs. See bench/README.md.

    python scripts/bench/run_bench.py --sizes 100k --verify --no-matrix
    python -m unittest discover -s scripts/bench -p "test_*.py"

check_doc_links.py (Python 3.10+, standard library only)

Checks the relative links and #anchors in Markdown files (GitHub heading slugs); external URLs are not fetched. Default targets are docs/guide and README.md; the Docs workflow (.github/workflows/docs.yml) also checks docs/cli. Exits 1 and lists each broken link.

    python scripts/check_doc_links.py
    python scripts/check_doc_links.py docs/guide docs/cli README.md
