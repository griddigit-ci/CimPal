/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.writer.formats;

import org.apache.jena.rdf.model.RDFWriterI;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.adapters.AdapterRDFWriter;

/**
 * Custom wrapper to RDF/XML writers (preRIOT).
 */
public class RDFXMLCustomWriter extends AdapterRDFWriter {
    private final boolean isPretty;

    public RDFXMLCustomWriter() {
        this(false);
    }

    public RDFXMLCustomWriter(boolean isPretty) {
        this.isPretty = isPretty;
    }

    @Override
    protected RDFWriterI create() {
        if (isPretty) {
            return new CustomBasicPretty();
        } else {
            return new CustomBasic();
        }

    }

    @Override
    public Lang getLang() {
        return Lang.RDFXML;
    }
}
