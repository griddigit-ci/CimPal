package eu.griddigit.cimpal.core.interfaces;

import eu.griddigit.cimpal.core.models.RDFCompareResult;
import org.apache.jena.rdf.model.Model;

public interface IRDFComparator {
    RDFCompareResult compare(Model modelA, Model modelB);
}
