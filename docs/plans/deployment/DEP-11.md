<!--
  Copyright (c) 2020-2026 gridDigIt Kft.
  Licensed under the EUPL-1.2-or-later.
  SPDX-License-Identifier: EUPL-1.2+
-->

# DEP-11 — Durable job store (PostgreSQL)

| Field | Value |
| --- | --- |
| Status | Not started |
| Phase | D3 (option 2 hardening, see [OPTION-2-ROADMAP.md](OPTION-2-ROADMAP.md)) |
| Depends on | DEP-5, DEP-7, DEP-8; decisions D-14, D-18 |
| Size | L |
| Branch | `feature/dep-11-durable-job-store` |
| Requirements | R17 |
| Decisions needed | D-14 store technology, D-18 interrupted-job policy |

## Goal

Jobs, their results and logs, and the uploads' bookkeeping survive a restart, a redeploy and a crash of `serve`.
- A client that submitted a job before a restart can poll it, fetch its result and download its outputs afterwards.
- Jobs still queued at the restart run after it.
- A job that was running when the process died ends as `failed` ("interrupted"), never as a silent loss or a silent re-run (D-18).

This is also the foundation of DEP-12: once the state is in a database, several replicas can share it.

## Today (what changes)

- **Job state:** `JobManager` holds the jobs in a `LinkedHashMap` in memory. It has TTL eviction, `--max-jobs` and `--job-store-bytes`.
- **Job objects:** `Job` holds the state (synchronized transitions `start`/`cancel`/`finish`/`fail`/`checkTimeout`) and the result string. `JobLog` is a ring buffer of lines.
- **Work area:** `WorkArea` keeps the uploads and job workspaces on disk under `--work-root`, but its index lives in memory, and the work root is **emptied at every start** (marker file `.cimpal-work`).
- **Queue:** `ServeServer` queues by submitting a task to the worker `ThreadPoolExecutor`. The queue order exists only in that executor.

## Design

**1. A `JobStore` interface** (package `eu.griddigit.CimPal.cli.command`, or a new `…cli.jobs` package; HTTP stays in `ServeServer`):

```java
interface JobStore extends AutoCloseable {
    Job create(UUID id, String command, String label, byte[] checkedConfig, UUID uploadId);
    Optional<Job> get(UUID id);
    List<Job> list(JobStatus status, int limit);
    boolean start(UUID id, Instant now);            // queued -> running, false if not queued
    boolean cancel(UUID id, Instant now, String reason);
    void finish(UUID id, Instant now, int exitCode, String result, long maxResultBytes);
    void fail(UUID id, Instant now, String message);
    void appendLog(UUID id, String line);           // cleaned line, as today
    JobLog.Slice readLog(UUID id, long from, int max);
    List<UUID> queuedInOrder(int limit);            // what the workers should run next
    void evict(Instant now);                        // TTL, max jobs, retained bytes
    int recoverAfterRestart(Instant now, String reason); // running -> failed (D-18)
}
```

- **Two implementations:**
  - `MemoryJobStore` (today's `JobManager` logic moved behind the interface; the default, unchanged behaviour);
  - `JdbcJobStore` (PostgreSQL).
- **What stays in `Job`:** the status machine. Both stores implement the same transitions. In the database they are single conditional `UPDATE`s (`… WHERE id = ? AND status = 'queued'`), so they stay atomic without locks in Java.
- **Snapshots:** `Job.Snapshot` stays the read model returned to the HTTP layer.

**2. The queue goes through the store.** The worker threads stop taking `Runnable`s from the executor's own queue. Each worker loops:
1. claim the oldest queued job (`start`);
2. run it;
3. repeat.

**Single instance (this WP):**
- The claim is a conditional `UPDATE` on the job.
- The in-process order is kept by `queuedInOrder`.
- A `Condition`/semaphore wakes the workers on submit and on cancel.

The executor-based `queuedTasks` and `dropQueuedTask` disappear: a cancelled job is just a row that is no longer `queued`. The bounded queue (`--queue-size`, 503 + `Retry-After`) counts queued rows.

**3. The schema** (SQL migrations in `CimPal-CLI/src/main/resources/db/`, applied at start by a small migrator with a `schema_version` table; no Flyway dependency):

| Table | Columns (essential) |
| --- | --- |
| `jobs` | `id uuid pk`, `command`, `label`, `status`, `created_at`, `started_at`, `finished_at`, `exit_code`, `has_violations`, `error`, `config jsonb` (the checked config), `upload_id uuid null`, `result text null`, `result_dropped bool`, `retained_bytes bigint`, `owner text null` (for DEP-13), `node text null` and `lease_until timestamptz null` (for DEP-12) |
| `job_log` | `job_id`, `seq bigint`, `line text`, primary key `(job_id, seq)`; ring-buffer semantics by deleting below `seq - capacity` |
| `uploads` | `id uuid pk`, `created_at`, `expires_at`, `total_bytes`, `files jsonb`, `owner text null` |
| `schema_version` | `version int`, `applied_at` |

Indexes: `jobs(status, created_at)` for the queue and `jobs(finished_at)` for eviction.

**4. The work area survives restarts** in durable mode.
- **At start:** with `--job-store jdbc`, the work root is **not** emptied.
- **Reconciling:** workspaces and uploads whose rows are missing are deleted; rows whose files are missing are marked (the job keeps its status, and its `files` route answers 410).
- **Safety:** the `.cimpal-work` marker rule stays.

**5. Restart recovery (D-18).**
- **At start:** `recoverAfterRestart` sets every `running` job to `failed` with "Interrupted: the server stopped while the job ran; its outputs may be incomplete".
- **Opt-in requeue:** `--requeue-interrupted` instead puts them back to `queued` (documented as safe only for commands whose outputs are overwritten).
- **Timed-out jobs:** a job in `timed_out` stays as it is.

**6. Configuration** (new `serve` options; the same keys in a config file and the env variables `CIMPAL_JOB_STORE_*`):

| Option | Meaning |
| --- | --- |
| `--job-store memory\|jdbc` | Default `memory` (today's behaviour). |
| `--job-store-url` | JDBC URL, e.g. `jdbc:postgresql://db:5432/cimpal?sslmode=verify-full`. |
| `--job-store-user` | Database user. |
| `--job-store-password-file` | The password, read from a file (a mounted secret); never a command-line value. `CIMPAL_JOB_STORE_PASSWORD` also works. |
| `--job-store-pool` | Connections (default `workers + 4`). |

**7. Dependencies.**
- The PostgreSQL JDBC driver (`org.postgresql:postgresql`) goes into CimPal-CLI only, with `requires java.sql;` added to CLI's `module-info.java`.
- No ORM and no pool library: a small fixed pool (a `BlockingQueue<Connection>` with validation) is enough for a handful of workers.
- The driver goes through the OWASP check (`-Psecurity-scan`).

**8. Results and logs outside the heap.**
- **Heap:** in durable mode a finished job's result lives in the database, not in the JVM, so `--job-store-bytes` no longer limits the heap.
- **Result size:** `--max-result-bytes` still caps one result (stored as `text`, up to the configured limit). This closes gap G-J in practice.

**9. Health and metrics.**
- `/ready` answers 503 when the database is unreachable (in durable mode).
- The `cimpal_jobs{status}` gauge comes from the store.
- New: `cimpal_job_store_errors_total`.

## Scope

- **Code:** `JobStore`, `MemoryJobStore` (a refactor of `JobManager`), `JdbcJobStore`, the migrator and SQL, the `ServeServer` worker loop, and `ServeCommand` options.
- **Work area:** `WorkArea` reconciliation in durable mode.
- **Deployment examples:** `deploy/docker-compose` gets a `postgres` service (pinned by digest) and an optional profile; `deploy/kubernetes` gets a note and an example `Secret` for the DB password (the database itself is the operator's).
- **Docs:** `docs/cli/serve.md` (options, behaviour across restarts), `docs/guide/service.md` (backups, sizing of the DB), PROJECT.md.

## Acceptance criteria (status checklist)

- [ ] `--job-store memory` (the default) behaves exactly as before: the existing `ServeServerTest`, `JobApiTest`, `FileExchangeApiTest`, `ServeWorkersTest` and `ConcurrencyIT` pass unchanged.
- [ ] The same API tests run against `JdbcJobStore`, parameterised over both stores, using an embedded test database (H2 in PostgreSQL mode for unit tests) and a real PostgreSQL in a failsafe IT on Ubuntu (Testcontainers; skipped cleanly without Docker).
- [ ] Restart test (IT):
  - submit 3 jobs and stop the server while one runs and two wait;
  - start it again on the same DB and work root;
  - the running one is `failed` ("interrupted"), the two queued ones run, and every result and output from before the stop can still be fetched.
- [ ] Every state transition is a single conditional `UPDATE`: a cancel racing a start never ends with both succeeding (a concurrency test with many threads).
- [ ] Queue limits: `--queue-size` counts queued rows; a full queue still answers 503 + `Retry-After`.
- [ ] The password is never logged, never in `/v1/health` or metrics, and not accepted as a plain command-line option.
- [ ] All SQL uses prepared statements (a test asserts there is no string-built SQL with request data).
- [ ] `/ready` is 503 while the database is unreachable, and it recovers.
- [ ] Docs updated; `/security-review` clean.

## Instructions for Claude Code

Start a session with: `Execute docs/plans/deployment/DEP-11.md` (in plan mode).

```text
Confirm D-14 and D-18 with the maintainer (recommendations in OPTION-2-ROADMAP.md §5), then:
1. Characterise first: run the existing serve/job tests and note the counts.
2. Extract JobStore from JobManager without behaviour change (MemoryJobStore); make ServeServer's workers
   claim jobs from the store instead of the executor queue. All existing tests must stay green.
3. Add the migrator, the schema and JdbcJobStore; parameterise the job API tests over both stores
   (H2 in PostgreSQL mode for unit tests; Testcontainers PostgreSQL in a failsafe IT).
4. Restart recovery and work-area reconciliation; the restart IT.
5. Options, config keys, env variables, compose/kubernetes examples, docs.
Run /security-review (credentials, SQL, file reconciliation, fail-closed recovery).
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
