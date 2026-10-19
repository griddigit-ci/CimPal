/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code Idempotency-Key}s of {@code POST /v1/jobs} and {@code POST /v1/uploads} (DEP-14):
 * per client and key, the request's fingerprint and the job or upload it made, so a repeated
 * request gets that one back instead of a second one.
 *
 * <p>A key is <em>reserved</em> by {@link #begin} with the id the job or upload will have, before
 * it exists, and <em>completed</em> once it does; a request that is refused gives its key back
 * ({@link #abandon}). The store never takes a key over by itself: {@link #begin} returns what the
 * key holds, and the caller decides, knowing whether that job or upload exists, to take it over
 * with {@link #retake} (a reservation older than {@link #STALE} whose request died, or a key past
 * its TTL). {@link #complete} and {@link #abandon} only touch the reservation of their own id, so
 * a request whose key was taken over can't change the new one.
 */
interface IdempotencyStore {

    /** How long an unfinished reservation holds its key against a request without its job. */
    Duration STALE = Duration.ofMinutes(10);

    /** What a key holds: the request's fingerprint, the job or upload it made, and whether that exists now. */
    record Entry(String fingerprint, UUID resourceId, boolean done, Instant createdAt) {

        boolean expired(Instant now, Duration ttl) {
            return !createdAt.plus(ttl).isAfter(now);
        }

        boolean stale(Instant now) {
            return !done && !createdAt.plus(STALE).isAfter(now);
        }
    }

    /**
     * Reserves {@code key} of {@code client} for a request with {@code fingerprint} that will make
     * {@code resourceId}. Null when it was free: the caller goes ahead and then calls
     * {@link #complete} or {@link #abandon}. Otherwise what the key holds, untouched.
     *
     * @throws JobStore.Unavailable when the store is full
     */
    Entry begin(String client, String key, String fingerprint, UUID resourceId, Instant now);

    /**
     * Takes over a key that still holds {@code seen} (expired, or stale with nothing made): true
     * when it is now reserved for {@code resourceId}, false when it changed meanwhile.
     */
    boolean retake(String client, String key, Entry seen, String fingerprint, UUID resourceId, Instant now);

    /** {@code resourceId} exists: a repeat gets it back. */
    void complete(String client, String key, UUID resourceId);

    /** The request for {@code resourceId} was refused: the key is free again (if it is still that one's). */
    void abandon(String client, String key, UUID resourceId);

    /** Drops keys first used before {@code before}. */
    void evict(Instant before);

    /**
     * The in-memory store (lost on restart, as the jobs are): at most {@link #PER_CLIENT} keys per
     * client, where a client's own oldest finished keys make room, and {@link #MAX_KEYS} in all,
     * beyond which new keys are refused (503) rather than another client's evicted.
     */
    final class Memory implements IdempotencyStore {

        static final int MAX_KEYS = 100_000;
        static final int PER_CLIENT = 10_000;

        private final Map<String, Map<String, Entry>> clients = new ConcurrentHashMap<>();

        private int total() {
            return clients.values().stream().mapToInt(Map::size).sum();
        }

        @Override
        public synchronized Entry begin(String client, String key, String fingerprint, UUID resourceId, Instant now) {
            Map<String, Entry> keys = clients.computeIfAbsent(client, c -> new ConcurrentHashMap<>());
            Entry existing = keys.get(key);
            if (existing != null) {
                return existing;
            }
            if (keys.size() >= PER_CLIENT) { // this client's own oldest finished keys make room
                keys.entrySet().stream().filter(e -> e.getValue().done())
                        .sorted(Comparator.comparing(e -> e.getValue().createdAt()))
                        .limit(keys.size() - PER_CLIENT + 1).map(Map.Entry::getKey).toList().forEach(keys::remove);
            }
            if (keys.size() >= PER_CLIENT || total() >= MAX_KEYS) {
                throw new JobStore.Unavailable("Too many Idempotency-Keys are held; retry later", null);
            }
            keys.put(key, new Entry(fingerprint, resourceId, false, now));
            return null;
        }

        @Override
        public synchronized boolean retake(String client, String key, Entry seen, String fingerprint, UUID resourceId,
                                           Instant now) {
            Map<String, Entry> keys = clients.get(client);
            if (keys == null || !seen.equals(keys.get(key))) {
                return false;
            }
            keys.put(key, new Entry(fingerprint, resourceId, false, now));
            return true;
        }

        @Override
        public synchronized void complete(String client, String key, UUID resourceId) {
            Map<String, Entry> keys = clients.get(client);
            if (keys != null) {
                keys.computeIfPresent(key, (k, e) -> e.resourceId().equals(resourceId)
                        ? new Entry(e.fingerprint(), e.resourceId(), true, e.createdAt()) : e);
            }
        }

        @Override
        public synchronized void abandon(String client, String key, UUID resourceId) {
            Map<String, Entry> keys = clients.get(client);
            if (keys != null) {
                keys.computeIfPresent(key, (k, e) -> !e.done() && e.resourceId().equals(resourceId) ? null : e);
            }
        }

        @Override
        public synchronized void evict(Instant before) {
            for (Map<String, Entry> keys : clients.values()) {
                keys.values().removeIf(e -> e.createdAt().isBefore(before));
            }
            clients.values().removeIf(Map::isEmpty);
        }

        synchronized int size() {
            return total();
        }
    }
}
