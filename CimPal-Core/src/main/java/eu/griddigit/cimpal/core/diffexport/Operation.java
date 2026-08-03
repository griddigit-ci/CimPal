/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.diffexport;

/**
 * The operation an RDF comparison difference represents when Model A is treated as the older/base
 * version and Model B as the newer version.
 * <ul>
 *     <li>{@link #ADD} — present only in Model B (added going A → B)</li>
 *     <li>{@link #REMOVE} — present only in Model A (removed going A → B)</li>
 *     <li>{@link #UPDATE} — present in both models but the value changed</li>
 * </ul>
 */
public enum Operation {
    ADD("add"),
    REMOVE("remove"),
    UPDATE("update");

    private final String label;

    Operation(String label) {
        this.label = label;
    }

    /** The lowercase label written to the Excel "Operation" column and the CSV. */
    public String label() {
        return label;
    }
}
