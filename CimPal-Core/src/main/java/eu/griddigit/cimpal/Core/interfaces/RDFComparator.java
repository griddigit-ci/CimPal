package eu.griddigit.cimpal.Core.interfaces;

import eu.griddigit.cimpal.Core.models.RDFCompareResult;
import org.apache.jena.rdf.model.Model;

public interface RDFComparator {
    RDFCompareResult compare(Model modelA, Model modelB);
}
