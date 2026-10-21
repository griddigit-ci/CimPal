<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-12 — Shared queue across replicas (horizontal scale-out)

| Field | Value |
| --- | --- |
| Status | Not started |
| Phase | D3 (option 2 hardening, see [OPTION-2-ROADMAP.md](OPTION-2-ROADMAP.md)) |
| Depends on | **DEP-11** (durable job store); DEP-16 baseline recommended first; decisions D-15, D-16, D-18 |
| Size | L |
| Branch | `feature/dep-12-shared-queue` |
| Requirements | R18 |
| Decisions needed | D-15 queue mechanism, D-16 shared files |

## Goal

Several identical `serve` replicas behind one load balancer form **one service with one queue**:
- **Any replica** accepts a job, answers any job's status, result, log and files, and any free replica runs it.
- **Failover:** when a replica dies, its queued work isn't lost. Its running jobs end as `failed` ("interrupted"), not stuck, and the others carry on.
- **Scaling:** capacity grows by adding replicas, which can be autoscaled on the queue depth.

That is the externals' "queuing mechanism" for a central service with many clients.

## Today

- **Queue per instance:** each instance has its own queue, job store and work area. A job id is known only to the instance that accepted it.
- **Scaling:** the docs therefore say "one replica per Deployment; scale with more instances, each with its own name" (`docs/guide/service.md`). Clients must be pinned to an instance.
- **Fault:** a dead instance strands its jobs.

## Design

**1. One shared database (D-15).** All replicas use the DEP-11 PostgreSQL store. The job table is the queue, so there is no broker.

- **Claiming a job** (each worker thread, in a loop), atomic across replicas:
  ```sql
  UPDATE jobs SET status = 'running', started_at = now(), node = :node, lease_until = now() + :lease
  WHERE id = (SELECT id FROM jobs WHERE status = 'queued'
              ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1)
  RETURNING id, command, config, upload_id;
  ```
  `SKIP LOCKED` lets replicas claim different rows without waiting on each other.
- **Waking up:** workers sleep between claims and are woken by PostgreSQL `LISTEN/NOTIFY` on submit and cancel, with a slow fallback poll (for example every 5 s).
- **Node identity:** `--node-id`, defaulting to the host name (the pod name in Kubernetes), recorded on the job and in logs.

**2. Leases and failover.**
- **Renewal:** a running job's replica renews `lease_until` (for example every 30 s, with a 2-minute lease).
- **Sweeper:** every replica runs a sweeper under a PostgreSQL advisory lock, so only one runs at a time. It sets jobs whose lease has expired to `failed` ("interrupted: node <id> stopped answering"), or back to `queued` with `--requeue-interrupted` (D-18).
- **Graceful shutdown (SIGTERM):** the replica stops claiming, finishes its running jobs within `--shutdown-grace`, and releases its leases. Its queued jobs stay in the shared queue for the others.

**3. Shared files (D-16).**
- **Where:** uploads and job outputs must be visible to every replica, so `--work-root` points to an **RWX volume** mounted on all replicas (NFS, Azure Files, AWS EFS, CephFS).
- **Paths:** the `WorkArea` layout stays; paths are already per upload id and per job id, so replicas don't collide.
- **Housekeeping:** upload TTL eviction and work-budget accounting move to the shared store, so the totals are global and not per replica. Cleanup runs under an advisory lock.
- **Shared-volume roots:** allowed roots must be the same paths on every replica (the same mounts).

**4. Limits become global.**
- **Queue size:** `--queue-size` is the number of queued rows in the database, so 503 + `Retry-After` means the whole service is full.
- **Per replica:** `--workers` and the memory admission (DEP-8) stay per replica, since they protect that replica's heap.
- **Already global:** `--max-jobs`, the job TTL and the work budget are global through the store.

**5. Load balancing and routing.**
- **Routing:** plain round-robin, with no sticky sessions; any replica answers any request.
- **Uploads:** an upload is written to the shared volume by whichever replica receives it, and its row is committed before the 201.
- **Health:** `/ready` on a replica is 503 when its database or shared volume is unavailable, so it leaves the load balancer.

**6. Observability.**
- **Gauges:** `cimpal_queue_depth` (global, from the database) and `cimpal_running{node}`.
- **New metrics:**
  - `cimpal_claims_total`;
  - `cimpal_lease_expired_total`;
  - `cimpal_jobs_interrupted_total`.
- **Logs:** carry `node`.
- **Autoscaling** (Kubernetes): an HPA on the queue-depth metric (KEDA or Prometheus Adapter), documented with an example.

**7. Deployment examples.**
- **Kubernetes:** a `Deployment` with `replicas: 3`, an RWX `PersistentVolumeClaim`, a `Secret` for the DB password, a `Service` and an `Ingress` without session affinity, and a `PodDisruptionBudget`.
- **Compose:** two `cimpal` services with a shared volume and `postgres`.
- **Testing:** the kind end-to-end test (`deploy/kubernetes/e2e-kind.sh`) gets a two-replica mode. kind can provide RWX with a hostPath-backed provisioner for a single-node cluster.

## Scope

- **From DEP-11's security review (L11):** until replicas share the queue, nothing stops a second `serve` on the same database from failing the first one's running jobs as interrupted at its start. DEP-12's leases replace the start-up recovery with lease expiry; a start-up check (for example an advisory lock held per node) should make that safe.

- **Code:**
  - `JdbcJobStore` claim, lease, sweeper and notify;
  - `ServeServer` worker loop and shutdown;
  - global limits;
  - `WorkArea` accounting through the store;
  - `--node-id`, lease and sweep options;
  - metrics.
- **Deployment:** examples and the kind e2e test.
- **Docs:**
  - `docs/guide/service.md`: the replicas row and a new "Scaling out" section;
  - `docs/guide/sizing.md`: sizing the database and the volume;
  - `docs/cli/serve.md`;
  - PROJECT.md.

## Acceptance criteria (status checklist)

- [ ] Two (and three) `serve` instances on one database and one work root: jobs submitted to one are visible, cancellable and downloadable through the others (IT).
- [ ] No job ever runs twice: a stress IT with 3 replicas × 4 workers and 200 short jobs checks that every job ran exactly once, and that the results equal a single-instance run (reusing `ConcurrencyIT`'s comparison).
- [ ] Failover:
  - kill one replica (no SIGTERM) while it runs a job;
  - within one lease period that job is `failed` "interrupted" (or requeued with the option);
  - the replica's queued jobs run on the others.
- [ ] Graceful drain: on SIGTERM a replica finishes its running jobs within the grace and claims no new ones; its queued jobs run elsewhere.
- [ ] Global queue limit: with all replicas busy, the next submit gets 503 + `Retry-After` from any replica.
- [ ] Upload TTL and work budget are enforced globally (an upload counted once, cleaned once).
- [ ] Kubernetes example with 3 replicas passes the kind e2e test; the HPA example validates (`kubeconform`).
- [ ] Single-instance and `--job-store memory` behaviour is unchanged (existing tests green).
- [ ] Docs updated; `/security-review` clean (shared-volume permissions, advisory locks, node identity spoofing, lease races).

## Instructions for Claude Code

Start a session with: `Execute docs/plans/deployment/DEP-12.md` (in plan mode).

```text
Only start when DEP-11 is merged. Confirm D-15, D-16 (and D-18 as decided in DEP-11).
1. Baseline: run DEP-16's load test against one instance (if DEP-16 is done) and record the numbers.
2. Claims with SKIP LOCKED, leases, renewal, the sweeper under an advisory lock, LISTEN/NOTIFY wake-up.
3. Global limits and work-area accounting through the store.
4. Multi-replica ITs (two/three ServeCommand instances in one JVM against one Testcontainers PostgreSQL and
   one temp work root), failover and drain tests, the no-double-run stress test.
5. Deployment examples (compose with two replicas, Kubernetes with RWX PVC, HPA example), kind e2e in
   two-replica mode, docs.
Run /security-review.
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
