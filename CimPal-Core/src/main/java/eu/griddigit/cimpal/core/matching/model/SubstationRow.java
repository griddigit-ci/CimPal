/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.model;

/**
 * One (substation, voltageLevel) pairing from Map04_Substations. A substation
 * with N voltage levels yields N rows; a substation with no VL yields one row
 * with null vl/nomV. Two rows with the same substation and same nomV signal
 * the duplicate-voltage-level ambiguity case.
 */
public record SubstationRow(
        String substation,
        String substationName,
        String region,
        String regionName,
        String vl,
        String vlName,
        Double nomV,
        String source
) {
}
