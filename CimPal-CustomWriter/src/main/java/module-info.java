/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
module CimPal.CustomWriter {
    requires org.apache.jena.arq;
    requires org.apache.jena.core;
    requires org.apache.jena.iri;
    requires org.slf4j;

    exports eu.griddigit.cimpal.writer.formats;
    exports eu.griddigit.cimpal.writer.jena;
}