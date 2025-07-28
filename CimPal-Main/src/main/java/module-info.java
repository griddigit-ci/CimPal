/**
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
module CimPal.Main {
    exports eu.griddigit.cimpal.Main.application;
    exports eu.griddigit.cimpal.Main.model;
    exports eu.griddigit.cimpal.Main.interfaces;
    opens eu.griddigit.cimpal.Main.application;
    opens eu.griddigit.cimpal.Main.preload;
    opens eu.griddigit.cimpal.Main.gui;
    opens eu.griddigit.cimpal.Main.core;


    requires javafx.base;
    requires javafx.fxml;
    requires javafx.graphics;
    requires javafx.controls;
    requires javafx.media;
    requires javafx.swing;
    requires javafx.web;
    requires org.apache.jena.core;
    requires org.apache.jena.arq;
    requires org.slf4j;
    requires org.slf4j.nop;
    requires org.apache.commons.compress;
    requires org.apache.commons.codec;
    requires org.apache.jena.tdb2;
    requires shacl;
    requires org.apache.jena.base;
    requires org.apache.jena.cmds;
    requires org.apache.commons.io;
    requires commons.math3;
    requires org.apache.commons.lang3;
    requires org.apache.poi.poi;
    requires org.apache.poi.ooxml;
    requires org.apache.poi.ooxml.schemas;
    requires java.prefs;
    requires com.fasterxml.jackson.annotation;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires org.apache.jena.iri;
    requires net.sourceforge.plantuml;
    requires guru.nidi.graphviz;
    requires com.fasterxml.jackson.dataformat.xml;
    requires velocity.engine.core;
    requires org.apache.jena.shacl;
    requires CimPal.Core;
    requires CimPal.CustomWriter;

    //requires smartgraph;
}