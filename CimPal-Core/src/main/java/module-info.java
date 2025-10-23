module CimPal.Core {
    exports eu.griddigit.cimpal.core.interfaces;
    exports eu.griddigit.cimpal.core.comparators;
    exports eu.griddigit.cimpal.core.models;
    exports eu.griddigit.cimpal.core.utils;
    exports eu.griddigit.cimpal.core.converters;

    requires org.apache.jena.core;
    requires org.apache.jena.arq;
    requires CimPal.CustomWriter;
    requires org.apache.jena.shacl;
    requires org.apache.commons.io;
    requires shacl;
}