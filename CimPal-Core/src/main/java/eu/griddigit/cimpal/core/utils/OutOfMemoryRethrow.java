/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

/**
 * Keeps an {@link OutOfMemoryError} from being swallowed. Worker pools wrap a worker's error in an
 * {@link java.util.concurrent.ExecutionException}; code that turns those into a failed row would
 * otherwise report an out-of-memory run as "violations found" (DEP-2, R2). The caller lets the
 * error propagate, and the CLI turns it into exit code 3.
 */
public final class OutOfMemoryRethrow {

    private OutOfMemoryRethrow() {
    }

    /** Throws the {@link OutOfMemoryError} in {@code failure}'s cause chain, if there is one. */
    public static void ifCause(Throwable failure) {
        Optional<OutOfMemoryError> oom = find(failure);
        if (oom.isPresent()) {
            throw oom.get();
        }
    }

    /** The first {@link OutOfMemoryError} in {@code failure}'s cause chain (including itself). */
    public static Optional<OutOfMemoryError> find(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = failure; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof OutOfMemoryError oom) {
                return Optional.of(oom);
            }
        }
        return Optional.empty();
    }
}
