package eu.griddigit.cimpal.main.application.datagenerator;

import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.util.*;

public class ProfileDataFactory {

    private static Map<String, RDFDatatype> dataTypeMapFromProfile;
    public static Model profileDataMapAsModelTemp;
    public static Resource mainClassTemp;

    //constructs a structure based on the profile data
    public static ArrayList<Object> constructShapeData(Model model, String rdfNs, String concreteNs) {

        dataTypeMapFromProfile = new HashMap<>();
        profileDataMapAsModelTemp = ModelFactory.createDefaultModel();

        // structure for the list rdfToShacl
        // level 0: the profile, element 0 data for the profile
        // level 1..*: the class and then all attributes and associations including the inherited
        ArrayList<Object> rdfToShacl = new ArrayList<Object>();
        ArrayList<Object> shapeData = new ArrayList<Object>();
        for (ResIterator i = model.listResourcesWithProperty(model.getProperty(rdfNs, "stereotype")); i.hasNext(); ) {
            Resource resItem = i.next();
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

                    classData.add(classMyData); // adds the 0 element for the class where
                    //add the class data to the temp model
                    profileDataMapAsModelTemp.add(resItem,RDF.type,RDFS.Class);
                    /*
                     * 0 is the complete resource of the class
                     * 1 is the namespace of the resource of the class
                     * 2 is the local name i.e. identifiedObject
                     * 3 is the label - RDFS label
                     */
                    mainClassTemp=classItem;
                    while (root == 0) {
                        classData = getLocalAttributesAssociations(classItem, model, classData, rdfNs);
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
        for (int clas = 0; clas < rdfToShacl.size(); clas++) {
            for (int attr = 1; attr < ((ArrayList) rdfToShacl.get(clas)).size(); attr++) {
                if (((ArrayList) ((ArrayList) rdfToShacl.get(clas)).get(attr)).get(0).equals("Attribute") && ((ArrayList) ((ArrayList) rdfToShacl.get(clas)).get(attr)).get(8).equals("Compound")){
                    List<RDFNode> pathComp= new LinkedList<>();
                    RDFNode r = ResourceFactory.createResource(((ArrayList) ((ArrayList) rdfToShacl.get(clas)).get(attr)).get(1).toString());
                    pathComp.add(r);
                    ((ArrayList) ((ArrayList) rdfToShacl.get(clas)).get(attr)).add(pathComp); // this is adding item 11 for the compound attribute
                    // this value will need to be added as item 11 of all attributes of the compound
                    //then go to the next level of compound
                    //TODO: ATTENTION: only 2 levels of nested compounds supported - see how to do it in a cleaver way
                    for (int attrComp = 0; attrComp < ((ArrayList) ((ArrayList) ((ArrayList) rdfToShacl.get(clas)).get(attr)).get(10)).size(); attrComp++) {
                        List<RDFNode> pathComp1= new LinkedList<>();
                        RDFNode r1 = ResourceFactory.createResource(((ArrayList) ((ArrayList) ((ArrayList) ((ArrayList) rdfToShacl.get(clas)).get(attr)).get(10)).get(attrComp)).get(1).toString());
                        pathComp1.add(r);
                        pathComp1.add(r1);
                        ((ArrayList) ((ArrayList) ((ArrayList) ((ArrayList) rdfToShacl.get(clas)).get(attr)).get(10)).get(attrComp)).add(pathComp1); // this is adding item 11 for the compound attribute
                        if (((ArrayList) ((ArrayList) ((ArrayList) ((ArrayList) rdfToShacl.get(clas)).get(attr)).get(10)).get(attrComp)).get(0).equals("Attribute") && ((ArrayList) ((ArrayList) ((ArrayList) ((ArrayList) rdfToShacl.get(clas)).get(attr)).get(10)).get(attrComp)).get(8).equals("Compound")) {
                            for (int attrComp2 = 0; attrComp2 < ((ArrayList) ((ArrayList) ((ArrayList) ((ArrayList) ((ArrayList) rdfToShacl.get(clas)).get(attr)).get(10)).get(attrComp)).get(10)).size(); attrComp2++) {
                                List<RDFNode> pathComp2 = new LinkedList<>();
                                RDFNode r2 = ResourceFactory.createResource(((ArrayList) ((ArrayList) ((ArrayList) ((ArrayList) ((ArrayList) ((ArrayList) rdfToShacl.get(clas)).get(attr)).get(10)).get(attrComp)).get(10)).get(attrComp2)).get(1).toString());
                                pathComp2.add(r);
                                pathComp2.add(r1);
                                pathComp2.add(r2);
                                ((ArrayList) ((ArrayList) ((ArrayList) ((ArrayList) ((ArrayList) ((ArrayList) rdfToShacl.get(clas)).get(attr)).get(10)).get(attrComp)).get(10)).get(attrComp2)).add(pathComp2); // this is adding item 11 for the compound attribute
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
    private static ArrayList<Object> getLocalAttributesAssociations(Resource resItem, Model model, ArrayList<Object> classData, String rdfNs) {
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
                10 linked list of concrete classes that inherit that association
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

        //Map dataTypeMapFromShapes= MainController.getDataTypeMapFromShapes();
        // int shaclNodataMap= MainController.getShaclNodataMap();

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

                classProperty.add(resItemDomain.toString()); // the complete resource of the attribute - item 1 for attribute, item 2 for association
                profileDataMapAsModelTemp.add(resItemDomain,RDF.type,RDF.Property);
                profileDataMapAsModelTemp.add(resItemDomain,RDFS.domain,mainClassTemp); //this is to assign not yo the original class but as property of the class that is processed
                classProperty.add(resItemDomain.getNameSpace()); // the namespace of the resource of the attribute - item 2 for attribute, item 3 for association
                classProperty.add(model.getNsURIPrefix(resItemDomain.getNameSpace())); // this is the prefix - item 3 for attribute, item 4 for association
                classProperty.add(resItemDomain.getLocalName()); // the local name i.e. identifiedObject - item 4 for attribute, item 5 for association
                if (resItemDomain.getLocalName().equals("IdentifiedObject.name")) {
                    int test = 1;
                }
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
                                //if (shaclNodataMap==0) {  // only if the user selects to create datamap when generating the shape file
                                    dataTypeMapFromProfile = DataTypeMaping.addDatatypeMapProperty(dataTypeMapFromProfile, resItemDomain.toString(), resItemNode.asResource().getLocalName());
                                //}
                                classProperty.add(resItemNode.toString()); // the resource - item 9
                                classProperty.add(resItemNode.toString().split("#", 2)[1]); //the local name of the resource e.g. "String" - item 10
                            }
                            if (resItemNew.hasProperty(model.getProperty(rdfNs, "stereotype"),"CIMDatatype")){
                                classProperty.add("CIMDatatype"); // item 8
                                Resource resItemDTvalue=model.getResource(resItemNew+".value");
                                for (NodeIterator jdt = model.listObjectsOfProperty(resItemDTvalue, model.getProperty(rdfNs, "dataType")); jdt.hasNext(); ) {
                                    RDFNode resItemNodedt = jdt.next();
                                    classProperty.add(resItemNodedt.asResource().getLocalName());// item 9
                                    //if (shaclNodataMap==0) { // only if the user selects to create datamap when generating the shape file
                                        dataTypeMapFromProfile = DataTypeMaping.addDatatypeMapProperty(dataTypeMapFromProfile, resItemDomain.toString(), resItemNodedt.asResource().getLocalName());
                                    //}
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
                    //get all subclasses (concrete and abstract) for the range of the association
                    Resource classRange=resItemDomain.getRequiredProperty(RDFS.range).getObject().asResource();
                    List<Resource> allSubclasses = getSubtypeClassesNlevel(classRange, model);
                    //check if the class is concrete
                    //TODO see if something should be done for abstract classes
                    if (allSubclasses.size()==0) {
                        Boolean concrete= classIsConcrete(classRange, model);
                        if (concrete) {
                            List<Resource> concreteSubclass = new LinkedList<>();
                            concreteSubclass.add(classRange);
                            classProperty.add(concreteSubclass);
                            //System.out.println("Association class is concrete" + classRange.toString());
                        }
                    }else{
                        List<Resource> allConcreteSubclasses = new LinkedList<>();
                        //here this is needed to cover the case when there are subclasses, but also the main class is concrete and needs to be put in the list
                        Boolean concreteMainclass= classIsConcrete(classRange, model);
                        if (concreteMainclass) {
                            allConcreteSubclasses.add(classRange);
                        }

                        //this below is for the subclasses
                        for (Iterator isub = allSubclasses.iterator(); isub.hasNext();) {
                            Resource subclass = (Resource) isub.next();
                            Boolean concrete= classIsConcrete(subclass, model);
                            if (concrete){
                                allConcreteSubclasses.add(subclass);
                            }
                        }
                        //System.out.println(allConcreteSubclasses);
                        classProperty.add(allConcreteSubclasses);
                    }
                }
                /*for (int p=0; p<classProperty.size(); p++) {// test printing
                    System.out.println(classProperty.get(p));
                }*/
                classData.add(classProperty);
            }
        }
        /*if (shaclNodataMap==0) { // only if the user selects to create datamap when generating the shape file
            MainController.setDataTypeMapFromShapes(dataTypeMapFromShapes);
        }*/

        return classData;
    }

    //returns a structure that contains the attributes of a compound
    private static ArrayList getAttributesOfCompound(Resource resItem, Model model, ArrayList<Object> classCompound, String rdfNs){
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

        //Map dataTypeMapFromShapes= MainController.getDataTypeMapFromShapes();
        //int shaclNodataMap= MainController.getShaclNodataMap();

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
                                //if (shaclNodataMap==0) {  // only if the user selects to create datamap when generating the shape file
                                    dataTypeMapFromProfile = DataTypeMaping.addDatatypeMapProperty(dataTypeMapFromProfile, resItemDomain.toString(), resItemNode.asResource().getLocalName());
                                //}
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
        /*if (shaclNodataMap==0) { // only if the user selects to create datamap when generating the shape file
            MainController.setDataTypeMapFromShapes(dataTypeMapFromShapes);
        }*/

        return classCompound;


    }

    //get subtypes of a class - first level
    private static List<Resource> getSubtypeClassesFirstLevel(Resource classRange, Model model){

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
    private static List<Resource> getSubtypeClassesNlevel(Resource classRange, Model model){
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

    private static Boolean classIsConcrete(Resource resource, Model model){
        boolean concrete=false;
        for (NodeIterator j = model.listObjectsOfProperty(resource, model.getProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "stereotype")); j.hasNext(); ) {
            RDFNode resItemNode = j.next();
            if (resItemNode.toString().equals("http://iec.ch/TC57/NonStandard/UML#concrete")) {// the resource is concrete - it is a concrete class
                concrete=true;
            }
        }
        return concrete;
    }

}
