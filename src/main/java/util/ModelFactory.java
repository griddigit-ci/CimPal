/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */

package util;


import application.MainController;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.topbraid.jenax.util.JenaUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ModelFactory {

    //Loads one or many models
    public static Model modelLoad(List files, String xmlBase, Lang rdfSourceFormat) throws FileNotFoundException {
        Model modelUnion = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        Map prefixMap = modelUnion.getNsPrefixMap();
        for (int i=0; i<files.size();i++) {
            Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
            InputStream inputStream = new FileInputStream(files.get(i).toString());
            RDFDataMgr.read(model, inputStream, xmlBase, rdfSourceFormat);
            prefixMap.putAll(model.getNsPrefixMap());
            modelUnion.add(model);
        }
        modelUnion.setNsPrefixes(prefixMap);
        return modelUnion;
    }

    //Loads shape model data
    public static void shapeModelLoad(int m, List file)  {

        if (MainController.shapeModels == null) {
            MainController.shapeModels = new ArrayList<>();
            MainController.shapeModelsNames = new ArrayList<>(); // this is a collection of the name of the profile packages
        }
        Model model = JenaUtil.createDefaultModel();
        try {
            if (file.get(m).toString().endsWith(".ttl")) {
                RDFDataMgr.read(model, new FileInputStream(file.get(m).toString()), Lang.TURTLE);
            }else if (file.get(m).toString().endsWith(".rdf")){
                RDFDataMgr.read(model, new FileInputStream(file.get(m).toString()), Lang.RDFXML);
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        MainController.shapeModels.add(model);
    }


}
