/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.ExitCode;
import eu.griddigit.cimpal.core.utils.AtomicFiles;
import eu.griddigit.cimpal.core.utils.LogSanitizer;
import eu.griddigit.cimpal.core.utils.PathPolicy;
import tools.jackson.databind.JsonNode;

import java.io.File;
import java.io.IOException;

/**
 * The options for running a JSON-capable command from a scheduler such as Airflow (DEP-3):
 * {@code --summary-file} writes the JSON result to a file as well, and
 * {@code --violations-exit-code} replaces the exit code for "violations found" (1), so findings
 * can be data instead of a failed task. Exit codes 2 and 3, and exit 1 for rows that failed with
 * an error, never change.
 */
final class AutomationOptions {

    /** The highest exit code a process can report on every platform. */
    static final int MAX_EXIT_CODE = 255;

    /** Stands for a config value that is not a JSON integer; it fails the range check. */
    static final int NOT_AN_INTEGER = Integer.MIN_VALUE;

    private AutomationOptions() {
    }

    /**
     * The exit code for "violations found": {@code configured}, or 1 when it is not set. Under
     * serve, mcp and run (which run commands with an active {@link PathPolicy}) it is always 1:
     * they turn exit codes into HTTP status, {@code isError} and step status themselves, and
     * report {@code hasViolations} in their JSON anyway.
     */
    static int violationsExitCode(Integer configured) {
        if (configured == null || PathPolicy.active().isPresent()) {
            return ExitCode.VIOLATIONS;
        }
        return configured;
    }

    /** The config key's value; anything but a JSON integer (true, 0.5, "0", a long) is invalid. */
    static int exitCodeFromConfig(JsonNode value) {
        return value.isInt() ? value.asInt() : NOT_AN_INTEGER;
    }

    /** True when {@code code} is unset or a valid exit code; otherwise prints why on stderr. */
    static boolean checkViolationsExitCode(Integer code) {
        if (code == null || (code >= 0 && code <= MAX_EXIT_CODE)) {
            return true;
        }
        System.err.println("[ERROR] --violations-exit-code (config key violationsExitCode) must be an integer between 0 and "
                + MAX_EXIT_CODE + (code == NOT_AN_INTEGER ? "." : ": " + code));
        return false;
    }

    /**
     * Writes the JSON result to {@code summaryFile} atomically, creating parent folders. Returns
     * false, after saying why on stderr, when the file can't be written.
     */
    static boolean writeSummary(File summaryFile, String json) {
        try {
            AtomicFiles.writeString(summaryFile.toPath(), json + System.lineSeparator());
            return true;
        } catch (IOException e) {
            System.err.println("[ERROR] Could not write the summary file "
                    + LogSanitizer.forLog(summaryFile.getAbsolutePath()) + ": " + LogSanitizer.forLog(e.getMessage()));
            return false;
        }
    }
}
