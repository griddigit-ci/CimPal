/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Which finished jobs a {@link JobStore} drops, the same for every store: finished jobs past the
 * TTL, then finished ones beyond {@code --max-jobs}, then finished ones until their results and
 * logs fit in {@code --job-store-bytes} (the most recently finished job is always kept, so its
 * result can be fetched). Beyond the TTL, the oldest finished job of the client that holds the most
 * (jobs, then bytes) goes first, so one client's many jobs can't push out another client's results
 * (DEP-13); with one client this is simply oldest first.
 */
final class JobEviction {

    /** A finished job, as eviction sees it. */
    record Finished(UUID id, String owner, Instant finishedAt, long retainedBytes) {
    }

    private JobEviction() {
    }

    /**
     * The ids to drop.
     *
     * @param finished     every finished job in the store
     * @param storedJobs   how many jobs the store holds, finished or not
     * @param storedBytes  the bytes all of them retain
     */
    static List<UUID> victims(List<Finished> finished, int storedJobs, long storedBytes, Instant now, Duration ttl,
                              int maxJobs, long storeBytes) {
        List<UUID> removed = new ArrayList<>();
        List<Finished> kept = new ArrayList<>();
        int jobs = storedJobs;
        long retained = storedBytes;
        for (Finished f : finished) {
            if (f.finishedAt().plus(ttl).isBefore(now)) {
                removed.add(f.id());
                jobs--;
                retained -= f.retainedBytes();
            } else {
                kept.add(f);
            }
        }
        kept.sort(Comparator.comparing(Finished::finishedAt).thenComparing(Finished::id));
        // Per client: its finished jobs, oldest first, and the bytes they hold.
        Map<String, ArrayDeque<Finished>> byOwner = new TreeMap<>();
        Map<String, Long> ownerBytes = new HashMap<>();
        for (Finished f : kept) {
            byOwner.computeIfAbsent(f.owner(), o -> new ArrayDeque<>()).add(f);
            ownerBytes.merge(f.owner(), f.retainedBytes(), Long::sum);
        }
        while (jobs > maxJobs) {
            Finished victim = oldestOfHeaviest(byOwner, ownerBytes, false);
            if (victim == null) {
                break;
            }
            drop(victim, byOwner, ownerBytes, removed);
            jobs--;
            retained -= victim.retainedBytes();
        }
        // The most recently finished job stays, so its result can be fetched.
        Finished newest = kept.isEmpty() ? null : kept.getLast();
        if (newest != null && !removed.contains(newest.id())) {
            byOwner.get(newest.owner()).remove(newest);
            ownerBytes.merge(newest.owner(), -newest.retainedBytes(), Long::sum);
        }
        while (retained > storeBytes) {
            Finished victim = oldestOfHeaviest(byOwner, ownerBytes, true);
            if (victim == null) {
                break;
            }
            retained -= victim.retainedBytes();
            drop(victim, byOwner, ownerBytes, removed);
        }
        return removed;
    }

    /** The oldest finished job of the client with the most finished jobs (or bytes); null when none is left. */
    private static Finished oldestOfHeaviest(Map<String, ArrayDeque<Finished>> byOwner, Map<String, Long> ownerBytes,
                                             boolean byBytes) {
        String heaviest = null;
        long most = -1;
        for (Map.Entry<String, ArrayDeque<Finished>> e : byOwner.entrySet()) {
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

    private static void drop(Finished f, Map<String, ArrayDeque<Finished>> byOwner, Map<String, Long> ownerBytes,
                             List<UUID> removed) {
        byOwner.get(f.owner()).remove(f);
        ownerBytes.merge(f.owner(), -f.retainedBytes(), Long::sum);
        removed.add(f.id());
    }
}
