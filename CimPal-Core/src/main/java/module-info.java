/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
module CimPal.Core {
    exports eu.griddigit.cimpal.core.interfaces;
    exports eu.griddigit.cimpal.core.comparators;
    exports eu.griddigit.cimpal.core.models;
    exports eu.griddigit.cimpal.core.presets;
    exports eu.griddigit.cimpal.core.utils;
    exports eu.griddigit.cimpal.core.converters;
    exports eu.griddigit.cimpal.core.shacl_tools;
    exports eu.griddigit.cimpal.core.generators;
    exports eu.griddigit.cimpal.core.kgcl;
    exports eu.griddigit.cimpal.core.diffexport;

    requires org.apache.jena.core;
    requires org.apache.jena.arq;
    requires titanium.json.ld;
    requires CimPal.CustomWriter;
    requires org.apache.jena.shacl;
    requires org.apache.commons.io;
    requires shacl;
    requires org.apache.poi.poi;
    requires org.apache.poi.ooxml;
    requires java.prefs;
    requires java.xml;
    requires java.net.http;
}
