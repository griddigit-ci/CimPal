<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-6 — Service deployment: proxy, tokens, probes, SIGTERM, logs, manifests

| Field | Value |
| --- | --- |
| Status | In review (PR into `devel`) |
| Phase | D1 |
| Depends on | DEP-5, DEP-1 |
| Size | M |
| Branch | `feature/dep-6-service-deployment` |
| Requirements | R9, R10, R12, R13 |
| Decisions needed | D-7 TLS at proxy |

## Goal

Run `serve` as a real service for a team or an Airflow installation: inside a container, behind a reverse proxy or Kubernetes ingress, with tokens from a secret, health probes, clean shutdown on SIGTERM, and logs an operator can search. Ship example deployment files.

## Gaps this closes

| Gap today | Change |
| --- | --- |
| Host check accepts only loopback or the bound host, so requests via `cimpal.example.com` behind a proxy get 403 | `--allowed-host <name[:port]>` (repeatable); the DNS-rebinding defence stays: unknown Host still 403 |
| Token is generated per start or comes from `CIMPAL_API_TOKEN`; no way to read a mounted secret file without serve writing/deleting it; no rotation | `CIMPAL_API_TOKEN_FILE` / `--token-from-file <path>`: read-only, never written or deleted; up to 2 tokens (one per line) accepted at once for rotation; file re-read on change or on `SIGHUP`-free polling (e.g. every 60 s) |
| No readiness signal | `GET /ready`: 200 when accepting work; 503 when shutting down or queue full |
| SIGTERM behaviour undocumented / untested | SIGTERM = same as `/shutdown`: stop accepting, cancel queued jobs, wait for the running job up to `--shutdown-grace` (default `PT60S`), exit 0 |
| Logs are free text | `--log-format json`: one JSON object per line on stderr with `ts`, `level`, `event`, `jobId`, `command`, `durationMs`, `exitCode`, `remote`; never tokens; sanitised |
| No metrics | Optional `GET /metrics` (Prometheus text, token-protected): jobs by status, job duration histogram, queue depth, JVM heap |
| No deployment examples | `deploy/docker-compose/` and `deploy/kubernetes/` examples |

## Scope

- CLI: `ServeServer`, `ServeSecurity`, `ServeCommand` options; small `JsonLogger` (no new logging framework unless the plan justifies one)
- `deploy/docker-compose/compose.yaml` + README: CimPal serve + Caddy or nginx with TLS (self-signed for the demo), token as a Docker secret, `/data` volume
- `deploy/kubernetes/`: Namespace, Secret (token), PersistentVolumeClaim, Deployment (1 replica, resources from the sizing guide, liveness `/health`, readiness `/ready`, `securityContext` non-root + read-only root FS + `/tmp` emptyDir, `terminationGracePeriodSeconds` > `--shutdown-grace`), Service, Ingress (TLS)
- `docs/cli/serve.md`, `docs/guide/service.md` (new), `docs/guide/security.md` update

## Acceptance criteria (status checklist)

- [x] `--allowed-host` works behind a proxy; unknown Host still 403; tests for port handling and case-insensitivity
- [x] Token file mode: read-only, never deleted; two tokens accepted; rotation (replace file → old token refused after reload) tested with an injected clock; file permission check like SEC-1 (warn or refuse if world-readable on POSIX)
- [x] `/ready` semantics tested (normal, queue full, shutting down)
- [x] SIGTERM drain tested with a forked JVM (failsafe): running job finishes within grace, queued jobs cancelled, exit 0; job exceeding grace → exit non-zero and a log line
- [x] JSON logs: schema documented; test that no token or `Authorization` header ever appears; all log fields sanitised
- [x] `/metrics` behind a flag (`--metrics`), token-protected, tested
- [x] `deploy/kubernetes/` passes `kubectl apply --dry-run=client` and kubeconform in `integrations.yml`; compose file passes `docker compose config`
- [x] End-to-end on kind or Docker Desktop: deploy, submit a job through the ingress with curl, read the result; steps in `deploy/kubernetes/README.md`; result recorded in notes
- [x] `/security-review` clean or findings fixed with regression tests

## Instructions for Claude Code

Start a session with: `Execute docs/plans/deployment/DEP-6.md` (in plan mode).

```text
Make serve deployable as a team/Airflow service (R9, R10, R12, R13 in docs/plans/deployment/README.md). Read the SEC-1 decisions log first; keep its check order and guarantees.
Decision D-7: TLS terminates at the proxy/ingress; CimPal stays plain HTTP inside the pod network. Document that --allow-remote without a proxy is plain HTTP.
1. Tests first for each item in the "Gaps this closes" table (CLI tests with the TEST-1 harness; failsafe for SIGTERM with a forked JVM).
2. --allowed-host (repeatable) in ServeSecurity's Host check. --token-from-file / CIMPAL_API_TOKEN_FILE: read-only token source, up to two tokens, periodic reload with an injected clock, constant-time compare against each. Never log or echo a token. Precedence when several sources are given: refuse to start with a clear error rather than guessing.
3. /ready; SIGTERM via a shutdown hook that reuses the /shutdown path; --shutdown-grace.
4. --log-format text|json; JSON lines on stderr; reuse LogSanitizer.
5. --metrics: Prometheus text format, hand-written (no new dependency unless justified), token-protected.
6. deploy/docker-compose and deploy/kubernetes examples as listed in Scope, with resources from docs/guide/sizing.md. Add kubeconform + compose config checks to .github/workflows/integrations.yml.
7. Docs: docs/cli/serve.md (new flags, deployment section), docs/guide/service.md, docs/guide/security.md.
Run /security-review and /security-check-change; record findings here.
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
| 2026-10-07 | D-7: TLS ends at the proxy or Ingress; CimPal stays plain HTTP and the docs say so. The Kubernetes example adds a NetworkPolicy so only the ingress controller reaches the pod. | Claude Code (plan approved by maintainer) |
| 2026-10-07 | The end-to-end run is a kind job in CI (`integrations.yml`, `deploy/kubernetes/e2e-kind.sh`); this Windows machine has no Docker or kind. | Claude Code (plan approved) |
| 2026-10-07 | SIGTERM via `sun.misc.Signal` (`requires jdk.unsupported`) runs the `/shutdown` path; exit 0, or 3 when a command outlives `--shutdown-grace`. The shutdown hook drains as well (Ctrl-C). | Claude Code (plan approved) |
| 2026-10-07 | No new dependencies: `ServeLog` writes JSON lines with Jackson, `ServeMetrics` writes Prometheus text by hand. | Claude Code (plan approved) |
| 2026-10-08 | Token file permissions: warn only when the file is world-readable. Group-read is how Kubernetes shares a secret with `fsGroup` (`defaultMode: 0440`). | Claude Code |
| 2026-10-08 | Token file revocation must not fail open (security review): no token means revoke all; a broken file is bridged for one check, then everything is refused and `/ready` answers 503. Change detection is by content hash. | Claude Code |
| 2026-10-08 | `/ready` is 503 only while shutting down or without a usable token, never for a full queue: one replica would otherwise drop out of the Service and block polling and cancelling (security review). | Claude Code |

## Notes and results

**Built** (branch `feature/dep-6-service-deployment`, CLI module only):
- **Host check:** `--allowed-host`, in `ServeSecurity.isAllowedHost`/`allowedHostEntry`.
- **Token file:** `TokenSource` with `FileTokens` (`--token-from-file`, `CIMPAL_API_TOKEN_FILE`, `--token-reload`).
- **Endpoints:** `GET /ready`; `GET /metrics` with `--metrics` (`ServeMetrics`).
- **Logs:** `--log-format json` (`ServeLog`; the commands' stderr is wrapped into `output` events).
- **Shutdown:** SIGTERM (`ServeSignals`) and `--shutdown-grace`.
- **Examples:** `deploy/docker-compose` (Caddy, `tls internal`) and `deploy/kubernetes` (kustomization, NetworkPolicy, `e2e-kind.sh`).
- **CI:** `integrations.yml` gets the `deploy-static` job (compose config, digest pins, kubeconform) and the `deploy-kind` job.
- **Docs:** `docs/cli/serve.md` ("Running as a service"), the new `docs/guide/service.md`, and the guide's security, deployment-modes, capabilities and index pages.

**Tests:**
- **CLI suite:** 173 → 217 (0 failures, 3 skipped).
- **New classes:** `TokenSourceTest`, `ServeLogTest`, `ServeMetricsTest`.
- **New cases:** in `ServeServerTest`, `ServeSecurityTest` and `ServeCommandTest`.
- **`ServeSignalIT`** (maven-failsafe, Linux/macOS only, so it runs in CI on Ubuntu): it forks a JVM, sends SIGTERM, and checks that the running job finishes, the queued one never runs and the exit code is 0; and exit 3 when the grace runs out.
- **Coverage:** CLI line coverage is 65.9 % (floor 28.5 %).
- **Manual run** from the dev classpath (token file, JSON logs, metrics): `/ready` 200; a proxy name gets 200 and an unknown Host 403; a job runs; `/metrics` answers 200 with the token and 401 without; stdout stays empty, stderr is JSON only, and no token appears in the log.

**Security review** (`security-reviewer`, 2026-10-08). No Critical or High. Fixed with regression tests:

| Severity | Finding | Fix |
| --- | --- | --- |
| Medium | A failed token-file reload kept the old tokens forever, so a revocation that broke the file failed open. | No token → revoke all. A broken file is bridged for one check, then refused, and `/ready` answers 503. Each failed check is logged. Tests: `aBrokenFileKeepsTheOldTokensForOneCheckThenRefusesEverything`, `anEmptyFileRevokesEveryTokenAtOnce`, `readyIs503WhileNoTokenCanBeAccepted`. |
| Medium | `/ready` 503 on a full queue took the only replica out of the Service, so polling and cancelling were blocked too. | Readiness ignores the queue (`readyStays200WithAFullQueueAndIs503OnlyWhenShuttingDown`). |
| Medium | The compose example mounted the token as a single-file secret, so a replaced file was never seen. | The `./secrets` folder is mounted. The container runs as the folder's owner (`CIMPAL_UID`), so the file can stay `chmod 600`. |
| Low | The token file was read unbounded, from any file type, with mtime/size change detection, under a lock on request threads. | Regular file only; a bounded read (4 KiB); a content hash; reload under `tryLock`. Tests: `onlyARegularFileOfBoundedSizeIsRead`, `aReplacementOfTheSameSizeAndTimeIsStillSeen`. |
| Low | The JSON log took a line's level from words anywhere in it; server threads got the job's id; partial lines were lost. | Level from the line's start only; server threads (`StderrTee.isServerThread`) are not attributed; partial lines are flushed at the end; dead threads are pruned. Tests in `ServeLogTest`. |
| Low | Supply chain: mutable image tags, a manifest fetched by tag, a same-origin checksum. | Caddy and kubeconform pinned by digest (checked in CI); ingress-nginx by commit; the kind SHA-256 is in the workflow. The CimPal image stays a tag, with a comment to pin a release. |
| Low | No NetworkPolicy; the compose service had capabilities; the field name `token` in the startup event; a fixed sleep in a test. | `networkpolicy.yaml`; `cap_drop: ALL` and `no-new-privileges`; `tokenSource`; the test polls instead. |
| Low | Unauthenticated probes show the version publicly through the proxy; Prometheus pod discovery is refused by the Host check. | Documented (block at the proxy if needed; scrape through the Ingress). Not changed: `/health` showing the version predates DEP-6. |

Also added from the review's list of missing tests: Host edge cases for `--allowed-host`, and the Host check on `/ready` and `/metrics`.

Not covered by a test:
- **SIGINT during a SIGTERM drain:** `close()` is idempotent and both paths wait on the same worker.
- **Partial writes of the token file:** the docs say to replace the file in one step.

**Open:**
- the kind end-to-end job passed on its first run (PR #64, `integrations.yml` run 37752999161): ingress-nginx, the manifests with an image from the branch, a `sparql` job through the Ingress over TLS, JSON logs without the token, and a clean pod deletion;
- DEP-7 (file exchange) will replace `kubectl cp`/volume filling.
