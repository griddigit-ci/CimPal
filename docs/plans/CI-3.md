<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# CI-3 — Docker image

| Field | Value |
| --- | --- |
| Status | In progress (implemented on the branch, not yet committed or run on GitHub) |
| Phase | 3 |
| Depends on | CI-1. SEC-1 before `serve` is advertised for network use; CI-2 for release gating. |
| Size | M |
| Branch | `feature/ci-3-docker-image` |

## Goal

Publish the CLI as a Docker image with every release, built from the released `CimPal-CLI.jar`, and keep the image tested between releases.

## Scope

- `CimPal-CLI/docker/`: `Dockerfile`, `Dockerfile.dockerignore`, `entrypoint.sh`
- "Docker image" jobs in `.github/workflows/ci.yml` and `.github/workflows/release.yml`
- `scripts/Test-DockerImage.ps1` and the fixture `CimPal-CLI/src/test/resources/fixtures/docker-smoke/`
- The CLI version, which was hard-coded as `2026.9` in `CimPalCli`, `ServeCommand` and `McpCommand`
- `docs/cli/docker.md`, `CimPal-CLI/configs/claude-desktop-config-docker.json` and links from the CLI docs

## Acceptance criteria (status checklist)

- [x] Runtime-only image: Temurin 25 JRE pinned by digest plus the fat JAR, non-root user, no Maven run inside Docker
- [x] `--version`, `serve` `/health` and the `mcp` `serverInfo` report the pom version (`CliVersionTest`, `CimPalCliTest`, `McpCommandTest`)
- [x] The smoke test passes locally and covers:
  - the version and the numeric non-root user;
  - no exposed port and read-only binaries;
  - a validation into a bind mount;
  - cache privacy per UID;
  - offline fail-closed `owl:imports`;
  - MCP over stdio (also with `USE_SYSTEM_CA_CERTS`);
  - `serve` on a loopback port.
- [x] CI builds `linux/amd64` and `linux/arm64` and runs the smoke test on every push and PR; nothing is pushed
- [x] Release job downloads the released JAR, checks its hash, smoke-tests it, and pushes `<tag>` (and `latest` if newest) to GHCR with SBOM and provenance; `packages: write` only on that job; actions and build images pinned
- [x] `docs/cli/docker.md`, the Docker MCP template, links from the CLI docs and `scripts/README.md`
- [ ] First CI run on GitHub green, including the "Docker image" job
- [ ] First release publishes the image, and the package is made public (manual step below)

## Instructions for Claude Code

Planned and implemented in one Claude Code session on 2026-10-01; this records the brief.

```text
Publish the CLI as a Docker image on every release tag. Image = Temurin 25 JRE + the released CimPal-CLI.jar
(copied, not rebuilt), non-root, multi-arch (amd64, arm64), on GHCR as ghcr.io/<owner>/cimpal:<tag> and :latest.
Add a CI job that builds it on every push/PR and smoke-tests it (version, user, validation into a bind mount,
mcp over stdio, serve on loopback). Fix the hard-coded CLI version so the image tag and the CLI agree.
serve stays undocumented for network use until SEC-1: loopback-only publish, no EXPOSE.
Document usage in docs/cli/docker.md. No secrets, no repository settings, nothing pushed.
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
| 2026-10-01 | Registry GHCR (`ghcr.io/griddigit-ci/cimpal`), pushed with `GITHUB_TOKEN`, so no new secrets. Docker Hub was the alternative. | Claude Code, proposal accepted by maintainer |
| 2026-10-01 | The Dockerfile only copies the JAR. The release job downloads the `CimPal-CLI.jar` it has just published, so the image holds exactly the released bytes. Building inside Docker would have needed every module pom (the reactor lists Main and Coverage) and a second Maven build. | Claude Code, proposal accepted by maintainer |
| 2026-10-01 | `linux/amd64` and `linux/arm64`, arm64 through QEMU with the binfmt image pinned by digest. Tags: the release tag and `latest`. | Claude Code, proposal accepted by maintainer |
| 2026-10-01 | Base `eclipse-temurin:25-jre-noble`, pinned by index digest. It ships fontconfig and DejaVu fonts, which POI's `autoSizeColumn` needs for the Excel reports. It has no curl, and one image serves one-shot runs, `mcp` and `serve`, so there is no `HEALTHCHECK`. | Claude Code |
| 2026-10-01 | Non-root user `USER 10001:10001` (`cimpal`; numeric for Kubernetes `runAsNonRoot`). `/home/cimpal` belongs to it alone. `entrypoint.sh` gives every other UID a private `mktemp -d` home and passes `-Duser.home`. Examples are `--user "$(id -u):$(id -g)"`, which Linux hosts use to own bind-mount output; root; and `ubuntu` (UID 1000, from the base image). A first version made `/home/cimpal` world-writable instead. The security review showed that would undercut the private-cache fix (attestation finding 6) for UIDs sharing a home. | Claude Code, accepted by maintainer |
| 2026-10-01 | `entrypoint.sh` runs the base image's `/__cacert_entrypoint.sh` with stdout pointed at stderr and stdin at `/dev/null`, and hands both back to the JVM. That hook `echo`es to stdout; called directly, the first line an MCP client received was "Using a temporary truststore at ...". | Claude Code |
| 2026-10-01 | No `EXPOSE`. `serve` is documented for loopback only until SEC-1: `-p 127.0.0.1:...` with `--host 0.0.0.0` inside, its own Docker network, read-only inputs, no secrets, Docker Engine ≥ 28 on Linux. | Claude Code, accepted by maintainer |
| 2026-10-01 | Release hardening from the security review: `latest` moves only when the tag is the newest release (`gh release view`), and image jobs share a concurrency group; the docker job checks the downloaded JAR against the SHA-256 the build job recorded; BuildKit (`v0.33.1`) and the SBOM scanner (`1.12.0`) are pinned by digest. | Claude Code, accepted by maintainer |
| 2026-10-01 | The CLI version comes from the filtered resource `cimpal-cli-version.properties` (`${project.version}`), so `New-ReleaseTag.ps1` needs no change. | Claude Code |
| 2026-10-01 | The smoke test is PowerShell like the other scripts (pwsh is on the Ubuntu runners). On Linux it runs the writing containers with the caller's UID, as the docs advise. | Claude Code |
| 2026-10-01 | Not in this WP: image scanning and signing (optional follow-ups), Dependabot for the base digest (goes to CI-2), a Python-engine variant. | Claude Code |

## Notes and results

- **Local run (Windows 11, Docker Desktop 29.8.1):** the image builds; Docker reports 516 MB (base 433 MB, JAR layer 46 MB). `Test-DockerImage.ps1`: 36 of 36 checks pass. A deliberately broken variant (`EXPOSE 7474`, world-writable home, writable JAR) fails 6 of them. The arm64 image builds and runs under emulation (`uname -m` = `aarch64`, correct version). actionlint finds nothing in `ci.yml` or `release.yml`.
- **Security review (`/security-check-change`):** no High; it confirmed no regression of the fixed findings in the attestation (§4). Fixed in this WP:
  - the Medium: `serve` reachable from other containers on the same network, now covered by the docs above;
  - five Lows: the world-writable home, the `latest` rollback, the unchecked JAR, unpinned BuildKit and SBOM-scanner images, and the leftover `serve` container in the smoke test;
  - the missing tests: no `EXPOSE`, cache privacy, offline fail-closed, read-only binaries, rejected sentinel.

  The sixth Low, updates for the pinned base image, is left to CI-2.
- **Tests:** `mvn -B verify` green (Windows): Core 81, Main 32, CLI 9, 0 failures; all coverage checks met. CLI tests 5 → 9 (`CliVersionTest` 2, `CimPalCliTest` +1, `McpCommandTest` 1). CLI coverage 3.0 % → 5.6 % line, 2.2 % → 2.8 % branch; its floors were raised with `Update-CoverageBaseline.ps1 -Modules CimPal-CLI`. Core and Main floors are unchanged.
- **Local environment, not this change:** on the maintainer's machine every NIO `Selector.open()` fails ("Unable to establish loopback connection", `UnixDomainSockets.connect0`: Invalid argument), because AF_UNIX sockets, which the JDK uses for the selector's wake-up pipe in the temp folder, fail anywhere under `%LOCALAPPDATA%` there. That breaks the `StubHttpServer`, `AiKnowledgeSearch` and JavaFX FXML tests locally and the Stop hook with them. The green run above used `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=<plain folder>` for that one command; GitHub CI is unaffected.
- **Verified in the container:** a UID other than 10001 (12345, 1000, 0) gets a private `/tmp/tmp.*` home with its cache at mode 700; with `USE_SYSTEM_CA_CERTS=1` and a mounted certificate the hook imports it and stdout stays pure JSON; killing the `docker` client of a running `mcp` container removes the container within a second (stdin closes, `--rm`); Windows `-v` works with `C:\x:/data` and `C:/x:/data`.
- **Found:** `POST /shutdown` stops the HTTP listener but not the JVM: `ServeCommand.call()` blocks on `Thread.currentThread().join()`, and the executor thread is non-daemon. Reproduced in the image (the container keeps running). Handed to SEC-1; documented in `serve.md` and `docker.md`.
- **Manual GitHub steps for the maintainer:**
  1. After the first release with this workflow: GitHub → Packages → `cimpal` → Package settings → Change visibility → Public. New GHCR packages start private.
  2. Check that the package shows the repository under "Manage Actions access" (expected automatically, because the workflow of this repository pushed it).
- **Open items:**
  - SEC-1: an allowed-Host list for containers, the token via `CIMPAL_API_TOKEN` or a mounted `--token-file`, and the `/shutdown` fix (see SEC-1 notes).
  - CI-2: gate the `docker` job like the release job; add the `docker` ecosystem for `/CimPal-CLI/docker` to Dependabot; optionally scan the image nightly. From the security review, present before this WP:
    - the release step puts `${{ github.ref_name }}` straight into PowerShell (move it to `env:`);
    - that job's actions are still `@v4`;
    - no actionlint or zizmor in CI;
    - provenance attestations are unsigned (`actions/attest-build-provenance`).
  - Core (optional): check owner and mode before trusting entries of the remote cache.
  - SEC-4: the image is a new published artifact, which falls under the attestation's "integrity of published binaries".
