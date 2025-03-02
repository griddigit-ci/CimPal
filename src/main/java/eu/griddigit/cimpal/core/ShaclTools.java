/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2023, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package eu.griddigit.cimpal.core;


import eu.griddigit.cimpal.application.MainController;
import eu.griddigit.cimpal.model.SHACLValidationResult;
import javafx.scene.control.ChoiceDialog;
import javafx.stage.DirectoryChooser;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.datatypes.xsd.impl.RDFLangString;
import org.apache.jena.enhanced.EnhGraph;
import org.apache.jena.graph.GraphMemFactory;
import org.apache.jena.rdf.model.*;
import org.apache.jena.rdf.model.impl.PropertyImpl;
import org.apache.jena.riot.*;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.shacl.vocabulary.SHACL;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.vocabulary.*;
import org.topbraid.shacl.vocabulary.DASH;
import org.topbraid.shacl.vocabulary.SH;
import eu.griddigit.cimpal.util.PropertyHolder;

import java.io.*;
import java.util.*;

import static eu.griddigit.cimpal.application.MainController.*;
import static org.topbraid.shacl.vocabulary.SH.path;
import static eu.griddigit.cimpal.util.CompareFactory.isBlankNodeAlist;

public class ShaclTools {

    private static Map<String, RDFDatatype> dataTypeMapFromProfile;
    public static Model profileDataMapAsModelTemp;
    public static Resource mainClassTemp;
    public static List<Statement> rdfsHeaderStatements;
    public static Model originalModel;


    //constructs the shape data necessary to create the set of shapes for the basic profile validations
    public static ArrayList<Object> constructShapeData(Model model, String rdfNs, String concreteNs) {

        dataTypeMapFromProfile = new HashMap<>();
        profileDataMapAsModelTemp = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();

        originalModel = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        originalModel.add(model.listStatements());

        String enumNS = "http://iec.ch/TC57/NonStandard/UML#enumeration";


        //Extract RDFS header information
        if (model.listSubjectsWithProperty(RDF.type,ResourceFactory.createProperty("http://www.w3.org/2002/07/owl#Ontology")).hasNext()){
            Resource hearerTypeRes = model.listSubjectsWithProperty(RDF.type,ResourceFactory.createProperty("http://www.w3.org/2002/07/owl#Ontology")).next();
            rdfsHeaderStatements = model.listStatements(hearerTypeRes, null, (Property) null).toList();
        }

        // structure for the list rdfToShacl
        // level 0: the profile, element 0 data for the profile
        // level 1..*: the class and then all attributes and associations including the inherited
        ArrayList<Object> rdfToShacl = new ArrayList<Object>();
        ArrayList<Object> shapeData = new ArrayList<Object>();

        //start base profiles
        //add here some logic that looks at the base profiles and
        // -adds all concrete and abstract classes to the model for all classes that are in the model and if the classes are not in the model
        if (baseprofilesshaclglag == 1) {
            LinkedList<Statement> subclassStatements = getSubClassesInheritance(model, unionmodelbaseprofilesshacl);
            model.add(subclassStatements);
        }
        if (baseprofilesshaclglag2nd == 1) {
            LinkedList<Statement> subclassStatements2nd = getSubClassesInheritance(model, unionmodelbaseprofilesshacl2nd);
            model.add(subclassStatements2nd);
        }
        if (baseprofilesshaclglag3rd == 1) {
            LinkedList<Statement> subclassStatements3rd = getSubClassesInheritance(model, unionmodelbaseprofilesshacl3rd);
            model.add(subclassStatements3rd);
        }
        //end base profiles

        for (ResIterator i = model.listResourcesWithProperty(RDF.type,RDFS.Class); i.hasNext(); ) {
            Resource resItem = i.next();
            boolean descriptionStereotype=false;
            //check if there is Description stereotype
            for (NodeIterator k = model.listObjectsOfProperty(resItem, model.getProperty(rdfNs, "stereotype")); k.hasNext(); ) {
                RDFNode resItemNodeDescr = k.next();
                if (resItemNodeDescr.toString().equals("Description")){
                    descriptionStereotype=true; // the description stereotype
                    break;
                }
            }

            //check if the class is concrete
            boolean classConcrete=false;
            for (NodeIterator k = model.listObjectsOfProperty(resItem, model.getProperty(rdfNs, "stereotype")); k.hasNext(); ) {
                RDFNode resItemNodeConcrete = k.next();
                if (resItemNodeConcrete.toString().equals(concreteNs)) {
                    classConcrete=true; // the class is concrete
                    break;
                }
            }

            //check if the class is enum
            boolean classIsEnum=false;
            for (NodeIterator k = model.listObjectsOfProperty(resItem, model.getProperty(rdfNs, "stereotype")); k.hasNext(); ) {
                RDFNode resItemNodeEnum = k.next();
                if (resItemNodeEnum.toString().equals(enumNS)) {
                    classIsEnum=true; // the class is concrete
                    break;
                }
            }

            //check if the class is datatype
            boolean classIsDatatype=false;
            for (NodeIterator k = model.listObjectsOfProperty(resItem, model.getProperty(rdfNs, "stereotype")); k.hasNext(); ) {
                RDFNode resItemNodeDatatype = k.next();
                if (resItemNodeDatatype.toString().equals("Primitive") || resItemNodeDatatype.toString().equals("CIMDatatype") || resItemNodeDatatype.toString().equals("Compound")) {
                    classIsDatatype=true; // the class is concrete
                    break;
                }
            }

            if (!classIsEnum && !classIsDatatype) {

                Resource classItem = resItem;
                ArrayList<Object> classData = new ArrayList<>(); // this collects the basic data for the class and then the attributes and associations
                ArrayList<String> classMyData = new ArrayList<>(); // basic data for the class
                classMyData.add(resItem.toString()); // the complete resource of the class
                classMyData.add(resItem.getNameSpace()); // the namespace of the resource of the class
                classMyData.add(resItem.getLocalName()); // the local name i.e. identifiedObject
                classMyData.add(resItem.getRequiredProperty(RDFS.label).getObject().toString()); // the label
                if (descriptionStereotype) {
                    classMyData.add("Yes"); // there is description stereotype
                } else {
                    classMyData.add("No");
                }

                if (classConcrete) {
                    classMyData.add("Yes"); // the class is concrete
                } else {
                    classMyData.add("No");
                }

                classData.add(classMyData); // adds the 0 element for the class where
                //add the class data to the temp model
                profileDataMapAsModelTemp.add(resItem, RDF.type, RDFS.Class);
                /*
                 * 0 is the complete resource of the class
                 * 1 is the namespace of the resource of the class
                 * 2 is the local name i.e. identifiedObject
                 * 3 is the label - RDFS label
                 * 4 has "DescriptionStereotype" is the indication if the class is stereotyped description
                 * 5 has "classConcrete" is the indication if the class is concrete
                 */
                mainClassTemp = classItem;

                if (shapesOnAbstractOption == 0) {
                    if (classConcrete) {// the resource is concrete - it is a concrete class
                        int root = 0;
                        while (root == 0) {
                            classData = ShaclTools.getLocalAttributesAssociations(classItem, model, classData, rdfNs);
                            if (classItem.hasProperty(RDFS.subClassOf)) {//has subClassOf
                                classItem = classItem.getRequiredProperty(RDFS.subClassOf).getResource(); // the resource of the subClassOf
                            } else {
                                root = 1;
                            }
                        }
                    }
                } else {
                    classData = ShaclTools.getLocalAttributesAssociations(classItem, model, classData, rdfNs);
                }
                rdfToShacl.add(classData);
            }
        }
        // add the path to be used for the compound
        for (Object o : rdfToShacl) {
            for (int attr = 1; attr < ((ArrayList<?>) o).size(); attr++) {
                if (((ArrayList<?>) ((ArrayList<?>) o).get(attr)).get(0).equals("Attribute") && ((ArrayList<?>) ((ArrayList<?>) o).get(attr)).get(8).equals("Compound")) {
                    List<RDFNode> pathComp = new LinkedList<>();
                    RDFNode r = ResourceFactory.createResource(((ArrayList<?>) ((ArrayList<?>) o).get(attr)).get(1).toString());
                    pathComp.add(r);
                    ((ArrayList) ((ArrayList<?>) o).get(attr)).add(pathComp); // this is adding item 11 for the compound attribute
                    // this value will need to be added as item 11 of all attributes of the compound
                    //then go to the next level of compound
                    //TODO: ATTENTION: only 2 levels of nested compounds supported - see how to do it in a cleaver way
                    for (int attrComp = 0; attrComp < ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) o).get(attr)).get(10)).size(); attrComp++) {
                        List<RDFNode> pathComp1 = new LinkedList<>();
                        RDFNode r1 = ResourceFactory.createResource(((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) o).get(attr)).get(10)).get(attrComp)).get(1).toString());
                        pathComp1.add(r);
                        pathComp1.add(r1);
                        ((ArrayList) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) o).get(attr)).get(10)).get(attrComp)).add(pathComp1); // this is adding item 11 for the compound attribute
                        if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) o).get(attr)).get(10)).get(attrComp)).get(0).equals("Attribute") && ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) o).get(attr)).get(10)).get(attrComp)).get(8).equals("Compound")) {
                            for (int attrComp2 = 0; attrComp2 < ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) o).get(attr)).get(10)).get(attrComp)).get(10)).size(); attrComp2++) {
                                List<RDFNode> pathComp2 = new LinkedList<>();
                                RDFNode r2 = ResourceFactory.createResource(((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) o).get(attr)).get(10)).get(attrComp)).get(10)).get(attrComp2)).get(1).toString());
                                pathComp2.add(r);
                                pathComp2.add(r1);
                                pathComp2.add(r2);
                                ((ArrayList) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList) ((ArrayList) ((ArrayList) o).get(attr)).get(10)).get(attrComp)).get(10)).get(attrComp2)).add(pathComp2); // this is adding item 11 for the compound attribute
                            }
                        }
                    }
                }
            }
        }

        shapeData.add(rdfToShacl); // adds the structure for one rdf file
        return shapeData;
    }

    //get subclasses for inheritance
    public static LinkedList<Statement> getSubClassesInheritance(Model sourceModel, Model baseModel) {

        LinkedList<Statement> subclassStatements = new LinkedList<>();

        for (StmtIterator i = sourceModel.listStatements(null, RDF.type, RDFS.Class); i.hasNext(); ) {
            Statement stmtM = i.next();
            subclassStatements = getAllSubclasses(baseModel,stmtM.getSubject(), subclassStatements);
        }
        return subclassStatements;
    }

    //get subclasses for inheritance - next level
    public static LinkedList<Statement> getAllSubclasses(Model model, Resource parentClassRes, LinkedList<Statement> subclassStatements) {

        boolean classInBase = false;
        List<Statement> classes = model.listStatements(null, RDF.type,RDFS.Class).toList();
        for (Statement stmt : classes) {
            if (stmt.getSubject().getLocalName().equals(parentClassRes.getLocalName())) {
                classInBase = true;
                break;
            }
        }

        if (model.listStatements(parentClassRes, RDF.type,RDFS.Class).hasNext() || classInBase){
            for (StmtIterator j = model.listStatements(null, RDFS.subClassOf,(RDFNode) null); j.hasNext(); ) {
                Statement stmtB = j.next();
                if (stmtB.getObject().asResource().getLocalName().equals(parentClassRes.getLocalName())) {
                    subclassStatements.add(ResourceFactory.createStatement(stmtB.getSubject(), RDF.type, RDFS.Class));
                    subclassStatements.add(ResourceFactory.createStatement(stmtB.getSubject(), RDFS.subClassOf, parentClassRes));
                    subclassStatements.add(ResourceFactory.createStatement(stmtB.getSubject(), RDFS.label, model.getRequiredProperty(stmtB.getSubject(),RDFS.label).getObject()));
                    Boolean concrete = classIsConcrete(stmtB.getSubject(), model);
                    if (concrete) {
                        Property stereotype = ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "stereotype");
                        Resource con = ResourceFactory.createResource("http://iec.ch/TC57/NonStandard/UML#concrete");
                        subclassStatements.add(ResourceFactory.createStatement(stmtB.getSubject(), stereotype, con));
                    }
                    subclassStatements = getAllSubclasses(model, stmtB.getSubject(), subclassStatements);
                }
            }
        }
        return subclassStatements;
    }

    //gets all attributes and associations (including inherited) for a given concrete class
    public static ArrayList<Object> getLocalAttributesAssociations(Resource resItem, Model model, ArrayList<Object> classData, String rdfNs) {
        /*classProperty structure
            If an association
                0 Association
                1 used or not Yes or No
                2 complete resource
                3 namespace of the resource
                4 prefix of the resource
                5 local name on the resource
                6 cardinality
                7  label
                8 domain
                9 range
                10 inverse role name
                11 linked list of concrete classes that inherit that association
            If an attribute
                0 Attribute
                1 complete resource
                2 namespace of the resource
                3 prefix of the resource
                4 local name on the resource
                5 cardinality
                6 label
                7 domain

                8 Enumeration, Primitive or CIMDatatype or Compound
                if Primitive
                9 - uri of the primitive
                10 - local name of the primitive i.e. String
                11 the path for the primitive
                if Enumeration
                9 this is the resource of the enum
                10 a substructure of the enum attributes
                if CIMdatatype
                9 local name of the datatype of the .value attribute of the CIMdatatype e.g. String
                10 uri of the datatype
                11 the local name of the datatype e.g. Voltage
                if Compound
                9 this is the resource of the compound
                10 a substructure of the compound attributes, their datatypes and cardinalities
                11 the path for the compound

        */

        Map dataTypeMapFromShapes= MainController.getDataTypeMapFromShapes();
        int shaclNodataMap= MainController.getShaclNodataMap();


        for (ResIterator i=model.listResourcesWithProperty(RDFS.domain); i.hasNext();) {
            Resource resItemDomain = i.next();
            //System.out.println(resItem.toString());
            //System.out.println(resItemDomain.getRequiredProperty(RDFS.domain).toString());
            if (resItem.toString().equals(resItemDomain.getRequiredProperty(RDFS.domain).getObject().toString())){
                ArrayList<Object> classProperty = new ArrayList<>();
                int isAttr=0;
                if (model.listObjectsOfProperty(resItemDomain, model.getProperty(rdfNs, "AssociationUsed")).hasNext()) {
                    for (NodeIterator j = model.listObjectsOfProperty(resItemDomain, model.getProperty(rdfNs, "AssociationUsed")); j.hasNext(); ) {
                        RDFNode resItemNode = j.next();
                        if (resItemNode.toString().equals("Yes")) {
                            isAttr = 0;
                            classProperty.add("Association"); // it is an association - item 0
                            classProperty.add("Yes"); // index 1 gives the direction - item 1
                        } else if (resItemNode.toString().equals("No")) {
                            isAttr = 0;
                            classProperty.add("Association"); // it is an association - item 0
                            classProperty.add("No"); // index 1 gives the direction - item 1
                        }
                    }
                } else {
                    isAttr = 1;
                    classProperty.add("Attribute"); // it is an attribute - item 0
                }

                profileDataMapAsModelTemp.add(resItemDomain,RDF.type,RDF.Property);
                profileDataMapAsModelTemp.add(resItemDomain,RDFS.domain,mainClassTemp); //this is to assign not yo the original class but as property of the class that is processed

                classProperty.add(resItemDomain.toString()); // the complete resource of the attribute - item 1 for attribute, item 2 for association
                classProperty.add(resItemDomain.getNameSpace()); // the namespace of the resource of the attribute - item 2 for attribute, item 3 for association
                classProperty.add(model.getNsURIPrefix(resItemDomain.getNameSpace())); // this is the prefix - item 3 for attribute, item 4 for association
                classProperty.add(resItemDomain.getLocalName()); // the local name i.e. identifiedObject - item 4 for attribute, item 5 for association
//                if (resItemDomain.getLocalName().equals("IdentifiedObject.name")) {
//                    int test = 1;
//                }
                for (NodeIterator j = model.listObjectsOfProperty(resItemDomain, model.getProperty(rdfNs, "multiplicity")); j.hasNext(); ) {
                    RDFNode resItemNode = j.next();
                    classProperty.add(resItemNode.toString().split("#M:", 2)[1]); //- item 5 for attribute, item 6 for association
                    if (isAttr==1) {
                        //TODO - need to have something more clever here. if there are different cardinalities for different profiles with same attribute/association
                        if (model.listObjectsOfProperty(resItemDomain, model.getProperty(rdfNs, "multiplicity")).toList().size() > 1) {
                            //GuiHelper.appendTextToOutputWindow("[Attention] The property: " + resItemDomain.getLocalName() + " has different multiplicity in different profiles.", true);
                            break;
                        }
                    }
                }

                if (isAttr==1) {
                    classProperty.add(resItemDomain.getRequiredProperty(RDFS.label).getObject().toString().split("@", 2)[0]); // the label - item 6
                    classProperty.add(resItemDomain.getRequiredProperty(RDFS.domain).getObject().toString()); // item 7

                    //if it is an attribute and has rdf range it means that the datatype is an enumeration
                    if (resItemDomain.hasProperty(RDFS.range)){
                        classProperty.add("Enumeration"); //- item 8
                        ArrayList<Object> enumAttr = new ArrayList<>();
                        classProperty.add(resItemDomain.getProperty(RDFS.range).getObject().toString());// the resource of the enumeration - item 9
                        for (ResIterator ienum = model.listSubjects(); ienum.hasNext(); ) { //iterate on the items found in the rdf file
                            Resource resItemEnum = ienum.next();
                            if (resItemDomain.getProperty(RDFS.range).getObject().toString().equals(resItemEnum.getProperty(RDF.type).getObject().toString())) {
                                enumAttr.add(resItemEnum.toString());
                            }
                        }
                        classProperty.add(enumAttr); //- item 10
                    }


                    for (NodeIterator j = model.listObjectsOfProperty(resItemDomain, model.getProperty(rdfNs, "dataType")); j.hasNext(); ) {
                        RDFNode resItemNode = j.next();
                        Resource resItemNew = model.getResource(resItemNode.toString());
                        //System.out.println(resItemNew.toString());
                        String[] rdfTypeInit = resItemNew.getRequiredProperty(RDF.type).getObject().toString().split("#", 2); // the second part of the resource of of the rdf:type
                        String rdfType;
                        if (rdfTypeInit.length == 0) {
                            rdfType = rdfTypeInit[0];
                        } else {
                            rdfType = rdfTypeInit[1];
                        }

                        if (rdfType.equals("Class")) { // if it is a class
                            if (resItemNew.hasProperty(model.getProperty(rdfNs, "stereotype"),"Primitive")){
                                classProperty.add("Primitive"); //- item 8
                                if (shaclNodataMap==0) {  // only if the user selects to create datamap when generating the shape file
                                    dataTypeMapFromShapes = DataTypeMaping.addDatatypeMapProperty(dataTypeMapFromShapes, resItemDomain.toString(), resItemNode.asResource().getLocalName());
                                }
                                dataTypeMapFromProfile = DataTypeMaping.addDatatypeMapProperty(dataTypeMapFromProfile, resItemDomain.toString(), resItemNode.asResource().getLocalName());
                                classProperty.add(resItemNode.toString()); // the resource - item 9
                                classProperty.add(resItemNode.toString().split("#", 2)[1]); //the local name of the resource e.g. "String" - item 10
                            }
                            if (resItemNew.hasProperty(model.getProperty(rdfNs, "stereotype"),"CIMDatatype")){
                                classProperty.add("CIMDatatype"); // item 8
                                Resource resItemDTvalue=model.getResource(resItemNew+".value");
                                for (NodeIterator jdt = model.listObjectsOfProperty(resItemDTvalue, model.getProperty(rdfNs, "dataType")); jdt.hasNext(); ) {
                                    RDFNode resItemNodedt = jdt.next();
                                    classProperty.add(resItemNodedt.asResource().getLocalName());// item 9
                                    if (shaclNodataMap==0) { // only if the user selects to create datamap when generating the shape file
                                        dataTypeMapFromShapes = DataTypeMaping.addDatatypeMapProperty(dataTypeMapFromShapes, resItemDomain.toString(), resItemNodedt.asResource().getLocalName());
                                    }
                                    dataTypeMapFromProfile = DataTypeMaping.addDatatypeMapProperty(dataTypeMapFromProfile, resItemDomain.toString(), resItemNodedt.asResource().getLocalName());
                                }
                                classProperty.add(resItemNode.toString()); // the resource - item 10
                                classProperty.add(resItemNode.toString().split("#", 2)[1]); //the local name of the resource e.g. "String" - item 11
                            }

                            //If the datatypes is a Compound
                            if (resItemNew.hasProperty(model.getProperty(rdfNs, "stereotype"),"Compound")){
                                classProperty.add("Compound"); // this is No 8

                                ArrayList<Object> classCompound = new ArrayList<>();
                                //resItemDomain is the http://iec.ch/TC57/CIM100#Location.mainAddress this is already in
                                //resItemNew is the http://iec.ch/TC57/CIM100#StreetAddress
                                classProperty.add(resItemNew.toString());// the resource of the compound - item 9

                                // this adds the whole structure including nested compounds
                                classCompound = getAttributesOfCompound(resItemNew,model,classCompound,rdfNs);

                                classProperty.add(classCompound); // item 10
                            }
                        }

                    }
                }else{
                    classProperty.add(resItemDomain.getRequiredProperty(RDFS.label).getObject().toString().split("@", 2)[0]); // the label
                    classProperty.add(resItemDomain.getRequiredProperty(RDFS.domain).getObject().toString());
                    classProperty.add(resItemDomain.getRequiredProperty(RDFS.range).getObject().toString());
                    classProperty.add(resItemDomain.getRequiredProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#inverseRoleName")).getObject().toString());
                    //get all subclasses (concrete and abstract) for the range of the association
                    Resource classRange=resItemDomain.getRequiredProperty(RDFS.range).getObject().asResource();
                    List<Resource> allSubclasses = getSubtypeClassesNlevel(classRange, model);
                    //check if the class is concrete
                    //TODO see if something should be done for abstract classes
//                    if (classRange.toString().contains("ACDCTerminal")){
//                        int k=1;
//                    }
                    if (allSubclasses.isEmpty()) {
                        Boolean concrete= classIsConcrete(classRange, model);
                        List<Resource> concreteSubclass = new LinkedList<>();
                        if (concrete) {
                            concreteSubclass.add(classRange);
                            classProperty.add(concreteSubclass);
                           //System.out.println("Association class is concrete" + classRange.toString());
                        }else{
                            concreteSubclass.add(ResourceFactory.createResource("http://abstract.eu"));
                            classProperty.add(concreteSubclass);
                        }
                    }else{
                        List<Resource> allConcreteSubclasses = new LinkedList<>();
                        //here this is needed to cover the case when there are subclasses, but also the main class is concrete and needs to be put in the list
                        Boolean concreteMainclass= classIsConcrete(classRange, model);
                        if (concreteMainclass) {
                            allConcreteSubclasses.add(classRange);
                        }

                        //this below is for the subclasses
                        for (Resource subclass : allSubclasses) {
                            Boolean concrete = classIsConcrete(subclass, model);
                            if (concrete) {
                                allConcreteSubclasses.add(subclass);
                            }
                        }

                        if (allConcreteSubclasses.isEmpty() && allSubclasses.size()==1){
                            allConcreteSubclasses.add(ResourceFactory.createResource("http://abstract.eu"));
                            classProperty.add(allConcreteSubclasses);
                        }else if (!allConcreteSubclasses.isEmpty()) {
                            //System.out.println(allConcreteSubclasses);
                            classProperty.add(allConcreteSubclasses);
                        }
                    }
                }
                /*for (int p=0; p<classProperty.size(); p++) {// test printing
                    System.out.println(classProperty.get(p));
                }*/
                classData.add(classProperty);
            }
        }
        if (shaclNodataMap==0) { // only if the user selects to create datamap when generating the shape file
            MainController.setDataTypeMapFromShapes(dataTypeMapFromShapes);
        }

        return classData;
    }

    //returns a structure that contains the attributes of a compound
    public static ArrayList getAttributesOfCompound(Resource resItem, Model model, ArrayList<Object> classCompound, String rdfNs){
        /*compoundProperty structure
         If an attribute
         0 Attribute
         1 complete resource
         2 namespace of the resource
         3 prefix of the resource
         4 local name on the resource
         5 cardinality
         6 label
         7 domain

         8 Primitive or Compound
         if Primitive
         9 - uri of the primitive
         10 - local name of the primitive i.e. String
         11 the path for the primitive

         if Compound
         9 this is the resource of the compound
         10 a substructure of the compound attributes, their datatypes and cardinalities
         11 the path for the compound
         */

        Map dataTypeMapFromShapes= MainController.getDataTypeMapFromShapes();
        int shaclNodataMap= MainController.getShaclNodataMap();

        for (ResIterator i=model.listResourcesWithProperty(RDFS.domain); i.hasNext();) {
            Resource resItemDomain = i.next();
            //System.out.println(resItem.toString());
            //System.out.println(resItemDomain.getRequiredProperty(RDFS.domain).toString());
            if (resItem.toString().equals(resItemDomain.getRequiredProperty(RDFS.domain).getObject().toString())){
                ArrayList<Object> compoundProperty = new ArrayList<>();
                int isAttr=0;
                if (!model.listObjectsOfProperty(resItemDomain, model.getProperty(rdfNs, "AssociationUsed")).hasNext()) {
                    isAttr = 1;
                    compoundProperty.add("Attribute"); // it is an attribute - item 0
                    compoundProperty.add(resItemDomain.toString()); // the complete resource of the attribute - item 1
                    compoundProperty.add(resItemDomain.getNameSpace()); // the namespace of the resource of the attribute - item 2
                    compoundProperty.add(model.getNsURIPrefix(resItemDomain.getNameSpace())); // this is the prefix - item 3
                    compoundProperty.add(resItemDomain.getLocalName()); // the local name i.e. identifiedObject - item 4
                }

                if (isAttr==1) {
                    for (NodeIterator j = model.listObjectsOfProperty(resItemDomain, model.getProperty(rdfNs, "multiplicity")); j.hasNext(); ) {
                        RDFNode resItemNode = j.next();
                        compoundProperty.add(resItemNode.toString().split("#M:", 2)[1]); // item 5
                    }
                    compoundProperty.add(resItemDomain.getRequiredProperty(RDFS.label).getObject().toString().split("@", 2)[0]); // the label - item 6
                    compoundProperty.add(resItemDomain.getRequiredProperty(RDFS.domain).getObject().toString()); // item 7

                    for (NodeIterator j = model.listObjectsOfProperty(resItemDomain, model.getProperty(rdfNs, "dataType")); j.hasNext(); ) {
                        RDFNode resItemNode = j.next();
                        Resource resItemNew = model.getResource(resItemNode.toString());
                        String[] rdfTypeInit = resItemNew.getRequiredProperty(RDF.type).getObject().toString().split("#", 2); // the second part of the resource of of the rdf:type
                        String rdfType;
                        if (rdfTypeInit.length == 0) {
                            rdfType = rdfTypeInit[0];
                        } else {
                            rdfType = rdfTypeInit[1];
                        }

                        if (rdfType.equals("Class")) { // if it is a class
                            if (resItemNew.hasProperty(model.getProperty(rdfNs, "stereotype"),"Primitive")){
                                compoundProperty.add("Primitive"); // item 8
                                if (shaclNodataMap==0) {  // only if the user selects to create datamap when generating the shape file
                                    dataTypeMapFromShapes = DataTypeMaping.addDatatypeMapProperty(dataTypeMapFromShapes, resItemDomain.toString(), resItemNode.asResource().getLocalName());
                                }
                                dataTypeMapFromProfile = DataTypeMaping.addDatatypeMapProperty(dataTypeMapFromProfile, resItemDomain.toString(), resItemNode.asResource().getLocalName());
                                compoundProperty.add(resItemNode.toString()); // the resource - item 9
                                compoundProperty.add(resItemNode.toString().split("#", 2)[1]); //the local name of the resource e.g. "String" - item 10
                            }

                            //If the datatypes is a Compound
                            if (resItemNew.hasProperty(model.getProperty(rdfNs, "stereotype"),"Compound")){
                                compoundProperty.add("Compound"); // this is No 8

                                ArrayList<Object> classCompoundNested = new ArrayList<>();
                                //resItemDomain is the http://iec.ch/TC57/CIM100#Location.mainAddress this is already in
                                //resItemNew is the http://iec.ch/TC57/CIM100#StreetAddress
                                compoundProperty.add(resItemNew.toString());// the resource of the compound - item 9

                                // this adds the whole structure including nested compounds
                                classCompoundNested = getAttributesOfCompound(resItemNew,model,classCompoundNested,rdfNs);

                                compoundProperty.add(classCompoundNested); // item 10
                            }
                        }
                    }
                }
                /*for (int p=0; p<classProperty.size(); p++) {// test printing
                    System.out.println(classProperty.get(p));
                }*/
                classCompound.add(compoundProperty);
            }
        }
        if (shaclNodataMap==0) { // only if the user selects to create datamap when generating the shape file
            MainController.setDataTypeMapFromShapes(dataTypeMapFromShapes);
        }

        return classCompound;


    }

    //add a NodeShape to a shape model including all necessary properties
    public static Model addNodeShape(Model shapeModel, String nsURIprofile, String localName, String classFullURI){
        /*shapeModel - is the shape model
         * nsURIprofile - is the namespace of the NodeShape
         * localName - is the name of the NodeShape
         * classFullURI - is the full URI of the CIM class
         */

        //creates the resource
        Resource r = shapeModel.createResource(nsURIprofile + localName);
        //creates the property
        //Property p = shapeModel.createProperty(rdfURI, "type");
        //creates the object
        //RDFNode o = shapeModel.createResource(shaclURI + "NodeShape");
        //adds the property
        r.addProperty(RDF.type, SH.NodeShape);
        //up to here this defines e.g. eqbd:GeographicalRegion a sh:NodeShape ;

        //creates property sh:targetClass
        //Property p1 = shapeModel.createProperty(shaclURI, "targetClass");
        //creates the object which is the CIM class
        RDFNode o1 = shapeModel.createResource(classFullURI);
        r.addProperty(SH.targetClass, o1);
        //up to here this defines e.g. sh:targetClass cim:GeographicalRegion ;

        return shapeModel;
    }

    //add a NodeShape to a shape model including all necessary properties for Class check
    public static Model addNodeShapeProfileClass(Model shapeModel, String nsURIprofile, String localName, String classFullURI){
        /*shapeModel - is the shape model
         * nsURIprofile - is the namespace of the NodeShape
         * localName - is the name of the NodeShape
         * classFullURI - is the full URI of the CIM class
         */

        //creates the resource
        Resource r = shapeModel.createResource(nsURIprofile + localName);
        //creates the property
        //Property p = shapeModel.createProperty(rdfURI, "type");
        //creates the object
        //RDFNode o = shapeModel.createResource(shaclURI + "NodeShape");
        //adds the property
        r.addProperty(RDF.type, SH.NodeShape);
        //up to here this defines e.g. eqbd:GeographicalRegion a sh:NodeShape ;

        //creates property sh:targetClass
        //Property p1 = shapeModel.createProperty(shaclURI, "targetClass");
        //creates the object which is the CIM class
        RDFNode o1 = shapeModel.createResource(classFullURI);
        r.addProperty(SH.targetSubjectsOf, o1);
        //up to here this defines e.g. sh:targetClass cim:GeographicalRegion ;

        return shapeModel;
    }

    //add a NodeShape to a shape model including all necessary properties for Class Count check
    public static Model addNodeShapeProfileClassCount(Model shapeModel, String nsURIprofile, String localName, String classFullURI){
        /*shapeModel - is the shape model
         * nsURIprofile - is the namespace of the NodeShape
         * localName - is the name of the NodeShape
         * classFullURI - is the full URI of the CIM class
         */

        //creates the resource
        Resource r = shapeModel.createResource(nsURIprofile + localName);
        //creates the property
        //Property p = shapeModel.createProperty(rdfURI, "type");
        //creates the object
        //RDFNode o = shapeModel.createResource(shaclURI + "NodeShape");
        //adds the property
        r.addProperty(RDF.type, SH.NodeShape);
        //up to here this defines e.g. eqbd:GeographicalRegion a sh:NodeShape ;

        //creates property sh:targetClass
        //Property p1 = shapeModel.createProperty(shaclURI, "targetClass");
        //creates the object which is the CIM class
        RDFNode o1 = shapeModel.createResource(classFullURI);
        r.addProperty(SH.targetNode, o1);
        //up to here this defines e.g. sh:targetClass cim:GeographicalRegion ;

        return shapeModel;
    }

    //add a NodeShape to a shape model including all necessary properties
    public static Model addNodeShapeValueType(Model shapeModel, String nsURIprofile, String localName, String classFullURI){
        /*shapeModel - is the shape model
         * nsURIprofile - is the namespace of the NodeShape
         * localName - is the name of the NodeShape
         * classFullURI - is the full URI of the CIM class
         */
        String valueType;
        if (associationValueTypeOption==1) {
            valueType = "-valueTypeNodeShape";
        }else{
            valueType = "-valueType";
        }
        //creates the resource
        Resource r = shapeModel.createResource(nsURIprofile + localName+valueType);
        r.addProperty(RDF.type, SH.NodeShape);
        RDFNode o1 = shapeModel.createResource(classFullURI);
        r.addProperty(SH.targetClass, o1);


        return shapeModel;
    }

    //add a PropertyNode to a shape model including all necessary properties
    public static Model addPropertyNode(Model shapeModel, Resource nodeShapeResource, ArrayList<Object> propertyNodeFeatures,String nsURIprofile, String localName, String propertyFullURI){
         /*
         * propertyNodeFeatures structure
         * 0 - type of check: cardinality, datatype, associationValueType
         * 1 - message
         * 2 - name
         * 3 - description
         * 4 - severity
         * 5 - cardinality
         * 6 - the primitive either it is directly a primitive or it is the primitive of the .value attribute of a CIMdatatype
         * in case of enumeration 6 is set to Enumeration
         * in case of compound 6 is set to the compound class
         * 7 - is a list of uri of the enumeration attributes
         * 8 - order
         * 9 - group
          * 10 - - the list of concrete classes for association - the value type at the used end
          * 11 - classFullURI for the targetClass of the NodeShape
          * 12 - the uri of the compound class to be used in sh:class
         */
        String checkType = propertyNodeFeatures.getFirst().toString();
        String multiplicity ="";
        int lowerBound = 0;
        int upperBound =0;
        switch (checkType) {
            case "cardinality":
                String cardinality = propertyNodeFeatures.get(5).toString();
                if (cardinality.length() == 1) {
                    //need to have sh:minCount 1 ; and sh:maxCount 1 ;
                    multiplicity = "required";
                    lowerBound = 1;
                    upperBound = 1;
                    shapeModel = ShaclTools.addPropertyNodeCardinality(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localName, propertyFullURI, multiplicity, lowerBound, upperBound);

                } else if (cardinality.length() == 4) {
                    multiplicity = "seeBounds";
                    lowerBound = 0;
                    upperBound = 0;

                    if (Character.isDigit(cardinality.charAt(0))) {
                        lowerBound = Character.getNumericValue(cardinality.charAt(0));
                        //propertyNodeFeatures.set(1,"Cardinality violation. Lower bound shall be "+lowerBound);
                    }
                    if (Character.isDigit(cardinality.charAt(3))) {
                        upperBound = Character.getNumericValue(cardinality.charAt(3));
                        //propertyNodeFeatures.set(1,"Cardinality violation. Upper bound shall be "+upperBound);
                    } else {
                        upperBound = 999; // means that no upper bound is defined when we have upper bound "to many"
                    }
             /*   if (lowerBound!=1 && upperBound!=1) {//is they are the same 1..1 "Missing required association" is used
                    propertyNodeFeatures.set(1, "Cardinality violation. Cardinality shall be " + cardinality);
                }else if (lowerBound!=1 && upperBound!=1) {
                }else if (lowerBound!=1 && upperBound!=1) {
                }*/
                    if (lowerBound != 0 && upperBound != 999) { // covers 1..1 x..y excludes 0..n
                        if (lowerBound != 1 && upperBound != 1) {//is they are the same 1..1 "Missing required association" is used
                            propertyNodeFeatures.set(1, "Cardinality violation. Cardinality shall be " + cardinality);
                        }
                        shapeModel = ShaclTools.addPropertyNodeCardinality(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localName, propertyFullURI, multiplicity, lowerBound, upperBound);
                    } else if (lowerBound == 0 && upperBound != 999) {//need to cover 0..x
                        propertyNodeFeatures.set(1, "Cardinality violation. Upper bound shall be " + upperBound);
                        shapeModel = ShaclTools.addPropertyNodeCardinality(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localName, propertyFullURI, multiplicity, lowerBound, upperBound);
                    } else if (lowerBound != 0 && upperBound == 999) {//need to cover x..n
                        propertyNodeFeatures.set(1, "Cardinality violation. Lower bound shall be " + lowerBound);
                        shapeModel = ShaclTools.addPropertyNodeCardinality(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localName, propertyFullURI, multiplicity, lowerBound, upperBound);
                    }

                }
                break;
            case "datatype":
                //the if is checking if the property shape is existing, if it is existing a new one is not added, but it is
                //just linked with the nodeshape
                String nsURIprofileID = "";
                if (localName.contains("IdentifiedObject")) {
                    nsURIprofileID = MainController.prefs.get("IOuri", "");
                } else {
                    nsURIprofileID = nsURIprofile;
                }

                if (shapeModel.containsResource(shapeModel.getResource(nsURIprofileID + localName + "-datatype"))) {
                    //adding the reference in the NodeShape
                    //Property p = shapeModel.createProperty(shaclURI, "property");
                    //RDFNode o = shapeModel.createResource(r1); // this gives sh:property     [ a  eqbd:TestR ] ;
                    RDFNode o = shapeModel.createResource(nsURIprofileID + localName + "-datatype");
                    nodeShapeResource.addProperty(SH.property, o);
                } else {
                    //adding the reference in the NodeShape
                    //Property p = shapeModel.createProperty(shaclURI, "property");
                    //RDFNode o = shapeModel.createResource(r1); // this gives sh:property     [ a  eqbd:TestR ] ;
                    RDFNode o = shapeModel.createResource(nsURIprofileID + localName + "-datatype");
                    nodeShapeResource.addProperty(SH.property, o);

                    //adding the properties for the PropertyShape
                    Resource r = shapeModel.createResource(nsURIprofileID + localName + "-datatype");
                    //Property p1 = shapeModel.createProperty(rdfURI, "type");
                    //RDFNode o1 = shapeModel.createResource(shaclURI + "PropertyShape");
                    r.addProperty(RDF.type, SH.PropertyShape);

                    //adding the property for the sh:group
                    //Property p1g = shapeModel.createProperty(shaclURI, "group");
                    //creates the object which is the Group
                    RDFNode o1g = shapeModel.createResource(propertyNodeFeatures.get(9).toString());
                    if (localName.contains("IdentifiedObject")) {
                        o1g = shapeModel.createResource(MainController.prefs.get("IOuri", "") + "DatatypesGroupIO");
                    }
                    r.addProperty(SH.group, o1g);

                    //adding a property for the order
                    //Property p1o = shapeModel.createProperty(shaclURI, "order");
                    RDFNode o1o = shapeModel.createTypedLiteral(propertyNodeFeatures.get(8), "http://www.w3.org/2001/XMLSchema#integer");
                    if (localName.contains("IdentifiedObject")) {
                        o1o = shapeModel.createTypedLiteral("0.1", "http://www.w3.org/2001/XMLSchema#decimal");
                    }
                    r.addProperty(SH.order, o1o);

                    //adding a message
                    //Property p2 = shapeModel.createProperty(shaclURI, "message");
                    r.addProperty(SH.message, propertyNodeFeatures.get(1).toString());

                    if (propertyNodeFeatures.get(13).toString().isEmpty()) {
                        RDFNode o5 = shapeModel.createResource(propertyFullURI);
                        if (propertyNodeFeatures.get(6).toString().equals("Compound")) {
                            List<RDFNode> pathList = new ArrayList<>();
                            pathList.add(o5);
                            pathList.add(RDF.type.asResource());

                            RDFList pathRDFlist = shapeModel.createList(pathList.iterator());
                            r.addProperty(path, pathRDFlist);
                        } else {
                            //Property p5 = shapeModel.createProperty(shaclURI, "path");

                            r.addProperty(path, o5);
                        }


                    } else { // it enters here when it is  compound and the sh:path needs to be a list
                        //path for the case when the attribute is a compound
                        List<RDFNode> pathList = (List<RDFNode>) propertyNodeFeatures.get(13);
                        if (propertyNodeFeatures.get(6).toString().equals("Compound")) {
                            pathList.add(RDF.type.asResource());
                        }
                        RDFList pathRDFlist = shapeModel.createList(pathList.iterator());
                        r.addProperty(path, pathRDFlist);
                    }

                    //Property p6 = shapeModel.createProperty(shaclURI, "name");
                    r.addProperty(SH.name, propertyNodeFeatures.get(2).toString());

                    //Property p7 = shapeModel.createProperty(shaclURI, "description");
                    r.addProperty(SH.description, propertyNodeFeatures.get(3).toString());

                    //Property p8 = shapeModel.createProperty(shaclURI, "severity");
                    RDFNode o8 = shapeModel.createResource(SH.NS + propertyNodeFeatures.get(4).toString());
                    r.addProperty(SH.severity, o8);

                    //add specific properties for the datatype check
                    if (propertyNodeFeatures.get(6).toString().equals("Enumeration")) {
                        //this is the case where the datatype is enumeration
                        if (shapeModel.getProperty(shapeModel.getResource(nsURIprofile + localName + "-datatype"), SH.in) == null) {
                            //Property p9 = shapeModel.createProperty(shaclURI, "nodeKind");
                            //RDFNode o9 = shapeModel.createResource(shaclURI + "IRI");
                            r.addProperty(SH.nodeKind, SH.IRI);

                            //Property p10 = shapeModel.createProperty(shaclURI, "in");
                            List<RDFNode> enumAttributes = new ArrayList<>();
                            for (int en = 0; en < ((ArrayList<?>) propertyNodeFeatures.get(7)).size(); en++) {
                                enumAttributes.add(shapeModel.createResource(((ArrayList<?>) propertyNodeFeatures.get(7)).get(en).toString()));
                            }
                            RDFList enumRDFlist = shapeModel.createList(enumAttributes.iterator());
                            r.addProperty(SH.in, enumRDFlist);
                        }
                    } else if (propertyNodeFeatures.get(6).toString().equals("Compound")) {
                        //this is the case where the datatype is compound

                        r.addProperty(SH.nodeKind, SH.BlankNode);
                        RDFNode oC = shapeModel.createResource(propertyNodeFeatures.get(12).toString());
                        if (associationValueTypeOption == 1) {// this is the case when the checkbox "Use sh:in for association value type constraint instead of sh:class and sh:or" is selected
                            //adding sh:in
                            List<RDFNode> classIn = new ArrayList<>();
                            classIn.add(oC);

                            RDFList classInRDFlist = shapeModel.createList(classIn.iterator());
                            r.addProperty(SH.in, classInRDFlist);

                        } else {
                            r.addProperty(SH.class_, oC);
                        }

                    } else {
                        //this is the case where the datatype is a primitive or it is the primitive of the .value attribute of CIMDatatype
                        //Property p9 = shapeModel.createProperty(shaclURI, "nodeKind");
                        //RDFNode o9 = shapeModel.createResource(shaclURI + "Literal");
                        r.addProperty(SH.nodeKind, SH.Literal);

                        //Property p10 = shapeModel.createProperty(shaclURI, "datatype");
                        //the following are all primitives part of CIM17 domain package
                        RDFNode o10 = shapeModel.createResource();
                        if (propertyNodeFeatures.get(6).toString().equals("Integer")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDinteger.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("Float")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDfloat.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("String")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDstring.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("Boolean")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDboolean.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("Date")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDdate.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("DateTime")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDdateTime.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("DateTimeStamp")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDdateTimeStamp.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("Decimal")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDdecimal.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("Duration")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDduration.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("MonthDay")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDgMonthDay.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("Time")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDtime.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("URI")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDanyURI.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("IRI")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDanyURI.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("StringIRI")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDanyURI.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("StringFixedLanguage")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDstring.getURI());
                        } else if (propertyNodeFeatures.get(6).toString().equals("URL")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDanyURI.getURI());
                        }else if (propertyNodeFeatures.get(6).toString().equals("LangString")) {
                            o10 = shapeModel.createResource(RDFLangString.rdfLangString.getURI());
                        }else if (propertyNodeFeatures.get(6).toString().equals("Version")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDstring.getURI());
                        }else if (propertyNodeFeatures.get(6).toString().equals("UUID")) {
                            o10 = shapeModel.createResource(XSDDatatype.XSDstring.getURI());
                        }
                        r.addProperty(SH.datatype, o10);
                    }
                }
                break;
            case "associationValueType":
                if (((LinkedList<?>) propertyNodeFeatures.get(10)).size() == 1) {
                    //the if is checking if the property shape is existing, if it is existing a new one is not added, but it is
                    //just linked with the nodeshape
                    //if (localName.contains("DCLine")){
                    //    System.out.println(localName);
                    //}
//                    Resource r = null;
//                    RDFNode o9 = null;
//                    if (propertyNodeFeatures.get(2).toString().contains("AssessedElement.OverlappingZone")){
//                        int debug=2;
//                    }

                    if (associationValueTypeOption == 1) {// this is the case when the checkbox "Use sh:in for association value type constraint instead of sh:class and sh:or" is selected

                        if (propertyNodeFeatures.get(2).toString().endsWith("-nodeKind")){
                            if (shapeModel.containsResource(shapeModel.getResource(nsURIprofile + localName + "-nodeKind"))) {
                                //adding the reference in the NodeShape
                                RDFNode o = shapeModel.createResource(nsURIprofile + localName + "-nodeKind");
                                nodeShapeResource.addProperty(SH.property, o);
                            } else {
                                //adding the reference in the NodeShape
                                RDFNode o = shapeModel.createResource(nsURIprofile + localName + "-nodeKind");
                                nodeShapeResource.addProperty(SH.property, o);

                                //adding the properties for the PropertyShape
                                Resource r = shapeModel.createResource(nsURIprofile + localName + "-nodeKind");
                                r.addProperty(RDF.type, SH.PropertyShape);

                                //adding the property for the sh:group
                                //creates the object which is the Group
                                RDFNode o1g = shapeModel.createResource(propertyNodeFeatures.get(9).toString());
                                r.addProperty(SH.group, o1g);

                                //adding a property for the order
                                RDFNode o1o = shapeModel.createTypedLiteral(propertyNodeFeatures.get(8), "http://www.w3.org/2001/XMLSchema#integer");
                                r.addProperty(SH.order, o1o);

                                //adding name
                                r.addProperty(SH.name, propertyNodeFeatures.get(2).toString());

                                //adding description
                                r.addProperty(SH.description, propertyNodeFeatures.get(3).toString());

                                //adding severity
                                RDFNode o8 = shapeModel.createResource(SH.NS + propertyNodeFeatures.get(4).toString());
                                r.addProperty(SH.severity, o8);

                                //the 09 is the class - value type
                                RDFNode o9 = shapeModel.createResource(((LinkedList<?>) propertyNodeFeatures.get(10)).getFirst().toString());


                                r.addProperty(SH.message, "The node kind shall be IRI (rdf:resource is expected).");
                                RDFNode o5 = shapeModel.createResource(propertyFullURI);
                                r.addProperty(path, o5);

                                //adding sh:nodeKind
                                r.addProperty(SH.nodeKind, SH.IRI);
                            }
                        }else{
                            if (shapeModel.containsResource(shapeModel.getResource(nsURIprofile + localName + "-valueType"))) {
                                //adding the reference in the NodeShape
                                RDFNode o = shapeModel.createResource(nsURIprofile + localName + "-valueType");
                                nodeShapeResource.addProperty(SH.property, o);
                            } else {
                                //adding the reference in the NodeShape
                                RDFNode o = shapeModel.createResource(nsURIprofile + localName + "-valueType");
                                nodeShapeResource.addProperty(SH.property, o);

                                //adding the properties for the PropertyShape
                                Resource r = shapeModel.createResource(nsURIprofile + localName + "-valueType");
                                r.addProperty(RDF.type, SH.PropertyShape);

                                //adding the property for the sh:group
                                //creates the object which is the Group
                                RDFNode o1g = shapeModel.createResource(propertyNodeFeatures.get(9).toString());
                                r.addProperty(SH.group, o1g);

                                //adding a property for the order
                                RDFNode o1o = shapeModel.createTypedLiteral(propertyNodeFeatures.get(8), "http://www.w3.org/2001/XMLSchema#integer");
                                r.addProperty(SH.order, o1o);

                                //adding name
                                r.addProperty(SH.name, propertyNodeFeatures.get(2).toString());

                                //adding description
                                r.addProperty(SH.description, propertyNodeFeatures.get(3).toString());

                                //adding severity
                                RDFNode o8 = shapeModel.createResource(SH.NS + propertyNodeFeatures.get(4).toString());
                                r.addProperty(SH.severity, o8);

                                //the 09 is the class - value type
                                RDFNode o9 = shapeModel.createResource(((LinkedList<?>) propertyNodeFeatures.get(10)).getFirst().toString());

                                //adding a message
                                //r.addProperty(SH.message, propertyNodeFeatures.get(1).toString())
                                r.addProperty(SH.message, "One of the following does not conform: 1) The value shall be IRI; 2) The value shall be an instance of the class: "
                                        + shapeModel.getNsURIPrefix(o9.asResource().getNameSpace()) + ":" + o9.asResource().getLocalName());

                                if (associationValueTypeOptionSingle == 1 ){
                                    //adding path
                                    RDFNode o5path = shapeModel.createResource(propertyFullURI);
                                    r.addProperty(path, o5path);


                                    //adding the sh:class

                                    r.addProperty(SH.class_, o9);

                                }else {
                                    //adding path
                                    List<RDFNode> pathList = new ArrayList<>();
                                    pathList.add(shapeModel.createResource(propertyFullURI));
                                    pathList.add(RDF.type.asResource());

                                    RDFList pathRDFlist = shapeModel.createList(pathList.iterator());
                                    r.addProperty(path, pathRDFlist);
                                    //adding sh:in
                                    List<RDFNode> classIn = new ArrayList<>();
                                    classIn.add(o9);
                                    RDFList classInRDFlist = shapeModel.createList(classIn.iterator());
                                    r.addProperty(SH.in, classInRDFlist);
                                }

                                //adding sh:nodeKind
                                r.addProperty(SH.nodeKind, SH.IRI);
                            }

                        }

                    } else {
                        if (shapeModel.containsResource(shapeModel.getResource(nsURIprofile + localName + "-valueType"))) {
                            //adding the reference in the NodeShape
                            RDFNode o = shapeModel.createResource(nsURIprofile + localName + "-valueType");
                            nodeShapeResource.addProperty(SH.property, o);
                        } else {
                            //adding the reference in the NodeShape
                            RDFNode o = shapeModel.createResource(nsURIprofile + localName + "-valueType");
                            nodeShapeResource.addProperty(SH.property, o);

                            //adding the properties for the PropertyShape
                            Resource r = shapeModel.createResource(nsURIprofile + localName + "-valueType");
                            r.addProperty(RDF.type, SH.PropertyShape);

                            //adding the property for the sh:group
                            //creates the object which is the Group
                            RDFNode o1g = shapeModel.createResource(propertyNodeFeatures.get(9).toString());
                            r.addProperty(SH.group, o1g);

                            //adding a property for the order
                            RDFNode o1o = shapeModel.createTypedLiteral(propertyNodeFeatures.get(8), "http://www.w3.org/2001/XMLSchema#integer");
                            r.addProperty(SH.order, o1o);

                            //adding name
                            r.addProperty(SH.name, propertyNodeFeatures.get(2).toString());

                            //adding description
                            r.addProperty(SH.description, propertyNodeFeatures.get(3).toString());

                            //adding severity
                            RDFNode o8 = shapeModel.createResource(SH.NS + propertyNodeFeatures.get(4).toString());
                            r.addProperty(SH.severity, o8);

                            //the 09 is the class - value type
                            RDFNode o9 = shapeModel.createResource(((LinkedList<?>) propertyNodeFeatures.get(10)).getFirst().toString());

                            //adding path
                            RDFNode o5 = shapeModel.createResource(propertyFullURI);
                            r.addProperty(path, o5);

                            //adding the sh:class

                            r.addProperty(SH.class_, o9);

                            //adding a message
                            //r.addProperty(SH.message, propertyNodeFeatures.get(1).toString());
                            r.addProperty(SH.message, "One of the following does not conform: 1) The value shall be IRI; 2) The value shall be an instance of the class: "
                                    + shapeModel.getNsURIPrefix(o9.asResource().getNameSpace()) + ":" + o9.asResource().getLocalName());

                            //adding sh:nodeKind
                            r.addProperty(SH.nodeKind, SH.IRI);
                        }
                    }

                } else {
                    //in case there are multiple concrete classes that inherit the association
                    if (associationValueTypeOption == 1) {// this is the case when the checkbox "Use sh:in for association value type constraint instead of sh:class and sh:or" is selected

                        String classFullURI = propertyNodeFeatures.get(11).toString();

                        shapeModel = ShaclTools.addNodeShapeValueType(shapeModel, nsURIprofile, localName, classFullURI);


                        if (propertyNodeFeatures.get(2).toString().endsWith("-nodeKind")){
                            Resource nodeShapeResourceValueType = shapeModel.getResource(nsURIprofile + localName + "-valueTypeNodeShape");

                            if (shapeModel.containsResource(shapeModel.createResource(nsURIprofile + localName + "-nodeKind"))) {
                                //adding the reference in the NodeShape
                                RDFNode o = shapeModel.createResource(nsURIprofile + localName + "-nodeKind");
                                nodeShapeResourceValueType.addProperty(SH.property, o);
                            } else {
                                //adding the reference in the NodeShape
                                RDFNode o = shapeModel.createResource(nsURIprofile + localName + "-nodeKind");
                                nodeShapeResourceValueType.addProperty(SH.property, o);

                                //adding the properties for the PropertyShape
                                Resource r = shapeModel.createResource(nsURIprofile + localName + "-nodeKind");
                                r.addProperty(RDF.type, SH.PropertyShape);

                                //adding the property for the sh:group
                                //creates the object which is the Group
                                RDFNode o1g = shapeModel.createResource(propertyNodeFeatures.get(9).toString());
                                r.addProperty(SH.group, o1g);

                                //adding a property for the order
                                RDFNode o1o = shapeModel.createTypedLiteral(propertyNodeFeatures.get(8), "http://www.w3.org/2001/XMLSchema#integer");
                                r.addProperty(SH.order, o1o);

                                //adding name
                                r.addProperty(SH.name, propertyNodeFeatures.get(2).toString());

                                //adding description
                                r.addProperty(SH.description, propertyNodeFeatures.get(3).toString());

                                //adding severity
                                RDFNode o8 = shapeModel.createResource(SH.NS + propertyNodeFeatures.get(4).toString());
                                r.addProperty(SH.severity, o8);

                                //adding path
                                RDFNode o5 = shapeModel.createResource(propertyFullURI);
                                r.addProperty(path, o5);

                                //adding a message
                                //r.addProperty(SH.message, propertyNodeFeatures.get(1).toString());
                                r.addProperty(SH.message, "The node kind shall be IRI (rdf:resource is expected).");


                                //adding sh:nodeKind
                                r.addProperty(SH.nodeKind, SH.IRI);

                            }
                        }else{
                            Resource nodeShapeResourceValueType = shapeModel.getResource(nsURIprofile + localName + "-valueTypeNodeShape");

                            if (shapeModel.containsResource(shapeModel.createResource(nsURIprofile + localName + "-valueType"))) {
                                //adding the reference in the NodeShape
                                RDFNode o = shapeModel.createResource(nsURIprofile + localName + "-valueType");
                                nodeShapeResourceValueType.addProperty(SH.property, o);
                            } else {
                                //adding the reference in the NodeShape
                                RDFNode o = shapeModel.createResource(nsURIprofile + localName + "-valueType");
                                nodeShapeResourceValueType.addProperty(SH.property, o);

                                //adding the properties for the PropertyShape
                                Resource r = shapeModel.createResource(nsURIprofile + localName + "-valueType");
                                r.addProperty(RDF.type, SH.PropertyShape);

                                //adding the property for the sh:group
                                //creates the object which is the Group
                                RDFNode o1g = shapeModel.createResource(propertyNodeFeatures.get(9).toString());
                                r.addProperty(SH.group, o1g);

                                //adding a property for the order
                                RDFNode o1o = shapeModel.createTypedLiteral(propertyNodeFeatures.get(8), "http://www.w3.org/2001/XMLSchema#integer");
                                r.addProperty(SH.order, o1o);

                                //adding name
                                r.addProperty(SH.name, propertyNodeFeatures.get(2).toString());

                                //adding description
                                r.addProperty(SH.description, propertyNodeFeatures.get(3).toString());

                                //adding severity
                                RDFNode o8 = shapeModel.createResource(SH.NS + propertyNodeFeatures.get(4).toString());
                                r.addProperty(SH.severity, o8);

                                //adding path
                                List<RDFNode> pathList = new ArrayList<>();
                                pathList.add(shapeModel.createResource(propertyFullURI));
                                pathList.add(RDF.type.asResource());

                                RDFList pathRDFlist = shapeModel.createList(pathList.iterator());
                                r.addProperty(path, pathRDFlist);

                                //adding a message
                                //r.addProperty(SH.message, propertyNodeFeatures.get(1).toString());
                                r.addProperty(SH.message, "One of the following occurs: 1) The value is not IRI; 2) The value is not the right class.");

                                //adding sh:in
                                List<RDFNode> classNames = new ArrayList<>();
                                for (int item = 0; item < ((LinkedList<?>) propertyNodeFeatures.get(10)).size(); item++) {
                                    classNames.add(shapeModel.createResource(((LinkedList<?>) propertyNodeFeatures.get(10)).get(item).toString()));
                                }

                                RDFList classInRDFlist = shapeModel.createList(classNames.iterator());
                                r.addProperty(SH.in, classInRDFlist);


                                //adding sh:nodeKind
                                r.addProperty(SH.nodeKind, SH.IRI);
                            }
                        }


                    } else { // in case the option "Use sh:in for association value type constraint instead of sh:class and sh:or" is not selected
                        List<RDFNode> classNames = new ArrayList<>();
                        for (int item = 0; item < ((LinkedList<?>) propertyNodeFeatures.get(10)).size(); item++) {
                            Resource classNameRes = (Resource) ((LinkedList<?>) propertyNodeFeatures.get(10)).get(item);
                            if (shapeModel.containsResource(shapeModel.getResource(nsURIprofile + localName + classNameRes.getLocalName() + "-valueType"))) {
                                classNames.add(shapeModel.createResource(nsURIprofile + localName + classNameRes.getLocalName() + "-valueType")); // adds to the list to be used for sh:or
                            } else {
                                classNames.add(shapeModel.createResource(nsURIprofile + localName + classNameRes.getLocalName() + "-valueType")); // adds to the list to be used for sh:or
                                //adding the properties for the PropertyShape
                                Resource r = shapeModel.createResource(nsURIprofile + localName + classNameRes.getLocalName() + "-valueType");
                                r.addProperty(RDF.type, SH.PropertyShape);

                                //adding the property for the sh:group
                                //creates the object which is the Group
                                RDFNode o1g = shapeModel.createResource(propertyNodeFeatures.get(9).toString());
                                r.addProperty(SH.group, o1g);

                                //adding a property for the order
                                RDFNode o1o = shapeModel.createTypedLiteral(propertyNodeFeatures.get(8), "http://www.w3.org/2001/XMLSchema#integer");
                                r.addProperty(SH.order, o1o);

                                //adding path
                                RDFNode o5 = shapeModel.createResource(propertyFullURI);
                                r.addProperty(path, o5);

                                //adding name
                                r.addProperty(SH.name, localName + classNameRes.getLocalName() + "-valueType");//propertyNodeFeatures.get(2).toString()

                                //adding description
                                r.addProperty(SH.description, propertyNodeFeatures.get(3).toString());

                                //adding severity
                                RDFNode o8 = shapeModel.createResource(SH.NS + propertyNodeFeatures.get(4).toString());
                                r.addProperty(SH.severity, o8);

                                //adding the sh:class
                                RDFNode o9 = shapeModel.createResource(((LinkedList<?>) propertyNodeFeatures.get(10)).get(item).toString());
                                r.addProperty(SH.class_, o9);

                                //adding sh:nodeKind
                                r.addProperty(SH.nodeKind, SH.IRI);

                                //adding a message
                                //r.addProperty(SH.message, propertyNodeFeatures.get(1).toString());
                                r.addProperty(SH.message, "One of the following does not conform: 1) The value shall be IRI; 2) The value shall be an instance of the class: "
                                        + shapeModel.getNsURIPrefix(o9.asResource().getNameSpace()) + ":" + o9.asResource().getLocalName());

                            }
                        }

                        String classFullURI = propertyNodeFeatures.get(11).toString();

                        shapeModel = ShaclTools.addNodeShapeValueType(shapeModel, nsURIprofile, localName, classFullURI);
                        //RDFList orRDFlist = shapeModel.createList(classNames.iterator());
                        Resource nodeShapeResourceOr = shapeModel.getResource(nsURIprofile + localName + "-valueType");
                        if (!shapeModel.getResource(nodeShapeResourceOr.toString()).hasProperty(SH.or)) { // creates sh:or only if it is missing
                            RDFList orRDFlist = shapeModel.createList(classNames.iterator());
                            nodeShapeResourceOr.addProperty(SH.or, orRDFlist);
                        }
                    }
                }
                break;
        }

        return shapeModel;
    }

    public static Model addPropertyNodeCardinality(Model shapeModel, Resource nodeShapeResource, ArrayList<Object> propertyNodeFeatures,String nsURIprofile, String localName, String propertyFullURI, String multiplicity, Integer lowerBound, Integer upperBound) {
        /*
         * propertyNodeFeatures structure
         * 0 - type of check: cardinality, datatype
         * 1 - message
         * 2 - name
         * 3 - description
         * 4 - severity
         * 5 - cardinality
         * 6 - the primitive either it is directly a primitive or it is the primitive of the .value attribute of a CIMdatatype
         * in case of enumeration 6 is set to Enumeration
         * 7 - is a list of uri of the enumeration attributes
         * 8 - order
         * 9 - group
         */
        //The if here is to ensure that all property shapes for IdentifiedObjects are in one namespace
        if (localName.contains("IdentifiedObject")){
            nsURIprofile=MainController.prefs.get("IOuri","");
        }

        //the if is checking if the property shape is existing, if it is existing a new one is not added, but it is
        //just linked with the nodeshape
        if (shapeModel.containsResource(shapeModel.getResource(nsURIprofile+localName+"-cardinality"))) {
            //adding the reference in the NodeShape
            //Property p = shapeModel.createProperty(shaclURI, "property");
            //RDFNode o = shapeModel.createResource(r1); // this gives sh:property     [ a  eqbd:TestR ] ;
            RDFNode o = shapeModel.createResource(nsURIprofile + localName+"-cardinality");
            nodeShapeResource.addProperty(SH.property, o);

        } else {
            //adding the reference in the NodeShape
            //Property p = shapeModel.createProperty(shaclURI, "property");
            //RDFNode o = shapeModel.createResource(r1); // this gives sh:property     [ a  eqbd:TestR ] ;
            RDFNode o = shapeModel.createResource(nsURIprofile + localName+"-cardinality");
            nodeShapeResource.addProperty(SH.property, o);

            //adding the properties for the PropertyShape
            Resource r = shapeModel.createResource(nsURIprofile + localName + "-cardinality");
            //Property p1 = shapeModel.createProperty(rdfURI, "type");
            //RDFNode o1 = shapeModel.createResource(shaclURI + "PropertyShape");
            r.addProperty(RDF.type, SH.PropertyShape);

            //adding the property for the sh:group
            //Property p1g = shapeModel.createProperty(shaclURI, "group");
            //creates the object which is the Group
            RDFNode o1g = shapeModel.createResource(propertyNodeFeatures.get(9).toString());
            if (localName.contains("IdentifiedObject")){
                o1g = shapeModel.createResource(MainController.prefs.get("IOuri","")+"CardinalityIO");
            }
            r.addProperty(SH.group, o1g);

            //adding a property for the order
            //Property p1o = shapeModel.createProperty(shaclURI, "order");
            RDFNode o1o = shapeModel.createTypedLiteral(propertyNodeFeatures.get(8), "http://www.w3.org/2001/XMLSchema#integer");
            if (localName.contains("IdentifiedObject")){
                o1o = shapeModel.createTypedLiteral("0.1", "http://www.w3.org/2001/XMLSchema#decimal");
            }
            r.addProperty(SH.order, o1o);

            if (multiplicity.equals("required")) {
                //Property p3 = shapeModel.createProperty(shaclURI, "minCount");
                RDFNode o3 = shapeModel.createTypedLiteral(1, "http://www.w3.org/2001/XMLSchema#integer");
                r.addProperty(SH.minCount, o3);
                //Property p4 = shapeModel.createProperty(shaclURI, "maxCount");
                RDFNode o4 = shapeModel.createTypedLiteral(1, "http://www.w3.org/2001/XMLSchema#integer");
                r.addProperty(SH.maxCount, o4);
            } else if (multiplicity.equals("seeBounds")) {
                if (lowerBound < 999 && lowerBound != 0) {
                    //Property p3 = shapeModel.createProperty(shaclURI, "minCount");
                    RDFNode o3 = shapeModel.createTypedLiteral(lowerBound, "http://www.w3.org/2001/XMLSchema#integer");
                    r.addProperty(SH.minCount, o3);
                }
                if (upperBound < 999 && upperBound != 0) {
                    //Property p4 = shapeModel.createProperty(shaclURI, "maxCount");
                    RDFNode o4 = shapeModel.createTypedLiteral(upperBound, "http://www.w3.org/2001/XMLSchema#integer");
                    r.addProperty(SH.maxCount, o4);
                }
            }

            //adding a message
            //Property p2 = shapeModel.createProperty(shaclURI, "message");
            r.addProperty(SH.message, propertyNodeFeatures.get(1).toString());

            if (propertyNodeFeatures.get(13).toString().equals("") && !propertyNodeFeatures.get(11).toString().equals("Inverse")) {
                //Property p5 = shapeModel.createProperty(shaclURI, "path");
                RDFNode o5 = shapeModel.createResource(propertyFullURI);
                r.addProperty(path, o5);
            }else if (!propertyNodeFeatures.get(13).toString().equals("")) { // it enters here when it is  compound and the sh:path needs to be a list
                //path for the case when the attribute is a compound
                List<RDFNode> pathList = (List<RDFNode>) propertyNodeFeatures.get(13);
                RDFList pathRDFlist = shapeModel.createList(pathList.iterator());
                r.addProperty(path, pathRDFlist);
                //Resource nodeShapeResourcePath = shapeModel.getResource(nsURIprofile + localName+"Cardinality");
                //if (!shapeModel.getResource(nodeShapeResourcePath.toString()).hasProperty(SH.path)) { // creates sh:path only if it is missing
                //    nodeShapeResourcePath.addProperty(SH.path, pathRDFlist);
                //}
            }else if (propertyNodeFeatures.get(11).toString().equals("Inverse")){
                Set<String> propSet = (Set<String>) propertyNodeFeatures.get(10);
                if (propSet.size() == 1){
                    Resource resbn = ResourceFactory.createResource();
                    for (String s : propSet) {
                        Statement stmtbn = ResourceFactory.createStatement(resbn, SH.inversePath, ResourceFactory.createProperty(s));
                        r.addProperty(SH.path, asProperty(resbn));
                        shapeModel.add(stmtbn);
                    }
                }else{
                //sh:path         [sh:alternativePath ([sh:inversePath  cim:ContingencyElement.Contingency] [sh:inversePath  cim17:ContingencyElement.Contingency])] ;
                    Resource resbnAP = ResourceFactory.createResource();
                    List<Property> invPathList = new LinkedList<>();
                    for (String s : propSet) {
                        Resource resbn = ResourceFactory.createResource();
                        Statement stmtbn = ResourceFactory.createStatement(resbn, SH.inversePath, ResourceFactory.createProperty(s));
                        invPathList.add(asProperty(resbn));
                        shapeModel.add(stmtbn);

                    }
                    RDFList classIPRDFlist = shapeModel.createList(invPathList.iterator());
                    Statement stmtbnAP = ResourceFactory.createStatement(resbnAP, SH.alternativePath, classIPRDFlist);
                    r.addProperty(SH.path, asProperty(resbnAP));
                    shapeModel.add(stmtbnAP);
                }

//                if (baseprofilesshaclglag == 0) {
//                    Resource resbn = ResourceFactory.createResource();
//                    Statement stmtbn = ResourceFactory.createStatement(resbn, SH.inversePath, ResourceFactory.createProperty(propertyNodeFeatures.get(10).toString()));
//                    //r.addProperty(SH.path, JenaUtil.asProperty(resbn));
//                    r.addProperty(SH.path, asProperty(resbn));
//                    shapeModel.add(stmtbn);
//                }else if (baseprofilesshaclglag == 1){
//                    Resource resbn = ResourceFactory.createResource();
//                    Property classinverseFull = ResourceFactory.createProperty(propertyNodeFeatures.get(10).toString());
//
//                    Statement stmtbn = ResourceFactory.createStatement(resbn, SH.inversePath, ResourceFactory.createProperty(propertyNodeFeatures.get(10).toString()));
//                    //r.addProperty(SH.path, JenaUtil.asProperty(resbn));
//                    r.addProperty(SH.path, asProperty(resbn));
//                    shapeModel.add(stmtbn);
//                }
            }

            //Property p6 = shapeModel.createProperty(shaclURI, "name");
            r.addProperty(SH.name, propertyNodeFeatures.get(2).toString());

            //Property p7 = shapeModel.createProperty(shaclURI, "description");
            r.addProperty(SH.description, propertyNodeFeatures.get(3).toString());

            //Property p8 = shapeModel.createProperty(shaclURI, "severity");
            RDFNode o8 = shapeModel.createResource(SH.NS + propertyNodeFeatures.get(4).toString());
            r.addProperty(SH.severity, o8);
        }
        return shapeModel;
    }

    public static Property asProperty(Resource resource) {
        return (Property)(resource instanceof Property ? (Property)resource : new PropertyImpl(resource.asNode(), (EnhGraph)resource.getModel()));
    }

    //add a PropertyGroup to a shape model including all necessary properties
    public static Model addPropertyGroup(Model shapeModel, String nsURIprofile, String localName, ArrayList<Object> groupFeatures){
        /*shapeModel - is the shape model
         * nsURIprofile - is the namespace of the PropertyGroup
         * localName - is the name of the PropertyGroup
         * rdfURI - is the URI of the rdf
         * shaclURI - is the URI of the shacl
         * groupFeatures / is a structure with the details
         * rdfsURI - is the URI of the rdfs
         */
        /*
         * groupFeatures structure
         * 0 - name
         * 1 - description
         * 2 - the value for rdfs:label
         * 3 - order
         */

        //creates the resource
        Resource r = shapeModel.createResource(nsURIprofile + localName);
        //creates the property
        //Property p = shapeModel.createProperty(rdfURI, "type");
        //creates the object
        //RDFNode o = shapeModel.createResource(shaclURI + "PropertyGroup");
        //adds the property
        r.addProperty(RDF.type, SH.PropertyGroup);
        //up to here this defines e.g. eqbd:MyGroup a sh:PropertyGroup ;

       //Property p1 = shapeModel.createProperty(shaclURI, "order");
        RDFNode o1 = shapeModel.createTypedLiteral(groupFeatures.get(3), "http://www.w3.org/2001/XMLSchema#integer");
        r.addProperty(SH.order, o1);

        //Property p2 = shapeModel.createProperty(shaclURI, "name");
        //r.addProperty(SH.name, groupFeatures.get(0).toString()); // this is ok if the group needs to be a name

        //Property p3 = shapeModel.createProperty(shaclURI, "description");
        //r.addProperty(SH.description, groupFeatures.get(1).toString()); // this is ok if the group needs to be a description

        //Property p4 = shapeModel.createProperty(rdfsURI, "label");
        //creates the object which is the CIM class
        r.addProperty(RDFS.label, groupFeatures.get(2).toString());

        //RDFDataMgr.write(System.out, shapeModel, RDFFormat.TURTLE);
        return shapeModel;
    }






    //open the ChoiceDialog for the save file and save the file in different formats
    public static File saveShapesFile(Model shapeModel, String baseURI, int dirOnly, String title) throws IOException {
        File savedFile = null;
        //String[] choiceDialogItems = {"TURTLE", "RDFXML", "RDFXML_PLAIN", "RDFXML_ABBREV" ,"NTRIPLES",
        //        "JSONLD", "N3", "RDFJSON"};
        String[] choiceDialogItems = {"TURTLE", "RDFXML"};
        ChoiceDialog choiceDialog = new ChoiceDialog("TURTLE", Arrays.asList(choiceDialogItems));//Alert(Alert.AlertType.ERROR);
        choiceDialog.setContentText("Please select the type of the file.");
        choiceDialog.setHeaderText("Do you want to save the SHACL model as file?");
        choiceDialog.setTitle("Save SHACL file");
        choiceDialog.showAndWait();


        if (choiceDialog.resultProperty().getValue() != null) { //if OK button is selected
            savedFile = switch (choiceDialog.resultProperty().getValue().toString()) {
                case "TURTLE" -> fileSave(shapeModel, "TTL files", "*.ttl", RDFFormat.TURTLE, baseURI, dirOnly, title);
                case "RDFXML" -> fileSave(shapeModel, "XML files", "*.xml", RDFFormat.RDFXML, baseURI, dirOnly, title);
                case "RDFXML_PLAIN" ->
                        fileSave(shapeModel, "XML files", "*.xml", RDFFormat.RDFXML_PLAIN, baseURI, dirOnly, title);
                case "RDFXML_ABBREV" ->
                        fileSave(shapeModel, "XML files", "*.xml", RDFFormat.RDFXML_ABBREV, baseURI, dirOnly, title);
                case "NTRIPLES" ->
                        fileSave(shapeModel, "N3 files", "*.nt", RDFFormat.NTRIPLES, baseURI, dirOnly, title);
                case "JSONLD" ->
                        fileSave(shapeModel, "JSON-LD files", "*.jsonld", RDFFormat.JSONLD, baseURI, dirOnly, title);
                case "N3" -> fileSave(shapeModel, "N3 files", "*.nt", RDFFormat.NT, baseURI, dirOnly, title);
                case "RDFJSON" ->
                        fileSave(shapeModel, "RDF JSON files", "*.rj", RDFFormat.RDFJSON, baseURI, dirOnly, title);
                default -> savedFile;
            };
        }

        return savedFile;
    }

    //file save in various formats
    public static File fileSave(Model model, String extensionText, String extension, RDFFormat rdfFormat, String baseURI, int dirOnly, String title) throws IOException {
        //export to ttl
        File saveFile=null;
        File selectedDirectory;
        if (dirOnly==0) {// this is the normal save when the user selects the file
//            FileChooser filechooserS = new FileChooser();
//            filechooserS.getExtensionFilters().addAll(new FileChooser.ExtensionFilter(extensionText, extension));
//            filechooserS.setInitialFileName(title.split(": ",2)[1]);
//            filechooserS.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder","")));
//            filechooserS.setTitle(title);
//            saveFile = filechooserS.showSaveDialog(null);
            saveFile = eu.griddigit.cimpal.util.ModelFactory.fileSaveCustom(extensionText, List.of(extension),title,title.split(": ",2)[1]);
            if (saveFile != null) {
                //MainController.prefs.put("LastWorkingFolder", saveFile.getParent());
                OutputStream out = new FileOutputStream(saveFile);
                try {
                    //model.write(out, rdfFormat.getLang().getLabel().toUpperCase(),baseURI);//String.valueOf(rdfFormat)

                    if (rdfFormat.getLang().getLabel().equalsIgnoreCase("RDF/XML")) {
                        //Update - test the writer
                        //baseURI = "http://iec.ch/TC57/61970-600/CoreEquipment-European/3/0/cgmes/shapes";

                        Map<String, Object> properties = new HashMap<>();
                        properties.put("showXmlDeclaration", "true");
                        properties.put("showDoctypeDeclaration", "false");
                        //properties.put("blockRules", RDFSyntax.propertyAttr.toString()); //???? not sure
                        properties.put("xmlbase", baseURI);
                        properties.put("tab", "2");
                        properties.put("relativeURIs", "same-document");
                        properties.put("prettyTypes", new Resource[]{OWL2.Ontology});
                        //properties.put("prettyTypes", new Resource[]{ResourceFactory.createResource(headerClassResource)});


                        // Put a properties object into the Context.
                        Context cxt = new Context();
                        cxt.set(SysRIOT.sysRdfWriterProperties, properties);

                        org.apache.jena.riot.RDFWriter.create()
                                .base(baseURI)
                                .format(RDFFormat.RDFXML_PRETTY) //.RDFXML_PLAIN
                                .context(cxt)
                                .source(model)
                                .output(out);

                    }else if (rdfFormat.getLang().getLabel().equalsIgnoreCase("TURTLE")) {
                        RDFWriter.create()
                                .base(baseURI)
                                .set(RIOT.symTurtleOmitBase, false)
                                .set(RIOT.symTurtleIndentStyle, "wide")
                                .set(RIOT.symTurtleDirectiveStyle, "rdf10")
                                .set(RIOT.multilineLiterals, true)
                                .lang(Lang.TURTLE)
                                .source(model)
                                .output(out);
                    }else{
                        model.write(out, rdfFormat.getLang().getLabel().toUpperCase(),baseURI);
                        //model.write(out, RDFFormat.TURTLE.getLang().getLabel().toUpperCase(), baseURI);
                        //RDFDataMgr.write(out, model, RDFFormat.JSONLD_PRETTY);

                        /*baseURI = "http://iec.ch/TC57/61970-600/CoreEquipment-European/3/0/cgmes/shapes";
                        //model.write(out, rdfFormat.getLang().getLabel().toUpperCase(),baseURI);//String.valueOf(rdfFormat)

                        Map<String, Object> properties = new HashMap<>();
                        properties.put("showXmlDeclaration", "true");
                        properties.put("showDoctypeDeclaration", "false");
                        //properties.put("blockRules", RDFSyntax.propertyAttr.toString()); //???? not sure
                        properties.put("xmlbase", baseURI);
                        properties.put("tab", "2");
                        properties.put("relativeURIs", "same-document");


                        // Put a properties object into the Context.
                        Context cxt = new Context();
                        cxt.set(SysRIOT.sysRdfWriterProperties, properties);

                        org.apache.jena.riot.RDFWriter.create()
                                .base(baseURI)
                                .format(RDFFormat.JSONLD)
                                .context(cxt)
                                .source(model)
                                .output(out);*/

                    }


                } finally {
                    out.close();
                }
            }

        }else { // this is where the user only selects the folder and the files are saved there
            DirectoryChooser directoryChooser = new DirectoryChooser();
            directoryChooser.setTitle(title);
            selectedDirectory = directoryChooser.showDialog(null);


            for (int mod = 0; mod < MainController.shapeModels.size(); mod++){
                saveFile = new File(selectedDirectory.toString() + "\\" + ((ArrayList) MainController.shapeModelsNames.get(mod)).get(0).toString() + extension.replace("*", ""));
                Model modelToWrite = (Model) MainController.shapeModels.get(mod);
                baseURI = ((ArrayList) MainController.shapeModelsNames.get(mod)).get(2).toString();
                OutputStream out = new FileOutputStream(saveFile);
                try {
                    modelToWrite.write(out, rdfFormat.getLang().getLabel().toUpperCase(),baseURI);//String.valueOf(rdfFormat)
                } finally {
                    out.close();
                }
            }
        }
        return saveFile;
    }

    //add owl:imports
    public static Model addOWLimports(Model shapeModel, String baseURI, String owlImports){
        if (!owlImports.equals("") && !owlImports.isEmpty()) {
            String[] listImports = owlImports.split(",");
            for (int i = 0; i < listImports.length; i++) {
                //creates the resource
                Resource r = shapeModel.createResource(baseURI);
                //creates the object
                RDFNode o = shapeModel.createResource(listImports[i].replace(" ", ""));
                //adds the property
                r.addProperty(OWL2.imports, o);
            }
        }
        return shapeModel;

        //TODO: below is important to be used before model validation - to be put in another method
        //this below seems actually for the combining models and not to write the owl:imports
        //shapeModel = SHACLUtil.createIncludesModel((Model) shapeModel,fowlImportsDefineTab.getText());
        //shapeModels.set(m,shapeModel);
    }

    //add header to SHACL
    public static Model addSHACLheader(Model shapeModel, String baseURI){

        Resource shaclHeaderRes = ResourceFactory.createResource(baseURI+"#Ontology");
        shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes,RDF.type,OWL2.Ontology));

        for (Statement stmt : rdfsHeaderStatements) {
            if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#title")) || stmt.getPredicate().equals(DCTerms.title)) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, DCTerms.title, stmt.getObject()));
            } else if (stmt.getPredicate().equals(OWL2.versionIRI)) {
                int len = Arrays.asList(stmt.getObject().toString().split("/")).size();
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, stmt.getPredicate(), ResourceFactory.createProperty(baseURI+"/"+ Arrays.asList(stmt.getObject().toString().split("/")).get(len-1))));
            } else if (stmt.getPredicate().equals(DCAT.keyword)) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, stmt.getPredicate(), stmt.getObject()));
            } else if (stmt.getPredicate().equals(DCAT.theme)) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, stmt.getPredicate(), ResourceFactory.createLangLiteral("constraint","en")));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#license")) || stmt.getPredicate().equals(DCTerms.license)) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, DCTerms.license, stmt.getObject()));
            } else if (stmt.getPredicate().equals(OWL2.versionInfo)) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, stmt.getPredicate(), stmt.getObject()));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#rightsHolder")) || stmt.getPredicate().equals(DCTerms.rightsHolder)) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, DCTerms.rightsHolder, stmt.getObject()));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#conformsTo")) || stmt.getPredicate().equals(DCTerms.conformsTo)) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, DCTerms.conformsTo, stmt.getObject()));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#description")) || stmt.getPredicate().equals(DCTerms.description)) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, DCTerms.description, ResourceFactory.createPlainLiteral("Describing constraints extracted from RDFS.")));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#modified")) || stmt.getPredicate().equals(DCTerms.modified)) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, DCTerms.modified, stmt.getObject()));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#language")) || stmt.getPredicate().equals(DCTerms.language)) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, DCTerms.language, stmt.getObject()));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#publisher")) || stmt.getPredicate().equals(DCTerms.publisher)) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, DCTerms.publisher, stmt.getObject()));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#identifier")) || stmt.getPredicate().equals(DCTerms.identifier)) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, DCTerms.identifier, ResourceFactory.createPlainLiteral("urn:uuid" + UUID.randomUUID())));
            }
        }
            shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, DCTerms.issued, ResourceFactory.createTypedLiteral(String.valueOf(java.time.LocalDateTime.now()),XSDDatatype.XSDdateTime)));
        return shapeModel;
    }
    //get owl:imports
    public static String getOWLimports(Model shapeModel, String baseURI){
        String owlImports="";
        if (shapeModel.contains(shapeModel.getResource(baseURI),OWL2.imports)){
            int count=0;
            for (NodeIterator res=shapeModel.listObjectsOfProperty(shapeModel.getResource(baseURI),OWL2.imports);res.hasNext();){
                if (count==0) {
                    owlImports=res.next().toString();
                }else {
                    owlImports=owlImports+", "+res.next().toString();
                }
                count++;
            }
        }
        return owlImports;
    }


    // lists all statements related to a shape
    public static List<Statement> listShapeStatements(Model shapeModel, Resource resItem, int includeResItemStmt){
        List<Statement> result = new LinkedList<>();

        //find out what it is NodeShape, PropertyGroup, PropertyShape or SPARQLConstraint
        RDFNode resItemType = null;
        for (NodeIterator i=shapeModel.listObjectsOfProperty(resItem,RDF.type); i.hasNext();){
            resItemType = i.next();
        }
        Statement askedStmt=shapeModel.listStatements(resItem,RDF.type, resItemType).next();

        if (includeResItemStmt==1){ // it will add the statement of the main shape/class to the list
            result.add(askedStmt);
        }

        //get all statements for the shape - resource
        for (StmtIterator si = resItem.listProperties(); si.hasNext();){
            Statement stmtItem = si.next();
            result.add(stmtItem);
        }
        return result;
    }

    // print simple validation report
    public static void printSHACLreport(ValidationReport report){

        Model reportModel = report.getModel();

        // Query for validation results in the report
        //Property resultPredicate = SH.result;
        ResIterator results = reportModel.listResourcesWithProperty(RDF.type, SHACL.ValidationResult);

        while (results.hasNext()) {
            Resource result = results.next();

            // Get focus node
            Resource focusNode = result.getPropertyResourceValue(SH.focusNode);
            System.out.println("Focus Node: " + (focusNode != null ? focusNode.toString() : "None"));

            // Get severity
            Resource severity = result.getPropertyResourceValue(SH.resultSeverity);
            System.out.println("Severity: " + (severity != null ? severity.getLocalName() : "None"));

            // Get message
            Statement messageStmt = result.getProperty(SH.resultMessage);
            if (messageStmt != null) {
                System.out.println("Message: " + messageStmt.getString());
            }

            // Get value causing the issue
            Statement valueStmt = result.getProperty(SH.value);
            if (valueStmt != null) {
                System.out.println("Value: " + valueStmt.getObject().toString());
            }

            // Get path (if available)
            Statement pathStmt = result.getProperty(SH.resultPath);
            if (pathStmt != null) {
                System.out.println("Path: " + pathStmt.getObject().toString());
            }

            System.out.println("----------\n");
        }
    }

    public static List<SHACLValidationResult> extractSHACLValidationResults(ValidationReport report) {
        List<SHACLValidationResult> resultsList = new ArrayList<>();
        Model reportModel = report.getModel();

        ResIterator results = reportModel.listResourcesWithProperty(RDF.type, SH.ValidationResult);

        while (results.hasNext()) {
            Resource result = results.next();

            String focusNode = getResourceValue(result.getPropertyResourceValue(SH.focusNode));
            String severity = getResourceValue(result.getPropertyResourceValue(SH.resultSeverity));
            String message = getStatementValue(result.getProperty(SH.resultMessage));
            String value = getStatementValue(result.getProperty(SH.value));
            String path = getStatementValue(result.getProperty(SH.resultPath));

            resultsList.add(new SHACLValidationResult(focusNode, severity, message, value, path));
        }

        return resultsList;
    }

    private static String getResourceValue(Resource resource) {
        return (resource != null) ? resource.toString() : "None";
    }

    private static String getStatementValue(Statement statement) {
        return (statement != null) ? statement.getObject().toString() : "None";
    }


    //This creates a shape model from a profile
    public static Model createShapesModelFromProfile(Model model, String nsPrefixprofile, String nsURIprofile, ArrayList<?> shapeData){

        //initial setup of the shape model
        //creates shape model. This is per profile. shapeModels is for all profiles
        //Model shapeModel = JenaUtil.createDefaultModel();
        Model shapeModel = ModelFactory.createModelForGraph(GraphMemFactory.createDefaultGraph());
        shapeModel.setNsPrefix("rdf", RDF.getURI());
        shapeModel.setNsPrefix("rdfs", RDFS.getURI());
        shapeModel.setNsPrefix("owl", OWL.getURI());
        shapeModel.setNsPrefix("xsd", XSD.getURI());
        shapeModel.setNsPrefix("dcat", DCAT.getURI());
        shapeModel.setNsPrefix("dcterms", DCTerms.getURI());
        shapeModel.setNsPrefix("md", "http://iec.ch/TC57/61970-552/ModelDescription/1#");
        shapeModel.setNsPrefix("dm", "http://iec.ch/TC57/61970-552/DifferenceModel/1#");
        shapeModel.setNsPrefixes(model.getNsPrefixMap());
        if (baseprofilesshaclglag==1) {
            shapeModel.setNsPrefixes(unionmodelbaseprofilesshacl.getNsPrefixMap());
        }
        //add the namespace of the profile
        shapeModel.setNsPrefix(nsPrefixprofile, nsURIprofile);


        //add the additional two namespaces
        shapeModel.setNsPrefix("sh", SH.NS);
        shapeModel.setNsPrefix("dash", DASH.NS);

        shapeModel.setNsPrefix(prefs.get("IOprefix",""), prefs.get("IOuri","")); // the uri for the identified object related shapes

        //adding the two PropertyGroup-s
        String localNameGroup = "CardinalityGroup";
        ArrayList<Object> groupFeatures = new ArrayList<>();
        /*
         * groupFeatures structure
         * 0 - name
         * 1 - description
         * 2 - the value for rdfs:label
         * 3 - order
         */
        for (int i = 0; i < 4; i++) {
            groupFeatures.add("");
        }
        groupFeatures.set(0, "Cardinality");
        groupFeatures.set(1, "This group of validation rules relate to cardinality validation of properties (attributes and associations).");
        groupFeatures.set(2, "Cardinality");
        groupFeatures.set(3, 0);

        shapeModel = ShaclTools.addPropertyGroup(shapeModel, nsURIprofile, localNameGroup, groupFeatures);

        //for IdentifiedObject
        groupFeatures.set(0, "CardinalityIO");
        groupFeatures.set(1, "This group of validation rules relate to cardinality validation of properties (attributes and associations).");
        groupFeatures.set(2, "CardinalityIO");
        groupFeatures.set(3, 0);

        shapeModel = ShaclTools.addPropertyGroup(shapeModel, prefs.get("IOuri",""), localNameGroup, groupFeatures);
        //for Datatypes group
        localNameGroup = "DatatypesGroup";
        groupFeatures.set(0, "Datatypes");
        groupFeatures.set(1, "This group of validation rules relate to validation of datatypes.");
        groupFeatures.set(2, "Datatypes");
        groupFeatures.set(3, 1);

        shapeModel = ShaclTools.addPropertyGroup(shapeModel, nsURIprofile, localNameGroup, groupFeatures);

        //for Inverse associations group
        localNameGroup = "InverseAssociationsGroup";
        groupFeatures.set(0, "InverseAssociations");
        groupFeatures.set(1, "This group of validation rules relate to validation of inverse associations presence.");
        groupFeatures.set(2, "InverseAssociations");
        groupFeatures.set(3, 1);

        shapeModel = ShaclTools.addPropertyGroup(shapeModel, nsURIprofile, localNameGroup, groupFeatures);

        //for Profile Classes group
        localNameGroup = "ProfileClassesGroup";
        groupFeatures.set(0, "ProfileClasses");
        groupFeatures.set(1, "This group of validation rules relate to validation of Profile Classes.");
        groupFeatures.set(2, "ProfileClasses");
        groupFeatures.set(3, 1);

        shapeModel = ShaclTools.addPropertyGroup(shapeModel, nsURIprofile, localNameGroup, groupFeatures);

        //for IdentifiedObject
        localNameGroup = "DatatypesGroupIO";
        groupFeatures.set(0, "DatatypesIO");
        groupFeatures.set(1, "This group of validation rules relate to validation of datatypes.");
        groupFeatures.set(2, "DatatypesIO");
        groupFeatures.set(3, 1);

        shapeModel = ShaclTools.addPropertyGroup(shapeModel, prefs.get("IOuri",""), localNameGroup, groupFeatures);

        //for Associations group
        localNameGroup = "AssociationsGroup";
        groupFeatures.set(0, "Associations");
        groupFeatures.set(1, "This group of validation rules relate to validation of target classes of associations.");
        groupFeatures.set(2, "Associations");
        groupFeatures.set(3, 2);

        shapeModel = ShaclTools.addPropertyGroup(shapeModel, nsURIprofile, localNameGroup, groupFeatures);

        // Adding node shape and property shape to check if we have additional classes that are not defined in the profile
        shapeModel = ShaclTools.addNodeShapeProfileClass(shapeModel, nsURIprofile, "AllowedClasses-node", RDF.type.getURI());

        //create the property shape
        RDFNode o = shapeModel.createResource(nsURIprofile + "AllowedClasses-property");
        Resource nodeShapeResourceClass = shapeModel.getResource(nsURIprofile + "AllowedClasses-node");
        nodeShapeResourceClass.addProperty(SH.property, o);

        //adding the properties for the PropertyShape
        Resource r = shapeModel.createResource(nsURIprofile + "AllowedClasses-property");
        r.addProperty(RDF.type, SH.PropertyShape);
        r.addProperty(SH.name, "ClassNotInProfile");
        r.addProperty(SH.description, "Checks if the dataset contains classes which are not defined in the profile to which this dataset conforms to.");
        RDFNode o8 = shapeModel.createResource(SH.NS + "Info");
        r.addProperty(SH.severity, o8);
        r.addProperty(SH.message, "This class is not part of the profile to which this dataset conforms to.");
        RDFNode o5 = shapeModel.createResource(RDF.type.getURI());
        r.addProperty(path, o5);
        RDFNode o1o = shapeModel.createTypedLiteral(1, "http://www.w3.org/2001/XMLSchema#integer");
        r.addProperty(SH.order, o1o);
        RDFNode o1g = shapeModel.createResource(nsURIprofile+"ProfileClassesGroup");
        r.addProperty(SH.group, o1g);

        //sh.in
        List<RDFNode> enumClass = new ArrayList<>();
        for (int cl = 0; cl < ((ArrayList<?>) shapeData.getFirst()).size(); cl++) { //this is to loop on the classes in the profile and add NodeShape for each concrete class
            //String localName = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).getFirst()).get(2).toString();
            String classFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).getFirst()).getFirst().toString();
            if (originalModel.contains(ResourceFactory.createStatement(ResourceFactory.createResource(classFullURI), RDF.type, ResourceFactory.createProperty("http://www.w3.org/2000/01/rdf-schema#Class")))) {
                enumClass.add(shapeModel.createResource(classFullURI));
            }
            //TODO see how to do for description classes
        }
        // add FullModel, DifferenceModel and Dataset
        enumClass.add(shapeModel.createResource("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel"));
        enumClass.add(shapeModel.createResource("http://iec.ch/TC57/61970-552/DifferenceModel/1#DifferenceModel"));
        enumClass.add(shapeModel.createResource(DCAT.Dataset.toString()));

        RDFList enumRDFlist = shapeModel.createList(enumClass.iterator());
        r.addProperty(SH.in, enumRDFlist);


        // Add shapes for counting classes
        if (shaclflagCount==1) {
            //for Profile Classes Count group
            localNameGroup = "ClassesCountGroup";
            groupFeatures.set(0, "ClassesCount");
            groupFeatures.set(1, "This group of validation rules relate to count of classes.");
            groupFeatures.set(2, "ClassesCount");
            groupFeatures.set(3, 1);

            String commonNSuri = nsURIprofile;

            if (shaclflagCountDefaultURI == 0) {
                commonNSuri = shaclCommonURI;
                shapeModel.setNsPrefix(shaclCommonPref, shaclCommonURI);
            }
            shapeModel = ShaclTools.addPropertyGroup(shapeModel, commonNSuri, localNameGroup, groupFeatures);

            //add NodeShape
            shapeModel = ShaclTools.addNodeShapeProfileClassCount(shapeModel, commonNSuri, "ClassCount-node", commonNSuri+"ClassCount");

            //create the property shape
            RDFNode pc = shapeModel.createResource(commonNSuri + "ClassCount-property");
            Resource nodeShapeResourceClasspc = shapeModel.getResource(commonNSuri + "ClassCount-node");
            nodeShapeResourceClasspc.addProperty(SH.property, pc);

            //add PropertyShape
            Resource rc = shapeModel.createResource(commonNSuri + "ClassCount-property");
            rc.addProperty(RDF.type, SH.PropertyShape);
            rc.addProperty(SH.name, "ClassCount");
            rc.addProperty(SH.description, "Counts instance of classes present in the data graph.");
            RDFNode o8pc = shapeModel.createResource(SH.NS + "Info");
            rc.addProperty(SH.severity, o8pc);
            RDFNode o5pc = shapeModel.createResource(RDF.type.getURI());
            rc.addProperty(path, o5pc);
            RDFNode o1opc = shapeModel.createTypedLiteral(1, "http://www.w3.org/2001/XMLSchema#integer");
            rc.addProperty(SH.order, o1opc);
            RDFNode o1gpc = shapeModel.createResource(commonNSuri + "ClassesCountGroup");
            rc.addProperty(SH.group, o1gpc);

            //add SPARQL constraint
            RDFNode spc = shapeModel.createResource(commonNSuri + "ClassCount-propertySparql");
            rc.addProperty(SH.sparql, spc);

            Resource ps = shapeModel.createResource(commonNSuri + "ClassCount-propertySparql");
            ps.addProperty(RDF.type, SH.SPARQLConstraint);
            ps.addProperty(SH.message, "The class {?class} appears {?value} times in the data graph.");
            ps.addProperty(SH.select, """
                    \s
                                SELECT $this ?class (COUNT(?instance) AS ?value)
                                WHERE {
                                        ?instance rdf:type ?class .
                                       }
                                GROUP BY $this ?class
                                \s""");


            //declare prefixes
            Resource commonRes = shapeModel.createResource(commonNSuri);
            shapeModel.add(ResourceFactory.createStatement(commonRes,RDF.type,OWL2.Ontology));
            shapeModel.add(ResourceFactory.createStatement(commonRes,OWL2.imports,ResourceFactory.createResource(SH.getURI())));

            //List<RDFNode> prefixesList = new ArrayList<>();
            Resource resbn = ResourceFactory.createResource();
            Statement stmtbn0 = ResourceFactory.createStatement(resbn, RDF.type, SH.PrefixDeclaration);
            Statement stmtbn1 = ResourceFactory.createStatement(resbn, SH.prefix, ResourceFactory.createPlainLiteral("rdf"));
            Statement stmtbn2 = ResourceFactory.createStatement(resbn, SH.namespace, ResourceFactory.createPlainLiteral(RDF.getURI()));
            shapeModel.add(stmtbn0);
            shapeModel.add(stmtbn1);
            shapeModel.add(stmtbn2);
            commonRes.addProperty(SH.declare, resbn);

            //prefixesList.add(resbn);
            //RDFList prefixesListRDF = shapeModel.createList(prefixesList.iterator());
            //ps.addProperty(SH.prefixes, prefixesListRDF);
            ps.addProperty(SH.prefixes, commonRes);

        }

        for (int cl = 0; cl < ((ArrayList<?>) shapeData.getFirst()).size(); cl++) { //this is to loop on the classes in the profile and add NodeShape for each concrete class
            //add the ShapeNode
            String localName = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).getFirst()).get(2).toString();
            String classFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).getFirst()).getFirst().toString();
            //RDFNode classRes = ResourceFactory.createResource(classFullURI);
            if (originalModel.contains(ResourceFactory.createStatement(ResourceFactory.createResource(classFullURI), RDF.type, ResourceFactory.createProperty("http://www.w3.org/2000/01/rdf-schema#Class")))) {
                //add NodeShape for the CIM class
                shapeModel = ShaclTools.addNodeShape(shapeModel, nsURIprofile, localName, classFullURI);
                //check if the class is stereotyped Description
                boolean classDescripStereo = false;
                if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).getFirst()).get(4).toString().equals("Yes")) {
                    classDescripStereo = true;
                }


                for (int atas = 1; atas < ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).size(); atas++) {
                    // this is to loop on the attributes and associations (including inherited) for a given class and add PropertyNode for each attribute or association
                    //every time a new property is added the reference is also added to the ShapeNode of the class
                    ArrayList<Object> propertyNodeFeatures = new ArrayList<>();
                    /*
                     * propertyNodeFeatures structure
                     * 0 - type of check: cardinality, datatype, associationValueType
                     * 1 - message
                     * 2 - name
                     * 3 - description
                     * 4 - severity
                     * 5 - cardinality
                     * 6 - the primitive either it is directly a primitive or it is the primitive of the .value attribute of a CIMdatatype
                     * in case of enumeration 6 is set to Enumeration
                     * in case of compound 6 is set to Compound
                     * 7 - is a list of uri of the enumeration attributes
                     * 8 - order
                     * 9 - group
                     * 10 - the inverse role name in case of association - the inverse end
                     * 11 - the list of concrete classes for association - the value type at the used end
                     * 12 - classFullURI for the targetClass of the NodeShape
                     * 13 - the uri of the compound class to be used in sh:class
                     * 14 - path for the attributes of the compound
                     */
                    for (int i = 0; i < 14; i++) {
                        propertyNodeFeatures.add("");
                    }

                    //Resource nodeObject = ResourceFactory.createResource(((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(2).toString());
                    //if (originalModel.listStatements(nodeObject, RDFS.domain, classRes).toList().isEmpty()){
                    //    continue;
                    //}
                    if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).getFirst().toString().equals("Association")) {//if it is an association
                        if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(1).toString().equals("Yes")) {
                            //Cardinality check
                            propertyNodeFeatures.set(0, "cardinality");
                            String cardinality = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(6).toString();
                            String localNameAssoc = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(5).toString();
                            propertyNodeFeatures.set(5, cardinality);
                            Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                            propertyNodeFeatures.set(1, "Association with cardinality violation at the used direction.");
                            propertyNodeFeatures.set(2, localNameAssoc + "-cardinality");
                            propertyNodeFeatures.set(3, "This constraint validates the cardinality of the association at the used direction.");
                            propertyNodeFeatures.set(4, "Violation");
                            propertyNodeFeatures.set(8, atas - 1); // this is the order
                            propertyNodeFeatures.set(9, nsURIprofile + "CardinalityGroup"); // this is the group

                            String propertyFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(2).toString();

                            shapeModel = ShaclTools.addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAssoc, propertyFullURI);

                            //Association check for target class

                            propertyNodeFeatures.set(0, "associationValueType");
                            //String cardinality = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(6).toString();
                            //localNameAssoc = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(5).toString();
                            //propertyNodeFeatures.set(5, cardinality);
                            //nodeShapeResource = shapeModel.getResource(nsURIprofile + localName+"ValueType");

                            //Check if there is a self association on model header and if this is the case then do not include sh:in and the sh:path needs to be different
                            String assocDomain = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(8).toString();
                            String assocRange = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(9).toString();
                            Resource assocRangeRes = ResourceFactory.createResource(assocRange);
                            Resource assocRangeResBase = null;
                            if (baseprofilesshaclglag == 1) {

                                if (assocRangeRes.getNameSpace().equals(cimURI)) {
                                    assocRangeResBase = ResourceFactory.createResource(cimURI + assocRangeRes.getLocalName());
                                } else if (assocRangeRes.getNameSpace().equals(cim2URI)) {
                                    assocRangeResBase = ResourceFactory.createResource(cim2URI + assocRangeRes.getLocalName());
                                } else if (assocRangeRes.getNameSpace().equals(cim3URI)) {
                                    assocRangeResBase = ResourceFactory.createResource(cim3URI + assocRangeRes.getLocalName());
                                } else {
                                    assocRangeResBase = assocRangeRes;
                                }

                            }
                            Resource assocRangeRes2nd = null;
                            if (baseprofilesshaclglag2nd == 1) {
                                if (assocRangeRes.getNameSpace().equals(cimURI)) {
                                    assocRangeRes2nd = ResourceFactory.createResource(cimURI + assocRangeRes.getLocalName());
                                } else if (assocRangeRes.getNameSpace().equals(cim2URI)) {
                                    assocRangeRes2nd = ResourceFactory.createResource(cim2URI + assocRangeRes.getLocalName());
                                } else if (assocRangeRes.getNameSpace().equals(cim3URI)) {
                                    assocRangeRes2nd = ResourceFactory.createResource(cim3URI + assocRangeRes.getLocalName());
                                } else {
                                    assocRangeRes2nd = assocRangeRes;
                                }
                            }
                            Resource assocRangeRes3rd = null;
                            if (baseprofilesshaclglag3rd == 1) {
                                if (assocRangeRes.getNameSpace().equals(cimURI)) {
                                    assocRangeRes3rd = ResourceFactory.createResource(cimURI + assocRangeRes.getLocalName());
                                } else if (assocRangeRes.getNameSpace().equals(cim2URI)) {
                                    assocRangeRes3rd = ResourceFactory.createResource(cim2URI + assocRangeRes.getLocalName());
                                } else if (assocRangeRes.getNameSpace().equals(cim3URI)) {
                                    assocRangeRes3rd = ResourceFactory.createResource(cim3URI + assocRangeRes.getLocalName());
                                } else {
                                    assocRangeRes3rd = assocRangeRes;
                                }
                            }

                            if (assocDomain.equals(assocRange) && assocDomain.equals("http://iec.ch/TC57/61970-552/ModelDescription/1#Model")) { // this is the case when it is a header
                                propertyNodeFeatures.set(1, "Not correct serialisation (rdf:resource is expected).");
                                propertyNodeFeatures.set(2, localNameAssoc + "-nodeKind");
                                propertyNodeFeatures.set(3, "This constraint validates the node kind of the association at the used direction.");
                            } else {//any other case
                                propertyNodeFeatures.set(1, "Not correct target class.");
                                propertyNodeFeatures.set(2, localNameAssoc + "-valueType");
                                propertyNodeFeatures.set(3, "This constraint validates the value of the association at the used direction.");
                            }

                            propertyNodeFeatures.set(4, "Violation");
                            propertyNodeFeatures.set(8, atas - 1); // this is the order
                            propertyNodeFeatures.set(9, nsURIprofile + "AssociationsGroup"); // this is the group
                            List<Resource> concreteClasses = null;
//                            if (localNameAssoc.contains("Terminal.ConductingEquipment")){
//                                int k=1;
//                            }
//                        System.out.println(localNameAssoc);
//                        System.out.println(((ArrayList<?>) ((ArrayList<?>) shapeData.get(0)).get(cl)).get(atas));
                            // TODO check if this if is necessary if (((List<Resource>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.get(0)).get(cl)).get(atas)).get(11)).size()==1) {
//                        if (((List<Resource>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.get(0)).get(cl)).get(atas)).get(11)).size()==0) {
//                            int k=1;
//                        }

                            if (!((LinkedList<?>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(11)).getFirst().toString().equals("http://abstract.eu")) {
                                concreteClasses = (List<Resource>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.get(0)).get(cl)).get(atas)).get(11);
                                //String propertyFullURI = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(2).toString();

                                //TODO check this part as assocRange was used instead of abstract class. In the ER the Equipment is concrete
                                if (baseprofilesshaclglag == 1) {
                                    // get the class URI at position 9; then search for this class in the unionmodelbaseprofilesshaclinheritanceonly and find all OWL2 members; store these members in List<Resource> concreteClasses

                                    for (StmtIterator i = unionmodelbaseprofilesshaclinheritanceonly.listStatements(assocRangeRes, OWL2.members, (RDFNode) null); i.hasNext(); ) {
                                        Statement stmtinheritance = i.next();
                                        concreteClasses.add(stmtinheritance.getObject().asResource());
                                    }

                                    for (StmtIterator i = unionmodelbaseprofilesshaclinheritanceonly.listStatements(assocRangeResBase, OWL2.members, (RDFNode) null); i.hasNext(); ) {
                                        Statement stmtinheritanceB = i.next();
                                        concreteClasses.add(stmtinheritanceB.getObject().asResource());
                                    }

                                    if (baseprofilesshaclglag2nd == 1) {
                                        for (StmtIterator i = unionmodelbaseprofilesshaclinheritanceonly.listStatements(assocRangeRes2nd, OWL2.members, (RDFNode) null); i.hasNext(); ) {
                                            Statement stmtinheritance2 = i.next();
                                            concreteClasses.add(stmtinheritance2.getObject().asResource());
                                        }
                                    }
                                    if (baseprofilesshaclglag3rd == 1) {
                                        for (StmtIterator i = unionmodelbaseprofilesshaclinheritanceonly.listStatements(assocRangeRes3rd, OWL2.members, (RDFNode) null); i.hasNext(); ) {
                                            Statement stmtinheritance3 = i.next();
                                            concreteClasses.add(stmtinheritance3.getObject().asResource());
                                        }
                                    }

                                    if (unionmodelbaseprofilesshacl.listStatements(assocRangeRes, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#concrete")).hasNext()) {
                                        concreteClasses.add(assocRangeRes);
                                    }
                                    if (baseprofilesshaclglag2nd == 1) {
                                        if (unionmodelbaseprofilesshacl.listStatements(assocRangeRes2nd, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#concrete")).hasNext()) {
                                            concreteClasses.add(assocRangeRes2nd);
                                        }
                                    }
                                    if (baseprofilesshaclglag3rd == 1) {
                                        if (unionmodelbaseprofilesshacl.listStatements(assocRangeRes3rd, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#concrete")).hasNext()) {
                                            concreteClasses.add(assocRangeRes3rd);
                                        }
                                    }

                                    if (baseprofilesshaclignorens == 1) {//check if the class exists in the base profiles
                                        //get all classes and check their local name
                                        for (StmtIterator i = unionmodelbaseprofilesshaclinheritanceonly.listStatements(null, OWL2.members, (RDFNode) null); i.hasNext(); ) {
                                            Statement stmtinheritanceign = i.next();
                                            if (stmtinheritanceign.getSubject().getLocalName().equals(assocRangeRes.getLocalName())) {
                                                concreteClasses.add(stmtinheritanceign.getObject().asResource());
                                            }
                                        }
                                    }


                                    if (concreteClasses.isEmpty()) { //it means that there are no child classes, and then it needs to be checked if the class is concrete in the base data
                                        if (unionmodelbaseprofilesshacl.listStatements(assocRangeRes, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#concrete")).hasNext()) {
                                            concreteClasses.add(assocRangeRes);
                                        } else {
                                            System.out.println("WARNING: The class " + assocRange + " is abstract and it is referenced by the association " + localNameAssoc + " for class " + classFullURI + ". ValueType SHACL constraint is set to require the type of this abstract class.");
                                            concreteClasses.add(assocRangeRes);
                                        }
                                        if (baseprofilesshaclglag2nd == 1) {
                                            assert assocRangeRes2nd != null;
                                            if (assocRangeRes2nd.getNameSpace().equals(cim2URI)) {
                                                if (unionmodelbaseprofilesshacl.listStatements(assocRangeRes2nd, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#concrete")).hasNext()) {
                                                    concreteClasses.add(assocRangeRes2nd);
                                                } else {
                                                    System.out.println("WARNING: The class " + assocRangeRes2nd + " is abstract and it is referenced by the association " + localNameAssoc + " for class " + classFullURI + ". ValueType SHACL constraint is set to require the type of this abstract class.");
                                                    concreteClasses.add(assocRangeRes2nd);
                                                }
                                            }
                                        }
                                        if (baseprofilesshaclglag3rd == 1) {
                                            assert assocRangeRes3rd != null;
                                            if (assocRangeRes3rd.getNameSpace().equals(cim3URI)) {
                                                if (unionmodelbaseprofilesshacl.listStatements(assocRangeRes3rd, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#concrete")).hasNext()) {
                                                    concreteClasses.add(assocRangeRes3rd);
                                                } else {
                                                    System.out.println("WARNING: The class " + assocRangeRes3rd + " is abstract and it is referenced by the association " + localNameAssoc + " for class " + classFullURI + ". ValueType SHACL constraint is set to require the type of this abstract class.");
                                                    concreteClasses.add(assocRangeRes3rd);
                                                }
                                            }
                                        }

                                    }
                                }

                                LinkedHashSet<Resource> concreteClasseshashSet = new LinkedHashSet<>(concreteClasses);
                                Iterator<Resource> itr = concreteClasseshashSet.iterator();
                                LinkedList<Resource> concreteClassesList = new LinkedList<>();
                                if (shapesOnAbstractOption == 1){
                                    concreteClassesList.add(assocRangeRes);
                                }else {
                                    while (itr.hasNext()) {
                                        concreteClassesList.add(itr.next());
                                    }
                                }
                                propertyNodeFeatures.set(10, concreteClassesList);
                                propertyNodeFeatures.set(11, classFullURI);

                                shapeModel = ShaclTools.addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAssoc, propertyFullURI);
                            } else { // if it is abstract class
                                concreteClasses = new LinkedList<>();
                                String abstractclass = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(9).toString();
                                Resource abstractclassRes = ResourceFactory.createResource(abstractclass);
                                Resource abstractclassResBase = null;
                                Resource abstractclassRes2nd = null;
                                Resource abstractclassRes3rd = null;

                                if (baseprofilesshaclglag == 1) {
                                    if (abstractclassRes.getNameSpace().equals(cimURI)) {
                                        abstractclassResBase = ResourceFactory.createResource(cimURI + abstractclassRes.getLocalName());
                                    } else if (abstractclassRes.getNameSpace().equals(cim2URI)) {
                                        abstractclassResBase = ResourceFactory.createResource(cim2URI + abstractclassRes.getLocalName());
                                    } else if (abstractclassRes.getNameSpace().equals(cim3URI)) {
                                        abstractclassResBase = ResourceFactory.createResource(cim3URI + abstractclassRes.getLocalName());
                                    } else {
                                        abstractclassResBase = abstractclassRes;
                                    }
                                }

                                if (baseprofilesshaclglag2nd == 1) {
                                    if (abstractclassRes.getNameSpace().equals(cimURI)) {
                                        abstractclassRes2nd = ResourceFactory.createResource(cimURI + abstractclassRes.getLocalName());
                                    } else if (abstractclassRes.getNameSpace().equals(cim2URI)) {
                                        abstractclassRes2nd = ResourceFactory.createResource(cim2URI + abstractclassRes.getLocalName());
                                    } else if (abstractclassRes.getNameSpace().equals(cim3URI)) {
                                        abstractclassRes2nd = ResourceFactory.createResource(cim3URI + abstractclassRes.getLocalName());
                                    } else {
                                        abstractclassRes2nd = abstractclassRes;
                                    }
                                }

                                if (baseprofilesshaclglag3rd == 1) {
                                    if (abstractclassRes.getNameSpace().equals(cimURI)) {
                                        abstractclassRes3rd = ResourceFactory.createResource(cimURI + abstractclassRes.getLocalName());
                                    } else if (abstractclassRes.getNameSpace().equals(cim2URI)) {
                                        abstractclassRes3rd = ResourceFactory.createResource(cim2URI + abstractclassRes.getLocalName());
                                    } else if (abstractclassRes.getNameSpace().equals(cim3URI)) {
                                        abstractclassRes3rd = ResourceFactory.createResource(cim3URI + abstractclassRes.getLocalName());
                                    } else {
                                        abstractclassRes3rd = abstractclassRes;
                                    }
                                }

                                if (baseprofilesshaclglag == 1) {
                                    // get the class URI at position 9; then search for this class in the unionmodelbaseprofilesshaclinheritanceonly and find all OWL2 members; store these members in List<Resource> concreteClasses
                                    for (StmtIterator i = unionmodelbaseprofilesshaclinheritanceonly.listStatements(abstractclassRes, OWL2.members, (RDFNode) null); i.hasNext(); ) {
                                        Statement stmtinheritance = i.next();
                                        concreteClasses.add(stmtinheritance.getObject().asResource());
                                    }

                                    for (StmtIterator i = unionmodelbaseprofilesshaclinheritanceonly.listStatements(abstractclassResBase, OWL2.members, (RDFNode) null); i.hasNext(); ) {
                                        Statement stmtinheritanceB = i.next();
                                        concreteClasses.add(stmtinheritanceB.getObject().asResource());
                                    }

                                    if (baseprofilesshaclglag2nd == 1) {
                                        for (StmtIterator i = unionmodelbaseprofilesshaclinheritanceonly.listStatements(abstractclassRes2nd, OWL2.members, (RDFNode) null); i.hasNext(); ) {
                                            Statement stmtinheritance2 = i.next();
                                            concreteClasses.add(stmtinheritance2.getObject().asResource());
                                        }
                                    }
                                    if (baseprofilesshaclglag3rd == 1) {
                                        for (StmtIterator i = unionmodelbaseprofilesshaclinheritanceonly.listStatements(abstractclassRes3rd, OWL2.members, (RDFNode) null); i.hasNext(); ) {
                                            Statement stmtinheritance3 = i.next();
                                            concreteClasses.add(stmtinheritance3.getObject().asResource());
                                        }
                                    }
                                    if (unionmodelbaseprofilesshacl.listStatements(abstractclassRes, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#concrete")).hasNext()) {
                                        concreteClasses.add(abstractclassRes);
                                    }
                                    if (baseprofilesshaclglag2nd == 1) {
                                        if (unionmodelbaseprofilesshacl.listStatements(abstractclassRes2nd, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#concrete")).hasNext()) {
                                            concreteClasses.add(abstractclassRes2nd);
                                        }
                                    }
                                    if (baseprofilesshaclglag3rd == 1) {
                                        if (unionmodelbaseprofilesshacl.listStatements(abstractclassRes3rd, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#concrete")).hasNext()) {
                                            concreteClasses.add(abstractclassRes3rd);
                                        }
                                    }

                                    if (baseprofilesshaclignorens == 1) {//check if the class exists in the base profiles
                                        //get all classes and check their local name
                                        for (StmtIterator i = unionmodelbaseprofilesshaclinheritanceonly.listStatements(null, OWL2.members, (RDFNode) null); i.hasNext(); ) {
                                            Statement stmtinheritanceign = i.next();
                                            if (stmtinheritanceign.getSubject().getLocalName().equals(abstractclassRes.getLocalName())) {
                                                concreteClasses.add(stmtinheritanceign.getObject().asResource());
                                            }
                                        }
                                    }

                                    if (concreteClasses.isEmpty()) { //it means that there are no child classes, and then it needs to be checked if the class is concrete in the base data
                                        if (unionmodelbaseprofilesshacl.listStatements(abstractclassRes, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#concrete")).hasNext()) {
                                            concreteClasses.add(abstractclassRes);
                                        } else {
                                            System.out.println("WARNING: The class " + abstractclass + " is abstract and it is referenced by the association " + localNameAssoc + " for class " + classFullURI + ". ValueType SHACL constraint is set to require the type of this abstract class.");
                                            concreteClasses.add(abstractclassRes);
                                        }
                                        if (baseprofilesshaclglag2nd == 1) {
                                            assert abstractclassRes2nd != null;
                                            if (abstractclassRes2nd.getNameSpace().equals(cim2URI)) {
                                                if (unionmodelbaseprofilesshacl.listStatements(abstractclassRes2nd, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#concrete")).hasNext()) {
                                                    concreteClasses.add(abstractclassRes2nd);
                                                } else {
                                                    System.out.println("WARNING: The class " + cim2URI + abstractclassRes.getLocalName() + " is abstract and it is referenced by the association " + localNameAssoc + " for class " + classFullURI + ". ValueType SHACL constraint is set to require the type of this abstract class.");
                                                    concreteClasses.add(abstractclassRes2nd);
                                                }
                                            }
                                        }
                                        if (baseprofilesshaclglag3rd == 1) {
                                            assert abstractclassRes3rd != null;
                                            if (abstractclassRes3rd.getNameSpace().equals(cim2URI)) {
                                                if (unionmodelbaseprofilesshacl.listStatements(abstractclassRes3rd, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#concrete")).hasNext()) {
                                                    concreteClasses.add(abstractclassRes3rd);
                                                } else {
                                                    System.out.println("WARNING: The class " + cim2URI + abstractclassRes.getLocalName() + " is abstract and it is referenced by the association " + localNameAssoc + " for class " + classFullURI + ". ValueType SHACL constraint is set to require the type of this abstract class.");
                                                    concreteClasses.add(abstractclassRes3rd);
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    System.out.println("WARNING: The class " + abstractclass + " is abstract and it is referenced by the association " + localNameAssoc + " for class " + classFullURI + ". ValueType SHACL constraint is set to require the type of this abstract class.");
                                    concreteClasses.add(abstractclassRes);
                                }
                                LinkedHashSet<Resource> concreteClasseshashSet = new LinkedHashSet<>(concreteClasses);
                                Iterator<Resource> itr = concreteClasseshashSet.iterator();
                                LinkedList<Resource> concreteClassesList = new LinkedList<>();
                                while (itr.hasNext()) {
                                    concreteClassesList.add(itr.next());
                                }
                                propertyNodeFeatures.set(10, concreteClassesList);
                                propertyNodeFeatures.set(11, classFullURI);

                                shapeModel = ShaclTools.addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAssoc, propertyFullURI);
                            }

                            // TODO check if this if is necessary}

                        } else { // this is for cardinality of inverse associations
                            //Add node shape and property shape for the presence of the inverse association
                            String invAssocURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(2).toString();
                            String localNameInvAssoc = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(5).toString();
                            shapeModel = ShaclTools.addNodeShapeProfileClass(shapeModel, nsURIprofile, localNameInvAssoc+"-inverseNodePresent", invAssocURI);
                            //create the property shape
                            RDFNode oi = shapeModel.createResource(nsURIprofile + localNameInvAssoc+"-propertyInverse");
                            Resource nodeShapeResourceClassInv = shapeModel.getResource(nsURIprofile + localNameInvAssoc+"-inverseNodePresent");
                            nodeShapeResourceClassInv.addProperty(SH.property, oi);

                            //adding the properties for the PropertyShape
                            Resource ri = shapeModel.createResource(nsURIprofile +localNameInvAssoc+"-propertyInverse");
                            ri.addProperty(RDF.type, SH.PropertyShape);
                            ri.addProperty(SH.name, "InverseAssociationPresent");
                            ri.addProperty(SH.description, "Inverse associations shall not be instantiated.");
                            RDFNode o8i = shapeModel.createResource(SH.NS + "Violation");
                            ri.addProperty(SH.severity, o8i);
                            ri.addProperty(SH.message, "Inverse association is present.");
                            RDFNode o5i = shapeModel.createResource(invAssocURI);
                            ri.addProperty(path, o5i);
                            RDFNode o1oi = shapeModel.createTypedLiteral(atas - 1, "http://www.w3.org/2001/XMLSchema#integer");
                            ri.addProperty(SH.order, o1oi);
                            RDFNode o1gi = shapeModel.createResource(nsURIprofile+"InverseAssociationsGroup");
                            ri.addProperty(SH.group, o1gi);
                            RDFNode o4i = shapeModel.createTypedLiteral(0, "http://www.w3.org/2001/XMLSchema#integer");
                            ri.addProperty(SH.maxCount, o4i);





                            if (shaclflaginverse == 1) {
                                propertyNodeFeatures.set(0, "cardinality");
                                String cardinality = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(6).toString();
                                String localNameAssoc = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(5).toString();
                                propertyNodeFeatures.set(5, cardinality);
                                Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                                propertyNodeFeatures.set(1, "Association with cardinality violation at the inverse direction.");
                                propertyNodeFeatures.set(2, localNameAssoc + "-cardinality");
                                propertyNodeFeatures.set(3, "This constraint validates the cardinality of the association at the inverse direction.");
                                propertyNodeFeatures.set(4, "Violation");
                                propertyNodeFeatures.set(8, atas - 1); // this is the order
                                propertyNodeFeatures.set(9, nsURIprofile + "CardinalityGroup"); // this is the group
                                Set<String> inverseAssocSet = new LinkedHashSet<>();
                                Property assocProp = ResourceFactory.createProperty(((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(10).toString());
                                String assocPropName = assocProp.getLocalName();
                                String assocPropNS = assocProp.getNameSpace();
                                if (baseprofilesshaclglag == 1){
                                    inverseAssocSet.add(assocProp.toString());
                                    //add the 3 cim namespaces if it is cim namespace
                                    if (assocPropNS.equals("http://iec.ch/TC57/CIM100#") || assocPropNS.equals("http://iec.ch/TC57/2013/CIM-schema-cim16#") || assocPropNS.equals("https://cim.ucaiug.io/ns#")) {
                                        inverseAssocSet.add("http://iec.ch/TC57/CIM100#" + assocPropName);
                                        inverseAssocSet.add("http://iec.ch/TC57/2013/CIM-schema-cim16#" + assocPropName);
                                        inverseAssocSet.add("https://cim.ucaiug.io/ns#" + assocPropName);
                                    }
                                }else{
                                    inverseAssocSet.add(assocProp.toString());
                                }
                                propertyNodeFeatures.set(10, inverseAssocSet); // this is a list of role names
                                //propertyNodeFeatures.set(10, ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(10).toString()); // this is the inverse role name
                                propertyNodeFeatures.set(11, "Inverse");

                                String propertyFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(2).toString();

                                shapeModel = ShaclTools.addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAssoc, propertyFullURI);
                            }
                        }

                    } else if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).getFirst().toString().equals("Attribute")) {//if it is an attribute

                        Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                        String localNameAttr = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(4).toString();
                        String propertyFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(1).toString();


                        //need to check if the class has any subclasses in the base profiles, if it has then the property shape should appear on the subclasses
                        if (excludeMRID) { // user selects that mRID should be skipped for description classes
                            if (classDescripStereo) { //the class is stereotyped description
                                if (!localNameAttr.equals("IdentifiedObject.mRID")) { //the attribute is not mRID
                                    shapeModel = addPropertyNodeForAttributeSingle(shapeModel, propertyNodeFeatures, shapeData, nsURIprofile, cl, atas, nodeShapeResource, localNameAttr, propertyFullURI);
                                }
                            } else {
                                shapeModel = addPropertyNodeForAttributeSingle(shapeModel, propertyNodeFeatures, shapeData, nsURIprofile, cl, atas, nodeShapeResource, localNameAttr, propertyFullURI);
                            }
                        } else {
                            shapeModel = addPropertyNodeForAttributeSingle(shapeModel, propertyNodeFeatures, shapeData, nsURIprofile, cl, atas, nodeShapeResource, localNameAttr, propertyFullURI);
                        }

                        //check if the attribute is datatype, if yes the whole structure of the compound should be checked and property nodes should be created
                        // for each attribute of the compound
                        if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(8).toString().equals("Compound")) {
                            ArrayList<?> shapeDataCompound = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(10));
                            shapeModel = addPropertyNodeForAttributeCompound(shapeModel, propertyNodeFeatures, shapeDataCompound, nsURIprofile, nodeShapeResource, localNameAttr, propertyFullURI);
                        }

                    }
                }

            }

        }



        return shapeModel;
    }

    //adds the PropertyNode for attribute for compound.
    public static Model addPropertyNodeForAttributeCompound(Model shapeModel, ArrayList<Object> propertyNodeFeatures, ArrayList<?> shapeDataCompound,String nsURIprofile, Resource nodeShapeResource, String localNameAttr,String propertyFullURI){

            for (int atasComp = 0; atasComp < shapeDataCompound.size(); atasComp++) {
                String localNameAttrComp = ((ArrayList<?>) shapeDataCompound.get(atasComp)).get(4).toString();
                String propertyFullURIComp = ((ArrayList<?>) shapeDataCompound.get(atasComp)).get(1).toString();
                shapeModel= addPropertyNodeForAttributeSingleCompound(shapeModel, propertyNodeFeatures, shapeDataCompound, nsURIprofile,atasComp, nodeShapeResource,localNameAttrComp,propertyFullURIComp);
                if (((ArrayList<?>) shapeDataCompound.get(atasComp)).get(8).toString().equals("Compound")) {
                    ArrayList<?> shapeDataCompoundDeep = ((ArrayList<?>) ((ArrayList<?>) shapeDataCompound.get(atasComp)).get(10));
                    shapeModel= addPropertyNodeForAttributeCompound(shapeModel, propertyNodeFeatures, shapeDataCompoundDeep, nsURIprofile, nodeShapeResource, localNameAttrComp,propertyFullURIComp);

                }
            }



        return shapeModel;
    }

    //adds the PropertyNode for attribute. It runs one time for a regular attribute and it is reused multiple times for Compound
    public static Model addPropertyNodeForAttributeSingleCompound(Model shapeModel, ArrayList<Object> propertyNodeFeatures, ArrayList<?> shapeData,String nsURIprofile, Integer atas, Resource nodeShapeResource, String localNameAttr,String propertyFullURI){
        //Cardinality check
        propertyNodeFeatures.set(0, "cardinality");
        String cardinality = ((ArrayList<?>) shapeData.get(atas)).get(5).toString();
        propertyNodeFeatures.set(5, cardinality);
        propertyNodeFeatures.set(1, "Missing required attribute.");
        propertyNodeFeatures.set(2, localNameAttr + "-cardinality");
        propertyNodeFeatures.set(3, "This constraint validates the cardinality of the property (attribute).");
        propertyNodeFeatures.set(4, "Violation");
        propertyNodeFeatures.set(8, atas - 1); // this is the order
        propertyNodeFeatures.set(9, nsURIprofile + "CardinalityGroup"); // this is the group

        boolean skip=false;
        try {
            List pathCompound = (List) ((ArrayList<?>) shapeData.get(atas)).get(11);
            propertyNodeFeatures.set(13, pathCompound);
        }catch(Exception e){
            skip=true;
        }
        if (skip==false) {


            shapeModel = ShaclTools.addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAttr, propertyFullURI);

            //add datatypes checks depending on it is Primitive, Datatype or Enumeration
            propertyNodeFeatures.set(2, localNameAttr + "-datatype");
            propertyNodeFeatures.set(3, "This constraint validates the datatype of the property (attribute).");
            propertyNodeFeatures.set(0, "datatype");
            propertyNodeFeatures.set(8, atas - 1); // this is the order
            propertyNodeFeatures.set(9, nsURIprofile + "DatatypesGroup"); // this is the group
            switch (((ArrayList<?>) shapeData.get(atas)).get(8).toString()) {
                case "Primitive": {
                    String datatypePrimitive = ((ArrayList<?>) shapeData.get(atas)).get(10).toString(); //this is localName e.g. String

                    propertyNodeFeatures.set(6, datatypePrimitive);
                    propertyNodeFeatures.set(1, "The datatype is not literal or it violates the xsd datatype.");

                    break;
                }
                case "CIMDatatype": {
                    String datatypePrimitive = ((ArrayList<?>) shapeData.get(atas)).get(9).toString(); //this is localName e.g. String

                    propertyNodeFeatures.set(6, datatypePrimitive);
                    propertyNodeFeatures.set(1, "The datatype is not literal or it violates the xsd datatype.");

                    break;
                }
                case "Compound": {
                    String datatypeCompound = ((ArrayList<?>) shapeData.get(atas)).get(9).toString(); //this is localName e.g. String

                    propertyNodeFeatures.set(6, "Compound");
                    propertyNodeFeatures.set(1, "Blank node (compound datatype) violation. Either it is not a blank node (nested structure, compound datatype) or it is not the right class.");
                    propertyNodeFeatures.set(12, datatypeCompound);
                    break;
                }
                case "Enumeration":
                    propertyNodeFeatures.set(6, "Enumeration");
                    propertyNodeFeatures.set(1, "The datatype is not IRI (Internationalized Resource Identifier) or it is an enumerated value which is not part of the enumeration.");
                    //this adds the structure which is a list of possible enumerated values
                    propertyNodeFeatures.set(7, ((ArrayList<?>) shapeData.get(atas)).get(10));
                    break;
            }
            shapeModel = ShaclTools.addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAttr, propertyFullURI);
            propertyNodeFeatures.set(13, ""); // in order to be empty for the next attribute
        }
        return shapeModel;
    }

    //adds the PropertyNode for attribute. It runs one time for a regular attribute and it is reused multiple times for Compound
    public static Model addPropertyNodeForAttributeSingle(Model shapeModel, ArrayList<Object> propertyNodeFeatures, ArrayList<?> shapeData,String nsURIprofile, Integer cl, Integer atas, Resource nodeShapeResource, String localNameAttr,String propertyFullURI){
        //Cardinality check
        propertyNodeFeatures.set(0, "cardinality");
        String cardinality = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(5).toString();
        propertyNodeFeatures.set(5, cardinality);
        propertyNodeFeatures.set(1, "Missing required property (attribute).");
        propertyNodeFeatures.set(2, localNameAttr + "-cardinality");
        propertyNodeFeatures.set(3, "This constraint validates the cardinality of the property (attribute).");
        propertyNodeFeatures.set(4, "Violation");
        propertyNodeFeatures.set(8, atas - 1); // this is the order
        propertyNodeFeatures.set(9, nsURIprofile + "CardinalityGroup"); // this is the group
        shapeModel = ShaclTools.addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAttr, propertyFullURI);
        //add datatypes checks depending on it is Primitive, Datatype or Enumeration
        propertyNodeFeatures.set(2, localNameAttr + "-datatype");
        propertyNodeFeatures.set(3, "This constraint validates the datatype of the property (attribute).");
        propertyNodeFeatures.set(0, "datatype");
        propertyNodeFeatures.set(8, atas - 1); // this is the order
        propertyNodeFeatures.set(9, nsURIprofile + "DatatypesGroup"); // this is the group
        switch (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(8).toString()) {
            case "Primitive": {
                String datatypePrimitive = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(10).toString(); //this is localName e.g. String

                propertyNodeFeatures.set(6, datatypePrimitive);
                propertyNodeFeatures.set(1, "The datatype is not literal or it violates the xsd datatype.");

                break;
            }
            case "CIMDatatype": {
                String datatypePrimitive = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(9).toString(); //this is localName e.g. String

                propertyNodeFeatures.set(6, datatypePrimitive);
                propertyNodeFeatures.set(1, "The datatype is not literal or it violates the xsd datatype.");

                break;
            }
            case "Compound": {
                String datatypeCompound = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(9).toString(); //this is localName e.g. String

                propertyNodeFeatures.set(6, "Compound");
                propertyNodeFeatures.set(1, "Blank node (compound datatype) violation. Either it is not a blank node (nested structure, compound datatype) or it is not the right class.");
                propertyNodeFeatures.set(12, datatypeCompound);
                break;
            }
            case "Enumeration":
                propertyNodeFeatures.set(6, "Enumeration");
                propertyNodeFeatures.set(1, "The datatype is not IRI (Internationalized Resource Identifier) or it is enumerated value not part of the profile.");
                //this adds the structure which is a list of possible enumerated values
                propertyNodeFeatures.set(7, ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(10));
                break;
        }
        shapeModel = ShaclTools.addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAttr, propertyFullURI);

        return shapeModel;
    }






    //get subtypes of a class - first level
    public static List<Resource> getSubtypeClassesFirstLevel(Resource classRange, Model model){

        List<Resource> subtypeClasses = new LinkedList<>();

        for (ResIterator i=model.listResourcesWithProperty(RDFS.subClassOf); i.hasNext();) {
            Resource resItemsubClassOf = i.next();
            if (classRange.toString().equals(resItemsubClassOf.getRequiredProperty(RDFS.subClassOf).getObject().toString())) {
            //System.out.println(resItemsubClassOf);
                subtypeClasses.add(resItemsubClassOf);
            }
        }
        return subtypeClasses;
    }

    //get subtypes of a class - n level
    public static List<Resource> getSubtypeClassesNlevel(Resource classRange, Model model){
        //ArrayList<Resource> subtypeClassesN = new ArrayList<>();
        //ArrayList<Resource> subtypeClasses = new ArrayList<>();
        List<Resource> subtypeClassesN = new LinkedList<>();

        List<Resource> subtypeClasses=getSubtypeClassesFirstLevel(classRange,model);
        subtypeClassesN.addAll(subtypeClasses);
        if (subtypeClasses.size()==0){
            return subtypeClassesN;
        }
        //Iterator stubtype = subtypeClasses.iterator();
        int root = 0;
        while (root == 0) {
            List<Resource> tempList =new LinkedList<>();
            for (Iterator i=subtypeClasses.iterator(); i.hasNext();){
                classRange = (Resource) i.next();
                List<Resource> subtypeClassesNew=getSubtypeClassesFirstLevel(classRange,model);
                if (subtypeClassesNew.size()!=0) {
                    tempList.addAll(subtypeClassesNew);
                }
            }
            if (tempList.size()==0) {
                root = 1;
            }else{
                subtypeClassesN.addAll(tempList);
                subtypeClasses=tempList;
            }
        }

        return subtypeClassesN;
    }

    public static Boolean classIsConcrete(Resource resource, Model model){
        boolean concrete=false;
        for (NodeIterator j = model.listObjectsOfProperty(resource, model.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "stereotype")); j.hasNext(); ) {
            RDFNode resItemNode = j.next();
            if (resItemNode.toString().equals("http://iec.ch/TC57/NonStandard/UML#concrete")) {// the resource is concrete - it is a concrete class
                concrete=true;
            }
        }
        return concrete;
    }

    //List of SHACL NodeShape properties
    public static ArrayList<String> getListShaclNodeShapeProperties(){
        ArrayList<String> shaclNodeShapeProperties =new ArrayList<>();
        //these are properties of sh:Shape
        shaclNodeShapeProperties.add("targetClass");
        shaclNodeShapeProperties.add("targetNode");
        shaclNodeShapeProperties.add("targetObjectsOf");
        shaclNodeShapeProperties.add("targetSubjectsOf");
        shaclNodeShapeProperties.add("severity");
        shaclNodeShapeProperties.add("message");
        shaclNodeShapeProperties.add("deactivated");
        shaclNodeShapeProperties.add("property");
        shaclNodeShapeProperties.add("target");
        shaclNodeShapeProperties.add("rule");
        //these are properties of sh:NodeShape

        //Constraints
        shaclNodeShapeProperties.add("class");
        shaclNodeShapeProperties.add("datatype");
        shaclNodeShapeProperties.add("nodeKind");
        shaclNodeShapeProperties.add("minExclusive");
        shaclNodeShapeProperties.add("minInclusive");
        shaclNodeShapeProperties.add("maxExclusive");
        shaclNodeShapeProperties.add("maxInclusive");
        shaclNodeShapeProperties.add("minLength");
        shaclNodeShapeProperties.add("maxLength");
        shaclNodeShapeProperties.add("pattern");
        shaclNodeShapeProperties.add("flags");
        shaclNodeShapeProperties.add("languageIn");
        shaclNodeShapeProperties.add("not");
        shaclNodeShapeProperties.add("and");
        shaclNodeShapeProperties.add("or");
        shaclNodeShapeProperties.add("xone");
        shaclNodeShapeProperties.add("node");
        shaclNodeShapeProperties.add("property");
        shaclNodeShapeProperties.add("closed");
        shaclNodeShapeProperties.add("ignoredProperties");
        shaclNodeShapeProperties.add("hasValue");
        shaclNodeShapeProperties.add("in");
        shaclNodeShapeProperties.add("sparql");



        return shaclNodeShapeProperties;
    }

    //List of SHACL PropertyShape properties
    public static ArrayList<String> getListShaclPropertyShapeProperties(){
        ArrayList<String> shaclPropertyShapeProperties =new ArrayList<>();
        //these are properties of sh:Shape
        shaclPropertyShapeProperties.add("targetClass");
        shaclPropertyShapeProperties.add("targetNode");
        shaclPropertyShapeProperties.add("targetObjectsOf");
        shaclPropertyShapeProperties.add("targetSubjectsOf");
        shaclPropertyShapeProperties.add("severity");
        shaclPropertyShapeProperties.add("message");
        shaclPropertyShapeProperties.add("deactivated");
        shaclPropertyShapeProperties.add("property");
        shaclPropertyShapeProperties.add("target");
        //these are properties of sh:PropertyShape
        shaclPropertyShapeProperties.add("path");
        shaclPropertyShapeProperties.add("defaultValue");
        shaclPropertyShapeProperties.add("description");
        shaclPropertyShapeProperties.add("group");
        shaclPropertyShapeProperties.add("name");
        shaclPropertyShapeProperties.add("rule");
        shaclPropertyShapeProperties.add("order");
        //Constraints
        shaclPropertyShapeProperties.add("class");
        shaclPropertyShapeProperties.add("datatype");
        shaclPropertyShapeProperties.add("nodeKind");
        shaclPropertyShapeProperties.add("minCount");
        shaclPropertyShapeProperties.add("maxCount");
        shaclPropertyShapeProperties.add("minExclusive");
        shaclPropertyShapeProperties.add("minInclusive");
        shaclPropertyShapeProperties.add("maxExclusive");
        shaclPropertyShapeProperties.add("maxInclusive");
        shaclPropertyShapeProperties.add("minLength");
        shaclPropertyShapeProperties.add("maxLength");
        shaclPropertyShapeProperties.add("pattern");
        shaclPropertyShapeProperties.add("flags");
        shaclPropertyShapeProperties.add("languageIn");
        shaclPropertyShapeProperties.add("uniqueLang");
        shaclPropertyShapeProperties.add("equals");
        shaclPropertyShapeProperties.add("disjoint");
        shaclPropertyShapeProperties.add("lessThan");
        shaclPropertyShapeProperties.add("lessThanOrEquals");
        shaclPropertyShapeProperties.add("not");
        shaclPropertyShapeProperties.add("and");
        shaclPropertyShapeProperties.add("or");
        shaclPropertyShapeProperties.add("xone");
        shaclPropertyShapeProperties.add("node");
        shaclPropertyShapeProperties.add("property");
        shaclPropertyShapeProperties.add("qualifiedValueShape");
        shaclPropertyShapeProperties.add("qualifiedValueShapesDisjoint");
        shaclPropertyShapeProperties.add("qualifiedMinCount");
        shaclPropertyShapeProperties.add("qualifiedMaxCount");
        shaclPropertyShapeProperties.add("closed");
        shaclPropertyShapeProperties.add("ignoredProperties");
        shaclPropertyShapeProperties.add("hasValue");
        shaclPropertyShapeProperties.add("in");
        shaclPropertyShapeProperties.add("sparql");




        return shaclPropertyShapeProperties;
    }

    //List of SHACL PropertyGroup properties
    public static ArrayList<String> getListShaclPropertyGroupProperties(){
        ArrayList<String> shaclPropertyGroupProperties =new ArrayList<>();
        //these are properties of sh:Shape
        //shaclPropertyGroupProperties.add("targetClass");
        //shaclPropertyGroupProperties.add("targetNode");
        //shaclPropertyGroupProperties.add("targetObjectsOf");
        //shaclPropertyGroupProperties.add("targetSubjectsOf");
        //shaclPropertyGroupProperties.add("severity");
        //shaclPropertyGroupProperties.add("message");
        //shaclPropertyGroupProperties.add("deactivated");
        //shaclPropertyGroupProperties.add("property");
        //these are properties of sh:PropertyGroup
        shaclPropertyGroupProperties.add("label"); // it is the rdfs:label
        shaclPropertyGroupProperties.add("order");
        //TODO remove name and description from the auto generation for the PropertyGroup - see what Holger will reply


        return shaclPropertyGroupProperties;
    }

    //List of SHACL type properties
    public static ArrayList<String> getListShacltypeProperties(){
        ArrayList<String> shacltypeProperties =new ArrayList<>();
        shacltypeProperties.add("type"); // it is the rdfs:label

        return shacltypeProperties;
    }

    //List of SHACL SPARQLConstraint properties
    public static ArrayList<String> getListShaclSPARQLConstraintProperties(){
        ArrayList<String> shaclSPARQLConstraintProperties =new ArrayList<>();
        //these are properties of sh:Shape
        //shaclSPARQLConstraintProperties.add("targetClass");
        //shaclSPARQLConstraintProperties.add("targetNode");
        //shaclSPARQLConstraintProperties.add("targetObjectsOf");
        //shaclSPARQLConstraintProperties.add("targetSubjectsOf");
        //shaclSPARQLConstraintProperties.add("severity");


        //shaclSPARQLConstraintProperties.add("property");
        //these are properties of sh:SPARQLConstraint
        shaclSPARQLConstraintProperties.add("deactivated");
        shaclSPARQLConstraintProperties.add("message");
        shaclSPARQLConstraintProperties.add("prefixes");
        shaclSPARQLConstraintProperties.add("select");


        return shaclSPARQLConstraintProperties;
    }

    //List of SHACL type properties
    public static void splitShaclPerXlsInput(ArrayList<Object> inputXLSdata) throws IOException {

        Map<String, Model> splitShaclMap = new HashMap<>();
        Map<String, PropertyHolder> constraintPropertiesMap = new HashMap<>();
        Map<String, String> baseMap = new HashMap<>();
        Model shaclModel;
        File folder = eu.griddigit.cimpal.util.ModelFactory.folderChooserCustom();
        //the map where we store the things before we process with the save
        //the string can ve the path and the file name that can be used by the save
        for (Object inputXLSrow : inputXLSdata) {

            List<String> currentRow = (List<String>) inputXLSrow;
            String constraintName = currentRow.getFirst(); //put here the value of column A from inputXLSdata
            String constraintFilePath = folder+"\\"+currentRow.get(1)+"\\"+currentRow.get(2); //put here the value of column B from inputXLSdata
            String newPrefix = currentRow.get(3); // this is the prefix //TODO implement what happens if it is not "keep"
            String newPrefixNS = currentRow.get(4); // this is the prefix namespace
            String newBaseURI = currentRow.get(5); // this is the base URI
            String newGroupURI = currentRow.get(6); // this is the group URI //TODO implement what happens if it is not "keep"
            String newGroupName = currentRow.get(7); // this is the group Name
            PropertyHolder properties = new PropertyHolder(constraintFilePath, newPrefix, newPrefixNS, newBaseURI, newGroupURI, newGroupName);
            constraintPropertiesMap.put(constraintName,properties);

            if (newPrefixNS.equals("skip")){ // of that column is "skip" we do not process that constraint// TODO see if we want to create another column for this or we use this one
                continue;
            }

            //check if the constraintFilePath is in the splitShaclMap
            if (splitShaclMap.containsKey(constraintFilePath)) {
                shaclModel = splitShaclMap.get(constraintFilePath);
            } else {
                shaclModel = ModelFactory.createDefaultModel();
                splitShaclMap.put(constraintFilePath, shaclModel);
                baseMap.put(constraintFilePath,newBaseURI);
            }


            for (Object shapeModel : shapeModels) {
                // here we go through each imported models
                //here we need to check if the constraintName if in some of the files and take it then put it in the model that will populate constraintFilePath
                Model shModel = (Model) shapeModel;  // see if this works, but the idea is to get the model
                if (shModel.listStatements(null, SH.name, ResourceFactory.createPlainLiteral(constraintName)).hasNext()) {
                    // the sh:name is found
                    //here we need to take the things
                    LinkedList<Statement> stmtTosave = new LinkedList<>();

                    Statement leadStmt = shModel.listStatements(null, SH.name, ResourceFactory.createPlainLiteral(constraintName)).next();

                    //this below assumes that the sh:name is in the PropertyShape, but we may need to improve as I think we may have cases where we do not have PropertyShape and we have the name in the NodeShape
                    Resource sparqlURI = ResourceFactory.createResource("https://griddigit.eu/sparql/empty");
                    boolean hasSparql = false;
                    Resource groupURI = ResourceFactory.createResource("https://griddigit.eu/group/empty");
                    boolean hasGroup = false;
                    for (StmtIterator i = shModel.listStatements(leadStmt.getSubject(), null, (RDFNode) null); i.hasNext(); ) {
                        Statement stmt = i.next();

                        if (stmt.getObject().isAnon()){
                            if (newPrefix.equals("keep")) {
                                shaclModel = addBlankNode(shModel, shaclModel, stmt, false, null);
                            }
                        }else{
                            if (newPrefix.equals("keep")) {
                                stmtTosave.add(stmt);
                            }
                        }

                        if (stmt.getPredicate().equals(SH.sparql)) {
                            sparqlURI = stmt.getObject().asResource();
                            hasSparql = true;
                        }
                        if (stmt.getPredicate().equals(SH.group)) {
                            groupURI = stmt.getObject().asResource();
                            hasGroup = true;
                        }
                    }
                    //gather sparql
                    if (hasSparql) {
                        for (StmtIterator i = shModel.listStatements(sparqlURI, null, (RDFNode) null); i.hasNext(); ) {
                            Statement stmt = i.next();
                            if (stmt.getObject().isAnon()){
                                if (newPrefix.equals("keep")) {
                                    shaclModel = addBlankNode(shModel, shaclModel, stmt, false, null);
                                }
                            }else{
                                if (newPrefix.equals("keep")) {
                                    stmtTosave.add(stmt);
                                }
                            }
                        }
                    }
                    //gather group
                    if (hasGroup) {
                        for (StmtIterator i = shModel.listStatements(groupURI, null, (RDFNode) null); i.hasNext(); ) {
                            Statement stmt = i.next();
                            if (newPrefix.equals("keep")) {
                                stmtTosave.add(stmt);
                            }
                        }
                    }
                    //gather the NodeShape
                    for (StmtIterator i = shModel.listStatements(null, SH.property, leadStmt.getSubject()); i.hasNext(); ) {
                        Statement stmt = i.next();

                        if (stmt.getObject().isAnon()){
                            if (newPrefix.equals("keep")) {
                                shaclModel = addBlankNode(shModel, shaclModel, stmt, false, null);
                            }
                        }else{
                            if (newPrefix.equals("keep")) {
                                stmtTosave.add(stmt);
                            }
                        }

                        for (StmtIterator j = shModel.listStatements(stmt.getSubject(), null, (RDFNode) null); j.hasNext(); ) {
                            Statement stmtNode = j.next();
                            if (!stmtNode.getPredicate().equals(SH.property)) {
                                if (stmtNode.getObject().isAnon()){
                                    if (newPrefix.equals("keep")) {
                                        shaclModel = addBlankNode(shModel, shaclModel, stmtNode, false, null);
                                    }
                                }else{
                                    if (newPrefix.equals("keep")) {
                                        stmtTosave.add(stmtNode);
                                    }
                                }
                            }
                        }
                    }

                    // TODO - improve here so that we do not do this in every iteration; also if the shacl has header this is also Ontology - to be checked
                    if (hasSparql) {
                        for (StmtIterator i = shModel.listStatements(null, RDF.type, OWL2.Ontology); i.hasNext(); ) {
                            Statement stmt = i.next();
                            for (StmtIterator j = shModel.listStatements(stmt.getSubject(), null, (RDFNode) null); j.hasNext(); ) {
                                Statement stmtOnt = j.next();
                                if (stmtOnt.getObject().isAnon()){
                                    //stmtTosave.add(stmtOnt);
                                    //shaclModel.add(stmtOnt.getSubject(),stmtOnt.getPredicate(),shModel.getRDFNode(stmtOnt.getObject().asNode()));
                                    if (newPrefix.equals("keep")) {
                                        shaclModel = addBlankNode(shModel, shaclModel, stmtOnt, false, null); //TODO
                                    }
                                }else{
                                    if (newPrefix.equals("keep")) {
                                        stmtTosave.add(stmtOnt);
                                    }
                                }
                            }
                        }
                    }

                    //save the stmtTosave to the model
                    shaclModel.add(stmtTosave);
                    //get prefixes and add it to the model - we can do this better and not in every iteration - TODO improve
                    Map<String, String> prefMap = shModel.getNsPrefixMap();
                    shaclModel.setNsPrefixes(prefMap);

                    //update the model in the map
                    splitShaclMap.replace(constraintFilePath, shaclModel); // see if this works or we need to use put instead of replace
                }

            }
        }

        //here you iterate on the splitShaclMap
        //and for each file path splitShaclMap the key, we save the model which is in the value
        for (Map.Entry<String, Model> entry : splitShaclMap.entrySet()) {
            String filePath = entry.getKey();
            Model shaclModelToSave = entry.getValue();
            if (!shaclModelToSave.isEmpty()) {
                String saveBaseURI = baseMap.get(filePath);

                File directory = new File(filePath);

                if (!directory.getParentFile().exists()) {
                    if (directory.getParentFile().mkdir()) {
                        System.out.println("Folder created successfully: [" + directory.getParentFile() + "]");
                    } else {
                        System.out.println("Failed to create the folder: [" + directory.getParentFile() + "]");
                    }
                } else {
                    System.out.println("Folder already exists and will be used: [" + directory.getParentFile() + "]");
                }

                try (OutputStream out = new FileOutputStream(filePath)) {
                    //shaclModelToSave.write(out, RDFFormat.TURTLE.getLang().getLabel().toUpperCase());
                    RDFWriter.create()
                            .base(saveBaseURI)
                            .set(RIOT.symTurtleOmitBase, false)
                            .set(RIOT.symTurtleIndentStyle, "wide")
                            .set(RIOT.symTurtleDirectiveStyle, "rdf10")
                            .set(RIOT.multilineLiterals, true)
                            .lang(Lang.TURTLE)
                            .source(shaclModelToSave)
                            .output(out);
                    System.out.println("Model saved successfully to " + filePath);
                } catch (IOException e) {
                    System.err.println("Error saving model to file: " + e.getMessage());
                }
            }
        }
    }

    //List of SHACL type properties
    public static Model addBlankNode(Model modelOrig, Model modelTarget, Statement stmt, boolean blankInBlank, RDFNode obj) {

        if (blankInBlank) {
            //modelTarget.add(stmt);
            for (StmtIterator i = modelOrig.listStatements(obj.asResource(), null, (RDFNode) null); i.hasNext(); ) {
                Statement stmtB = i.next();
                modelTarget.add(stmtB);
                if (stmtB.getObject().isAnon()){
                    modelTarget = addBlankNode(modelOrig,modelTarget,stmtB,false,null);
                }
            }
        }else{
            Map<Boolean, List<RDFNode>> isBNlistB = isBlankNodeAlist(stmt);

            if (isBNlistB.containsKey(true)) {
                //List<RDFNode> listModelB = isBNlistB.get(true);
                RDFList objectlist = modelOrig.getList(stmt.getObject().asResource());
                RDFList pathRDFlist = modelTarget.createList(objectlist.iterator());
                //check of elements of the RDFlist are blank nodes
                for (RDFNode objO : objectlist.asJavaList()) {
                    if (objO.isAnon()) {
                        blankInBlank = true;
                        modelTarget = addBlankNode(modelOrig,modelTarget,stmt,blankInBlank,objO);
                    }
                }
                Resource r = modelTarget.createResource(stmt.getSubject().toString());
                r.addProperty(stmt.getPredicate(), pathRDFlist);
            } else {
                //get all statements of the blank node and put them in the model
                modelTarget.add(stmt);
                for (StmtIterator i = modelOrig.listStatements(stmt.getObject().asResource(), null, (RDFNode) null); i.hasNext(); ) {
                    Statement stmtB = i.next();
                    modelTarget.add(stmtB);
                }
            }
        }

        return modelTarget;
    }
    //Changes the Namespaces for either subject, predicate, object or all
    public static Statement statementNewNS(Statement stmt, boolean subject, String subjectNSold, String subjectNSnew,boolean predicate, String predicateNSold, String predicateNSnew,boolean object, String objectNSold, String objectNSnew ) {
        Resource subjRes;
        Property pred;
        RDFNode obj;

        if (subject) {
            if (stmt.getSubject().getNameSpace().equals(subjectNSold)){
                subjRes = ResourceFactory.createResource(subjectNSnew+stmt.getSubject().getLocalName());
            }else{
                subjRes = stmt.getSubject();
            }
        }else{
            subjRes = stmt.getSubject();
        }
        if (predicate) {
            if (stmt.getPredicate().getNameSpace().equals(predicateNSold)){
                pred = ResourceFactory.createProperty(predicateNSnew,stmt.getPredicate().getLocalName());
            }else{
                pred = stmt.getPredicate();
            }
        }else{
            pred = stmt.getPredicate();
        }
        if (object) {
            if (stmt.getObject().isResource()) {
                if (stmt.getObject().asResource().getNameSpace().equals(subjectNSold)){
                    obj = ResourceFactory.createProperty(objectNSnew+stmt.getObject().asResource().getLocalName());
                }else{
                    obj = stmt.getObject();
                }
            }else{
                obj = stmt.getObject();
            }
        }else{
            obj = stmt.getObject();
        }

        return ResourceFactory.createStatement(subjRes,pred,obj);
    }

}
