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
    /** Told about every job that leaves the store (DEP-7: its workspace goes with it). */
    private volatile java.util.function.Consumer<UUID> onRemoved = id -> { };

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

    void onRemoved(java.util.function.Consumer<UUID> listener) {
        this.onRemoved = listener;
    }

    /** A new queued job of client {@code default} with a random (SecureRandom-based) id (tests). */
    Job create(String command, String label) {
        Job job;
        do {
            job = create(UUID.randomUUID(), command, label, TokenSource.Client.DEFAULT.name());
        } while (job == null);
        return job;
    }

    /** A random id no job has, for a job whose workspace is prepared before it is created. */
    UUID newId() {
        UUID id;
        do {
            id = UUID.randomUUID();
        } while (jobs.containsKey(id));
        return id;
    }

    /** A new queued job of {@code owner} with the given id, or null if a job already has it. */
    Job create(UUID id, String command, String label, String owner) {
        Job job = new Job(id, command, label, owner, now(), logLines);
        return jobs.putIfAbsent(id, job) == null ? job : null;
    }

    /** The job, or null; marks it timed out first if it has run too long. */
    Job get(UUID id) {
        Job job = jobs.get(id);
        if (job != null) {
            job.checkTimeout(now(), timeout);
        }
        return job;
    }

    /** Newest first, optionally only one status and only one owner's (DEP-13). */
    List<Job> list(JobStatus status, String owner, int limit) {
        evict();
        Instant now = now();
        return jobs.values().stream()
                .filter(job -> owner == null || job.owner().equals(owner))
                .peek(job -> job.checkTimeout(now, timeout))
                .filter(job -> status == null || job.snapshot().status() == status)
                .sorted(Comparator.comparing(Job::createdAt).reversed().thenComparing(Job::id))
                .limit(limit)
                .toList();
    }

    /**
     * Drops finished jobs past the TTL, then finished ones beyond {@code maxJobs}, then finished
     * ones until their results and logs fit in {@code --job-store-bytes} (the most recently finished
     * job is always kept, so its result can be fetched). Beyond the TTL, the oldest finished job of
     * the client that holds the most (jobs, then bytes) goes first, so one client's many jobs can't
     * push out another client's results (DEP-13); with one client this is simply oldest first.
     */
    void evict() {
        List<UUID> removed = new java.util.ArrayList<>();
        synchronized (this) {
            evictInto(removed);
        }
        removed.forEach(onRemoved);
    }

    private void evictInto(List<UUID> removed) {
        Instant now = now();
        jobs.values().removeIf(job -> {
            Job.Snapshot s = job.snapshot();
            boolean expired = s.finished() && s.finishedAt().plus(ttl).isBefore(now);
            if (expired) {
                removed.add(job.id());
            }
            return expired;
        });
        List<Job> finished = jobs.values().stream()
                .filter(job -> job.snapshot().finished())
                .sorted(Comparator.comparing((Job job) -> job.snapshot().finishedAt()).thenComparing(Job::id))
                .toList();
        // Per client: its finished jobs, oldest first, and the bytes they hold.
        Map<String, java.util.ArrayDeque<Job>> byOwner = new java.util.TreeMap<>();
        Map<String, Long> ownerBytes = new java.util.HashMap<>();
        for (Job job : finished) {
            byOwner.computeIfAbsent(job.owner(), o -> new java.util.ArrayDeque<>()).add(job);
            ownerBytes.merge(job.owner(), job.retainedBytes(), Long::sum);
        }
        while (jobs.size() > maxJobs) {
            Job victim = oldestOfHeaviest(byOwner, ownerBytes, false);
            if (victim == null) {
                break;
            }
            drop(victim, byOwner, ownerBytes, removed);
        }
        // The most recently finished job stays, so its result can be fetched.
        Job newest = finished.isEmpty() ? null : finished.getLast();
        if (newest != null && jobs.containsKey(newest.id())) {
            byOwner.get(newest.owner()).remove(newest);
            ownerBytes.merge(newest.owner(), -newest.retainedBytes(), Long::sum);
        }
        long retained = jobs.values().stream().mapToLong(Job::retainedBytes).sum();
        while (retained > storeBytes) {
            Job victim = oldestOfHeaviest(byOwner, ownerBytes, true);
            if (victim == null) {
                break;
            }
            retained -= victim.retainedBytes();
            drop(victim, byOwner, ownerBytes, removed);
        }
    }

    /** The oldest finished job of the client with the most finished jobs (or bytes); null when none is left. */
    private static Job oldestOfHeaviest(Map<String, java.util.ArrayDeque<Job>> byOwner, Map<String, Long> ownerBytes,
                                        boolean byBytes) {
        String heaviest = null;
        long most = -1;
        for (Map.Entry<String, java.util.ArrayDeque<Job>> e : byOwner.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            long weight = byBytes ? ownerBytes.getOrDefault(e.getKey(), 0L) : e.getValue().size();
            if (weight > most) {
                most = weight;
                heaviest = e.getKey();
            }
        }
        return heaviest == null ? null : byOwner.get(heaviest).peekFirst();
    }

    private void drop(Job job, Map<String, java.util.ArrayDeque<Job>> byOwner, Map<String, Long> ownerBytes,
                      List<UUID> removed) {
        byOwner.get(job.owner()).remove(job);
        ownerBytes.merge(job.owner(), -job.retainedBytes(), Long::sum);
        jobs.remove(job.id());
        removed.add(job.id());
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
