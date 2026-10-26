/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Memory admission of {@code serve --workers} (DEP-8). */
class AdmissionGateTest {

    private static final long MIB = 1L << 20;

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition not met within 10 s").isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    @Test
    void commandsThatFitTogetherRunTogether() throws Exception {
        AdmissionGate gate = new AdmissionGate(100 * MIB);
        AdmissionGate.Ticket a = gate.acquire(60 * MIB, () -> false);
        AdmissionGate.Ticket b = gate.acquire(40 * MIB, () -> false);
        assertThat(gate.inUse()).isEqualTo(100 * MIB);
        a.close();
        b.close();
        assertThat(gate.inUse()).isZero();
    }

    @Test
    void aCommandAloneAlwaysStartsHoweverLarge() throws Exception {
        AdmissionGate gate = new AdmissionGate(100 * MIB);
        try (AdmissionGate.Ticket huge = gate.acquire(Long.MAX_VALUE, () -> false)) {
            assertThat(huge).isNotNull();
            assertThat(gate.inUse()).as("an estimate counts at most the budget").isEqualTo(100 * MIB);
        }
    }

    @Test
    void aCommandThatDoesNotFitWaitsAndSmallerOnesBehindItDoNotOvertake() throws Exception {
        AdmissionGate gate = new AdmissionGate(100 * MIB);
        AdmissionGate.Ticket big = gate.acquire(80 * MIB, () -> false);
        // The abandoned check runs under the gate's lock, last of all just before admission, so
        // the last value each waiter saw is the gate's use at the moment it was let in. Recording
        // the order after acquire returned raced: both are admitted within microseconds.
        List<Long> seenByBig2 = new CopyOnWriteArrayList<>();
        List<Long> seenBySmall = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AdmissionGate.Ticket> second = pool.submit(() -> gate.acquire(80 * MIB, () -> {
                seenByBig2.add(gate.inUse());
                return false;
            }));
            waitUntil(() -> gate.waitingForTest() == 1);
            Future<AdmissionGate.Ticket> small = pool.submit(() -> gate.acquire(10 * MIB, () -> {
                seenBySmall.add(gate.inUse());
                return false;
            }));
            waitUntil(() -> gate.waitingForTest() == 2);
            Thread.sleep(100);
            // The small one would fit (90 MiB) but is behind big2: first come, first served.
            assertThat(second.isDone()).isFalse();
            assertThat(small.isDone()).isFalse();

            big.close();
            AdmissionGate.Ticket t2 = second.get(10, TimeUnit.SECONDS);
            AdmissionGate.Ticket t3 = small.get(10, TimeUnit.SECONDS);
            assertThat(seenByBig2.getLast()).as("big2 let in once big had left").isZero();
            assertThat(seenBySmall.getLast()).as("small let in after big2").isEqualTo(80 * MIB);
            assertThat(gate.inUse()).isEqualTo(90 * MIB);
            t2.close();
            t3.close();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anAbandonedWaiterGivesUpItsTurn() throws Exception {
        AdmissionGate gate = new AdmissionGate(100 * MIB);
        AdmissionGate.Ticket big = gate.acquire(80 * MIB, () -> false);
        AtomicBoolean cancelled = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AdmissionGate.Ticket> waiter = pool.submit(() -> gate.acquire(80 * MIB, cancelled::get));
            waitUntil(() -> gate.waitingForTest() == 1);
            Future<AdmissionGate.Ticket> small = pool.submit(() -> gate.acquire(10 * MIB, () -> false));
            waitUntil(() -> gate.waitingForTest() == 2);

            cancelled.set(true);
            assertThat(waiter.get(10, TimeUnit.SECONDS)).isNull();
            // The next in line fits next to the running one and starts without waiting for it.
            assertThat(small.get(10, TimeUnit.SECONDS)).isNotNull();
            assertThat(gate.inUse()).isEqualTo(90 * MIB);
        } finally {
            big.close();
            pool.shutdownNow();
        }
    }

    @Test
    void aTicketIsReleasedOnce() throws Exception {
        AdmissionGate gate = new AdmissionGate(100 * MIB);
        AdmissionGate.Ticket a = gate.acquire(30 * MIB, () -> false);
        AdmissionGate.Ticket b = gate.acquire(30 * MIB, () -> false);
        a.close();
        a.close();
        assertThat(gate.inUse()).isEqualTo(30 * MIB);
        b.close();
    }

    @Test
    void theBudgetMustBePositive() {
        assertThatThrownBy(() -> new AdmissionGate(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
