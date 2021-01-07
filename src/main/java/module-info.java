/**
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
module CimPal {
    exports application;
    opens application;
    opens preload;
    opens gui;
    opens core;


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
    requires poi;
    requires poi.ooxml;
    requires poi.ooxml.schemas;
    requires java.prefs;
    requires com.fasterxml.jackson.annotation;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
}