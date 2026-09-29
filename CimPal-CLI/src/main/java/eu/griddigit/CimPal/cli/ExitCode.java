/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli;

/** Standard exit codes used across all CimPal CLI subcommands. */
public final class ExitCode {
    /** Command completed successfully; no violations found. */
    public static final int OK = 0;
    /** Validation completed but violations were found. */
    public static final int VIOLATIONS = 1;
    /** Bad or missing input; command was not executed. */
    public static final int INVALID_INPUT = 2;
    /** Unexpected internal error during execution. */
    public static final int INTERNAL_ERROR = 3;

    private ExitCode() {}
}
