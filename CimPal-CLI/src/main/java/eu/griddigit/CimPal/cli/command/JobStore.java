/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Where the {@code /v1} jobs live (DEP-11): their state, result and log. {@link MemoryJobStore} is
 * the default (lost on restart); {@link JdbcJobStore} keeps them in PostgreSQL across restarts.
 *
 * <p>The store is the source of truth for a job's status. Every transition is one atomic
 * check-and-set: {@link #start} and {@link #cancel} only succeed on a queued job, so a cancel and a
 * worker starting the job can't both win. {@link ServeServer} decides which queued job runs next
 * ({@link JobScheduler}) and owns the HTTP side.
 */
interface JobStore extends AutoCloseable {

    /** The store can't be reached or failed; the request is answered 503, and nothing is half-done. */
    final class Unavailable extends RuntimeException {
        Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A job that is queued after a restart, with what it needs to run. */
    record Queued(Job.Snapshot job, byte[] body, UUID uploadId) {
    }

    Instant now();

    /** Told about every job that leaves the store (DEP-7: its workspace goes with it). */
    void onRemoved(Consumer<UUID> listener);

    /** A random id no job has, for a job whose workspace is prepared before it is created. */
    UUID newId();

    /**
     * A new queued job, or null if a job already has {@code id}.
     *
     * @param body     the checked config the command runs with (kept to run the job after a restart)
     * @param uploadId the upload it reads, or null
     */
    Job.Snapshot create(UUID id, String command, String label, String owner, byte[] body, UUID uploadId);

    /** The job, or null; marks it timed out first if it has run too long. */
    Job.Snapshot get(UUID id);

    /** Its status now, or null when there is no such job (cheap; asked while a job waits to start). */
    JobStatus status(UUID id);

    /** Newest first, optionally only one status and only one owner's. */
    List<Job.Snapshot> list(JobStatus status, String owner, int limit);

    /** Queued → running. False when it isn't queued (cancelled first); then it must not run. */
    boolean start(UUID id);

    /** Queued → cancelled. False when it already started or ended. */
    boolean cancel(UUID id, String reason);

    /** The command returned: exit 0 and 1 are results, 2 and 3 failures; a timed-out job keeps that status. */
    void finish(UUID id, int exitCode, String stdout, long maxResultBytes);

    /** The command threw instead of returning an exit code. */
    void fail(UUID id, String message);

    /** The command's JSON output, or null when there is none (yet). */
    String result(UUID id);

    /** Where a running job's log lines go. */
    LogSink logSink(UUID id);

    /** Up to {@code max} lines of the job's log from {@code from} on. */
    JobLog.Slice readLog(UUID id, long from, int max);

    /** True when the job has run past {@code --job-timeout} (it is marked timed out first). */
    boolean timedOut(UUID id);

    /** Drops expired and surplus finished jobs ({@link JobEviction}) and tells {@link #onRemoved}. */
    void evict();

    /** The queued jobs, oldest first. */
    List<Job.Snapshot> queued();

    long count(JobStatus status);

    int size();

    /**
     * After a restart (D-18): jobs that were running are failed as interrupted, or queued again
     * with {@code requeue}. Returns the queued jobs, oldest first, to be handed to the workers.
     */
    default List<Queued> recover(boolean requeue) {
        return List.of();
    }

    /** Records uploads across restarts; {@link WorkArea.UploadIndex#NONE} when jobs don't survive one. */
    default WorkArea.UploadIndex uploadIndex() {
        return WorkArea.UploadIndex.NONE;
    }

    /** The uploads recorded before a restart. */
    default List<WorkArea.UploadRecord> uploadRecords() {
        return List.of();
    }

    /** The jobs that used an upload; active while queued (call after {@link #recover}). */
    default List<WorkArea.JobRef> jobRefs() {
        return List.of();
    }

    /** False while the store can't be reached; {@code /ready} then answers 503. */
    default boolean usable() {
        return true;
    }

    /** Failed store operations since start ({@code cimpal_job_store_errors_total}). */
    default long errors() {
        return 0;
    }

    /** True when jobs survive a restart (the work area is then kept too). */
    default boolean durable() {
        return false;
    }

    @Override
    default void close() {
    }
}
