/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package eu.griddigit.cimpal.core;

import org.apache.jena.rdf.model.Model;
import eu.griddigit.cimpal.util.CompareFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

public class ComparisonSHACLshapes {
    public static Map<String,String> modelsABPrefMap;

    public static ArrayList<Object> compareSHACLshapes(Model modelA, Model modelB){

        ArrayList<Object> compareResults = new ArrayList<>();
        LinkedList<String> skiplist = new LinkedList<>();
        Map<String,String> modAprefMap = modelA.getNsPrefixMap();
        Map<String,String> modBprefMap = modelB.getNsPrefixMap();
        modelsABPrefMap = new HashMap<>(modAprefMap);
        modelsABPrefMap.putAll(modBprefMap);

        //first run - this compared model A with model B. Identified common parts and reports differences. New
        //classes in Model A that are not in Model B are reported
        compareResults = CompareFactory.compareModels(compareResults, modelA, modelB, 0,skiplist);
        //second run - reverse run. Model B is compared to Model A. Only if there are new parts (classes, attributes, associations) in Model B that are not in Model A
        //are reported
        compareResults = CompareFactory.compareModels(compareResults, modelB, modelA, 1,skiplist);

        return compareResults;
    }
}
