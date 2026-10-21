/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.idmapping;

/** The two models of an ID mapping: model 1, whose IDs are mapped, and model 2, the one they map to. */
public enum Side {
    MODEL1("Model 1"),
    MODEL2("Model 2");

    private final String label;

    Side(String label) {
        this.label = label;
    }

    /** The label used in reports: "Model 1" or "Model 2". */
    public String label() {
        return label;
    }
}
