/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */

package eu.griddigit.cimpal.core.comparators;

import eu.griddigit.cimpal.core.interfaces.IRDFComparator;
import eu.griddigit.cimpal.core.models.RDFCompareResult;
import eu.griddigit.cimpal.core.models.RDFCompareResultEntry;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.util.*;

public class ComparisonIRDFSprofileCIMTool implements IRDFComparator {

    public RDFCompareResult compare(Model modelA, Model modelB){
        RDFCompareResult compareResult = new RDFCompareResult();
        //first run - this compared model A with model B. Identified common parts and reports differences. New
        //classes in Model A that are not in Model B are reported
        compareResult = compareModels(compareResult, modelA, modelB, 0);
        //second run - reverse run. Model B is compared to Model A. Only if there are new parts (classes, attributes, associations) in Model B that are not in Model A
        //are reported
        compareResult = compareModels(compareResult, modelB, modelA, 1);

        return compareResult;

    }


    //compares two models (RDFS)
    private static RDFCompareResult compareModels(RDFCompareResult compareResult, Model modelA, Model modelB, int reverse){
        //iterate on the items found in the rdf file
        String cimNSmodelA=modelA.getNsPrefixURI("cim");
        for (ResIterator i = modelA.listSubjects(); i.hasNext(); ) {
            Resource resItem = i.next();

            String[] rdfTypeInit = resItem.getRequiredProperty(RDF.type).getObject().toString().split("#", 2); // the second part of the resource of of the rdf:type
            String rdfType;
            if (rdfTypeInit.length == 1) {
                rdfType = rdfTypeInit[0];
            } else {
                rdfType = rdfTypeInit[1];
            }

            if (rdfType.equals("ClassCategory")) { // if it is a package
                compareResult= comparePackage(compareResult, modelB, rdfType, resItem, cimNSmodelA, reverse);

            } else if (rdfType.equals("Class")) { // if it is a class
                compareResult= compareClass(compareResult, modelA, modelB, rdfType, resItem, cimNSmodelA, reverse);
            }
        }
        return compareResult;
    }

    //checks if a subject of a given type is in another model
    private static Map<String,String> contains(Model model, String rdftype, Resource value, String cimNSmodelValue){
        Map<String,String> resultMap = new HashMap<>();

        String cimNSmodel=model.getNsPrefixURI("cim");
        //iterate on the items found in the rdf file
        for (ResIterator i = model.listSubjects(); i.hasNext(); ) {
            Resource resItem = i.next();

            String[] rdfTypeInit = resItem.getRequiredProperty(RDF.type).getObject().toString().split("#", 2); // the second part of the resource of of the rdf:type
            String rdfType;
            if (rdfTypeInit.length == 1) {
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
                    resultMap.putIfAbsent("namespace",resItemNS);
                }else if (resItemNS.equals(cimNSmodel) && !valueNS.equals(cimNSmodelValue)) {
                    //if resItemNS==cimNSmodel and valueNS!=cimNSmodelValue => report the change
                    resultMap.putIfAbsent("namespace",resItemNS);
                }else if (!resItemNS.equals(cimNSmodel) && !resItemNS.equals(valueNS)) {
                    //if resItemNS!=cimNSmodel and valueNS!=cimNSmodelValue and resItemNS!=valueNS => report the change
                    resultMap.putIfAbsent("namespace",resItemNS);
                }

                if (rdfType.equals(rdftype)) {
                    if (rdfType.equals("ClassCategory")) {
                        String rdfsLabel = resItem.getRequiredProperty(RDFS.label).getObject().asLiteral().getString();
                        resultMap.putIfAbsent("label",rdfsLabel);
                        if (resItem.hasProperty(RDFS.comment)) {
                            String rdfsComment = resItem.getRequiredProperty(RDFS.comment).getObject().toString().split("\\^\\^", 0)[0];
                            resultMap.putIfAbsent("comment", rdfsComment);
                        }else{
                            resultMap.putIfAbsent("comment", "-");// added 30 Oct 2020
                        }

                    }else if (rdfType.equals("Class")){
                        String rdfsLabel = resItem.getRequiredProperty(RDFS.label).getObject().asLiteral().getString();
                        resultMap.putIfAbsent("label",rdfsLabel);
                        if (resItem.hasProperty(RDFS.comment)) {
                            String rdfsComment = resItem.getRequiredProperty(RDFS.comment).getObject().toString().split("\\^\\^", 0)[0];
                            resultMap.putIfAbsent("comment", rdfsComment);
                        }else{
                            resultMap.putIfAbsent("comment", "-");// added 30 Oct 2020
                        }
                        if (resItem.hasProperty(RDFS.subClassOf)) {
                            String subClassOf = resItem.getRequiredProperty(RDFS.subClassOf).getObject().toString().split("#", 2)[1];
                            resultMap.putIfAbsent("subClassOf", subClassOf);
                        }else{
                            resultMap.putIfAbsent("subClassOf", "-"); //added 30 Oct 2020
                        }
                        List<String> ClassStereotypes = new LinkedList<>();
                        String enumeration = "false";
                        String concreteClass = "false";
                        for (NodeIterator j = model.listObjectsOfProperty(resItem, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "stereotype")); j.hasNext(); ) {
                            RDFNode resItemNode = j.next();
                            if (resItemNode.isResource()){
                                if (resItemNode.toString().equals("http://iec.ch/TC57/NonStandard/UML#enumeration") || resItemNode.toString().equals("http://langdale.com.au/2005/UML#enumeration")) {
                                    enumeration = "true";
                                }else if (resItemNode.toString().equals("http://iec.ch/TC57/NonStandard/UML#concrete") || resItemNode.toString().equals("http://langdale.com.au/2005/UML#concrete")) {
                                    concreteClass="true";
                                }
                            }else if (resItemNode.isLiteral()){
                                ClassStereotypes.add(resItemNode.toString());
                            }
                        }
                        resultMap.putIfAbsent("enumeration",enumeration);
                        resultMap.putIfAbsent("concrete",concreteClass);
                        Collections.sort(ClassStereotypes);
                        String stereotypes = String.join(",", ClassStereotypes);
                        resultMap.putIfAbsent("stereotype",stereotypes);
                        String ClassBelongsToCategory = "";
                        for (NodeIterator j = model.listObjectsOfProperty(resItem, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "belongsToCategory")); j.hasNext(); ) {
                            RDFNode resItemNode = j.next();
                            ClassBelongsToCategory = resItemNode.toString().split("#Package_", 2)[1];
                        }
                        resultMap.putIfAbsent("belongsToCategory",ClassBelongsToCategory);

                    }
                }
            }
        }
        return resultMap;
    }

    //checks if a property of a given type is in another model
    private static Map<String,String> containsProperty(Model model, String attribute, Resource value, String cimNSmodelValue){
        Map<String,String> resultMap = new HashMap<>();


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
                    resultMap.putIfAbsent("namespace",resItemNS);
                }else if (resItemNS.equals(cimNSmodel) && !valueNS.equals(cimNSmodelValue)) {
                    //if resItemNS==cimNSmodel and valueNS!=cimNSmodelValue => report the change
                    resultMap.putIfAbsent("namespace",resItemNS);
                }else if (!resItemNS.equals(cimNSmodel) && !resItemNS.equals(valueNS)) {
                    //if resItemNS!=cimNSmodel and valueNS!=cimNSmodelValue and resItemNS!=valueNS => report the change
                    resultMap.putIfAbsent("namespace",resItemNS);
                }

                String rdfsLabel = resItem.getRequiredProperty(RDFS.label).getObject().asLiteral().getString();
                resultMap.putIfAbsent("label", rdfsLabel);
                if (resItem.hasProperty(RDFS.comment)) {
                    String rdfsComment = resItem.getRequiredProperty(RDFS.comment).getObject().toString().split("\\^\\^", 0)[0];
                    resultMap.putIfAbsent("comment", rdfsComment);
                }else{
                    resultMap.putIfAbsent("comment", "-"); //added 30 Oct 2020
                }
                String rdfsDomain = resItem.getRequiredProperty(RDFS.domain).getObject().toString().split("#", 2)[1];
                resultMap.putIfAbsent("domain", rdfsDomain);
                List<String> AttrAssocStereotypes = new LinkedList<>();
                for (NodeIterator j = model.listObjectsOfProperty(resItem, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "stereotype")); j.hasNext(); ) {
                    RDFNode resItemNode = j.next();
                    if (resItemNode.isLiteral()) {
                        AttrAssocStereotypes.add(resItemNode.toString());
                    }else{ //added 21 June 2021
                        if (resItemNode.asResource().getLocalName().equals("byreference")) {
                            AttrAssocStereotypes.add(resItemNode.asResource().getLocalName());
                        }
                    }
                }
                Collections.sort(AttrAssocStereotypes);
                String stereotypes = String.join(",", AttrAssocStereotypes);
                resultMap.putIfAbsent("stereotype", stereotypes);
                for (NodeIterator j = model.listObjectsOfProperty(resItem, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "multiplicity")); j.hasNext(); ) {
                    RDFNode resItemNode = j.next();
                    resultMap.putIfAbsent("multiplicity", resItemNode.toString().split("#M:", 2)[1]);
                }

                if (resItem.hasProperty(RDFS.range)) {// range is used if the type of the attribute is enumeration
                    String rdfsRange = resItem.getRequiredProperty(RDFS.range).getObject().toString().split("#", 2)[1];
                    resultMap.putIfAbsent("range", rdfsRange);
                }

                if (attribute.equals("true")) {// it is an attribute
                    if (resItem.hasProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "dataType"))){
                        //the datatype is used for primitive, datatype and compound
                        String dataType = resItem.getRequiredProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "dataType")).getObject().toString().split("#", 2)[1];
                        resultMap.putIfAbsent("dataType", dataType);
                    }
                    if (resItem.hasProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "isFixed"))){
                        String isFixed = resItem.getRequiredProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "isFixed")).getObject().toString();
                        resultMap.putIfAbsent("isFixed", isFixed);
                    }

                }else { //it is an association
                    if (resItem.hasProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "inverseRoleName"))){
                        String inverseRoleName = resItem.getRequiredProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "inverseRoleName")).getObject().toString().split("#", 2)[1];
                        resultMap.putIfAbsent("inverseRoleName", inverseRoleName);
                    }
                    if (resItem.hasProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "AssociationUsed"))){
                        String AssociationUsed = resItem.getRequiredProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "AssociationUsed")).getObject().toString();
                        resultMap.putIfAbsent("AssociationUsed", AssociationUsed);
                    }else{
                        if (AttrAssocStereotypes.contains("byreference")) {
                            resultMap.putIfAbsent("AssociationUsed", "Yes"); //added 21 Jun 2021
                        }else{
                            resultMap.putIfAbsent("AssociationUsed", "No"); //added 24 Jun 2021
                        }
                    }
                }
            }
        }
        return resultMap;
    }
    //adds a line to the compareResult
    private static RDFCompareResult addResult(RDFCompareResult compareResult, String item, String property, String valueModelA, String valueModelB) {
        //item; property; value in model A; value in model B
        compareResult.addEntry(new RDFCompareResultEntry(item, property, valueModelA, valueModelB));

        return compareResult;
    }
    //compare Package
    private static RDFCompareResult comparePackage(RDFCompareResult compareResult, Model modelB, String rdfType, Resource resItem, String cimNSmodelA, int reverse){
        Map<String,String> resultMap = contains(modelB, rdfType, resItem,cimNSmodelA);
        String rdfsLabel = resItem.getRequiredProperty(RDFS.label).getObject().asLiteral().getString();
        String rdfsComment="";
        if (resItem.hasProperty(RDFS.comment)) {
            rdfsComment = resItem.getRequiredProperty(RDFS.comment).getObject().toString().split("\\^\\^", 0)[0];
        }
        if (!resultMap.isEmpty()) { // the package is in the other model
            if (reverse==0) {
                if (!rdfsLabel.equals(resultMap.get("label"))) {
                    compareResult = addResult(compareResult, resItem.getLocalName(), "rdfs:label", rdfsLabel, resultMap.get("label"));
                }
                if (!rdfsComment.equals(resultMap.get("comment"))) {
                    compareResult = addResult(compareResult, resItem.getLocalName(), "rdfs:comment", rdfsComment, resultMap.get("comment"));
                }
                if (resultMap.containsKey("namespace")) {
                    compareResult = addResult(compareResult, resItem.getLocalName(), "namespace", resItem.getNameSpace(), resultMap.get("namespace"));
                }
            }
        } else {//the package is not in the other model
            if (reverse==0) {
                compareResult = addResult(compareResult, resItem.getLocalName(), "rdfs:label", rdfsLabel, "-");
                if (resItem.hasProperty(RDFS.comment)) {
                    compareResult = addResult(compareResult, resItem.getLocalName(), "rdfs:comment", rdfsComment, "-");
                }
                compareResult = addResult(compareResult, resItem.getLocalName(), "namespace", resItem.getNameSpace(), "-");
            }else{
                compareResult = addResult(compareResult, resItem.getLocalName(), "rdfs:label", "-", rdfsLabel);
                if (resItem.hasProperty(RDFS.comment)) {
                    compareResult = addResult(compareResult, resItem.getLocalName(), "rdfs:comment", "-", rdfsComment);
                }
                compareResult = addResult(compareResult, resItem.getLocalName(), "namespace", "-", resItem.getNameSpace());
            }
        }
        return compareResult;
    }

    //compare Class
    private static RDFCompareResult compareClass(RDFCompareResult compareResult, Model modelA, Model modelB, String rdfType, Resource resItem, String cimNSmodelA, int reverse){
        Map<String,String> resultMap = contains(modelB, rdfType, resItem,cimNSmodelA);
        String rdfsLabel = resItem.getRequiredProperty(RDFS.label).getObject().asLiteral().getString();
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
        for (NodeIterator j = modelA.listObjectsOfProperty(resItem, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "stereotype")); j.hasNext(); ) {
            RDFNode resItemNode = j.next();
            if (resItemNode.isResource()){
                if (resItemNode.toString().equals("http://iec.ch/TC57/NonStandard/UML#enumeration") || resItemNode.toString().equals("http://langdale.com.au/2005/UML#enumeration")) {
                    enumeration = "true";
                }else if (resItemNode.toString().equals("http://iec.ch/TC57/NonStandard/UML#concrete") || resItemNode.toString().equals("http://langdale.com.au/2005/UML#concrete")) {
                    concreteClass="true";
                }
            }else if (resItemNode.isLiteral()){
                ClassStereotypes.add(resItemNode.toString());
            }
        }
        Collections.sort(ClassStereotypes);
        String stereotypes = String.join(",", ClassStereotypes);
        String ClassBelongsToCategory = "";
        for (NodeIterator j = modelA.listObjectsOfProperty(resItem, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "belongsToCategory")); j.hasNext(); ) {
            RDFNode resItemNode = j.next();
            ClassBelongsToCategory = resItemNode.toString().split("#Package_", 2)[1];
        }


        if (!resultMap.isEmpty()) { // the class is in the other model
            if (reverse==0) {
                if (!rdfsLabel.equals(resultMap.get("label"))) {
                    compareResult = addResult(compareResult, resItem.getLocalName(), "rdfs:label", rdfsLabel, resultMap.get("label"));
                }
                if (!rdfsComment.equals(resultMap.get("comment"))) {
                    compareResult = addResult(compareResult, resItem.getLocalName(), "rdfs:comment", rdfsComment, resultMap.get("comment"));
                }
                if (resultMap.containsKey("namespace")) {
                    compareResult = addResult(compareResult, resItem.getLocalName(), "namespace", resItem.getNameSpace(), resultMap.get("namespace"));
                }
                if (!subClassOf.equals(resultMap.get("subClassOf")) && resItem.hasProperty(RDFS.subClassOf)) {
                    compareResult = addResult(compareResult, resItem.getLocalName(), "rdfs:subClassOf", subClassOf, resultMap.get("subClassOf"));
                }
                if (!ClassBelongsToCategory.equals(resultMap.get("belongsToCategory"))) {
                    compareResult = addResult(compareResult, resItem.getLocalName(), "cims:belongsToCategory", ClassBelongsToCategory, resultMap.get("belongsToCategory"));
                }
                if (!enumeration.equals(resultMap.get("enumeration"))) {
                    compareResult = addResult(compareResult, resItem.getLocalName(), "enumeration", enumeration, resultMap.get("enumeration"));
                }
                if (!concreteClass.equals(resultMap.get("concrete"))) {
                    compareResult = addResult(compareResult, resItem.getLocalName(), "concrete", concreteClass, resultMap.get("concrete"));
                }
                if (!stereotypes.equals(resultMap.get("stereotype"))) {
                    compareResult = addResult(compareResult, resItem.getLocalName(), "cims:stereotype", stereotypes, resultMap.get("stereotype"));
                }

                compareResult = compareAttributesAssociations(compareResult, modelA, modelB, resItem, cimNSmodelA, 1, reverse);
            }

        } else {//the class is not in the other model
            if (reverse==0) {
                compareResult = addResult(compareResult, resItem.getLocalName(), "rdfs:label", rdfsLabel, "-");
                compareResult = addResult(compareResult, resItem.getLocalName(), "rdfs:comment", rdfsComment, "-");
                compareResult = addResult(compareResult, resItem.getLocalName(), "namespace", resItem.getNameSpace(), "-");
                if (resItem.hasProperty(RDFS.subClassOf)) {
                    compareResult = addResult(compareResult, resItem.getLocalName(), "rdfs:subClassOf", subClassOf, "-");
                }
                compareResult = addResult(compareResult, resItem.getLocalName(), "cims:belongsToCategory", ClassBelongsToCategory, "-");
                compareResult = addResult(compareResult, resItem.getLocalName(), "enumeration", enumeration, "-");
                compareResult = addResult(compareResult, resItem.getLocalName(), "concrete", concreteClass, "-");
                if (!stereotypes.isEmpty()) {
                    compareResult = addResult(compareResult, resItem.getLocalName(), "cims:stereotype", stereotypes, "-");
                }
            }else{
                compareResult = addResult(compareResult, resItem.getLocalName(), "rdfs:label", "-", rdfsLabel);
                compareResult = addResult(compareResult, resItem.getLocalName(), "rdfs:comment", "-", rdfsComment);
                compareResult = addResult(compareResult, resItem.getLocalName(), "namespace", "-", resItem.getNameSpace());
                if (resItem.hasProperty(RDFS.subClassOf)) {
                    compareResult = addResult(compareResult, resItem.getLocalName(), "rdfs:subClassOf", "-", subClassOf);
                }
                compareResult = addResult(compareResult, resItem.getLocalName(), "cims:belongsToCategory", "-", ClassBelongsToCategory);
                compareResult = addResult(compareResult, resItem.getLocalName(), "enumeration", "-", enumeration);
                compareResult = addResult(compareResult, resItem.getLocalName(), "concrete", "-", concreteClass);
                if (!stereotypes.isEmpty()) {
                    compareResult = addResult(compareResult, resItem.getLocalName(), "cims:stereotype", "-", stereotypes);
                }
            }
            compareResult= compareAttributesAssociations(compareResult, modelA, modelB, resItem, cimNSmodelA,0, reverse);
        }
        return compareResult;
    }

    //compare Attributes and associations
    private static RDFCompareResult compareAttributesAssociations(RDFCompareResult compareResult, Model modelA, Model modelB, Resource resItem, String cimNSmodelA, int doCompare, int reverse){

        for (ResIterator i = modelA.listResourcesWithProperty(RDFS.domain,resItem); i.hasNext(); ) {
            Resource resItemAttr = i.next();

            List<String> AttrAssocStereotypes = new LinkedList<>();
            String attribute = "false";
            for (NodeIterator j = modelA.listObjectsOfProperty(resItemAttr, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "stereotype")); j.hasNext(); ) {
                RDFNode resItemNode = j.next();
                if (resItemNode.isResource()){
                    if (resItemNode.toString().equals("http://iec.ch/TC57/NonStandard/UML#attribute") || resItemNode.toString().equals("http://langdale.com.au/2005/UML#attribute")) {
                        attribute = "true";
                    }
                    if (resItemNode.asResource().getLocalName().equals("byreference")) {
                        AttrAssocStereotypes.add(resItemNode.asResource().getLocalName());
                    }
                }else if (resItemNode.isLiteral()){
                    AttrAssocStereotypes.add(resItemNode.toString());
                }

            }

            String rdfsLabel = resItemAttr.getRequiredProperty(RDFS.label).getObject().asLiteral().getString();
            String rdfsComment="";
            if (resItemAttr.hasProperty(RDFS.comment)) {
                rdfsComment = resItemAttr.getRequiredProperty(RDFS.comment).getObject().toString().split("\\^\\^", 0)[0];
            }
            String rdfsDomain = resItemAttr.getRequiredProperty(RDFS.domain).getObject().toString().split("#", 2)[1];
            Collections.sort(AttrAssocStereotypes);
            String stereotypes = String.join(",", AttrAssocStereotypes);
            String multiplicity="";
            for (NodeIterator j = modelA.listObjectsOfProperty(resItemAttr, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "multiplicity")); j.hasNext(); ) {
                RDFNode resItemNode = j.next();
                multiplicity = resItemNode.toString().split("#M:", 2)[1];
            }

            String rdfsRange="";
            if (resItemAttr.hasProperty(RDFS.range)) {// range is used if the type of the attribute is enumeration
                rdfsRange = resItemAttr.getRequiredProperty(RDFS.range).getObject().toString().split("#", 2)[1];
            }
            String dataType="";
            if (resItemAttr.hasProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "dataType"))){
                dataType = resItemAttr.getRequiredProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "dataType")).getObject().toString().split("#", 2)[1];
            }
            String isFixed="";
            if (resItemAttr.hasProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "isFixed"))){
                isFixed = resItemAttr.getRequiredProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "isFixed")).getObject().toString();
            }
            //special for association
            String inverseRoleName="";
            if (resItemAttr.hasProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "inverseRoleName"))){
                inverseRoleName = resItemAttr.getRequiredProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "inverseRoleName")).getObject().asResource().getLocalName();
            }
            //special for association
            String AssociationUsed;
            if (resItemAttr.hasProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "AssociationUsed"))) {
                AssociationUsed = resItemAttr.getRequiredProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "AssociationUsed")).getObject().toString();
            }else {
                if (AttrAssocStereotypes.contains("byreference")) {
                    AssociationUsed = "Yes"; //added 21 Jun 2021
                } else {
                    AssociationUsed = "No"; //added 21 Jun 2021
                }
            }



            Map<String,String> resultMap = new HashMap<>();
            if (doCompare==1) {
                resultMap = containsProperty(modelB, attribute, resItemAttr, cimNSmodelA);
            }

            if (!resultMap.isEmpty()) { // the property is in the other model
                if (reverse==0) {
                    if (!rdfsLabel.equals(resultMap.get("label"))) {
                        compareResult = addResult(compareResult, resItemAttr.getLocalName(), "rdfs:label", rdfsLabel, resultMap.get("label"));
                    }
                    if (!rdfsComment.equals(resultMap.get("comment")) && resItemAttr.hasProperty(RDFS.comment)) {
                        compareResult = addResult(compareResult, resItemAttr.getLocalName(), "rdfs:comment", rdfsComment, resultMap.get("comment"));
                    }
                    if (resultMap.containsKey("namespace")) {
                        compareResult = addResult(compareResult, resItemAttr.getLocalName(), "namespace", resItemAttr.getNameSpace(), resultMap.get("namespace"));
                    }
                    if (!rdfsDomain.equals(resultMap.get("domain"))) {
                        compareResult = addResult(compareResult, resItemAttr.getLocalName(), "rdfs:domain", rdfsDomain, resultMap.get("domain"));
                    }
                    if (!multiplicity.equals(resultMap.get("multiplicity"))) {
                        compareResult = addResult(compareResult, resItemAttr.getLocalName(), "cims:multiplicity", multiplicity, resultMap.get("multiplicity"));
                    }
                    if (!rdfsRange.equals(resultMap.get("range")) && resItemAttr.hasProperty(RDFS.range)) {
                        compareResult = addResult(compareResult, resItemAttr.getLocalName(), "rdfs:range", rdfsRange, resultMap.get("range"));
                    }
                    if (!stereotypes.equals(resultMap.get("stereotype"))) {
                        compareResult = addResult(compareResult, resItemAttr.getLocalName(), "cims:stereotype", stereotypes, resultMap.get("stereotype"));
                    }
                    if (attribute.equals("true")) { //it is an attribute
                        if (!dataType.equals(resultMap.get("dataType")) && resItemAttr.hasProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "dataType"))) {
                            compareResult = addResult(compareResult, resItemAttr.getLocalName(), "cims:dataType", dataType, resultMap.get("dataType"));
                        }
                        if (!isFixed.equals(resultMap.get("isFixed")) && resItemAttr.hasProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "isFixed"))) {
                            String isFixednull;
                            if (resultMap.get("isFixed")==null) {
                                isFixednull = "-";
                            }else{
                                isFixednull= resultMap.get("isFixed");
                            }
                            compareResult = addResult(compareResult, resItemAttr.getLocalName(), "cims:isFixed", isFixed, isFixednull);
                        }
                    } else {
                        if (!inverseRoleName.equals(resultMap.get("inverseRoleName")) && resItemAttr.hasProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "inverseRoleName"))) {
                            compareResult = addResult(compareResult, resItemAttr.getLocalName(), "cims:inverseRoleName", inverseRoleName, resultMap.get("inverseRoleName"));
                        }
                        if (!AssociationUsed.equals(resultMap.get("AssociationUsed")) && resItemAttr.hasProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "AssociationUsed"))) {
                            compareResult = addResult(compareResult, resItemAttr.getLocalName(), "cims:AssociationUsed", AssociationUsed, resultMap.get("AssociationUsed"));
                        }
                    }
                }

            } else {//the class is not in the other model
                if (reverse==0) {
                    compareResult = addResult(compareResult, resItemAttr.getLocalName(), "rdfs:label", rdfsLabel, "-");
                    if (resItemAttr.hasProperty(RDFS.comment)) {
                        compareResult = addResult(compareResult, resItemAttr.getLocalName(), "rdfs:comment", rdfsComment, "-");
                    }
                    compareResult = addResult(compareResult, resItemAttr.getLocalName(), "namespace", resItemAttr.getNameSpace(), "-");
                    compareResult = addResult(compareResult, resItemAttr.getLocalName(), "rdfs:domain", rdfsDomain, "-");
                    compareResult = addResult(compareResult, resItemAttr.getLocalName(), "cims:multiplicity", multiplicity, "-");
                    if (resItemAttr.hasProperty(RDFS.range)) {
                        compareResult = addResult(compareResult, resItemAttr.getLocalName(), "rdfs:range", rdfsRange, "-");
                    }
                    if (!stereotypes.equals("")) {
                        compareResult = addResult(compareResult, resItemAttr.getLocalName(), "cims:stereotype", stereotypes, "-");
                    }
                    if (attribute.equals("true")) { //it is an attribute
                        if (resItemAttr.hasProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "dataType"))) {
                            compareResult = addResult(compareResult, resItemAttr.getLocalName(), "cims:dataType", dataType, "-");
                        }
                        if (resItemAttr.hasProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "isFixed"))) {
                            compareResult = addResult(compareResult, resItemAttr.getLocalName(), "cims:isFixed", isFixed, "-");
                        }
                    } else {
                        if (resItemAttr.hasProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "inverseRoleName"))) {
                            compareResult = addResult(compareResult, resItemAttr.getLocalName(), "cims:inverseRoleName", inverseRoleName, "-");
                        }
                        if (resItemAttr.hasProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "AssociationUsed"))) {
                            compareResult = addResult(compareResult, resItemAttr.getLocalName(), "cims:AssociationUsed", AssociationUsed, "-");
                        }
                    }
                }else{
                    compareResult = addResult(compareResult, resItemAttr.getLocalName(), "rdfs:label", "-", rdfsLabel);
                    if (resItemAttr.hasProperty(RDFS.comment)) {
                        compareResult = addResult(compareResult, resItemAttr.getLocalName(), "rdfs:comment", "-", rdfsComment);
                    }
                    compareResult = addResult(compareResult, resItemAttr.getLocalName(), "namespace", "-", resItemAttr.getNameSpace());
                    compareResult = addResult(compareResult, resItemAttr.getLocalName(), "rdfs:domain", "-", rdfsDomain);
                    compareResult = addResult(compareResult, resItemAttr.getLocalName(), "cims:multiplicity", "-", multiplicity);
                    if (resItemAttr.hasProperty(RDFS.range)) {
                        compareResult = addResult(compareResult, resItemAttr.getLocalName(), "rdfs:range", "-", rdfsRange);
                    }
                    if (!stereotypes.equals("")) {
                        compareResult = addResult(compareResult, resItemAttr.getLocalName(), "cims:stereotype", "-", stereotypes);
                    }
                    if (attribute.equals("true")) { //it is an attribute
                        if (resItemAttr.hasProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "dataType"))) {
                            compareResult = addResult(compareResult, resItemAttr.getLocalName(), "cims:dataType", "-", dataType);
                        }
                        if (resItemAttr.hasProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "isFixed"))) {
                            compareResult = addResult(compareResult, resItemAttr.getLocalName(), "cims:isFixed", "-", isFixed);
                        }
                    } else {
                        if (resItemAttr.hasProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "inverseRoleName"))) {
                            compareResult = addResult(compareResult, resItemAttr.getLocalName(), "cims:inverseRoleName", "-", inverseRoleName);
                        }
                        if (resItemAttr.hasProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "AssociationUsed"))) {
                            compareResult = addResult(compareResult, resItemAttr.getLocalName(), "cims:AssociationUsed", "-", AssociationUsed);
                        }
                    }
                }
            }
        }
        return compareResult;
    }
}
