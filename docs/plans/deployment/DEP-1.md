<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-1 — Container image

| Field | Value |
| --- | --- |
| Status | Not started |
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

- [ ] Multi-stage build: stage 1 builds `CimPal-CLI.jar` with Maven + JDK 25 (`mvn -B -pl CimPal-CLI -am package -DskipTests`); stage 2 is the JRE 25 runtime with only the JAR, configs and licence files
- [ ] Runs as a fixed non-root user (UID 10001), `WORKDIR /data`; no write access needed outside `/data` and `/tmp` (works with a read-only root filesystem)
- [ ] `ENTRYPOINT` runs the CLI, so `docker run IMAGE validate --help` works; default `CMD` is `--help`
- [ ] JVM defaults for containers: `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`, overridable with `JAVA_TOOL_OPTIONS` / `JAVA_OPTS`
- [ ] `serve` usable in the container: documented `serve --host 0.0.0.0 --allow-remote --root /data` with `CIMPAL_API_TOKEN`; `HEALTHCHECK` is not baked in (CLI use is the default) but documented for serve mode
- [ ] OCI labels: version (from `cimpal.version`), revision (git SHA, build arg), source URL, licence `EUPL-1.2`
- [ ] Image size reported in the WP notes; target < 350 MB
- [ ] CI Ubuntu leg builds the image and runs: `--help`, a `validate` on a synthetic fixture (exit 0 or 1 with JSON on stdout), and `serve` + `GET /health`; all with `--read-only --user 10001`
- [ ] A container test class tagged `docker` (JUnit) or a shell smoke script; skipped cleanly when Docker is absent (Windows dev machines)
- [ ] `docs/cli/docker.md`: build, run, volumes (`-v host:/data`), memory/CPU flags, serve mode, Windows path notes

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

## Notes and results

(Claude Code: record findings, baseline numbers and open items here.)
