/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package core;

import javafx.util.Pair;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.util.*;

public class ComparisonRDFSprofile {

    public static ArrayList<Object> compareRDFSprofile(Model modelA, Model modelB){
        ArrayList<Object> compareResults = new ArrayList<>();
        //first run - this compared model A with model B. Identified common parts and reports differences. New
        //classes in Model A that are not in Model B are reported
        compareResults = compareModels(compareResults, modelA, modelB, 0);
        //second run - reverse run. Model B is compared to Model A. Only if there are new parts (classes, attributes, associations) in Model B that are not in Model A
        //are reported
        compareResults = compareModels(compareResults, modelB, modelA, 1);

        return compareResults;

    }


    //compares two models (RDFS)
    private static ArrayList<Object> compareModels(ArrayList<Object> compareResults, Model modelA, Model modelB, int reverse){
        //iterate on the items found in the rdf file
        String cimNSmodelA=modelA.getNsPrefixURI("cim");
        for (ResIterator i = modelA.listSubjects(); i.hasNext(); ) {
            Resource resItem = i.next();

            String[] rdfTypeInit = resItem.getRequiredProperty(RDF.type).getObject().toString().split("#", 2); // the second part of the resource of of the rdf:type
            String rdfType;
            if (rdfTypeInit.length == 0) {
                rdfType = rdfTypeInit[0];
            } else {
                rdfType = rdfTypeInit[1];
            }

            if (rdfType.equals("ClassCategory")) { // if it is a package
                compareResults= comparePackage(compareResults, modelB, rdfType, resItem, cimNSmodelA, reverse);

            } else if (rdfType.equals("Class")) { // if it is a class
                compareResults= compareClass(compareResults, modelA, modelB, rdfType, resItem, cimNSmodelA, reverse);
            }
        }
        return compareResults;
    }

    //checks if a subject os a given type is in another model
    private static Pair<Boolean, Map> contains(Model model, String rdftype, Resource value, String cimNSmodelValue){
        Pair<Boolean,Map> result = new Pair<>(false, null);
        Map<String,String> map = new HashMap<>();

        String cimNSmodel=model.getNsPrefixURI("cim");
        //iterate on the items found in the rdf file
        for (ResIterator i = model.listSubjects(); i.hasNext(); ) {
            Resource resItem = i.next();

            String[] rdfTypeInit = resItem.getRequiredProperty(RDF.type).getObject().toString().split("#", 2); // the second part of the resource of of the rdf:type
            String rdfType;
            if (rdfTypeInit.length == 0) {
                rdfType = rdfTypeInit[0];
            } else {
                rdfType = rdfTypeInit[1];
            }

            if (resItem.getLocalName().equals(value.getLocalName())) {
                String resItemNS=resItem.getNameSpace();
                String valueNS=value.getNameSpace();

                //if resItemNS==cimNSmodel and valueNS==cimNSmodelValue => all is ok ignore
                if (!resItemNS.equals(cimNSmodel) && valueNS.equals(cimNSmodelValue)) {
                    //if resItemNS!=cimNSmodel and valueNS==cimNSmodelValue => report the change
                    map.putIfAbsent("namespace",resItemNS);
                }else if (resItemNS.equals(cimNSmodel) && !valueNS.equals(cimNSmodelValue)) {
                    //if resItemNS==cimNSmodel and valueNS!=cimNSmodelValue => report the change
                    map.putIfAbsent("namespace",resItemNS);
                }else if (!resItemNS.equals(cimNSmodel) && !valueNS.equals(cimNSmodelValue) && !resItemNS.equals(valueNS)) {
                    //if resItemNS!=cimNSmodel and valueNS!=cimNSmodelValue and resItemNS!=valueNS => report the change
                    map.putIfAbsent("namespace",resItemNS);
                }

                if (rdfType.equals(rdftype)) {
                    if (rdfType.equals("ClassCategory")) {
                        String rdfsLabel = resItem.getRequiredProperty(RDFS.label).getObject().toString();
                        map.putIfAbsent("label",rdfsLabel);
                        if (resItem.hasProperty(RDFS.comment)) {
                            String rdfsComment = resItem.getRequiredProperty(RDFS.comment).getObject().toString().split("\\^\\^", 0)[0];
                            map.putIfAbsent("comment", rdfsComment);
                        }

                    }else if (rdfType.equals("Class")){
                        String rdfsLabel = resItem.getRequiredProperty(RDFS.label).getObject().toString();
                        map.putIfAbsent("label",rdfsLabel);
                        if (resItem.hasProperty(RDFS.comment)) {
                            String rdfsComment = resItem.getRequiredProperty(RDFS.comment).getObject().toString().split("\\^\\^", 0)[0];
                            map.putIfAbsent("comment", rdfsComment);
                        }
                        if (resItem.hasProperty(RDFS.subClassOf)) {
                            String subClassOf = resItem.getRequiredProperty(RDFS.subClassOf).getObject().toString().split("#", 2)[1];
                            map.putIfAbsent("subClassOf", subClassOf);
                        }
                        List<String> ClassStereotypes = new LinkedList<>();
                        String enumeration = "false";
                        String concreteClass = "false";
                        for (NodeIterator j = model.listObjectsOfProperty(resItem, model.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "stereotype")); j.hasNext(); ) {
                            RDFNode resItemNode = j.next();
                            if (resItemNode.isResource()){
                                if (resItemNode.toString().equals("http://iec.ch/TC57/NonStandard/UML#enumeration")) {
                                    enumeration = "true";
                                }else if (resItemNode.toString().equals("http://iec.ch/TC57/NonStandard/UML#concrete")) {
                                    concreteClass="true";
                                }
                            }else if (resItemNode.isLiteral()){
                                ClassStereotypes.add(resItemNode.toString());
                            }
                        }
                        map.putIfAbsent("enumeration",enumeration);
                        map.putIfAbsent("concrete",concreteClass);
                        Collections.sort(ClassStereotypes);
                        String stereotypes = String.join(",", ClassStereotypes);
                        map.putIfAbsent("stereotype",stereotypes);
                        String ClassBelongsToCategory = "";
                        for (NodeIterator j = model.listObjectsOfProperty(resItem, model.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "belongsToCategory")); j.hasNext(); ) {
                            RDFNode resItemNode = j.next();
                            ClassBelongsToCategory = resItemNode.toString().split("#Package_", 2)[1];
                        }
                        map.putIfAbsent("belongsToCategory",ClassBelongsToCategory);

                    }
                    result= new Pair<>(true,map);
                }
            }
        }
        return result;
    }

    //checks if a property os a given type is in another model
    private static Pair<Boolean,Map> containsProperty(Model model, String attribute, Resource value, String cimNSmodelValue){
        Pair<Boolean,Map> result = new Pair<>(false, null);
        Map<String,String> map = new HashMap<>();


        String cimNSmodel=model.getNsPrefixURI("cim");
        //iterate on the items found in the rdf file
        for (ResIterator i = model.listResourcesWithProperty(RDF.type, RDF.Property); i.hasNext(); ) {
            Resource resItem = i.next();

            if (resItem.getLocalName().equals(value.getLocalName())) {
                String resItemNS=resItem.getNameSpace();
                String valueNS=value.getNameSpace();

                //if resItemNS==cimNSmodel and valueNS==cimNSmodelValue => all is ok ignore
                if (!resItemNS.equals(cimNSmodel) && valueNS.equals(cimNSmodelValue)) {
                    //if resItemNS!=cimNSmodel and valueNS==cimNSmodelValue => report the change
                    map.putIfAbsent("namespace",resItemNS);
                }else if (resItemNS.equals(cimNSmodel) && !valueNS.equals(cimNSmodelValue)) {
                    //if resItemNS==cimNSmodel and valueNS!=cimNSmodelValue => report the change
                    map.putIfAbsent("namespace",resItemNS);
                }else if (!resItemNS.equals(cimNSmodel) && !valueNS.equals(cimNSmodelValue) && !resItemNS.equals(valueNS)) {
                    //if resItemNS!=cimNSmodel and valueNS!=cimNSmodelValue and resItemNS!=valueNS => report the change
                    map.putIfAbsent("namespace",resItemNS);
                }

                String rdfsLabel = resItem.getRequiredProperty(RDFS.label).getObject().toString();
                map.putIfAbsent("label", rdfsLabel);
                if (resItem.hasProperty(RDFS.comment)) {
                    String rdfsComment = resItem.getRequiredProperty(RDFS.comment).getObject().toString().split("\\^\\^", 0)[0];
                    map.putIfAbsent("comment", rdfsComment);
                }
                String rdfsDomain = resItem.getRequiredProperty(RDFS.domain).getObject().toString().split("#", 2)[1];
                map.putIfAbsent("domain", rdfsDomain);
                List<String> AttrAssocStereotypes = new LinkedList<>();
                for (NodeIterator j = model.listObjectsOfProperty(resItem, model.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "stereotype")); j.hasNext(); ) {
                    RDFNode resItemNode = j.next();
                    if (resItemNode.isLiteral()) {
                        AttrAssocStereotypes.add(resItemNode.toString());
                    }
                }
                Collections.sort(AttrAssocStereotypes);
                String stereotypes = String.join(",", AttrAssocStereotypes);
                map.putIfAbsent("stereotype", stereotypes);
                for (NodeIterator j = model.listObjectsOfProperty(resItem, model.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "multiplicity")); j.hasNext(); ) {
                    RDFNode resItemNode = j.next();
                    map.putIfAbsent("multiplicity", resItemNode.toString().split("#M:", 2)[1]);
                }

                if (resItem.hasProperty(RDFS.range)) {// range is used if the type of the attribute is enumeration
                    String rdfsRange = resItem.getRequiredProperty(RDFS.range).getObject().toString().split("#", 2)[1];
                    map.putIfAbsent("range", rdfsRange);
                }

                if (attribute.equals("true")) {// it is an attribute
                    if (resItem.hasProperty(model.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "dataType"))){
                        //the datatype is used for primitive, datatype and compound
                        String dataType = resItem.getRequiredProperty(model.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "dataType")).getObject().toString().split("#", 2)[1];
                        map.putIfAbsent("dataType", dataType);
                    }
                    if (resItem.hasProperty(model.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "isFixed"))){
                        String isFixed = resItem.getRequiredProperty(model.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "isFixed")).getObject().toString();
                        map.putIfAbsent("isFixed", isFixed);
                    }

                }else { //it is an association
                    if (resItem.hasProperty(model.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "inverseRoleName"))){
                        String inverseRoleName = resItem.getRequiredProperty(model.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "inverseRoleName")).getObject().toString().split("#", 2)[1];
                        map.putIfAbsent("inverseRoleName", inverseRoleName);
                    }
                    if (resItem.hasProperty(model.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "AssociationUsed"))){
                        String AssociationUsed = resItem.getRequiredProperty(model.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "AssociationUsed")).getObject().toString();
                        map.putIfAbsent("AssociationUsed", AssociationUsed);
                    }
                }
                result= new Pair<>(true,map);
            }
        }
        return result;
    }
    //adds a line to the compareResults
    private static ArrayList<Object> addResult(ArrayList<Object> compareResults, String item, String property, String valueModelA, String valueModelB) {
        //item; property; value in model A; value in model B
        List<String> diffItem = new LinkedList<>();

        diffItem.add(item);
        diffItem.add(property);
        diffItem.add(valueModelA);
        diffItem.add(valueModelB);
        compareResults.add(diffItem);

        return compareResults;
    }
    //compare Package
    private static ArrayList<Object> comparePackage(ArrayList<Object> compareResults, Model modelB, String rdfType, Resource resItem, String cimNSmodelA, int reverse){
        Pair<Boolean,Map> result = contains(modelB, rdfType, resItem,cimNSmodelA);
        String rdfsLabel = resItem.getRequiredProperty(RDFS.label).getObject().toString();
        String rdfsComment="";
        if (resItem.hasProperty(RDFS.comment)) {
            rdfsComment = resItem.getRequiredProperty(RDFS.comment).getObject().toString().split("\\^\\^", 0)[0];
        }
        if (result.getKey() ) { // the package is in the other model
            if (reverse==0) {
                Map propertiesMap = result.getValue();
                if (!rdfsLabel.equals(propertiesMap.get("label"))) {
                    compareResults = addResult(compareResults, resItem.getLocalName(), "rdfs:label", rdfsLabel, propertiesMap.get("label").toString());
                }
                if (!rdfsComment.equals(propertiesMap.get("comment"))) {
                    compareResults = addResult(compareResults, resItem.getLocalName(), "rdfs:comment", rdfsComment, propertiesMap.get("comment").toString());
                }
                if (propertiesMap.containsKey("namespace")) {
                    compareResults = addResult(compareResults, resItem.getLocalName(), "namespace", resItem.getNameSpace(), propertiesMap.get("namespace").toString());
                }
            }
        } else {//the package is not in the other model
            if (reverse==0) {
                compareResults = addResult(compareResults, resItem.getLocalName(), "rdfs:label", rdfsLabel, "-");
                if (resItem.hasProperty(RDFS.comment)) {
                    compareResults = addResult(compareResults, resItem.getLocalName(), "rdfs:comment", rdfsComment, "-");
                }
                compareResults = addResult(compareResults, resItem.getLocalName(), "namespace", resItem.getNameSpace(), "-");
            }else{
                compareResults = addResult(compareResults, resItem.getLocalName(), "rdfs:label", "-", rdfsLabel);
                if (resItem.hasProperty(RDFS.comment)) {
                    compareResults = addResult(compareResults, resItem.getLocalName(), "rdfs:comment", "-", rdfsComment);
                }
                compareResults = addResult(compareResults, resItem.getLocalName(), "namespace", "-", resItem.getNameSpace());
            }
        }
        return compareResults;
    }

    //compare Class
    private static ArrayList<Object> compareClass(ArrayList<Object> compareResults, Model modelA, Model modelB, String rdfType, Resource resItem, String cimNSmodelA, int reverse){
        Pair<Boolean,Map> result = contains(modelB, rdfType, resItem,cimNSmodelA);
        String rdfsLabel = resItem.getRequiredProperty(RDFS.label).getObject().toString();
        String rdfsComment="";
        if (resItem.hasProperty(RDFS.comment)) {
            rdfsComment = resItem.getRequiredProperty(RDFS.comment).getObject().toString().split("\\^\\^", 0)[0];
        }
        String subClassOf="";
        if (resItem.hasProperty(RDFS.subClassOf)) {
            subClassOf = resItem.getRequiredProperty(RDFS.subClassOf).getObject().toString().split("#", 2)[1];
        }
        List<String> ClassStereotypes = new LinkedList<>();
        String enumeration = "false";
        String concreteClass = "false";
        for (NodeIterator j = modelA.listObjectsOfProperty(resItem, modelA.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "stereotype")); j.hasNext(); ) {
            RDFNode resItemNode = j.next();
            if (resItemNode.isResource()){
                if (resItemNode.toString().equals("http://iec.ch/TC57/NonStandard/UML#enumeration")) {
                    enumeration = "true";
                }else if (resItemNode.toString().equals("http://iec.ch/TC57/NonStandard/UML#concrete")) {
                    concreteClass="true";
                }
            }else if (resItemNode.isLiteral()){
                ClassStereotypes.add(resItemNode.toString());
            }
        }
        Collections.sort(ClassStereotypes);
        String stereotypes = String.join(",", ClassStereotypes);
        String ClassBelongsToCategory = "";
        for (NodeIterator j = modelA.listObjectsOfProperty(resItem, modelA.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "belongsToCategory")); j.hasNext(); ) {
            RDFNode resItemNode = j.next();
            ClassBelongsToCategory = resItemNode.toString().split("#Package_", 2)[1];
        }


        if (result.getKey()) { // the class is in the other model
            if (reverse==0) {
                Map propertiesMap = result.getValue();
                if (!rdfsLabel.equals(propertiesMap.get("label"))) {
                    compareResults = addResult(compareResults, resItem.getLocalName(), "rdfs:label", rdfsLabel, propertiesMap.get("label").toString());
                }
                if (!rdfsComment.equals(propertiesMap.get("comment"))) {
                    compareResults = addResult(compareResults, resItem.getLocalName(), "rdfs:comment", rdfsComment, propertiesMap.get("comment").toString());
                }
                if (propertiesMap.containsKey("namespace")) {
                    compareResults = addResult(compareResults, resItem.getLocalName(), "namespace", resItem.getNameSpace(), propertiesMap.get("namespace").toString());
                }
                if (!subClassOf.equals(propertiesMap.get("subClassOf")) && resItem.hasProperty(RDFS.subClassOf)) {
                    compareResults = addResult(compareResults, resItem.getLocalName(), "rdfs:subClassOf", subClassOf, propertiesMap.get("subClassOf").toString());
                }
                if (!ClassBelongsToCategory.equals(propertiesMap.get("belongsToCategory"))) {
                    compareResults = addResult(compareResults, resItem.getLocalName(), "cims:belongsToCategory", ClassBelongsToCategory, propertiesMap.get("belongsToCategory").toString());
                }
                if (!enumeration.equals(propertiesMap.get("enumeration"))) {
                    compareResults = addResult(compareResults, resItem.getLocalName(), "enumeration", enumeration, propertiesMap.get("enumeration").toString());
                }
                if (!concreteClass.equals(propertiesMap.get("concrete"))) {
                    compareResults = addResult(compareResults, resItem.getLocalName(), "concrete", concreteClass, propertiesMap.get("concrete").toString());
                }
                if (!stereotypes.equals(propertiesMap.get("stereotype"))) {
                    compareResults = addResult(compareResults, resItem.getLocalName(), "cims:stereotype", stereotypes, propertiesMap.get("stereotype").toString());
                }

                compareResults = compareAttributesAssociations(compareResults, modelA, modelB, resItem, cimNSmodelA, 1, reverse);
            }

        } else {//the class is not in the other model
            if (reverse==0) {
                compareResults = addResult(compareResults, resItem.getLocalName(), "rdfs:label", rdfsLabel, "-");
                compareResults = addResult(compareResults, resItem.getLocalName(), "rdfs:comment", rdfsComment, "-");
                compareResults = addResult(compareResults, resItem.getLocalName(), "namespace", resItem.getNameSpace(), "-");
                if (resItem.hasProperty(RDFS.subClassOf)) {
                    compareResults = addResult(compareResults, resItem.getLocalName(), "rdfs:subClassOf", subClassOf, "-");
                }
                compareResults = addResult(compareResults, resItem.getLocalName(), "cims:belongsToCategory", ClassBelongsToCategory, "-");
                compareResults = addResult(compareResults, resItem.getLocalName(), "enumeration", enumeration, "-");
                compareResults = addResult(compareResults, resItem.getLocalName(), "concrete", concreteClass, "-");
                if (!stereotypes.equals("")) {
                    compareResults = addResult(compareResults, resItem.getLocalName(), "cims:stereotype", stereotypes, "-");
                }
            }else{
                compareResults = addResult(compareResults, resItem.getLocalName(), "rdfs:label", "-", rdfsLabel);
                compareResults = addResult(compareResults, resItem.getLocalName(), "rdfs:comment", "-", rdfsComment);
                compareResults = addResult(compareResults, resItem.getLocalName(), "namespace", "-", resItem.getNameSpace());
                if (resItem.hasProperty(RDFS.subClassOf)) {
                    compareResults = addResult(compareResults, resItem.getLocalName(), "rdfs:subClassOf", "-", subClassOf);
                }
                compareResults = addResult(compareResults, resItem.getLocalName(), "cims:belongsToCategory", "-", ClassBelongsToCategory);
                compareResults = addResult(compareResults, resItem.getLocalName(), "enumeration", "-", enumeration);
                compareResults = addResult(compareResults, resItem.getLocalName(), "concrete", "-", concreteClass);
                if (!stereotypes.equals("")) {
                    compareResults = addResult(compareResults, resItem.getLocalName(), "cims:stereotype", "-", stereotypes);
                }
            }
            compareResults= compareAttributesAssociations(compareResults, modelA, modelB, resItem, cimNSmodelA,0, reverse);
        }
        return compareResults;
    }

    //compare Attributes and associations
    private static ArrayList<Object> compareAttributesAssociations(ArrayList<Object> compareResults, Model modelA, Model modelB, Resource resItem, String cimNSmodelA, int doCompare, int reverse){

        for (ResIterator i = modelA.listResourcesWithProperty(RDFS.domain,resItem); i.hasNext(); ) {
            Resource resItemAttr = i.next();

            List<String> AttrAssocStereotypes = new LinkedList<>();
            String attribute = "false";
            for (NodeIterator j = modelA.listObjectsOfProperty(resItemAttr, modelA.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "stereotype")); j.hasNext(); ) {
                RDFNode resItemNode = j.next();
                if (resItemNode.isResource()){
                    if (resItemNode.toString().equals("http://iec.ch/TC57/NonStandard/UML#attribute")) {
                        attribute = "true";
                    }
                }else if (resItemNode.isLiteral()){
                    AttrAssocStereotypes.add(resItemNode.toString());
                }
            }

            String rdfsLabel = resItemAttr.getRequiredProperty(RDFS.label).getObject().toString();
            String rdfsComment="";
            if (resItemAttr.hasProperty(RDFS.comment)) {
                rdfsComment = resItemAttr.getRequiredProperty(RDFS.comment).getObject().toString().split("\\^\\^", 0)[0];
            }
            String rdfsDomain = resItemAttr.getRequiredProperty(RDFS.domain).getObject().toString().split("#", 2)[1];
            Collections.sort(AttrAssocStereotypes);
            String stereotypes = String.join(",", AttrAssocStereotypes);
            String multiplicity="";
            for (NodeIterator j = modelA.listObjectsOfProperty(resItemAttr, modelA.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "multiplicity")); j.hasNext(); ) {
                RDFNode resItemNode = j.next();
                multiplicity = resItemNode.toString().split("#M:", 2)[1];
            }

            String rdfsRange="";
            if (resItemAttr.hasProperty(RDFS.range)) {// range is used if the type of the attribute is enumeration
                rdfsRange = resItemAttr.getRequiredProperty(RDFS.range).getObject().toString().split("#", 2)[1];
            }
            String dataType="";
            if (resItemAttr.hasProperty(modelA.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "dataType"))){
                dataType = resItemAttr.getRequiredProperty(modelA.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "dataType")).getObject().toString().split("#", 2)[1];
            }
            String isFixed="";
            if (resItemAttr.hasProperty(modelA.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "isFixed"))){
                isFixed = resItemAttr.getRequiredProperty(modelA.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "isFixed")).getObject().toString();
            }
            //special for association
            String inverseRoleName="";
            if (resItemAttr.hasProperty(modelA.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "inverseRoleName"))){
                inverseRoleName = resItemAttr.getRequiredProperty(modelA.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "inverseRoleName")).getObject().asResource().getLocalName();
            }
            //special for association
            String AssociationUsed="";
            if (resItemAttr.hasProperty(modelA.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "AssociationUsed"))){
                AssociationUsed = resItemAttr.getRequiredProperty(modelA.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "AssociationUsed")).getObject().toString();
            }

            Pair<Boolean,Map> result = new Pair<>(false, null);
            if (doCompare==1) {
                result = containsProperty(modelB, attribute, resItemAttr, cimNSmodelA);
            }

            if (result.getKey()) { // the property is in the other model
                if (reverse==0) {
                    Map propertiesMap = result.getValue();
                    if (!rdfsLabel.equals(propertiesMap.get("label"))) {
                        compareResults = addResult(compareResults, resItemAttr.getLocalName(), "rdfs:label", rdfsLabel, propertiesMap.get("label").toString());
                    }
                    if (!rdfsComment.equals(propertiesMap.get("comment")) && resItemAttr.hasProperty(RDFS.comment)) {
                        compareResults = addResult(compareResults, resItemAttr.getLocalName(), "rdfs:comment", rdfsComment, propertiesMap.get("comment").toString());
                    }
                    if (propertiesMap.containsKey("namespace")) {
                        compareResults = addResult(compareResults, resItemAttr.getLocalName(), "namespace", resItemAttr.getNameSpace(), propertiesMap.get("namespace").toString());
                    }
                    if (!rdfsDomain.equals(propertiesMap.get("domain"))) {
                        compareResults = addResult(compareResults, resItemAttr.getLocalName(), "rdfs:domain", rdfsDomain, propertiesMap.get("domain").toString());
                    }
                    if (!multiplicity.equals(propertiesMap.get("multiplicity"))) {
                        compareResults = addResult(compareResults, resItemAttr.getLocalName(), "cims:multiplicity", multiplicity, propertiesMap.get("multiplicity").toString());
                    }
                    if (!rdfsRange.equals(propertiesMap.get("range")) && resItemAttr.hasProperty(RDFS.range)) {
                        compareResults = addResult(compareResults, resItemAttr.getLocalName(), "rdfs:range", rdfsRange, propertiesMap.get("range").toString());
                    }
                    if (!stereotypes.equals(propertiesMap.get("stereotype"))) {
                        compareResults = addResult(compareResults, resItemAttr.getLocalName(), "cims:stereotype", stereotypes, propertiesMap.get("stereotype").toString());
                    }
                    if (attribute.equals("true")) { //it is an attribute
                        if (!dataType.equals(propertiesMap.get("dataType")) && resItemAttr.hasProperty(modelA.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "dataType"))) {
                            compareResults = addResult(compareResults, resItemAttr.getLocalName(), "cims:dataType", dataType, propertiesMap.get("dataType").toString());
                        }
                        if (!isFixed.equals(propertiesMap.get("isFixed")) && resItemAttr.hasProperty(modelA.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "isFixed"))) {
                            String isFixednull="";
                            if (propertiesMap.get("isFixed")==null) {
                                isFixednull = "-";
                            }else{
                                isFixednull=propertiesMap.get("isFixed").toString();
                            }
                            compareResults = addResult(compareResults, resItemAttr.getLocalName(), "cims:isFixed", isFixed, isFixednull);
                        }
                    } else {
                        if (!inverseRoleName.equals(propertiesMap.get("inverseRoleName")) && resItemAttr.hasProperty(modelA.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "inverseRoleName"))) {
                            compareResults = addResult(compareResults, resItemAttr.getLocalName(), "cims:inverseRoleName", inverseRoleName, propertiesMap.get("inverseRoleName").toString());
                        }
                        if (!AssociationUsed.equals(propertiesMap.get("AssociationUsed")) && resItemAttr.hasProperty(modelA.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "AssociationUsed"))) {
                            compareResults = addResult(compareResults, resItemAttr.getLocalName(), "cims:AssociationUsed", AssociationUsed, propertiesMap.get("AssociationUsed").toString());
                        }
                    }
                }

            } else {//the class is not in the other model
                if (reverse==0) {
                    compareResults = addResult(compareResults, resItemAttr.getLocalName(), "rdfs:label", rdfsLabel, "-");
                    if (resItemAttr.hasProperty(RDFS.comment)) {
                        compareResults = addResult(compareResults, resItemAttr.getLocalName(), "rdfs:comment", rdfsComment, "-");
                    }
                    compareResults = addResult(compareResults, resItemAttr.getLocalName(), "namespace", resItemAttr.getNameSpace(), "-");
                    compareResults = addResult(compareResults, resItemAttr.getLocalName(), "rdfs:domain", rdfsDomain, "-");
                    compareResults = addResult(compareResults, resItemAttr.getLocalName(), "cims:multiplicity", multiplicity, "-");
                    if (resItemAttr.hasProperty(RDFS.range)) {
                        compareResults = addResult(compareResults, resItemAttr.getLocalName(), "rdfs:range", rdfsRange, "-");
                    }
                    if (!stereotypes.equals("")) {
                        compareResults = addResult(compareResults, resItemAttr.getLocalName(), "cims:stereotype", stereotypes, "-");
                    }
                    if (attribute.equals("true")) { //it is an attribute
                        if (resItemAttr.hasProperty(modelA.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "dataType"))) {
                            compareResults = addResult(compareResults, resItemAttr.getLocalName(), "cims:dataType", dataType, "-");
                        }
                        if (resItemAttr.hasProperty(modelA.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "isFixed"))) {
                            compareResults = addResult(compareResults, resItemAttr.getLocalName(), "cims:isFixed", isFixed, "-");
                        }
                    } else {
                        if (resItemAttr.hasProperty(modelA.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "inverseRoleName"))) {
                            compareResults = addResult(compareResults, resItemAttr.getLocalName(), "cims:inverseRoleName", inverseRoleName, "-");
                        }
                        if (resItemAttr.hasProperty(modelA.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "AssociationUsed"))) {
                            compareResults = addResult(compareResults, resItemAttr.getLocalName(), "cims:AssociationUsed", AssociationUsed, "-");
                        }
                    }
                }else{
                    compareResults = addResult(compareResults, resItemAttr.getLocalName(), "rdfs:label", "-", rdfsLabel);
                    if (resItemAttr.hasProperty(RDFS.comment)) {
                        compareResults = addResult(compareResults, resItemAttr.getLocalName(), "rdfs:comment", "-", rdfsComment);
                    }
                    compareResults = addResult(compareResults, resItemAttr.getLocalName(), "namespace", "-", resItemAttr.getNameSpace());
                    compareResults = addResult(compareResults, resItemAttr.getLocalName(), "rdfs:domain", "-", rdfsDomain);
                    compareResults = addResult(compareResults, resItemAttr.getLocalName(), "cims:multiplicity", "-", multiplicity);
                    if (resItemAttr.hasProperty(RDFS.range)) {
                        compareResults = addResult(compareResults, resItemAttr.getLocalName(), "rdfs:range", "-", rdfsRange);
                    }
                    if (!stereotypes.equals("")) {
                        compareResults = addResult(compareResults, resItemAttr.getLocalName(), "cims:stereotype", "-", stereotypes);
                    }
                    if (attribute.equals("true")) { //it is an attribute
                        if (resItemAttr.hasProperty(modelA.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "dataType"))) {
                            compareResults = addResult(compareResults, resItemAttr.getLocalName(), "cims:dataType", "-", dataType);
                        }
                        if (resItemAttr.hasProperty(modelA.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "isFixed"))) {
                            compareResults = addResult(compareResults, resItemAttr.getLocalName(), "cims:isFixed", "-", isFixed);
                        }
                    } else {
                        if (resItemAttr.hasProperty(modelA.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "inverseRoleName"))) {
                            compareResults = addResult(compareResults, resItemAttr.getLocalName(), "cims:inverseRoleName", "-", inverseRoleName);
                        }
                        if (resItemAttr.hasProperty(modelA.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "AssociationUsed"))) {
                            compareResults = addResult(compareResults, resItemAttr.getLocalName(), "cims:AssociationUsed", "-", AssociationUsed);
                        }
                    }
                }
            }
        }
        return compareResults;
    }
}
