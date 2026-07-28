/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.kgcl;

import eu.griddigit.cimpal.core.models.KgclOptions;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.RDFWriter;
import org.apache.jena.riot.RIOT;
import org.apache.jena.vocabulary.RDF;

import java.io.OutputStream;
import java.util.List;

/**
 * Serializes a list of {@link KgclChange} as the KGCL RDF vocabulary
 * (<a href="https://w3id.org/kgcl/">w3id.org/kgcl</a>), written as Turtle or RDF/XML via Jena RIOT,
 * reusing the writer configuration used elsewhere in the {@code converters} package.
 * <p>
 * Each change becomes a blank node typed with its KGCL class and described with the
 * {@code about_node}, {@code old_value} and {@code new_value} slots. Values are emitted as string
 * literals so the output always parses regardless of whether the compared items carry full IRIs.
 */
public class KgclRdfWriter {

    private static final String KGCL_NS = "https://w3id.org/kgcl/";

    private static final Property ABOUT_NODE = ResourceFactory.createProperty(KGCL_NS, "about_node");
    private static final Property OLD_VALUE = ResourceFactory.createProperty(KGCL_NS, "old_value");
    private static final Property NEW_VALUE = ResourceFactory.createProperty(KGCL_NS, "new_value");

    /** Builds a Jena model of the changeset in the KGCL vocabulary. */
    public Model toModel(List<KgclChange> changes) {
        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefix("kgcl", KGCL_NS);
        model.setNsPrefix("rdf", RDF.uri);

        for (KgclChange change : changes) {
            Resource changeRes = model.createResource();
            changeRes.addProperty(RDF.type, ResourceFactory.createResource(KGCL_NS + kgclClass(change.getType())));
            if (change.getAboutNode() != null) {
                changeRes.addProperty(ABOUT_NODE, change.getAboutNode());
            }
            if (change.getOldValue() != null) {
                changeRes.addProperty(OLD_VALUE, change.getOldValue());
            }
            if (change.getNewValue() != null) {
                changeRes.addProperty(NEW_VALUE, change.getNewValue());
            }
        }
        return model;
    }

    /** Writes the KGCL RDF model to the stream in the requested format. The stream is not closed. */
    public void write(OutputStream outputStream, List<KgclChange> changes, KgclOptions.OutputFormat format) {
        Model model = toModel(changes);
        switch (format) {
            case RDFXML -> RDFWriter.create()
                    .format(RDFFormat.RDFXML_PRETTY)
                    .source(model)
                    .output(outputStream);
            case TURTLE -> RDFWriter.create()
                    .set(RIOT.symTurtleOmitBase, false)
                    .set(RIOT.symTurtleIndentStyle, "wide")
                    .set(RIOT.symTurtleMultilineLiterals, true)
                    .lang(Lang.TURTLE)
                    .source(model)
                    .output(outputStream);
            case null, default -> throw new IllegalStateException("Unsupported KGCL RDF format: " + format);
        }
    }

    /** Maps a change type to its KGCL class local name. */
    private static String kgclClass(KgclChangeType type) {
        return switch (type) {
            case NODE_CREATION -> "NodeCreation";
            case NODE_DELETION -> "NodeDeletion";
            case NODE_RENAME -> "NodeRename";
            case NODE_DEFINITION_CHANGE -> "NodeTextDefinitionChange";
            case NODE_MOVE -> "NodeMove";
            case NODE_ANNOTATION_CHANGE -> "NodeAnnotationChange";
        };
    }
}
