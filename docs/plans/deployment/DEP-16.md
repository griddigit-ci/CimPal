<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-16 — Load tests, capacity model and SLOs for the service

| Field | Value |
| --- | --- |
| Status | Not started |
| Phase | D3 (option 2 hardening, see [OPTION-2-ROADMAP.md](OPTION-2-ROADMAP.md)) |
| Depends on | DEP-2 (benchmark models), DEP-9 (SDK as the load client); re-run after DEP-12 and DEP-15 |
| Size | M |
| Branch | `feature/dep-16-load-tests` |
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

- [ ] `scripts/bench/load/run.py --scenario mixed --rate R --duration D --url … ` reproduces a report from scratch (synthetic models are generated as in DEP-2).
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

## Notes and results

(Claude Code: record findings, baseline numbers and open items here.)
