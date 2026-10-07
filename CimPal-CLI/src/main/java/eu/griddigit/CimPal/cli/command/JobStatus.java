/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import java.util.Locale;

/** The life cycle of a {@code /v1} job (DEP-5). */
enum JobStatus {
    /** Waiting for the worker. */
    QUEUED,
    /** Running on the worker. */
    RUNNING,
    /** Ended with exit 0 or 1; {@code hasViolations} tells them apart. */
    SUCCEEDED,
    /** Ended with exit 2 or 3, or an exception. */
    FAILED,
    /** Cancelled while queued; never started. */
    CANCELLED,
    /** Ran longer than {@code --job-timeout}. Not interrupted; a later result is still kept. */
    TIMED_OUT;

    /** The name in the API, e.g. {@code timed_out}. */
    String apiName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The status for an API name, or null. */
    static JobStatus fromApiName(String name) {
        for (JobStatus status : values()) {
            if (status.apiName().equals(name)) {
                return status;
            }
        }
        return null;
    }
}
