/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.cimpal.core.utils.PythonExecutable;
import picocli.CommandLine.Option;

/**
 * {@code --python-executable}, shared by {@code validate}, {@code serve} and {@code mcp} (SEC-3,
 * gap G9). It is a start-up option only: no config key, request field or tool argument sets the
 * interpreter, so a caller of {@code serve} or {@code mcp} can't choose a program to run.
 */
final class PythonOptions {

    @Option(names = "--python-executable", paramLabel = "PATH",
            description = "Absolute path of the Python interpreter for the PYSHACL, PYSHACL_OXIGRAPH and "
                    + "RUST_SHACL engines. Default: the CIMPAL_PYTHON_EXECUTABLE environment variable, else "
                    + "python or py -3 on PATH.")
    String pythonExecutable;

    /**
     * Applies {@code --python-executable}; with {@code checkConfigured}, an interpreter set by
     * {@code -Dcimpal.python.executable} or {@code CIMPAL_PYTHON_EXECUTABLE} is checked too.
     *
     * @return false, after an error line on stderr, when the interpreter isn't an absolute path of
     *         an existing file
     */
    boolean apply(boolean checkConfigured) {
        try {
            if (pythonExecutable != null) {
                PythonExecutable.use(pythonExecutable, "--python-executable");
            } else if (checkConfigured) {
                PythonExecutable.checkConfigured();
            }
            return true;
        } catch (IllegalArgumentException e) {
            System.err.println("[ERROR] " + e.getMessage());
            return false;
        }
    }
}
