/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DEP-2 (R2): an OutOfMemoryError in a validation worker must end the run, never be counted as a
 * failed row, which the CLI would report as exit 1 ("violations found").
 */
class OutOfMemoryRethrowTest {

    @Test
    void anOutOfMemoryErrorFromAWorkerIsRethrown() throws Exception {
        OutOfMemoryError oom = new OutOfMemoryError("Java heap space");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ExecutionException wrapped;
        try {
            Future<?> future = pool.submit(() -> {
                throw oom;
            });
            wrapped = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class, future::get);
        } finally {
            pool.shutdownNow();
        }

        assertThatThrownBy(() -> OutOfMemoryRethrow.ifCause(wrapped)).isSameAs(oom);
    }

    @Test
    void anOutOfMemoryErrorDeeperInTheCauseChainIsRethrown() {
        OutOfMemoryError oom = new OutOfMemoryError("Java heap space");
        Exception chain = new ExecutionException(new RuntimeException("row 3", new IllegalStateException(oom)));

        assertThatThrownBy(() -> OutOfMemoryRethrow.ifCause(chain)).isSameAs(oom);
    }

    @Test
    void otherFailuresAreLeftToTheCaller() {
        Exception chain = new ExecutionException(new RuntimeException("bad row", new StackOverflowError()));

        assertThatCode(() -> OutOfMemoryRethrow.ifCause(chain)).doesNotThrowAnyException();
        assertThatCode(() -> OutOfMemoryRethrow.ifCause(null)).doesNotThrowAnyException();
    }

    @Test
    void aCyclicCauseChainEnds() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertThatCode(() -> OutOfMemoryRethrow.ifCause(a)).doesNotThrowAnyException();
    }

    @Test
    void findReturnsTheErrorWithoutThrowing() {
        OutOfMemoryError oom = new OutOfMemoryError();

        assertThat(OutOfMemoryRethrow.find(new RuntimeException(oom))).containsSame(oom);
        assertThat(OutOfMemoryRethrow.find(new RuntimeException())).isEmpty();
    }
}
