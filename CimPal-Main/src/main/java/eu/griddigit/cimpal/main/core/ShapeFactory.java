
/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2023, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package eu.griddigit.cimpal.main.core;

import org.apache.commons.io.FilenameUtils;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Triple;
import org.apache.jena.graph.compose.MultiUnion;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.util.FileUtils;
import org.apache.jena.vocabulary.OWL;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.HashSet;
import java.util.Set;

public class ShapeFactory {

    /**
     * Determine the {@link Lang language} of the shape file based on the file extension
     * @param fileName shape file name
     * @return {@link Lang}
     */
    private static Lang getShapeFileLanguage(String fileName)
    {
        String fileExtension = FilenameUtils.getExtension(fileName);
        Lang shapeLanguage;

        if("ttl".equalsIgnoreCase(fileExtension))
        {
            shapeLanguage = Lang.TURTLE;
        }
        else if("rdf".equalsIgnoreCase(fileExtension))
        {
            shapeLanguage = Lang.RDFXML;
        }
        else if("xml".equalsIgnoreCase(fileExtension))
        {
            shapeLanguage = Lang.RDFXML;
        }
        else
        {
            throw new UnsupportedOperationException(String.format("file extension %s is not supported for shape files", fileExtension));
        }

        return shapeLanguage;
    }

    /**
     * used for the including of owl import i.e. the collecting of models that are listed in owl import
     * @param model graph
     * @param uri URI of graph
     * @param graphs created graphs
     * @param reachedURIs URIs that has been reached
     */
    private static void addIncludes(Graph model, String uri, Set<Graph> graphs, Set<String> reachedURIs) throws FileNotFoundException {
        graphs.add(model);
        reachedURIs.add(uri);

        for(Triple t : model.find(null, OWL.imports.asNode(), null).toList()) {
            if(t.getObject().isURI())
            {
                String includeURI = t.getObject().getURI();

                if(!reachedURIs.contains(includeURI)) {
                    Model includeModel = ModelFactory.createDefaultModel();

                    /*if (includeURI.split("file:",2).length==2)
                    {
                        File file = new File(includeURI.split("file:", 2)[1]);
                        Lang shapeLanguage = getShapeFileLanguage(file.getName());

                        RDFDataMgr.read(includeModel, new FileInputStream(file), shapeLanguage);
                   }*/
                    if (FileUtils.isFile(includeURI)){
                        File file = new File(includeURI);
                        Lang shapeLanguage = getShapeFileLanguage(file.getName());

                        RDFDataMgr.read(includeModel, new FileInputStream(file), shapeLanguage);
                    }

                    Graph includeGraph = includeModel.getGraph();
                    addIncludes(includeGraph, includeURI, graphs, reachedURIs);
                }
            }
        }
    }

    public static Model createShapeModelWithOwlImport(Model model) throws FileNotFoundException {
        Set<Graph> graphs = new HashSet<>();
        Graph baseGraph = model.getGraph();

        addIncludes(baseGraph, baseGraph.toString(), graphs, new HashSet<>());

        if(graphs.size() == 1) {
            return model;
        }
        else {
            MultiUnion union = new MultiUnion(graphs.iterator());
            union.setBaseGraph(baseGraph);
            return ModelFactory.createModelForGraph(union);
        }
    }

}
