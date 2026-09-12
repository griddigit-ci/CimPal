/**
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
module CimPal.Main {
    exports eu.griddigit.cimpal.main.application;
    exports eu.griddigit.cimpal.main.model;
    exports eu.griddigit.cimpal.main.interfaces;
    exports eu.griddigit.cimpal.main.application.controllers.taskWizardControllers;
    opens eu.griddigit.cimpal.main.application.controllers;
    opens eu.griddigit.cimpal.main.application.controllers.ai to javafx.fxml;
    exports eu.griddigit.cimpal.main.application.PssePFcompare;
    exports eu.griddigit.cimpal.main.application.tasks;
    opens eu.griddigit.cimpal.main.application.tasks;
    opens eu.griddigit.cimpal.main.application.controllers.taskWizardControllers;
    opens eu.griddigit.cimpal.main.application.controllers.sparql;
    opens eu.griddigit.cimpal.main.application;
    opens eu.griddigit.cimpal.main.preload;
    opens eu.griddigit.cimpal.main.gui;
    opens eu.griddigit.cimpal.main.core;


    requires javafx.base;
    requires javafx.fxml;
    requires javafx.graphics;
    requires javafx.controls;
    requires javafx.web;
    // java.awt (Desktop, Taskbar) and javax.swing (JFileChooser) are used directly. This used to
    // be readable only by accident, via javafx.swing; nothing uses javafx.embed.swing itself, so
    // that dependency is gone and the real one is declared here.
    requires java.desktop;
    requires java.xml;
    requires java.net.http;
    requires org.apache.jena.core;
    requires org.apache.jena.arq;
    requires org.slf4j;
    // A real binding, not slf4j-nop: discarding all library output also discards the
    // security-relevant warnings emitted by the RDF parsers and the HTTP client.
    requires org.slf4j.simple;
    requires org.apache.commons.compress;
    requires shacl;
    requires org.apache.jena.base;
    requires org.apache.commons.io;
    requires commons.math3;
    requires org.apache.commons.lang3;
    requires org.apache.poi.poi;
    requires org.apache.poi.ooxml;
    requires org.apache.poi.ooxml.schemas;
    requires java.prefs;
    requires tools.jackson.core;
    requires tools.jackson.databind;
    requires org.apache.jena.iri;
    requires velocity.engine.core;
    requires org.apache.jena.shacl;
    requires CimPal.Core;
    requires CimPal.CustomWriter;
    // PowsyBl's ServiceLoader discovers its compressed-network importer at runtime.  The
    // importer references ZstdInputStream, so a named CimPal module must resolve zstd-jni even
    // though application code does not import it directly.
    requires com.github.luben.zstd_jni;
    // CGMES import obtains this RDF4J implementation through ServiceLoader. It is an automatic
    // module, so named CimPal launches must explicitly bring it into the module graph.
    requires com.powsybl.triplestore.impl.rdf4j;

    //requires smartgraph;
}
