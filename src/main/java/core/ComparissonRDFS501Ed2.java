package core;

import org.apache.jena.rdf.model.*;
import util.CompareFactory;

import java.util.*;

public class ComparissonRDFS501Ed2 {

    public static ArrayList<Object> compareRDFS501Ed2(Model modelA, Model modelB){
        ArrayList<Object> compareResults = new ArrayList<>();
        LinkedList<String> skiplist = new LinkedList<>();
        //first run - this compared model A with model B. Identified common parts and reports differences. New
        //classes in Model A that are not in Model B are reported
        compareResults = CompareFactory.compareModels(compareResults, modelA, modelB, 0,skiplist);
        //second run - reverse run. Model B is compared to Model A. Only if there are new parts (classes, attributes, associations) in Model B that are not in Model A
        //are reported
        compareResults = CompareFactory.compareModels(compareResults, modelB, modelA, 1,skiplist);

        return compareResults;

    }


}
