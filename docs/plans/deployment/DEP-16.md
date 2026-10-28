<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-16 — Load tests, capacity model and SLOs for the service

| Field | Value |
| --- | --- |
| Status | In progress: tooling, metrics, rules and nightly job merged ([PR #81](https://github.com/griddigit-ci/CimPal/pull/81)); the full measurement runs are open (need Docker) |
| Phase | D3 (option 2 hardening, see [OPTION-2-ROADMAP.md](OPTION-2-ROADMAP.md)) |
| Depends on | DEP-2 (benchmark models), DEP-9 (SDK as the load client); re-run after DEP-12 and DEP-15 |
| Size | M |
| Branch | `feature/dep-16-load-tests-v2` (the first `feature/dep-16-load-tests` lost its uncommitted edits) |
| Requirements | R22 |
| Decisions needed | SLO targets (proposed in plan mode, agreed with the maintainer) |

## Goal

Know, with numbers anyone can reproduce, what one `serve` instance (and later N replicas) can handle:
- jobs per hour by model size;
- queue wait times;
- the API's own latency under load;
- memory per worker;
- where it breaks (the queue is full, out of memory, the disk is full).

Then publish a capacity model and SLOs that operators can size from and alert on. This is the "scaling" answer for the externals.

## Today

- **Sizing:** DEP-2 measured **single runs** (heap and time by model size; `docs/guide/sizing.md`).
- **No service measurements:** nothing about a service under concurrent load, the effect of `--workers`, or API latency while jobs run.
- **No SLOs:** none defined, and no alert rules.

## Design

1. **Load generator:** `scripts/bench/load/` (Python, using `cimpal-client`'s `AsyncCimPal`; no extra framework).
   - **Scenarios, mixed by weight:**
     - small validations (DEP-2's 100k-triple synthetic model);
     - medium (1M triples);
     - SPARQL queries;
     - uploads with downloads.
   - **Arrival:** open-model (Poisson), at a configurable rate and duration.
   - **Records per job:** submit latency, queue wait (`startedAt − createdAt`), run time, end-to-end time, outcome, plus 503/429 counts.
   - **Samples during the run:** `/metrics` (queue depth, running, heap).
2. **Experiments:**
   1. One instance, `--workers 1`: increase the arrival rate until queue wait grows without bound, which gives the throughput limit.
   2. `--workers 1, 2, 4, 8` on 4 and 8 cores: throughput and heap, and where admission control kicks in.
   3. API latency (`GET /v1/jobs/{id}`, `/v1/health`) while the workers are busy: the p50, p95 and p99 targets.
   4. Overload: the queue fills, with 503 + `Retry-After`, and SDK clients recover without errors.
   5. After DEP-12: 1, 2 and 4 replicas, for linear scaling and the database's share. After DEP-15: in-process vs process isolation.
3. **Output:**
   - JSON results plus a Markdown report generated into `docs/guide/sizing-data/` (as DEP-2 does);
   - a new section "Running as a service: capacity" in `docs/guide/sizing.md`, with jobs per hour per instance by model size and the recommended workers per core.
4. **Proposed SLOs and alerts** (agreed in plan mode):
   - API availability 99.5 %;
   - `GET` p95 < 200 ms while busy;
   - queue wait p95 < 5 min at the planned load.
   - Prometheus alert rules in `deploy/monitoring/`:
     - queue near full;
     - stalled job;
     - heap > 90 %;
     - work area > 80 %;
     - 5xx rate.
5. **CI:** a short smoke variant (1 minute, low rate) as a manual or nightly workflow job (joining TEST-5's nightly). The full runs stay manual and are documented.

## Scope

- The load generator and scenarios.
- Report generation.
- The capacity section in `sizing.md`.
- Alert rules plus a Grafana dashboard JSON (optional).
- The nightly smoke job.

## Acceptance criteria (status checklist)

- [x] `scripts/bench/load/run.py --scenario mixed --rate R --duration D --url … ` reproduces a report from scratch (synthetic models are generated as in DEP-2).
- [ ] Measured and published: the throughput limit per instance and the effect of workers (1/2/4/8) on 4 and 8 cores; API latency under load; overload behaviour (503 + recovery).
- [ ] `docs/guide/sizing.md` has a service capacity table and worked examples ("200 validations of 1M triples per day → …").
- [ ] Alert rules validate (`promtool check rules`); SLOs written down in `docs/guide/service.md`.
- [ ] The nightly smoke job runs and stays green.
- [ ] Re-run sections added after DEP-12 and DEP-15 (open items until those land).

## Instructions for Claude Code

Start a session with: `Execute docs/plans/deployment/DEP-16.md` (in plan mode).

```text
Propose SLO targets in plan mode. Reuse DEP-2's model generator and report style (scripts/bench).
1. Load generator on AsyncCimPal; scenarios; metrics sampling.
2. Run the experiments locally (record the machine), write the report and the sizing section.
3. Alert rules (+ promtool in CI), nightly smoke.
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
| 2026-10-19 | SLOs as proposed: availability 99.5 % per 30 days (5xx counts; 503, 429 and connections the client broke don't), API p95 < 200 ms while busy, submit p95 < 500 ms, queue wait p95 < 5 min at the planned load. | Maintainer |
| 2026-10-19 | Branch stacked on DEP-14 (measures the server and SDK that will ship). | Maintainer |
| 2026-10-19 | Alert rules only; a Grafana dashboard later. | Maintainer |
| 2026-10-19 | Full runs in Docker (`--cpus` limits, the PostgreSQL store) once it is installed on the measuring laptop. | Maintainer |
| 2026-10-19 | `serve` gets the metrics the rules need: `cimpal_http_responses_total{code}`, `cimpal_http_request_duration_seconds{kind}`, `cimpal_job_queue_wait_seconds`, `cimpal_queue_size`, `cimpal_stalled`, `cimpal_work_used_bytes`/`_max_bytes`. Without them the queue wait and latency SLOs can't be alerted on. | Claude Code |
| 2026-10-19 | The nightly smoke is a new `nightly.yml` (TEST-5 adds its jobs there); promtool runs in `integrations.yml`'s deployment checks with a digest-pinned `prom/prometheus:v3.5.1`. | Claude Code |

## Notes and results

- **Done (2026-10-19):**
  - `scripts/bench/load/run.py` (open or closed load, four scenarios, probes, `/metrics` samples, CSVs, `summary.json`, `report.md`, `--fail-on-slo`), `sweep.py` (limit, workers × cores, ramp, overload; `--publish`), `test_load.py` (12 tests).
  - The metrics above, with `ServeMetricsTest` and `ServeServerTest` checks.
  - `deploy/monitoring/cimpal-alerts.yaml` + `cimpal-alerts.test.yaml`; SLOs in `docs/guide/service.md`; metrics in `docs/cli/serve.md`.
  - First smoke on the DEP-2 laptop (i7-1360P, 16 GB, Windows 11), 2 workers, 8 mixed jobs/min for 90 s: all 14 jobs succeeded, no queueing, `GET` p95 under 10 ms. A warm server validates the 100k model in about 0.5 s (the CLI's ~3 s in DEP-2 is mostly JVM start).
- **Quick sweep (`sweep.py --quick`, 30 s cells, set-up check only):** every experiment runs end to end. One worker: about 4,500 small (100k) or 390 medium (1M) validations per hour; 2 workers on 4 cores about 1.4× that. With only 2 cores, `GET` p95 rose to 170–196 ms while 1M validations ran, close to the 200 ms SLO: the API shares the CPU with the commands. The full runs should show whether that holds on 4 and 8 cores.
- **Tests:** `mvn -B verify` green: Core 482, Main 65 (with the GUI tests; without them Main's coverage ratchet fails in a clean tree, which is not new), CLI 464 (+4), ITs 22 (21 skipped without Docker); `test_load.py` 12.
- **Security review (2026-10-19, `security-reviewer` agent):** no Critical or High. Fixed:
  - (Medium) the load tool switched off the SDK's plain-http guard, so `--url http://host` would have sent the admin token in clear text (also on `/metrics`): only the server it launches may be plain http on loopback; `check_url` refuses the rest (`test_load.py`).
  - (Low) `none` (a connection the client broke, e.g. a cancelled upload) doesn't count against availability; the error ratio is `5xx` only.
  - (Low) the queue wait is observed inside the job's `try` (a failure there can't leak a worker slot); nothing allocates in `handle`'s `finally` after an out-of-memory error; the transfer path pattern is precompiled (`DOTALL`); `--start-server` drops inherited `CIMPAL_*` variables; `sweep.py --publish` takes a plain file name only.
  - CI: the bench and load self-tests run on PRs (`integrations.yml`, deployment checks; `scripts/bench/**` in the path filters). A non-admin `/metrics` 403 was already covered (`ClientIsolationTest`).
- **Open:**
  - The full sweep (`sweep.py --publish …`, a few hours) and the capacity section in `sizing.md` with worked examples, run in Docker with `--cpus` limits and the PostgreSQL store.
  - `promtool` hasn't run locally yet; CI runs it.
  - The nightly job only runs from the default branch (GitHub schedules); `workflow_dispatch` runs it on a branch.
  - Re-runs after DEP-12 (replicas) and DEP-15 (process isolation).
