module CimPal.CustomWriter {
    requires org.apache.jena.arq;
    requires org.apache.jena.core;
    requires org.apache.jena.iri;
    requires org.slf4j;

    exports eu.griddigit.cimpal.writer.formats;
    exports eu.griddigit.cimpal.writer.jena;
}