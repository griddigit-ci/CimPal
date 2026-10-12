/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BooleanSupplier;

/**
 * Memory admission for {@code serve --workers} (DEP-8). Each command comes with an estimate of the
 * heap it needs; it starts only while the estimates of the running commands plus its own fit the
 * budget ({@code --max-concurrent-heap-bytes}). A command with nothing else running always starts,
 * however large, so one big job is never refused. Waiting is first come, first served: a large
 * command at the head is not overtaken by small ones behind it, so it can't be starved.
 */
final class AdmissionGate {

    /** Released once, when the command ends. */
    interface Ticket extends AutoCloseable {
        @Override
        void close();
    }

    /** How often a waiting command checks whether it was abandoned (cancelled, timed out). */
    private static final long RECHECK_MILLIS = 200;

    private final long budget;
    /** Guarded by this. */
    private long inUse;
    private int running;
    private final Deque<Object> waiting = new ArrayDeque<>();

    AdmissionGate(long budget) {
        if (budget <= 0) {
            throw new IllegalArgumentException("budget must be positive");
        }
        this.budget = budget;
    }

    /**
     * Waits until the command may start. Returns null when {@code abandoned} turned true while it
     * waited (its turn passes to the next one).
     */
    Ticket acquire(long estimate, BooleanSupplier abandoned) throws InterruptedException {
        long need = Math.min(Math.max(0, estimate), budget); // a larger one runs alone, as if it were the budget
        Object me = new Object();
        synchronized (this) {
            waiting.addLast(me);
            try {
                while (waiting.peekFirst() != me || (running > 0 && inUse + need > budget)) {
                    if (abandoned.getAsBoolean()) {
                        return null;
                    }
                    wait(RECHECK_MILLIS);
                }
                if (abandoned.getAsBoolean()) {
                    return null;
                }
                running++;
                inUse += need;
            } finally {
                if (waiting.remove(me)) {
                    notifyAll(); // the next in line may fit now
                }
            }
        }
        return new Ticket() {
            private boolean released;

            @Override
            public void close() {
                synchronized (AdmissionGate.this) {
                    if (!released) {
                        released = true;
                        running--;
                        inUse -= need;
                        AdmissionGate.this.notifyAll();
                    }
                }
            }
        };
    }

    /** Commands waiting now (tests). */
    synchronized int waitingForTest() {
        return waiting.size();
    }

    /** Bytes of estimates of the running commands (tests). */
    synchronized long inUse() {
        return inUse;
    }
}
