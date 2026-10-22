/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.idmapping;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NamesTest {

    /** Station codes against full names, as one model abbreviates the other's names. */
    @ParameterizedTest
    @CsvSource({
            "AGANYGAL, Agannygal, true",
            "BLYLMFRD, Ballylumford 110, true",
            "ARDNA1, Ardnacrusha, true",
            "AUNGTOWN, Aungierstown, true",
            "CORDUFF, Corduff, true",
            "BRNSBARN, Baldonnell, false",
            "KILBRNSH, Ballywater, false",
            "FINGLAS1, Term_Finglas - Grange 110 kV S3, false",
            "CENTRAL, Belfast Central, false",
            "AB, Abbeyleix, false"
    })
    void stationCodeAbbreviatesTheName(String code, String name, boolean similar) {
        assertEquals(similar, Names.none().similar(code, name));
        assertEquals(similar, Names.none().similar(name, code));
    }
}
