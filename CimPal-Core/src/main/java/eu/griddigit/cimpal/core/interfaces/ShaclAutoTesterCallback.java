/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.interfaces;

public interface ShaclAutoTesterCallback {
    void updateProgress(double progress);
    void appendOutput(String message);
}
