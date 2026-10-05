<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-6 — Service deployment: proxy, tokens, probes, SIGTERM, logs, manifests

| Field | Value |
| --- | --- |
| Status | Not started |
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

- [ ] `--allowed-host` works behind a proxy; unknown Host still 403; tests for port handling and case-insensitivity
- [ ] Token file mode: read-only, never deleted; two tokens accepted; rotation (replace file → old token refused after reload) tested with an injected clock; file permission check like SEC-1 (warn or refuse if world-readable on POSIX)
- [ ] `/ready` semantics tested (normal, queue full, shutting down)
- [ ] SIGTERM drain tested with a forked JVM (failsafe): running job finishes within grace, queued jobs cancelled, exit 0; job exceeding grace → exit non-zero and a log line
- [ ] JSON logs: schema documented; test that no token or `Authorization` header ever appears; all log fields sanitised
- [ ] `/metrics` behind a flag (`--metrics`), token-protected, tested
- [ ] `deploy/kubernetes/` passes `kubectl apply --dry-run=client` and kubeconform in `integrations.yml`; compose file passes `docker compose config`
- [ ] End-to-end on kind or Docker Desktop: deploy, submit a job through the ingress with curl, read the result; steps in `deploy/kubernetes/README.md`; result recorded in notes
- [ ] `/security-review` clean or findings fixed with regression tests

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

## Notes and results

(Claude Code: record findings, baseline numbers and open items here.)
