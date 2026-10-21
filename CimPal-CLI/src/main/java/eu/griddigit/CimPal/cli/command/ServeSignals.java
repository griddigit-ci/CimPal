/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import sun.misc.Signal;

/**
 * SIGTERM for {@code serve} (DEP-6). Docker and Kubernetes stop a container with SIGTERM. By
 * default the JVM then runs its shutdown hooks and exits with 143, whatever the server was
 * doing. With this handler, SIGTERM takes the same path as {@code POST /shutdown}: the server
 * stops taking work, cancels queued jobs, and {@code serve} waits up to {@code --shutdown-grace}
 * for the running command before it exits with 0 (or 3 if the command was still running).
 *
 * <p>{@code sun.misc.Signal} (module {@code jdk.unsupported}) is the only JDK API for this.
 * Where it isn't available, the shutdown hook still drains the server, but the exit code is the
 * JVM's.
 */
final class ServeSignals {

    private ServeSignals() {
    }

    /** Runs {@code onTerm} on SIGTERM instead of the JVM's default. False if it can't be installed. */
    static boolean onTerm(Runnable onTerm) {
        try {
            Signal.handle(new Signal("TERM"), signal -> onTerm.run());
            return true;
        } catch (IllegalArgumentException | UnsupportedOperationException | LinkageError e) {
            return false;
        }
    }
}
