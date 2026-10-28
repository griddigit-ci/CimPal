/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

/** Where a running job's log lines go: a {@link JobLog} ring, or a durable store (DEP-11). */
@FunctionalInterface
interface LogSink {

    /** One line, already sanitised ({@code LogSanitizer.forLog}). */
    void append(String line);
}
