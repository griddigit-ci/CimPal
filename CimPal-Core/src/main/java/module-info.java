module CimPal.Core {
    exports eu.griddigit.cimpal.Core.interfaces;
    exports eu.griddigit.cimpal.Core.comparators;
    exports eu.griddigit.cimpal.Core.models;
    exports eu.griddigit.cimpal.Core.utils;

    requires org.apache.jena.core;
    requires org.apache.jena.arq;
    requires CimPal.CustomWriter;
}