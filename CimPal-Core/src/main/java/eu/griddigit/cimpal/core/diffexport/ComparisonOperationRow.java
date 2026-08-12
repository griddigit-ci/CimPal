/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.diffexport;

/**
 * An RDF comparison difference expressed as an operation, produced by
 * {@link ComparisonOperationsFactory} and consumed by the on-screen table, the Excel export and
 * {@link ComparisonCsvWriter}.
 * <p>
 * Immutable POJO in the house style of {@link eu.griddigit.cimpal.core.models.RDFCompareResultEntry}.
 * The value column that does not participate in the operation is stored as {@code ""} (blank), never
 * as a {@code -}/{@code N/A} placeholder: an {@link Operation#ADD} has only {@code valueB}, a
 * {@link Operation#REMOVE} has only {@code valueA}, an {@link Operation#UPDATE} has both.
 */
public class ComparisonOperationRow {
    private final String item;
    private final String rdfType;
    private final String property;
    private final String valueA;
    private final String valueB;
    private final Operation operation;

    public ComparisonOperationRow(String item, String rdfType, String property,
                                  String valueA, String valueB, Operation operation) {
        this.item = item;
        this.rdfType = rdfType;
        this.property = property;
        this.valueA = valueA;
        this.valueB = valueB;
        this.operation = operation;
    }

    public String getItem() {
        return item;
    }

    public String getRdfType() {
        return rdfType;
    }

    public String getProperty() {
        return property;
    }

    public String getValueA() {
        return valueA;
    }

    public String getValueB() {
        return valueB;
    }

    public Operation getOperation() {
        return operation;
    }
}
