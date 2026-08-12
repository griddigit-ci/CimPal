/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.kgcl;

/**
 * A single KGCL change, produced by {@link KgclConverter} from an RDF comparison result and
 * serialized by {@link KgclCnlWriter} (controlled natural language) or {@link KgclRdfWriter} (RDF).
 * <p>
 * Immutable POJO in the same style as
 * {@link eu.griddigit.cimpal.core.models.RDFCompareResultEntry}: constructor plus getters, no
 * setters. {@code property}/{@code oldValue}/{@code newValue} are nullable depending on the change
 * type (e.g. a {@code NODE_DELETION} carries only {@code aboutNode}).
 */
public class KgclChange {
    private final KgclChangeType type;
    private final String aboutNode;
    private final String property;
    private final String oldValue;
    private final String newValue;

    public KgclChange(KgclChangeType type, String aboutNode, String property, String oldValue, String newValue) {
        this.type = type;
        this.aboutNode = aboutNode;
        this.property = property;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public KgclChangeType getType() {
        return type;
    }

    public String getAboutNode() {
        return aboutNode;
    }

    public String getProperty() {
        return property;
    }

    public String getOldValue() {
        return oldValue;
    }

    public String getNewValue() {
        return newValue;
    }
}
