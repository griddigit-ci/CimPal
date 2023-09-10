/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2023, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package core;


import application.MainController;
import javafx.scene.control.ChoiceDialog;
import javafx.stage.DirectoryChooser;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.SysRIOT;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.topbraid.jenax.util.JenaUtil;
import org.topbraid.shacl.vocabulary.DASH;
import org.topbraid.shacl.vocabulary.SH;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;

import static application.MainController.*;
import static org.topbraid.shacl.vocabulary.SH.path;

public class ShaclTools {

    private static Map<String, RDFDatatype> dataTypeMapFromProfile;
    public static Model profileDataMapAsModelTemp;
    public static Resource mainClassTemp;
    public static List<Statement> rdfsHeaderStatements;


    //constructs the shape data necessary to create the set of shapes for the basic profile validations
    public static ArrayList<Object> constructShapeData(Model model, String rdfNs, String concreteNs) {

        dataTypeMapFromProfile = new HashMap<>();
        profileDataMapAsModelTemp = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();


        //Extract RDFS header information
        if (model.listSubjectsWithProperty(RDF.type,ResourceFactory.createProperty("http://www.w3.org/2002/07/owl#Ontology")).hasNext()){
            Resource hearerTypeRes = model.listSubjectsWithProperty(RDF.type,ResourceFactory.createProperty("http://www.w3.org/2002/07/owl#Ontology")).next();
            rdfsHeaderStatements = model.listStatements(new SimpleSelector(hearerTypeRes, null, (Property) null)).toList();
        }

        // structure for the list rdfToShacl
        // level 0: the profile, element 0 data for the profile
        // level 1..*: the class and then all attributes and associations including the inherited
        ArrayList<Object> rdfToShacl = new ArrayList<Object>();
        ArrayList<Object> shapeData = new ArrayList<Object>();
        for (ResIterator i = model.listResourcesWithProperty(model.getProperty(rdfNs, "stereotype")); i.hasNext(); ) {
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

            for (NodeIterator j = model.listObjectsOfProperty(resItem, model.getProperty(rdfNs, "stereotype")); j.hasNext(); ) {
                RDFNode resItemNode = j.next();

                if (resItemNode.toString().equals(concreteNs)) {// the resource is concrete - it is a concrete class
                    int root = 0;
                    Resource classItem = resItem;
                    ArrayList<Object> classData = new ArrayList<Object>(); // this collects the basic data for the class and then the attributes and associations
                    ArrayList<String> classMyData = new ArrayList<String>(); // basic data for the class
                    classMyData.add(resItem.toString()); // the complete resource of the class
                    classMyData.add(resItem.getNameSpace()); // the namespace of the resource of the class
                    classMyData.add(resItem.getLocalName()); // the local name i.e. identifiedObject
                    classMyData.add(resItem.getRequiredProperty(RDFS.label).getObject().toString()); // the label
                    if (descriptionStereotype){
                        classMyData.add("Yes"); // there is description stereotype
                    }else{
                        classMyData.add("No");
                    }


                    classData.add(classMyData); // adds the 0 element for the class where
                    //add the class data to the temp model
                    profileDataMapAsModelTemp.add(resItem,RDF.type,RDFS.Class);
                    /*
                     * 0 is the complete resource of the class
                     * 1 is the namespace of the resource of the class
                     * 2 is the local name i.e. identifiedObject
                     * 3 is the label - RDFS label
                     * 4 has "DescriptionStereotype" is the class is stereotyped description
                     */
                    mainClassTemp=classItem;
                    while (root == 0) {
                        classData = ShaclTools.getLocalAttributesAssociations(classItem, model, classData, rdfNs);
                        if (classItem.hasProperty(RDFS.subClassOf)) {//has subClassOf
                            classItem = classItem.getRequiredProperty(RDFS.subClassOf).getResource(); // the resource of the subClassOf
                        } else {
                            root = 1;
                        }
                    }
                    rdfToShacl.add(classData);
                    //System.out.println(classData);
                }
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
                    if (allSubclasses.size()==0) {
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

                        if (allConcreteSubclasses.size()==0 && allSubclasses.size()==1){
                            allConcreteSubclasses.add(ResourceFactory.createResource("http://abstract.eu"));
                            classProperty.add(allConcreteSubclasses);
                        }else if (allConcreteSubclasses.size()!=0) {
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
        String checkType = propertyNodeFeatures.get(0).toString();
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
                        } else if (propertyNodeFeatures.get(6).toString().equals("DateTimeStamp")) { //TODO check why dataTimeStamp is not in Jena
                            o10 = shapeModel.createResource(XSDDatatype.XSDdateTime.getURI());
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
//                    if (propertyNodeFeatures.get(2).toString().contains("PowerElectronicsUnit.PrimaryEnergySource")){
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
                                RDFNode o9 = shapeModel.createResource(((LinkedList<?>) propertyNodeFeatures.get(10)).get(0).toString());


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
                                RDFNode o9 = shapeModel.createResource(((LinkedList<?>) propertyNodeFeatures.get(10)).get(0).toString());

                                //adding path
                                List<RDFNode> pathList = new ArrayList<>();
                                pathList.add(shapeModel.createResource(propertyFullURI));
                                pathList.add(RDF.type.asResource());

                                RDFList pathRDFlist = shapeModel.createList(pathList.iterator());
                                r.addProperty(path, pathRDFlist);

                                //adding a message
                                //r.addProperty(SH.message, propertyNodeFeatures.get(1).toString())
                                r.addProperty(SH.message, "One of the following does not conform: 1) The value type shall be IRI; 2) The value type shall be an instance of the class: "
                                        + shapeModel.getNsURIPrefix(o9.asResource().getNameSpace()) + ":" + o9.asResource().getLocalName());

                                //adding sh:in
                                List<RDFNode> classIn = new ArrayList<>();
                                classIn.add(o9);
                                RDFList classInRDFlist = shapeModel.createList(classIn.iterator());
                                r.addProperty(SH.in, classInRDFlist);

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
                            RDFNode o9 = shapeModel.createResource(((LinkedList<?>) propertyNodeFeatures.get(10)).get(0).toString());

                            //adding path
                            RDFNode o5 = shapeModel.createResource(propertyFullURI);
                            r.addProperty(path, o5);

                            //adding the sh:class

                            r.addProperty(SH.class_, o9);

                            //adding a message
                            //r.addProperty(SH.message, propertyNodeFeatures.get(1).toString());
                            r.addProperty(SH.message, "One of the following does not conform: 1) The value type shall be IRI; 2) The value type shall be an instance of the class: "
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
                                r.addProperty(SH.message, "One of the following occurs: 1) The value type is not IRI; 2) The value type is not the right class.");

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
                                r.addProperty(SH.message, "One of the following does not conform: 1) The value type shall be IRI; 2) The value type shall be an instance of the class: "
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
                Resource resbn = ResourceFactory.createResource();
                Statement stmtbn = ResourceFactory.createStatement(resbn, SH.inversePath, ResourceFactory.createProperty(propertyNodeFeatures.get(10).toString()));
                r.addProperty(SH.path, JenaUtil.asProperty(resbn));
                shapeModel.add(stmtbn);
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
            switch (choiceDialog.resultProperty().getValue().toString()) {
                case "TURTLE":
                    savedFile=fileSave(shapeModel, "TTL files", "*.ttl", RDFFormat.TURTLE, baseURI, dirOnly, title);
                    break;
                case "RDFXML":
                    savedFile=fileSave(shapeModel, "XML files", "*.xml", RDFFormat.RDFXML, baseURI, dirOnly, title);
                    break;
                case "RDFXML_PLAIN":
                    savedFile=fileSave(shapeModel, "XML files", "*.xml", RDFFormat.RDFXML_PLAIN, baseURI, dirOnly, title);
                    break;
                case "RDFXML_ABBREV":
                    savedFile=fileSave(shapeModel, "XML files", "*.xml", RDFFormat.RDFXML_ABBREV, baseURI, dirOnly, title);
                    break;
                case "NTRIPLES":
                    savedFile=fileSave(shapeModel, "N3 files", "*.nt", RDFFormat.NTRIPLES, baseURI, dirOnly, title);
                    break;
                case "JSONLD":
                    savedFile=fileSave(shapeModel, "JSON-LD files", "*.jsonld", RDFFormat.JSONLD, baseURI, dirOnly, title);
                    break;
                case "N3":
                    savedFile=fileSave(shapeModel, "N3 files", "*.nt", RDFFormat.NT, baseURI, dirOnly, title);
                    break;
                case "RDFJSON":
                    savedFile= fileSave(shapeModel, "RDF JSON files", "*.rj", RDFFormat.RDFJSON, baseURI, dirOnly, title);
                    break;
            }
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
            saveFile = util.ModelFactory.filesavecustom(extensionText, List.of(extension),title,title.split(": ",2)[1]);
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

                    }else{
                        model.write(out, rdfFormat.getLang().getLabel().toUpperCase(),baseURI);
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
            if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#title"))) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, stmt.getPredicate(), stmt.getObject()));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://www.w3.org/2002/07/owl#versionIRI"))) {
                int len = Arrays.asList(stmt.getObject().toString().split("/")).size();
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, stmt.getPredicate(), ResourceFactory.createProperty(baseURI+"/"+ Arrays.asList(stmt.getObject().toString().split("/")).get(len-1))));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://www.w3.org/ns/dcat#keyword"))) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, stmt.getPredicate(), stmt.getObject()));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://www.w3.org/ns/dcat#theme"))) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, stmt.getPredicate(), ResourceFactory.createLangLiteral("constraint","en")));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#license"))) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, stmt.getPredicate(), stmt.getObject()));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://www.w3.org/2002/07/owl#versionInfo"))) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, stmt.getPredicate(), stmt.getObject()));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#rightsHolder"))) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, stmt.getPredicate(), stmt.getObject()));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#conformsTo"))) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, stmt.getPredicate(), stmt.getObject()));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#description"))) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, stmt.getPredicate(), ResourceFactory.createPlainLiteral("Describing constraints extracted from RDFS.")));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#modified"))) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, stmt.getPredicate(), stmt.getObject()));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#language"))) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, stmt.getPredicate(), stmt.getObject()));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#publisher"))) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, stmt.getPredicate(), stmt.getObject()));
            } else if (stmt.getPredicate().equals(ResourceFactory.createProperty("http://purl.org/dc/terms/#identifier"))) {
                shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, stmt.getPredicate(), ResourceFactory.createPlainLiteral("urn:uuid" + UUID.randomUUID())));
            }
        }
            shapeModel.add(ResourceFactory.createStatement(shaclHeaderRes, ResourceFactory.createProperty("http://purl.org/dc/terms/#issued"), ResourceFactory.createTypedLiteral(String.valueOf(java.time.LocalDateTime.now()),XSDDatatype.XSDdateTime)));
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


    //This creates a shape model from a profile
    public static Model createShapesModelFromProfile(Model model, String nsPrefixprofile, String nsURIprofile, ArrayList shapeData){

        //initial setup of the shape model
        //creates shape model. This is per profile. shapeModels is for all profiles
        Model shapeModel = JenaUtil.createDefaultModel();
        shapeModel.setNsPrefixes(model.getNsPrefixMap());
        //add the namespace of the profile
       shapeModel.setNsPrefix(nsPrefixprofile, nsURIprofile);



        //add the additional two namespaces
        shapeModel.setNsPrefix("sh", SH.NS);
        shapeModel.setNsPrefix("dash", DASH.NS);

        shapeModel.setNsPrefix(prefs.get("IOprefix",""), prefs.get("IOuri","")); // the uri for the identified object related shapes

        //adding the the two PropertyGroup-s
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

        for (int cl = 0; cl < ((ArrayList) shapeData.get(0)).size(); cl++) { //this is to loop on the classes in the profile and add NodeShape for each concrete class
            //add the ShapeNode
            String localName = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(0)).get(2).toString();
            String classFullURI = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(0)).get(0).toString();
            //add NodeShape for the CIM class
            shapeModel = ShaclTools.addNodeShape(shapeModel, nsURIprofile, localName, classFullURI);
            //check if the class is stereotyped Description
            boolean classDescripStereo=false;
            if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.get(0)).get(cl)).get(0)).get(4).toString().equals("Yes")){
                classDescripStereo=true;
            }


            for (int atas = 1; atas < ((ArrayList<?>) ((ArrayList<?>) shapeData.get(0)).get(cl)).size(); atas++) {
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

                if (((ArrayList<?>) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(0).toString().equals("Association")) {//if it is an association
                    if (((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(1).toString().equals("Yes")) {
                        //Cardinality check
                        propertyNodeFeatures.set(0, "cardinality");
                        String cardinality = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(6).toString();
                        String localNameAssoc = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(5).toString();
                        propertyNodeFeatures.set(5, cardinality);
                        Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                        propertyNodeFeatures.set(1, "Association with cardinality violation at the used direction.");
                        propertyNodeFeatures.set(2, localNameAssoc + "-cardinality");
                        propertyNodeFeatures.set(3, "This constraint validates the cardinality of the association at the used direction.");
                        propertyNodeFeatures.set(4, "Violation");
                        propertyNodeFeatures.set(8, atas - 1); // this is the order
                        propertyNodeFeatures.set(9, nsURIprofile + "CardinalityGroup"); // this is the group

                        String propertyFullURI = ((ArrayList) ((ArrayList) ((ArrayList<?>) shapeData.get(0)).get(cl)).get(atas)).get(2).toString();

                        shapeModel = ShaclTools.addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAssoc, propertyFullURI);

                        //Association check for target class

                        propertyNodeFeatures.set(0, "associationValueType");
                        //String cardinality = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(6).toString();
                        //localNameAssoc = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(5).toString();
                        //propertyNodeFeatures.set(5, cardinality);
                        //nodeShapeResource = shapeModel.getResource(nsURIprofile + localName+"ValueType");

                        //Check if there is a self assocition on model header and if this is the case then do not include sh:in and the sh:path needs to be different
                        String assocDomain = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.get(0)).get(cl)).get(atas)).get(8).toString();
                        String assocRange = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.get(0)).get(cl)).get(atas)).get(9).toString();
                        if (assocDomain.equals(assocRange) && assocDomain.equals("http://iec.ch/TC57/61970-552/ModelDescription/1#Model")) { // this is the case when it is a header
                            propertyNodeFeatures.set(1, "Not correct serialisation (rdf:resource is expected).");
                            propertyNodeFeatures.set(2, localNameAssoc + "-nodeKind");
                            propertyNodeFeatures.set(3, "This constraint validates the node kind of the association at the used direction.");
                        }else {//any other case
                            propertyNodeFeatures.set(1, "Not correct target class.");
                            propertyNodeFeatures.set(2, localNameAssoc + "-valueType");
                            propertyNodeFeatures.set(3, "This constraint validates the value type of the association at the used direction.");
                        }

                        propertyNodeFeatures.set(4, "Violation");
                        propertyNodeFeatures.set(8, atas - 1); // this is the order
                        propertyNodeFeatures.set(9, nsURIprofile + "AssociationsGroup"); // this is the group
                        List<Resource> concreteClasses = null;
                        if (localNameAssoc.contains("Equipment.AggregatedEquipment")){
                            int k=1;
                        }
//                        System.out.println(localNameAssoc);
//                        System.out.println(((ArrayList<?>) ((ArrayList<?>) shapeData.get(0)).get(cl)).get(atas));
                        // TODO check if this if is necessary if (((List<Resource>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.get(0)).get(cl)).get(atas)).get(11)).size()==1) {
//                        if (((List<Resource>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.get(0)).get(cl)).get(atas)).get(11)).size()==0) {
//                            int k=1;
//                        }

                        if (!((LinkedList<?>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.get(0)).get(cl)).get(atas)).get(11)).get(0).toString().equals("http://abstract.eu")) {
                            concreteClasses = (List<Resource>) ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.get(0)).get(cl)).get(atas)).get(11);
                            //String propertyFullURI = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(2).toString();

                            //TODO check this part as assocRange was used instead of abstract class. In the ER the Equioment is concrete
                            if (baseprofilesshaclglag == 1) {
                                // get the class URI at position 9; then search for this class in the unionmodelbaseprofilesshaclinheritanceonly and find all OWL2 members; store these members in List<Resource> concreteClasses
                                for (StmtIterator i = unionmodelbaseprofilesshaclinheritanceonly.listStatements(new SimpleSelector(ResourceFactory.createResource(assocRange), OWL2.members, (RDFNode) null)); i.hasNext(); ) {
                                    Statement stmtinheritance = i.next();
                                    concreteClasses.add(stmtinheritance.getObject().asResource());
                                }
                                if (unionmodelbaseprofilesshacl.listStatements(new SimpleSelector(ResourceFactory.createResource(assocRange), ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#concrete"))).hasNext()) {
                                    concreteClasses.add(ResourceFactory.createResource(assocRange));
                                }
                                if (concreteClasses.isEmpty()) { //it means that there are no child classes and then it needs to be checked if the class is concrete in the base data
                                    if (unionmodelbaseprofilesshacl.listStatements(new SimpleSelector(ResourceFactory.createResource(assocRange), ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#concrete"))).hasNext()) {
                                        concreteClasses.add(ResourceFactory.createResource(assocRange));
                                    } else {
                                        System.out.println("WARNING: The class " + assocRange + " is abstract and it is referenced by the association " + localNameAssoc + " for class " + classFullURI + ". ValueType SHACL constraint is set to require the type of this abstract class.");
                                        concreteClasses.add(ResourceFactory.createResource(assocRange));
                                    }
                                }
                            }



                            propertyNodeFeatures.set(10, concreteClasses);
                            propertyNodeFeatures.set(11, classFullURI);

                            shapeModel = ShaclTools.addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAssoc, propertyFullURI);
                        } else {
                            concreteClasses = new LinkedList<>();
                            String abstractclass = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.get(0)).get(cl)).get(atas)).get(9).toString();
                            if (baseprofilesshaclglag == 1) {
                                // get the class URI at position 9; then search for this class in the unionmodelbaseprofilesshaclinheritanceonly and find all OWL2 members; store these members in List<Resource> concreteClasses
                                for (StmtIterator i = unionmodelbaseprofilesshaclinheritanceonly.listStatements(new SimpleSelector(ResourceFactory.createResource(abstractclass), OWL2.members, (RDFNode) null)); i.hasNext(); ) {
                                    Statement stmtinheritance = i.next();
                                    concreteClasses.add(stmtinheritance.getObject().asResource());
                                }
                                if (unionmodelbaseprofilesshacl.listStatements(new SimpleSelector(ResourceFactory.createResource(abstractclass), ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#concrete"))).hasNext()) {
                                    concreteClasses.add(ResourceFactory.createResource(abstractclass));
                                }
                                if (concreteClasses.size() == 0) { //it means that there are no child classes and then it needs to be checked if the class is concrete in the base data
                                    if (unionmodelbaseprofilesshacl.listStatements(new SimpleSelector(ResourceFactory.createResource(abstractclass), ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#concrete"))).hasNext()) {
                                        concreteClasses.add(ResourceFactory.createResource(abstractclass));
                                    } else {
                                        System.out.println("WARNING: The class " + abstractclass + " is abstract and it is referenced by the association " + localNameAssoc + " for class " + classFullURI + ". ValueType SHACL constraint is set to require the type of this abstract class.");
                                        concreteClasses.add(ResourceFactory.createResource(abstractclass));
                                    }
                                }
                            } else {
                                System.out.println("WARNING: The class " + abstractclass + " is abstract and it is referenced by the association " + localNameAssoc + " for class " + classFullURI + ". ValueType SHACL constraint is set to require the type of this abstract class.");
                                concreteClasses.add(ResourceFactory.createResource(abstractclass));
                            }
                            propertyNodeFeatures.set(10, concreteClasses);
                            propertyNodeFeatures.set(11, classFullURI);

                            shapeModel = ShaclTools.addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAssoc, propertyFullURI);
                        }

                        // TODO check if this if is necessary}

                    }else{ // this is for cardinality of inverse associations
                        if (shaclflaginverse==1) {
                            propertyNodeFeatures.set(0, "cardinality");
                            String cardinality = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(6).toString();
                            String localNameAssoc = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(5).toString();
                            propertyNodeFeatures.set(5, cardinality);
                            Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                            propertyNodeFeatures.set(1, "Association with cardinality violation at the inverse direction.");
                            propertyNodeFeatures.set(2, localNameAssoc + "-cardinality");
                            propertyNodeFeatures.set(3, "This constraint validates the cardinality of the association at the inverse direction.");
                            propertyNodeFeatures.set(4, "Violation");
                            propertyNodeFeatures.set(8, atas - 1); // this is the order
                            propertyNodeFeatures.set(9, nsURIprofile + "CardinalityGroup"); // this is the group
                            propertyNodeFeatures.set(10, ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(10).toString()); // this is the inverse role name
                            propertyNodeFeatures.set(11, "Inverse");

                            String propertyFullURI = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(2).toString();

                            shapeModel = ShaclTools.addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAssoc, propertyFullURI);
                        }
                    }

                } else if (((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(0).toString().equals("Attribute")) {//if it is an attribute

                    Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                    String localNameAttr = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(4).toString();
                    String propertyFullURI = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(1).toString();

                    if (excludeMRID) { // user selects that mRID should be skipped for description classes
                        if (classDescripStereo) { //the class is stereotyped description
                            if (!localNameAttr.equals("IdentifiedObject.mRID")){ //the attribute is not mRID
                                shapeModel = addPropertyNodeForAttributeSingle(shapeModel, propertyNodeFeatures, shapeData, nsURIprofile, cl, atas, nodeShapeResource, localNameAttr, propertyFullURI);
                            }
                        }else{
                            shapeModel = addPropertyNodeForAttributeSingle(shapeModel, propertyNodeFeatures, shapeData, nsURIprofile, cl, atas, nodeShapeResource, localNameAttr, propertyFullURI);
                        }
                    }else{
                        shapeModel = addPropertyNodeForAttributeSingle(shapeModel, propertyNodeFeatures, shapeData, nsURIprofile, cl, atas, nodeShapeResource, localNameAttr, propertyFullURI);
                    }

                    //check if the attribute is datatype, if yes the whole structure of the compound should be checked and property nodes should be created
                    // for each attribute of the compound
                    if (((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(8).toString().equals("Compound")) {
                        ArrayList shapeDataCompound = ((ArrayList) ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(10));
                        shapeModel = addPropertyNodeForAttributeCompound(shapeModel, propertyNodeFeatures, shapeDataCompound, nsURIprofile, nodeShapeResource, localNameAttr, propertyFullURI);
                    }

                }
            }
        }

        return shapeModel;
    }

    //adds the PropertyNode for attribute for compound.
    public static Model addPropertyNodeForAttributeCompound(Model shapeModel, ArrayList<Object> propertyNodeFeatures, ArrayList shapeDataCompound,String nsURIprofile, Resource nodeShapeResource, String localNameAttr,String propertyFullURI){

            for (int atasComp = 0; atasComp < shapeDataCompound.size(); atasComp++) {
                String localNameAttrComp = ((ArrayList) shapeDataCompound.get(atasComp)).get(4).toString();
                String propertyFullURIComp = ((ArrayList) shapeDataCompound.get(atasComp)).get(1).toString();
                shapeModel= addPropertyNodeForAttributeSingleCompound(shapeModel, propertyNodeFeatures, shapeDataCompound, nsURIprofile,atasComp, nodeShapeResource,localNameAttrComp,propertyFullURIComp);
                if (((ArrayList) shapeDataCompound.get(atasComp)).get(8).toString().equals("Compound")) {
                    ArrayList shapeDataCompoundDeep = ((ArrayList) ((ArrayList) shapeDataCompound.get(atasComp)).get(10));
                    shapeModel= addPropertyNodeForAttributeCompound(shapeModel, propertyNodeFeatures, shapeDataCompoundDeep, nsURIprofile, nodeShapeResource, localNameAttrComp,propertyFullURIComp);

                }
            }



        return shapeModel;
    }

    //adds the PropertyNode for attribute. It runs one time for a regular attribute and it is reused multiple times for Compound
    public static Model addPropertyNodeForAttributeSingleCompound(Model shapeModel, ArrayList<Object> propertyNodeFeatures, ArrayList shapeData,String nsURIprofile, Integer atas, Resource nodeShapeResource, String localNameAttr,String propertyFullURI){
        //Cardinality check
        propertyNodeFeatures.set(0, "cardinality");
        String cardinality = ((ArrayList) shapeData.get(atas)).get(5).toString();
        propertyNodeFeatures.set(5, cardinality);
        propertyNodeFeatures.set(1, "Missing required attribute.");
        propertyNodeFeatures.set(2, localNameAttr + "-cardinality");
        propertyNodeFeatures.set(3, "This constraint validates the cardinality of the property (attribute).");
        propertyNodeFeatures.set(4, "Violation");
        propertyNodeFeatures.set(8, atas - 1); // this is the order
        propertyNodeFeatures.set(9, nsURIprofile + "CardinalityGroup"); // this is the group

        boolean skip=false;
        try {
            List pathCompound = (List) ((ArrayList) shapeData.get(atas)).get(11);
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
            switch (((ArrayList) shapeData.get(atas)).get(8).toString()) {
                case "Primitive": {
                    String datatypePrimitive = ((ArrayList) shapeData.get(atas)).get(10).toString(); //this is localName e.g. String

                    propertyNodeFeatures.set(6, datatypePrimitive);
                    propertyNodeFeatures.set(1, "The datatype is not literal or it violates the xsd datatype.");

                    break;
                }
                case "CIMDatatype": {
                    String datatypePrimitive = ((ArrayList) shapeData.get(atas)).get(9).toString(); //this is localName e.g. String

                    propertyNodeFeatures.set(6, datatypePrimitive);
                    propertyNodeFeatures.set(1, "The datatype is not literal or it violates the xsd datatype.");

                    break;
                }
                case "Compound": {
                    String datatypeCompound = ((ArrayList) shapeData.get(atas)).get(9).toString(); //this is localName e.g. String

                    propertyNodeFeatures.set(6, "Compound");
                    propertyNodeFeatures.set(1, "Blank node (compound datatype) violation. Either it is not a blank node (nested structure, compound datatype) or it is not the right class.");
                    propertyNodeFeatures.set(12, datatypeCompound);
                    break;
                }
                case "Enumeration":
                    propertyNodeFeatures.set(6, "Enumeration");
                    propertyNodeFeatures.set(1, "The datatype is not IRI (Internationalized Resource Identifier) or it is an enumerated value which is not part of the enumeration.");
                    //this adds the structure which is a list of possible enumerated values
                    propertyNodeFeatures.set(7, ((ArrayList) shapeData.get(atas)).get(10));
                    break;
            }
            shapeModel = ShaclTools.addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAttr, propertyFullURI);
            propertyNodeFeatures.set(13, ""); // in order to be empty for the next attribute
        }
        return shapeModel;
    }

    //adds the PropertyNode for attribute. It runs one time for a regular attribute and it is reused multiple times for Compound
    public static Model addPropertyNodeForAttributeSingle(Model shapeModel, ArrayList<Object> propertyNodeFeatures, ArrayList shapeData,String nsURIprofile, Integer cl, Integer atas, Resource nodeShapeResource, String localNameAttr,String propertyFullURI){
        //Cardinality check
        propertyNodeFeatures.set(0, "cardinality");
        String cardinality = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(5).toString();
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
        switch (((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(8).toString()) {
            case "Primitive": {
                String datatypePrimitive = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(10).toString(); //this is localName e.g. String

                propertyNodeFeatures.set(6, datatypePrimitive);
                propertyNodeFeatures.set(1, "The datatype is not literal or it violates the xsd datatype.");

                break;
            }
            case "CIMDatatype": {
                String datatypePrimitive = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(9).toString(); //this is localName e.g. String

                propertyNodeFeatures.set(6, datatypePrimitive);
                propertyNodeFeatures.set(1, "The datatype is not literal or it violates the xsd datatype.");

                break;
            }
            case "Compound": {
                String datatypeCompound = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(9).toString(); //this is localName e.g. String

                propertyNodeFeatures.set(6, "Compound");
                propertyNodeFeatures.set(1, "Blank node (compound datatype) violation. Either it is not a blank node (nested structure, compound datatype) or it is not the right class.");
                propertyNodeFeatures.set(12, datatypeCompound);
                break;
            }
            case "Enumeration":
                propertyNodeFeatures.set(6, "Enumeration");
                propertyNodeFeatures.set(1, "The datatype is not IRI (Internationalized Resource Identifier) or it is enumerated value not part of the profile.");
                //this adds the structure which is a list of possible enumerated values
                propertyNodeFeatures.set(7, ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(10));
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

}
