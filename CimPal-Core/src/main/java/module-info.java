module CimPal.Core {
    exports eu.griddigit.cimpal.Core.interfaces;
    exports eu.griddigit.cimpal.Core.comparators;
    exports eu.griddigit.cimpal.Core.models;
    exports eu.griddigit.cimpal.Core.utils;
    exports eu.griddigit.cimpal.Core.converters;

    requires org.apache.jena.core;
    requires org.apache.jena.arq;
    requires CimPal.CustomWriter;
}