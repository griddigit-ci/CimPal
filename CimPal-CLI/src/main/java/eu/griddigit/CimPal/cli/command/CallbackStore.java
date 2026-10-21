/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The completion callbacks of jobs (DEP-14, D-19): one per job that asked for one. A callback
 * <em>waits</em> while its job runs, is <em>pending</em> once the job is final, and ends
 * <em>delivered</em> or, after its retries, <em>failed</em>. Every attempt is recorded and shown in
 * the job ({@code callback.lastStatus}).
 */
interface CallbackStore {

    enum State {
        WAITING, PENDING, DELIVERED, FAILED;

        String apiName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** One job's callback. {@code lastStatus} is the receiver's HTTP status, or null (none, or no answer). */
    record Delivery(UUID jobId, String client, URI url, State state, int attempts, Instant nextAttemptAt,
                    Instant lastAttemptAt, Integer lastStatus, String lastError, Instant createdAt) {
    }

    /** The job will call {@code url} when it ends. */
    void register(UUID jobId, String client, URI url, Instant now);

    /** The job's callback, or null. */
    Delivery get(UUID jobId);

    /** Waiting callbacks whose job is final now ({@code finished}) become pending, due at {@code now}. */
    void arm(Predicate<UUID> finished, Instant now);

    /** Pending callbacks due at {@code now}, oldest first. */
    List<Delivery> due(Instant now, int limit);

    /**
     * Records an attempt: delivered, or (with {@code nextAttemptAt} null) failed for good, or
     * pending again at {@code nextAttemptAt}.
     */
    void attempted(UUID jobId, Instant at, Integer status, String error, boolean delivered, Instant nextAttemptAt);

    /** The job left the store: its callback goes with it. */
    void remove(UUID jobId);

    /** In memory: lost on restart, as the jobs are. */
    final class Memory implements CallbackStore {

        private final Map<UUID, Delivery> deliveries = new ConcurrentHashMap<>();

        @Override
        public void register(UUID jobId, String client, URI url, Instant now) {
            deliveries.put(jobId, new Delivery(jobId, client, url, State.WAITING, 0, null, null, null, null, now));
        }

        @Override
        public Delivery get(UUID jobId) {
            return deliveries.get(jobId);
        }

        @Override
        public synchronized void arm(Predicate<UUID> finished, Instant now) {
            for (Delivery d : List.copyOf(deliveries.values())) {
                if (d.state() == State.WAITING && finished.test(d.jobId())) {
                    deliveries.put(d.jobId(), new Delivery(d.jobId(), d.client(), d.url(), State.PENDING, 0, now, null,
                            null, null, d.createdAt()));
                }
            }
        }

        @Override
        public List<Delivery> due(Instant now, int limit) {
            List<Delivery> out = new ArrayList<>();
            deliveries.values().stream()
                    .filter(d -> d.state() == State.PENDING && !d.nextAttemptAt().isAfter(now))
                    .sorted(Comparator.comparing(Delivery::nextAttemptAt).thenComparing(Delivery::jobId))
                    .limit(limit).forEach(out::add);
            return out;
        }

        @Override
        public synchronized void attempted(UUID jobId, Instant at, Integer status, String error, boolean delivered,
                                           Instant nextAttemptAt) {
            deliveries.computeIfPresent(jobId, (id, d) -> new Delivery(id, d.client(), d.url(),
                    delivered ? State.DELIVERED : nextAttemptAt == null ? State.FAILED : State.PENDING,
                    d.attempts() + 1, delivered ? null : nextAttemptAt, at, status, error, d.createdAt()));
        }

        @Override
        public void remove(UUID jobId) {
            deliveries.remove(jobId);
        }
    }
}
