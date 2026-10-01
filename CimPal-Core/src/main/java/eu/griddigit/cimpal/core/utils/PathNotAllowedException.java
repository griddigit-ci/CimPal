/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

/**
 * A path refused by a {@link PathPolicy}. An {@link IllegalArgumentException}, so CLI commands
 * report it as bad input (exit code 2).
 */
public class PathNotAllowedException extends IllegalArgumentException {

    public PathNotAllowedException(String message) {
        super(message);
    }
}
