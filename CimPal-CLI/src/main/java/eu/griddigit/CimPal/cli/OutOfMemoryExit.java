/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli;

/**
 * Ends the JVM with exit code 3 after an {@link OutOfMemoryError} (DEP-2, R2): from {@code main}
 * for one-shot commands, {@code run} and {@code mcp}, and from {@code serve}, whose JVM can't be
 * trusted to carry on. The message is built before it is needed, and {@code halt} skips the
 * shutdown hooks, because both could need memory there is none of.
 */
public final class OutOfMemoryExit {

    private static final String MESSAGE = "[ERROR] Out of memory (max heap "
            + (Runtime.getRuntime().maxMemory() >> 20) + " MB). Give the JVM more memory (-Xmx, or the"
            + " container's memory limit); see docs/guide/sizing.md for sizes by model.";

    private OutOfMemoryExit() {
    }

    /** Prints the message on stderr and halts the JVM with {@link ExitCode#INTERNAL_ERROR}. */
    public static void halt() {
        try {
            System.err.println(MESSAGE);
            System.err.flush();
        } catch (Throwable ignored) {
            // Printing may need memory too; the exit code must not depend on it.
        }
        Runtime.getRuntime().halt(ExitCode.INTERNAL_ERROR);
    }

    /**
     * Loads this class and builds its message now. Called at start-up: done only once the heap is
     * exhausted, the class initialisation could itself run out of memory, and that error would
     * escape {@code main} as exit code 1 instead of 3.
     */
    public static void prepare() {
        // Loading the class is the work; MESSAGE is built by its static initialiser.
    }
}
