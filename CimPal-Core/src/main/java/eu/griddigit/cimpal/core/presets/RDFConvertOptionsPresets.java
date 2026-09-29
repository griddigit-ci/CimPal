/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.presets;

import eu.griddigit.cimpal.core.models.RDFConvertOptions;

import java.io.File;

/**
 * Predefined presets for common RDF conversion scenarios.
 * Each preset returns a Builder that can be further customized before calling build().
 */
public class RDFConvertOptionsPresets {
    /**
     * Basic RDFXML to Turtle conversion with minimal settings
     */
    public static RDFConvertOptions.Builder rdfXmlToTurtle(File sourceFile, String xmlBase) {
        return RDFConvertOptions.builder()
                .sourceFile(sourceFile)
                .sourceFormat(RDFConvertOptions.RDFFormats.RDFXML)
                .targetFormat(RDFConvertOptions.RDFFormats.TURTLE)
                .xmlBase(xmlBase);
    }
}
