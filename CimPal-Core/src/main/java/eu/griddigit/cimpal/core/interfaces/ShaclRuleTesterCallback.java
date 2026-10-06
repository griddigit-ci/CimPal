/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.interfaces;

/**
 * Progress and messages from a {@link eu.griddigit.cimpal.core.utils.ShaclRuleTester} run. Both
 * are called from worker threads.
 */
public interface ShaclRuleTesterCallback {

    /** Fraction of the run done, from 0 to 1. */
    void updateProgress(double progress);

    /** A line for the user, ending in a line break. */
    void appendOutput(String message);
}
