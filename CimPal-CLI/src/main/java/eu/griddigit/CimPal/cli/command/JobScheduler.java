/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * Which queued {@code /v1} job runs next (DEP-13): the oldest one whose client runs fewer jobs
 * than its {@code maxRunning}, so the jobs of a client at its limit wait while other clients'
 * jobs overtake them. With every client's {@code maxRunning} at {@code --workers} (the default)
 * this is plain first come, first served, as before.
 *
 * <p>{@link ServeServer} queues one interchangeable <em>turn</em> on its workers per submitted
 * job. A turn asks {@link #next()} for the job to run; when every queued job's client is at its
 * limit, the turn <em>parks</em> instead of holding a worker, and {@link #finished(String)} tells
 * when a parked turn should be queued again (a running job ended, so one more may start). A turn
 * that finds nothing queued (its job was cancelled) does nothing.
 */
final class JobScheduler {

    /** A queued job and its checked request body. */
    record Entry(Job job, byte[] body) {
    }

    private final ToIntFunction<String> maxRunning;
    /** Queued jobs in submit order. Guarded by this. */
    private final List<Entry> pending = new ArrayList<>();
    /** Per client: jobs taken by a turn that hasn't ended yet. Guarded by this. */
    private final Map<String, Integer> running = new HashMap<>();
    /** Turns that found no job they could run. Guarded by this. */
    private int parked;

    /** {@code maxRunning} gives each client's limit of jobs running at once (at least 1). */
    JobScheduler(ToIntFunction<String> maxRunning) {
        this.maxRunning = maxRunning;
    }

    /** A job was queued; one turn must be queued for it too. */
    synchronized void add(Job job, byte[] body) {
        pending.add(new Entry(job, body));
    }

    /**
     * Takes the oldest queued job whose client is under its limit and counts it as running, or
     * returns null: nothing is queued, or (then the turn parks) every queued job must wait.
     */
    synchronized Entry next() {
        if (pending.isEmpty()) {
            parked = 0;
            return null;
        }
        for (Iterator<Entry> it = pending.iterator(); it.hasNext(); ) {
            Entry entry = it.next();
            String owner = entry.job().owner();
            if (runningOf(owner) < Math.max(1, maxRunning.applyAsInt(owner))) {
                it.remove();
                running.merge(owner, 1, Integer::sum);
                return entry;
            }
        }
        parked++;
        return null;
    }

    /** Drops a queued job (cancelled); false when it isn't queued here (any more). */
    synchronized boolean remove(UUID jobId) {
        boolean removed = pending.removeIf(entry -> entry.job().id().equals(jobId));
        if (pending.isEmpty()) {
            parked = 0; // no turn is needed any more
        }
        return removed;
    }

    /**
     * The turn that ran a job of {@code owner} ended. True when a parked turn should be queued
     * again, because a queued job may run now.
     */
    synchronized boolean finished(String owner) {
        running.computeIfPresent(owner, (k, n) -> n > 1 ? n - 1 : null);
        if (parked == 0) {
            return false;
        }
        for (Entry entry : pending) {
            String o = entry.job().owner();
            if (runningOf(o) < Math.max(1, maxRunning.applyAsInt(o))) {
                parked--;
                return true;
            }
        }
        return false;
    }

    /** The jobs of {@code owner} queued or running: what {@code maxQueued} counts against. */
    synchronized int unfinished(String owner) {
        int n = runningOf(owner);
        for (Entry entry : pending) {
            if (entry.job().owner().equals(owner)) {
                n++;
            }
        }
        return n;
    }

    /** Jobs of {@code owner} taken by a turn that hasn't ended (tests). */
    synchronized int runningOf(String owner) {
        return running.getOrDefault(owner, 0);
    }

    /** Parked turns (tests). */
    synchronized int parkedForTest() {
        return parked;
    }

    /** Queued jobs (tests). */
    synchronized int pendingForTest() {
        return pending.size();
    }
}
