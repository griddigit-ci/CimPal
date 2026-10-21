/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.idmapping;

/**
 * One object left for a person to map: a row of the report's {@code Unmatched} sheet.
 *
 * @param side        the model the object is in
 * @param id          its ID
 * @param elementType its CIM class
 * @param name        its name
 * @param substation  the name of the substation it is in, if known
 * @param reason      why it was not matched
 * @param candidates  the objects of the other model it could be, as {@code ID (name)}; empty if none
 */
public record UnmatchedRow(Side side, String id, String elementType, String name, String substation,
                           String reason, String candidates) {
}
