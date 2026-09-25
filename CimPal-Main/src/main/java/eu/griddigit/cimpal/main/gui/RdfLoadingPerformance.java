/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.gui;

import eu.griddigit.cimpal.main.application.MainController;

/** Shared concurrency policy for independent RDF parsing and conversion jobs. */
public final class RdfLoadingPerformance {
    public static final String PREFERENCE_KEY = "rdf.loading.performance";
    public static final String BALANCED = "Balanced";
    public static final String HIGH_PERFORMANCE = "High performance";

    private RdfLoadingPerformance() { }

    public static int workerCount(int jobs) {
        int available = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        String mode = MainController.prefs == null ? BALANCED : MainController.prefs.get(PREFERENCE_KEY, BALANCED);
        int limit = HIGH_PERFORMANCE.equals(mode) ? available : Math.min(4, available);
        return Math.max(1, Math.min(Math.max(1, jobs), limit));
    }
}
