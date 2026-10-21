/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.idmapping;

/**
 * One mapped object: a row of the report's {@code Matched} sheet.
 *
 * @param model1Id          the object's ID in model 1
 * @param elementType   its CIM class, e.g. {@code ACLineSegment}
 * @param model2Id         the ID of the same object in model 2
 * @param model2SecondName the model 2 object's name (or short name or description, per the options); for
 *                      a line section, its Line's name with the section's own name in brackets
 * @param confidence    {@link #CERTAIN} when the evidence leaves no other reading, {@link #PROBABLE}
 *                      when a person should check it (see the Note)
 * @param model1Name        the object's name in model 1
 * @param model2Type       the model 2 object's class when it differs from {@code elementType}, else null
 * @param method        how the match was found (same-id, name, line-structure, topology, ...)
 * @param note          what a reviewer should know: differing values, weaker evidence; null if none
 */
public record MatchedRow(String model1Id, String elementType, String model2Id, String model2SecondName, String confidence,
                         String model1Name, String model2Type, String method, String note) {

    public static final String CERTAIN = "certain";
    public static final String PROBABLE = "probable";
}
