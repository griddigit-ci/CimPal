/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * The Python interpreter the experimental SHACL engines (pySHACL, the Rust binding) run, when it
 * is configured rather than found on {@code PATH} (SEC-3, gap G9). It is set by the operator at
 * start - the CLI's {@code --python-executable}, the system property {@value #PROPERTY} or the
 * environment variable {@value #ENV} - never by a request, so an MCP or serve caller can't choose
 * a program to run. A configured value must be the absolute path of an existing file.
 */
public final class PythonExecutable {

    /** System property naming the interpreter; it comes before {@link #ENV}. */
    public static final String PROPERTY = "cimpal.python.executable";

    /** Environment variable naming the interpreter. */
    public static final String ENV = "CIMPAL_PYTHON_EXECUTABLE";

    private PythonExecutable() {
    }

    /**
     * Checks {@code value} and makes it the configured interpreter for this JVM (it sets {@link #PROPERTY}).
     *
     * @param origin how the value was given, for the message, e.g. {@code --python-executable}
     * @throws IllegalArgumentException when it isn't the absolute path of an existing file
     */
    public static Path use(String value, String origin) {
        Path path = check(value, origin);
        System.setProperty(PROPERTY, path.toString());
        return path;
    }

    /**
     * Checks the interpreter configured by {@link #PROPERTY} or {@link #ENV}, if any, so a
     * long-running command can refuse a wrong one at start rather than at the first Python run.
     *
     * @throws IllegalArgumentException when a configured value isn't the absolute path of an existing file
     */
    public static void checkConfigured() {
        String property = System.getProperty(PROPERTY);
        if (property != null && !property.isBlank()) {
            check(property, "-D" + PROPERTY);
            return;
        }
        String env = System.getenv(ENV);
        if (env != null && !env.isBlank()) {
            check(env, ENV);
        }
    }

    /**
     * The normalised absolute path {@code value} names; the path that is checked is the path
     * that is returned.
     *
     * @throws IllegalArgumentException when it is a UNC or device path (refused before anything
     *                                  touches it, so no network share is contacted), relative (a
     *                                  bare name would be looked up on {@code PATH}), malformed,
     *                                  or not an existing file
     */
    static Path check(String value, String origin) {
        String shown = LogSanitizer.forLog(value);
        if (PathPolicy.isUncOrDevice(value)) {
            throw new IllegalArgumentException(origin + " must be a local path; UNC (network) and device paths "
                    + "are refused: " + shown);
        }
        Path path;
        try {
            path = Path.of(value.strip()).normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(origin + " is not a valid path: " + shown);
        }
        if (value.isBlank() || !path.isAbsolute()) {
            throw new IllegalArgumentException(origin + " must be the absolute path of the Python interpreter, not '"
                    + shown + "' (a bare name would be looked up on PATH).");
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(origin + " names a Python interpreter that does not exist: " + shown);
        }
        return path;
    }
}
