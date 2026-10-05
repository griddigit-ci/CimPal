<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-1 — Container image

| Field | Value |
| --- | --- |
| Status | In progress (on top of CI-3; CI run pending) |
| Phase | D0 |
| Depends on | CI-1 |
| Size | S–M |
| Branch | `feature/dep-1-container-image` |
| Requirements | R1, R2 (image part) |
| Decisions needed | D-1 registry/name, D-2 base image, D-3 Python engines |

## Goal

External users can run every CimPal CLI command, and `serve`, from a versioned container image without installing Java. The image is small, runs as non-root, respects container CPU and memory limits, and is built and smoke-tested in CI.

## Scope

- `Dockerfile` (multi-stage) and `.dockerignore` at the repo root
- `docker/` folder: entrypoint script, default `JAVA_TOOL_OPTIONS`, example configs copied into the image
- `.github/workflows/ci.yml`: build the image and run a smoke test on the Ubuntu leg (no push)
- `docs/cli/docker.md` (new) and a link from `docs/cli/index.md`
- Not in scope: publishing to a registry (needs D-1 and CI-2 signing; add a commented, disabled publish job), Helm/K8s manifests (DEP-6), the Airflow examples (DEP-3)

## Acceptance criteria (status checklist)

CI-3 ([PR #51](https://github.com/griddigit-ci/CimPal/pull/51)) had already delivered most of this WP before it started. Items marked *(CI-3)* come from there; *(changed)* items were met differently, see the decisions log.

- [x] *(changed)* Build: the image copies the `CimPal-CLI.jar` built by `mvn -B -pl CimPal-CLI -am package -DskipTests` (in CI) or the released JAR (in the release), instead of running Maven in a Docker build stage. The runtime image holds only the JRE, the JAR, the entrypoint, the example configs and the licence files. CimPal-Main is not built.
- [x] Fixed non-root user (UID 10001) *(CI-3)* and `WORKDIR /data` *(CI-3)*. No write access is needed outside `/data` and `/tmp`: with a read-only root filesystem every UID gets a private home in `/tmp`, and without a writable `/tmp` the entrypoint stops with exit 2 and names `--tmpfs /tmp`.
- [x] `ENTRYPOINT` runs the CLI, so `docker run IMAGE validate --help` works; default `CMD` is `--help` *(CI-3)*
- [x] JVM defaults `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`. `JAVA_OPTS` comes after them and overrides them. `JAVA_TOOL_OPTIONS` still works for options the defaults don't set. VM output goes to stderr (`-XX:+DisplayVMOutputToStderr`), so an out-of-memory run ends with exit 3 and the message on stderr, with stdout clean.
- [x] `serve` usable in the container with `--host 0.0.0.0 --allow-remote`, `CIMPAL_API_TOKEN` and `/data` as default root; no `HEALTHCHECK` *(CI-3, after SEC-1/SEC-2)*. Read-only root filesystem works because no token file is written when the token comes from the environment.
- [x] OCI labels: version (`CIMPAL_VERSION` build argument), revision (`GIT_SHA`), source URL, licence *(source and licence: CI-3)*
- [x] *(changed)* Image size reported: about 516 MB (base 433 MB, JAR 46 MB, from CI-3's measurement). The < 350 MB target is dropped for now; see the decisions log.
- [ ] CI Ubuntu leg builds the image and runs `--help`/`--version`, a `validate` on a synthetic fixture (exit 1 with JSON on stdout), and `serve` + `GET /health`, with `--read-only --tmpfs /tmp` *(changed: the "Docker image" job of CI-3, not the Ubuntu `verify` leg; open until that job is green on GitHub with the new checks)*
- [x] *(changed)* Shell smoke test, skipped where Docker is absent: `scripts/Test-DockerImage.ps1` (PowerShell, CI-3), run only by the "Docker image" CI job, so `mvn verify` doesn't need Docker
- [x] `docs/cli/docker.md`: run, volumes, memory/CPU flags, `JAVA_OPTS`, read-only root filesystem, serve mode, Windows paths, building locally *(run, volumes, serve, Windows paths: CI-3)*

## Instructions for Claude Code

Start a session with: `Execute docs/plans/deployment/DEP-1.md` (in plan mode).

```text
Create an official container image for CimPal-CLI (requirements R1, R2 in docs/plans/deployment/README.md).
Before finishing plan mode, confirm decisions D-1 (registry/name), D-2 (base image) and D-3 (no Python engines in the default image) with the maintainer, or use the recommendations in the README and record them in the decisions log.
1. Dockerfile at the repo root, multi-stage:
   - build stage: maven:3.9 with eclipse-temurin 25 JDK; copy only the poms first and run `mvn -B -pl CimPal-CLI -am dependency:go-offline` for layer caching, then copy sources and run `mvn -B -pl CimPal-CLI -am package -DskipTests`. CimPal-Main (JavaFX) must not be built.
   - runtime stage: eclipse-temurin:25-jre (or the D-2 choice). Copy CimPal-CLI.jar to /opt/cimpal/, CimPal-CLI/configs/ to /opt/cimpal/configs/, LICENSE.md and eupl_v1.2_en.pdf to /opt/cimpal/.
   - non-root user cimpal, UID/GID 10001; WORKDIR /data; VOLUME /data.
   - ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"; entrypoint script docker/entrypoint.sh: `exec java $JAVA_OPTS -jar /opt/cimpal/CimPal-CLI.jar "$@"`; CMD ["--help"].
   - OCI labels with build args CIMPAL_VERSION and GIT_SHA.
2. .dockerignore excluding target/, .git/, .idea/, .vscode/, CimPal-Main/, .claude/state/.
3. Check that the CLI works with a read-only root filesystem: anything that writes to $HOME or the working directory by default (token file for serve, temp configs in serve/mcp/run, Jena caches) must go to /tmp or /data. If serve's default token file path is not writable, the container docs must say to use CIMPAL_API_TOKEN; do not change the SEC-1 token-file semantics.
4. CI: add a step to the ubuntu leg of .github/workflows/ci.yml (after verify) that builds the image (docker buildx, cache to GitHub Actions cache) and runs scripts/docker-smoke.sh: --help; validate on a synthetic fixture from CimPal-Core test resources mounted at /data; serve with CIMPAL_API_TOKEN, curl /health and an authenticated /commands, then POST /shutdown. Run all with --read-only --tmpfs /tmp --user 10001. Add a commented-out publish job referencing D-1 and CI-2.
5. Write docs/cli/docker.md and link it from docs/cli/index.md. Record the image size in this file's notes.
Write the smoke script first and see it fail before the Dockerfile exists. No Java code change is expected; if one is needed (e.g. a temp path), it needs a unit test.
Docker may be missing on the Windows dev machine: say so, and rely on the CI run for verification; do not mark the CI criterion done until the CI run is green.
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
| 2026-10-05 | DEP-1 is a delta on CI-3, which was merged into `devel` the same day. CI-3's decisions stand: the image copies the built or released JAR and runs no Maven (the release image holds exactly the released bytes); the smoke test is PowerShell (`scripts/Test-DockerImage.ps1`); it runs in the separate "Docker image" job, not in the Ubuntu `verify` leg. | Maintainer |
| 2026-10-05 | D-1 settled by CI-3: `ghcr.io/griddigit-ci/cimpal`, published by `release.yml` with `GITHUB_TOKEN`. No commented publish job is needed. D-2: `eclipse-temurin:25-jre-noble`, pinned by digest (CI-3). D-3: no Python engines in the default image (CI-3 docs say so). | Maintainer (from CI-3) |
| 2026-10-05 | The < 350 MB size target is dropped for now. The image is about 516 MB, of which 433 MB is the Temurin noble JRE base. Its fonts (POI `autoSizeColumn`) and CA-certificate hook are needed. A jlink runtime on a plain Ubuntu base would save an estimated 200 MB, but it needs a verified module list and a replacement for the certificate hook. That is a follow-up. | Maintainer |
| 2026-10-05 | No `VOLUME /data`. It would create an anonymous volume for every run that doesn't mount `/data`, and those pile up. Mounting is documented instead. | Maintainer |
| 2026-10-05 | JVM flags: the defaults first, then `$JAVA_OPTS` (split on blanks, no globbing), then `-XX:+DisplayVMOutputToStderr -Djava.awt.headless=true -Duser.home=...`, which stay fixed because the stdout and cache-privacy guarantees depend on them. `-XX:+ExitOnOutOfMemoryError` exits with status 3, the CLI's "internal error", and the HotSpot message goes to stderr. | Claude Code |
| 2026-10-05 | Read-only root filesystem: the entrypoint's private-home rule now also applies when `$HOME` is not writable, so `/home/cimpal` under `--read-only` falls back to `mktemp -d` in `/tmp`. Without a writable `/tmp` it stops with exit 2 and a hint, instead of letting the JVM fail later on the cache or temp files. No Java change was needed: the only default writes outside the working directory are `~/.cimpal` (caches; `serve.token` only without `CIMPAL_API_TOKEN`) and `java.io.tmpdir`. | Claude Code |
| 2026-10-05 | From the security review: unified-logging warnings go to stderr (`-Xlog:disable -Xlog:all=warning:stderr:...` before `JAVA_OPTS`), because `DisplayVMOutputToStderr` covers only HotSpot's `tty` and UL warnings went to stdout by default. That was already true in CI-3. The entrypoint also unsets `LOCALAPPDATA`, which Core prefers over `user.home` for its cache. | Claude Code |
| 2026-10-05 | The deployment plan files reached `devel`'s history only in the unmerged TEST-3 commit `6a51068`; they are copied unchanged into this branch, and `docs/plans/README.md` lists the DEP track. | Maintainer |

## Notes and results

- **Local environment:** Docker is not installed on the maintainer's machine for this session. The image, the entrypoint and the new smoke checks have **not** been run locally. `entrypoint.sh` passes `sh -n`, `Test-DockerImage.ps1` parses, and the workflows are valid YAML. Verification is the "Docker image" job in CI.
- **Smoke-test checks added** (CI-3 had 36):
  - the configs and licence files are in the image;
  - with `--read-only` and no writable `/tmp`, exit 2 and the hint;
  - the cache is private with a read-only root filesystem (four checks);
  - the JVM defaults, VM output on stderr, the `JAVA_OPTS` override;
  - an out-of-memory run (`JAVA_OPTS=-Xmx4m`) exits 3 with the message on stderr and nothing on stdout;
  - the version and revision labels.

  The validation, offline, both `mcp` and the `serve` runs now use `--read-only --tmpfs /tmp`.
- **Security review (`security-reviewer`):** no Critical or High findings, and no fixed finding regressed. Fixed in this WP:
  - the Medium: unified-logging warnings reached stdout, which could corrupt `mcp` or `--format json`;
  - the Lows: the Docker MCP template still advised `JAVA_TOOL_OPTIONS`; the docs left out the fixed flags; a Kubernetes `/tmp` emptyDir without a sticky bit must not be shared (documented);
  - the Info items: `LOCALAPPDATA` could move the cache (now unset); out-of-memory ends a `serve`/`mcp` container (documented).

  Regression checks added for all of them: unified-logging warnings on stderr; `user.home`/`LOCALAPPDATA` overrides can't move the cache; UID 12345 with a read-only root filesystem. The narrow emptyDir case is closed for good only by the open Core item from CI-3: check the owner and mode of cache entries before trusting them.
- **Risks to watch in the first CI run:**
  - Whether `-Xmx4m` reliably runs out of memory during the fixture validation. If the JVM refuses the size or the run fits, use a smaller or larger value.
  - Whether `-XX:SharedArchiveFile=/nonexistent.jsa` produces a unified-logging warning on JDK 25. The check needs one; if there is none, pick another warning trigger.
  - Whether the Temurin certificate hook (`USE_SYSTEM_CA_CERTS`) works with a read-only root filesystem for UID 10001. It should use a temporary truststore in `/tmp`.
- **First CI run (PR #52):** the smoke test passed 59 of 61 checks. That included out of memory (exit 3, stdout empty), every read-only root filesystem run, and the CA hook with `--read-only`.
  - The missing CDS archive is logged at `[error]` on JDK 25, not `[warning]`. It went to stderr as intended, so the check now accepts either level.
  - `COPY --chmod=0644` of `CimPal-CLI/configs/` also gave the directory 0644, so UID 10001 couldn't enter it. That folder is now copied without `--chmod`.
  - Both "Build and test" legs failed on the Core coverage floor (CI 0.3464/0.3458 line, floor 0.4138), as `devel` does since `19e257f`. That commit raised the floor from a local measurement (0.4188) that CI doesn't reproduce. Cause, confirmed: `jacoco.exec` is appended to across builds (JaCoCo's default), and the local one still held an earlier build's run of the TEST-3 Core tests (`SHACLFromRDFTest`, `ShaclFromXlsTest`, `ShaclOrganizerTest`, `ShaclAutoTesterTest`). Without `clean`, the same tree measures 0.4188; after `mvn clean verify` it measures 0.3461 line / 0.2455 branch, matching CI. As the maintainer decided, the floors are lowered to that clean measurement (0.3411 / 0.2405), and merging TEST-3 ratchets them back up.
- **Image size:** about 516 MB (CI-3 measurement; this WP adds about 300 KB of configs and the licence PDF).
- **Java tests:** no Java or Maven file changed, so the counts are unchanged from CI-3 (Core 286, Main 65, CLI 109).
- **Open:**
  - the first green "Docker image" CI job with the new checks;
  - optionally, a smaller image (jlink) as a follow-up;
  - DEP-2 can now measure inside the image.
