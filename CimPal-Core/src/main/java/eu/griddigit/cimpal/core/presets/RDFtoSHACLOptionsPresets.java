package eu.griddigit.cimpal.core.presets;

import eu.griddigit.cimpal.core.models.RDFtoSHACLOptions;
import org.apache.jena.rdf.model.Model;

import java.util.ArrayList;

public class RDFtoSHACLOptionsPresets {
    public static String DEFAULT_CIMS_NAMESPACE = "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#";
    public static String DEFAULT_IO_PREFIX = "ido";
    public static String DEFAULT_IO_URI = "http://iec.ch/TC57/ns/CIM/IdentifiedObject/constraints/3.0#";

    public static RDFtoSHACLOptions.Builder defaultPreset(ArrayList<Model> rdfsModels, String identifiedObjectPrefix, String identifiedObjectURI, String cimsNamespace) {
        return RDFtoSHACLOptions.builder()
                .excludeMRID(true)
                .closedShapes(true)
                .splitDatatypes(false)
                .rdfsFormatShapes(RDFtoSHACLOptions.RdfsFormatShapes.RDFS_AUGMENTED_2020)
                .associationValueTypeOption(true)
                .associationValueTypeOptionSingle(true)
                .shapesOnAbstractOption(false)
                .exportInheritTree(false)
                .shaclURIDatatypeAsResource(true)
                .shaclSkipNcPropertyReference(true)
                .baseprofilesshaclglag(false)
                .baseprofilesshaclignorens(false)
                .baseprofilesshaclglag2nd(false)
                .baseprofilesshaclglag3rd(false)
                .shaclFlagInverse(true)
               // todo .rdfsModelDefinitions(RDFSmodelsNames)
                .rdfsModels(rdfsModels)
                .shaclOutputFormat(RDFtoSHACLOptions.SerializationFormat.TURTLE)
                .iOprefix(DEFAULT_IO_PREFIX)
                .iOuri(DEFAULT_IO_URI)
                .cimsNamespace(DEFAULT_CIMS_NAMESPACE);
    }
}
