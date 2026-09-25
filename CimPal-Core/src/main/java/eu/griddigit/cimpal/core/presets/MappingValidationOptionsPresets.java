/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.presets;

import eu.griddigit.cimpal.core.models.MappingValidationOptions;
import eu.griddigit.cimpal.core.utils.DatatypeMapPreset;

import static eu.griddigit.cimpal.core.presets.SHACLValidationOptionsPresets.CIM16_XML_BASE;
import static eu.griddigit.cimpal.core.presets.SHACLValidationOptionsPresets.CIM17_XML_BASE;

/**
 * Predefined presets for mapping-driven validation of CGMES datasets.
 * Each preset returns a Builder that still needs the mapping, input and output paths before calling build().
 */
public class MappingValidationOptionsPresets {

    /** CGMES 3.0 data with the NC 2.5 datatype map: the SHACL Validation tab's defaults. */
    public static MappingValidationOptions.Builder cgmes30() {
        return MappingValidationOptions.builder()
                .datatypeMap(DatatypeMapPreset.CGMES30_NC25)
                .xmlBase(CIM17_XML_BASE);
    }

    /** CGMES 2.4.15 data with the NC 2.2 datatype map. */
    public static MappingValidationOptions.Builder cgmes24() {
        return MappingValidationOptions.builder()
                .datatypeMap(DatatypeMapPreset.CGMES24_NC22)
                .xmlBase(CIM16_XML_BASE);
    }
}
