/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.presets;

import eu.griddigit.cimpal.core.matching.model.MatchingConfig;

/**
 * Named presets for {@link MatchingConfig}, mirroring the RDFConvertOptionsPresets
 * pattern. {@link #defaults()} returns the built-in configuration used when no
 * external JSON config file is supplied. Tolerances start deliberately wide
 * (per the design) and are meant to be retuned from Phase 0 reconnaissance on
 * the real data.
 */
public final class MatchingConfigPresets {

    public static final String CIM16_NAMESPACE = "http://iec.ch/TC57/2013/CIM-schema-cim16#";
    /** xmlBase used when unioning CGMES files for SPARQL, matching the SPARQL tab. */
    public static final String DEFAULT_XML_BASE = "http://iec.ch/TC57/2013/CIM-schema-cim16";

    private MatchingConfigPresets() {
    }

    /** Built-in default configuration (wide tolerances, name as second name). */
    public static MatchingConfig defaults() {
        return MatchingConfig.builder().build();
    }
}
