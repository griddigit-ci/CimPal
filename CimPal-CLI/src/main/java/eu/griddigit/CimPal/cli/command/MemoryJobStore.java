/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The in-memory {@link JobStore} of {@code /v1} jobs (DEP-5, the default), bounded by
 * {@code --max-jobs}, {@code --job-ttl} and {@code --job-store-bytes} ({@link JobEviction}).
 * Queued and running jobs are bounded by the server's queue instead. Jobs are lost on restart.
 */
final class MemoryJobStore implements JobStore {

    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final int maxJobs;
    private final Duration ttl;
    private final Duration timeout;
    private final int logLines;
    private final long storeBytes;
    private final InstantSource clock;
    private volatile Consumer<UUID> onRemoved = id -> { };

    MemoryJobStore(int maxJobs, Duration ttl, Duration timeout, int logLines, long storeBytes, InstantSource clock) {
        this.maxJobs = maxJobs;
        this.ttl = ttl;
        this.timeout = timeout;
        this.logLines = logLines;
        this.storeBytes = storeBytes;
        this.clock = clock;
    }

    @Override
    public Instant now() {
        return clock.instant();
    }

    @Override
    public void onRemoved(Consumer<UUID> listener) {
        this.onRemoved = listener;
    }

    /** A new queued job of client {@code default} with a random id (tests). */
    Job.Snapshot create(String command, String label) {
        Job.Snapshot job;
        do {
            job = create(UUID.randomUUID(), command, label, TokenSource.Client.DEFAULT.name(), null, null);
        } while (job == null);
        return job;
    }

    @Override
    public UUID newId() {
        UUID id;
        do {
            id = UUID.randomUUID();
        } while (jobs.containsKey(id));
        return id;
    }

    @Override
    public Job.Snapshot create(UUID id, String command, String label, String owner, byte[] body, UUID uploadId) {
        Job job = new Job(id, command, label, owner, now(), logLines);
        return jobs.putIfAbsent(id, job) == null ? job.snapshot() : null;
    }

    /** The live job, or null; marks it timed out first if it has run too long. */
    private Job job(UUID id) {
        Job job = jobs.get(id);
        if (job != null) {
            job.checkTimeout(now(), timeout);
        }
        return job;
    }

    @Override
    public Job.Snapshot get(UUID id) {
        Job job = job(id);
        return job == null ? null : job.snapshot();
    }

    @Override
    public JobStatus status(UUID id) {
        Job job = jobs.get(id);
        return job == null ? null : job.snapshot().status();
    }

    @Override
    public List<Job.Snapshot> list(JobStatus status, String owner, int limit) {
        evict();
        Instant now = now();
        return jobs.values().stream()
                .filter(job -> owner == null || job.owner().equals(owner))
                .peek(job -> job.checkTimeout(now, timeout))
                .map(Job::snapshot)
                .filter(s -> status == null || s.status() == status)
                .sorted(Comparator.comparing(Job.Snapshot::createdAt).reversed().thenComparing(Job.Snapshot::id))
                .limit(limit)
                .toList();
    }

    @Override
    public boolean start(UUID id) {
        Job job = jobs.get(id);
        return job != null && job.start(now());
    }

    @Override
    public boolean cancel(UUID id, String reason) {
        Job job = jobs.get(id);
        return job != null && job.cancel(now(), reason);
    }

    @Override
    public void finish(UUID id, int exitCode, String stdout, long maxResultBytes) {
        Job job = jobs.get(id);
        if (job != null) {
            job.finish(now(), exitCode, stdout, maxResultBytes);
        }
    }

    @Override
    public void fail(UUID id, String message) {
        Job job = jobs.get(id);
        if (job != null) {
            job.fail(now(), message);
        }
    }

    @Override
    public String result(UUID id) {
        Job job = jobs.get(id);
        return job == null ? null : job.result();
    }

    @Override
    public LogSink logSink(UUID id) {
        Job job = jobs.get(id);
        return job == null ? line -> { } : job.log();
    }

    @Override
    public JobLog.Slice readLog(UUID id, long from, int max) {
        Job job = jobs.get(id);
        return job == null ? new JobLog.Slice(List.of(), 0, 0, false) : job.log().read(from, max);
    }

    @Override
    public boolean timedOut(UUID id) {
        Job job = job(id);
        return job != null && job.snapshot().status() == JobStatus.TIMED_OUT;
    }

    @Override
    public void evict() {
        List<UUID> removed;
        synchronized (this) {
            List<JobEviction.Finished> finished = new ArrayList<>();
            long retained = 0;
            for (Job job : jobs.values()) {
                Job.Snapshot s = job.snapshot();
                long bytes = job.retainedBytes();
                retained += bytes;
                if (s.finished()) {
                    finished.add(new JobEviction.Finished(s.id(), s.owner(), s.finishedAt(), bytes));
                }
            }
            removed = JobEviction.victims(finished, jobs.size(), retained, now(), ttl, maxJobs, storeBytes);
            removed.forEach(jobs::remove);
        }
        removed.forEach(onRemoved);
    }

    @Override
    public List<Job.Snapshot> queued() {
        return jobs.values().stream()
                .map(Job::snapshot)
                .filter(s -> s.status() == JobStatus.QUEUED)
                .sorted(Comparator.comparing(Job.Snapshot::createdAt))
                .toList();
    }

    @Override
    public int size() {
        return jobs.size();
    }

    @Override
    public long count(JobStatus status) {
        return jobs.values().stream().filter(job -> job.snapshot().status() == status).count();
    }
}
