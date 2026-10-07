/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-memory store of {@code /v1} jobs (DEP-5), bounded by {@code --max-jobs} and
 * {@code --job-ttl}. Only finished jobs are evicted, expired ones first, then the oldest; queued
 * and running jobs are bounded by the server's queue instead. Jobs are lost on restart. Running
 * the jobs is {@link ServeServer}'s part (its single worker and queue slots).
 */
final class JobManager {

    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final int maxJobs;
    private final Duration ttl;
    private final Duration timeout;
    private final int logLines;
    private final long storeBytes;
    private final InstantSource clock;

    JobManager(int maxJobs, Duration ttl, Duration timeout, int logLines, long storeBytes, InstantSource clock) {
        this.maxJobs = maxJobs;
        this.ttl = ttl;
        this.timeout = timeout;
        this.logLines = logLines;
        this.storeBytes = storeBytes;
        this.clock = clock;
    }

    Instant now() {
        return clock.instant();
    }

    /** A new queued job with a random (SecureRandom-based) id. */
    Job create(String command, String label) {
        UUID id;
        Job job;
        do {
            id = UUID.randomUUID();
            job = new Job(id, command, label, now(), logLines);
        } while (jobs.putIfAbsent(id, job) != null);
        return job;
    }

    /** The job, or null; marks it timed out first if it has run too long. */
    Job get(UUID id) {
        Job job = jobs.get(id);
        if (job != null) {
            job.checkTimeout(now(), timeout);
        }
        return job;
    }

    /** Newest first, optionally only one status. */
    List<Job> list(JobStatus status, int limit) {
        evict();
        Instant now = now();
        return jobs.values().stream()
                .peek(job -> job.checkTimeout(now, timeout))
                .filter(job -> status == null || job.snapshot().status() == status)
                .sorted(Comparator.comparing(Job::createdAt).reversed().thenComparing(Job::id))
                .limit(limit)
                .toList();
    }

    /**
     * Drops finished jobs past the TTL, then the oldest finished ones beyond {@code maxJobs}, then
     * the oldest finished ones until their results and logs fit in {@code --job-store-bytes} (the
     * most recently finished job is always kept, so its result can be fetched).
     */
    synchronized void evict() {
        Instant now = now();
        jobs.values().removeIf(job -> {
            Job.Snapshot s = job.snapshot();
            return s.finished() && s.finishedAt().plus(ttl).isBefore(now);
        });
        int excess = jobs.size() - maxJobs;
        if (excess > 0) {
            jobs.values().stream()
                    .map(Job::snapshot)
                    .filter(Job.Snapshot::finished)
                    .sorted(Comparator.comparing(Job.Snapshot::finishedAt).thenComparing(Job.Snapshot::id))
                    .limit(excess)
                    .forEach(s -> jobs.remove(s.id()));
        }
        List<Job> finished = jobs.values().stream()
                .filter(job -> job.snapshot().finished())
                .sorted(Comparator.comparing((Job job) -> job.snapshot().finishedAt()).thenComparing(Job::id))
                .toList();
        long retained = jobs.values().stream().mapToLong(Job::retainedBytes).sum();
        for (int i = 0; i < finished.size() - 1 && retained > storeBytes; i++) {
            Job job = finished.get(i);
            retained -= job.retainedBytes();
            jobs.remove(job.id());
        }
    }

    /** True when {@code job} has run past {@code --job-timeout} (it is marked timed out first). */
    boolean timedOut(Job job) {
        job.checkTimeout(now(), timeout);
        return job.snapshot().status() == JobStatus.TIMED_OUT;
    }

    /** The queued jobs, oldest first (for cancelling them on shutdown). */
    List<Job> queued() {
        return jobs.values().stream()
                .filter(job -> job.snapshot().status() == JobStatus.QUEUED)
                .sorted(Comparator.comparing(Job::createdAt))
                .toList();
    }

    int size() {
        return jobs.size();
    }

    long count(JobStatus status) {
        return jobs.values().stream().filter(job -> job.snapshot().status() == status).count();
    }
}
