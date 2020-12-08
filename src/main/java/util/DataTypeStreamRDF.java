/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @authors Chavdar Ivanov, Merlin Bögershausen merlin.boegershausen@rwth-aachen.de (under MIT license)
 */
package util;

import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.graph.GraphFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * This implementation of an StreamRDF reads triple and uses a map to assign the datatype (an XSDDatatype) to them.
 * The result will be a graph.
 * The Stream should be used with the RDFDataMgr.
 */

//TODO - forMalte - this is needed for the datatypes mapping
public class DataTypeStreamRDF implements StreamRDF {
    private final Map<Node, Graph> sink = new HashMap<>();
    private final Graph graph;
    private final Map<String, RDFDatatype> dataTypeMap;
    private final Map<String, String> prefixMapping = new HashMap<>();
    private String base = new String();


    public DataTypeStreamRDF(Graph graph, Map<String, RDFDatatype> dataTypeMap) {
        this.graph=graph;
        this.dataTypeMap=dataTypeMap;
        }

    public Graph getGraph() {
        return graph;
    }
    public Map getPrefixMapping() {
        return this.prefixMapping;
    }

    public void start() {
        //Adds the empty default graph to the sink
        sink.putIfAbsent(Quad.defaultGraphNodeGenerated, this.graph);
    }

    public void triple(Triple triple) {
        //if the triple is found it is put to the defaut graph
        Triple dataTypeTriple = dataTypeTriple(triple);
        addToGraphs(Quad.defaultGraphNodeGenerated, dataTypeTriple);
    }

    private Triple dataTypeTriple(Triple triple) {
        //If the triple contains a literal, this is extracted and datype applied, otherwise the original triple is returned
        if (triple.getMatchObject().isLiteral()) {// only apply typing of object is literal
            /* determine datatype, use xsd:string as default */
            RDFDatatype datatype = dataTypeMap.getOrDefault(triple.getPredicate().toString(), XSDDatatype.XSDstring);
            /* generate typed literal */
            String literalValue = triple.getObject().getLiteralLexicalForm();
            Literal typedLiteral = ResourceFactory.createTypedLiteral(literalValue, datatype);
            /* generate new triple */
            triple = new Triple(triple.getSubject(), triple.getPredicate(), typedLiteral.asNode());
        }
        return triple;
    }

    /**
     * Add the triple to the referred graph. If the graph is not present, at it.
     *
     * @param graphRef graph to at the triple to
     * @param triple   triple to add
     */
    private void addToGraphs(Node graphRef, Triple triple) {
        // Add the triple to the referred graph. If the graph is not present, at it.
        /* get graph from sink */
        Graph graph = sink.getOrDefault(graphRef, GraphFactory.createDefaultGraph());
        /* add triple */
        graph.add(triple);
        /* add changed graph to sink */
        sink.put(graphRef, graph);
    }

    public void quad(Quad quad) {
        //Reader found a triple. Add type and add it to the respective graph
        Triple typedTriple = dataTypeTriple(quad.asTriple());
        Node graphRef = quad.getGraph();
        addToGraphs(graphRef, typedTriple);
    }

    public void base(String base) {
        this.base = base;
    }

    public void prefix(String prefix, String iri) {
        this.prefixMapping.put(prefix, iri);
    }

    public void finish() {
    }
}
