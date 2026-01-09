/**
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package eu.griddigit.cimpal.main.application.datagenerator;

import application.taskControllers.WizardContext;
import datagenerator.resources.UnzippedFiles;
import javafx.scene.control.Alert;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.DCAT;
import org.apache.jena.vocabulary.RDF;
import org.topbraid.shacl.vocabulary.SH;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class InstanceDataFactory {

    public static Resource classResource;
    public static Resource classResourceTerminal;
    private static Map<String,Integer> terminalMap;
    private static Map<String,Integer> terminalDCMap;
    private static Map<String,Integer> skipClassMap;
    private static Map<String,List<String>> headerDependencyMap;
    private static ArrayList<Object> skippedClassPropertyProcessed;
    private static ArrayList<Object> addedClassPropertyProcessed;
    public static List<RDFNode> supportedProperties;
    public static Map<Property,Map> shaclConstraints;
    public static int countOfRequiredAttributes;
    public static int countOfOptionalAttributes;
    public static int countOfRequiredAssociations;
    public static int countOfOptionalAssociations;
    public static int countOfClasses;
    public static Resource resDummyClass;
    public static Map<String,Statement> modelSplitMap;
    public static Map<Integer,Resource> connectivityNodeMap;
    public static Map<Integer,Resource> topologicalNodeMap;
    public static List<String> shuntCompensatorList;
    public static List<String> tapChangerList;
    public static List<String> conductingEquipmentList;



    //process profile data and all elements/equipment to single node
    private static Model processAndAddSingle(ArrayList<Object> profileData, Model instanceDataModel, Model shaclModel, String xmlBase, Integer cardinalityFlag, Boolean conform, String desiredClass, Map<String, Boolean> inputData) {


        for (int cl = 0; cl < ((ArrayList) profileData.get(0)).size(); cl++) { //this is to loop on the classes in the profile and add class for each concrete class


            //add a class
            String classFullURI = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(0)).get(0).toString();
            String classLocalName = classFullURI.split("#",2)[1];
            /*if (classLocalName.equals("Command")){
                int k=1;
            }*/
            if ((!classLocalName.equals("Terminal") && !classLocalName.equals("DCTerminal") && !classLocalName.equals("ACDCConverterDCTerminal") && !classLocalName.equals("ConnectivityNode") && !classLocalName.equals("TopologicalNode") && !skipClassMap.containsKey(classLocalName) && desiredClass==null) || (classLocalName.equals(desiredClass))) {
                if (desiredClass==null) {
                    //need to check if the class is already in the model; if not then it is added; if yes associations and attributes are added to it
                    boolean classInModel = instanceDataModel.listSubjectsWithProperty(RDF.type, ResourceFactory.createResource(classFullURI)).hasNext();
                    if (!classInModel) {
                        instanceDataModel = addClass(profileData, instanceDataModel, xmlBase, classFullURI, cardinalityFlag, inputData);
                    } else {
                        classResource = instanceDataModel.listSubjectsWithProperty(RDF.type, ResourceFactory.createResource(classFullURI)).next();
                    }
                }else{//if desired class is defined then the class is always added.
                    instanceDataModel = addClass(profileData, instanceDataModel, xmlBase, classFullURI, cardinalityFlag, inputData);
                }



                for (int atas = 1; atas < ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).size(); atas++) {
                    // this is to loop on the attributes and associations (including inherited) for a given class and attributes or associations


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
                     * 10 - the list of concrete classes for association - the value type at the used end
                     * 11 - classFullURI for the targetClass of the NodeShape
                     * 12 - the uri of the compound class to be used in sh:class
                     * 13 - path for the attributes of the compound
                     */
                   /* for (int i = 0; i < 14; i++) {
                        propertyNodeFeatures.add("");
                    }*/

                    if (((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(0).toString().equals("Association")) {//if it is an association
                        if (((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(1).toString().equals("Yes")) {

                            String cardinality = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(6).toString();
                            String localNameAssoc = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(5).toString();
                            String propertyFullURI = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(2).toString();
                            Property property = ResourceFactory.createProperty(propertyFullURI);


                            //Association info for target class - only for concrete classes in the profile
                            if (((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).size()>10) { //TODO check if this is OK. This is to avoid crash if there are no concrete classes in the profile, but something should be done for the abstract classes which are concrete in another profile
                                //propertyNodeFeatures.set(0, "associationValueType");
                                //String cardinality = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(6).toString();
                                //localNameAssoc = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(5).toString();
                                //propertyNodeFeatures.set(5, cardinality);
                                //nodeShapeResource = shapeModel.getResource(nsURIprofile + localName+"ValueType");
                                //propertyNodeFeatures.set(1, "Not correct target class.");
                                //propertyNodeFeatures.set(2, localNameAssoc + "-valueType");
                                //propertyNodeFeatures.set(3, "This constraint validates the value type of the association at the used direction.");
                                //propertyNodeFeatures.set(4, "Violation");
                                //propertyNodeFeatures.set(8, atas - 1); // this is the order
                                //propertyNodeFeatures.set(9, nsURIprofile + "AssociationsGroup"); // this is the group
                                List<Resource> concreteClasses = (List<Resource>) ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(10);
                                //propertyNodeFeatures.set(10, concreteClasses);
                                //String propertyFullURI = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(2).toString();
                                //propertyNodeFeatures.set(11, classFullURI);

                                //shapeModel = ShaclTools.addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAssoc, propertyFullURI);

                                //check if any of the concrete classes are already in the instance model and use them. This is done also in cases where shacl is restricting
                                instanceDataModel=selectAssociationReferenceAndAddAssociation(instanceDataModel,concreteClasses,inputData,classFullURI,property,conform,profileData,xmlBase,cardinalityFlag,cardinality, classLocalName);

                            }




                    /*    //Cardinality check
                        propertyNodeFeatures.set(0, "cardinality");
                        String cardinality = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(6).toString();
                        String localNameAssoc = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(5).toString();
                        propertyNodeFeatures.set(5, cardinality);
                        Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                        propertyNodeFeatures.set(1, "Missing required association.");
                        propertyNodeFeatures.set(2, localNameAssoc + "-cardinality");
                        propertyNodeFeatures.set(3, "This constraint validates the cardinality of the association at the used direction.");
                        propertyNodeFeatures.set(4, "Violation");
                        propertyNodeFeatures.set(8, atas - 1); // this is the order
                        propertyNodeFeatures.set(9, nsURIprofile + "CardinalityGroup"); // this is the group

                        String propertyFullURI = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(2).toString();

                        shapeModel = ShaclTools.addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAssoc, propertyFullURI);

                        //Association check for target class - only for concrete classes in the profile
                        if (((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).size()>10) { //TODO check if this is OK. This is to avoid crash if there are no concrete classes in the profile, but something should be done for the abstract classes which are concrete in another profile
                            propertyNodeFeatures.set(0, "associationValueType");
                            //String cardinality = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(6).toString();
                            //localNameAssoc = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(5).toString();
                            //propertyNodeFeatures.set(5, cardinality);
                            //nodeShapeResource = shapeModel.getResource(nsURIprofile + localName+"ValueType");
                            propertyNodeFeatures.set(1, "Not correct target class.");
                            propertyNodeFeatures.set(2, localNameAssoc + "-valueType");
                            propertyNodeFeatures.set(3, "This constraint validates the value type of the association at the used direction.");
                            propertyNodeFeatures.set(4, "Violation");
                            propertyNodeFeatures.set(8, atas - 1); // this is the order
                            propertyNodeFeatures.set(9, nsURIprofile + "AssociationsGroup"); // this is the group
                            List<Resource> concreteClasses = (List<Resource>) ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(10);
                            propertyNodeFeatures.set(10, concreteClasses);
                            //String propertyFullURI = ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(2).toString();
                            propertyNodeFeatures.set(11, classFullURI);

                            shapeModel = ShaclTools.addPropertyNode(shapeModel, nodeShapeResource, propertyNodeFeatures, nsURIprofile, localNameAssoc, propertyFullURI);
                        }*/
                        }

                    } else if (((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(0).toString().equals("Attribute")) {//if it is an attribute
                        //Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                        //String localNameAttr = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(4).toString();
                        String propertyFullURI = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(1).toString();
                        Property property = ResourceFactory.createProperty(propertyFullURI);
                        String cardinality = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(5).toString();

                        String datatypeType = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(8).toString();
                        String datatypeValueString = null;
                        Object datatypeValuesEnum = null;

                       /* if (property.getLocalName().equals("Model.profile")){
                            int k=1;
                        }*/

                        switch (datatypeType) {
                            case "Primitive": {
                                datatypeValueString = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(10).toString(); //this is localName e.g. String

                                //propertyNodeFeatures.set(6, datatypePrimitive);
                                //propertyNodeFeatures.set(1, "The datatype is not literal or it violates the xsd datatype.");

                                break;
                            }
                            case "CIMDatatype": {
                                datatypeValueString = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(9).toString(); //this is localName e.g. String

                                //propertyNodeFeatures.set(6, datatypePrimitive);
                                //propertyNodeFeatures.set(1, "The datatype is not literal or it violates the xsd datatype.");

                                break;
                            }
                            case "Compound": {
                                datatypeValueString = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(9).toString(); //this is localName e.g. String

                                //propertyNodeFeatures.set(6, "Compound");
                                //propertyNodeFeatures.set(1, "Blank node (compound datatype) violation. Either it is not a blank node (nested structure, compound datatype) or it is not the right class.");
                                //propertyNodeFeatures.set(12, datatypeCompound);
                                //break;
                            }
                            case "Enumeration":
                                //propertyNodeFeatures.set(6, "Enumeration");
                                //propertyNodeFeatures.set(1, "The datatype is not IRI (Internationalized Resource Identifier) or it is enumerated value not part of the profile.");
                                //this adds the structure which is a list of possible enumerated values

                                datatypeValuesEnum = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(10);
                                //propertyNodeFeatures.set(7, ((ArrayList) ((ArrayList) ((ArrayList) shapeData.get(0)).get(cl)).get(atas)).get(10));
                                break;
                        }

                        instanceDataModel = addAttribute(instanceDataModel, property, conform, datatypeType, datatypeValueString, datatypeValuesEnum, cardinality, cardinalityFlag,classLocalName,null,inputData,classFullURI);
                        //saveInstanceData(instanceDataModel, xmlBase);



                    /*shapeModel= addPropertyNodeForAttributeSingle(shapeModel, propertyNodeFeatures, shapeData, nsURIprofile, cl, atas, nodeShapeResource,localNameAttr,propertyFullURI);
                    //check if the attribute is datatype, if yes the whole structure of the compound should be checked and property nodes should be created
                    // for each attribute of the compound
                    if (((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(8).toString().equals("Compound")) {
                        ArrayList shapeDataCompound = ((ArrayList) ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(10));
                        shapeModel= addPropertyNodeForAttributeCompound(shapeModel, propertyNodeFeatures, shapeDataCompound, nsURIprofile, nodeShapeResource, localNameAttr,propertyFullURI);
                    }*/
                    }
                }
            }
        }


        return instanceDataModel;
    }

    //Loads one or many models
    public static Model modelLoadShaclFiles(String[] files, String xmlBase) throws FileNotFoundException {
        Model modelUnion = ModelFactory.createDefaultModel();
        Map prefixMap = modelUnion.getNsPrefixMap();
        for (String file : files) {
            Lang rdfSourceFormat = null;
            String extension = FilenameUtils.getExtension(file);
            if (extension.equals("rdf") || extension.equals("xml")){
                rdfSourceFormat=Lang.RDFXML;
            }else if(extension.equals("ttl")){
                rdfSourceFormat=Lang.TURTLE;
            }else if(extension.equals("jsonld")){
                rdfSourceFormat=Lang.JSONLD;
            }

            Model model = ModelFactory.createDefaultModel();
            InputStream inputStream = new FileInputStream(file);
            RDFDataMgr.read(model, inputStream, xmlBase, rdfSourceFormat);
            prefixMap.putAll(model.getNsPrefixMap());

            modelUnion.add(model);
        }

        modelUnion.setNsPrefixes(prefixMap);

        Model shaclModel = ShapeFactory.createShapeModelWithOwlImport(modelUnion);
        return shaclModel;
    }


    //Add class to instance data model
    private static Model addClass(ArrayList<Object> profileData, Model instanceDataModel, String xmlBase, String classFullURI,Integer cardinalityFlag,Map<String,Boolean> inputData) {

        RDFNode rdfType = ResourceFactory.createResource(classFullURI);
        //an exception for FullModel and Difference model classes
        String uuidPrefix=xmlBase+"#_";
        if (rdfType.asResource().getLocalName().equals("FullModel") || rdfType.asResource().getLocalName().equals("DifferenceModel")){
            uuidPrefix="urn:uuid:";
        }
        classResource = ResourceFactory.createResource(uuidPrefix+UUID.randomUUID());
        RDFNode resource = classResource;

        Statement stmt = ResourceFactory.createStatement(classResource, RDF.type,rdfType);
        instanceDataModel.add(stmt);
        countOfClasses=countOfClasses+1;

        //add necessary number of terminals
        if (!rdfType.asResource().getLocalName().equals("Terminal") || !rdfType.asResource().getLocalName().equals("DCTerminal") || !rdfType.asResource().getLocalName().equals("ACDCConverterDCTerminal")) {
            Map<String,Integer> terminals = identifyClassType(rdfType.asResource().getLocalName());
            if(terminals.containsKey("AC")) {
                if (terminals.get("AC") == 1) {
                    addTerminal(profileData, instanceDataModel, xmlBase, 1, cardinalityFlag, true, resource, inputData);
                } else if (terminals.get("AC") == 2) {
                    addTerminal(profileData, instanceDataModel, xmlBase, 1, cardinalityFlag, true, resource, inputData);
                    addTerminal(profileData, instanceDataModel, xmlBase, 2, cardinalityFlag, true, resource, inputData);
                }
            }else if(terminals.containsKey("DC")) {
                if (terminals.get("DC") == 1) {
                    addDCTerminal(profileData, instanceDataModel, xmlBase, 1, cardinalityFlag, true, resource, inputData);
                } else if (terminals.get("DC") == 2) {
                    addDCTerminal(profileData, instanceDataModel, xmlBase, 1, cardinalityFlag, true, resource, inputData);
                    addDCTerminal(profileData, instanceDataModel, xmlBase, 2, cardinalityFlag, true, resource, inputData);
                }
            }
        }
        //need to add 2 ACDCConverter Terminal-s
        if (rdfType.asResource().getLocalName().equals("VsConverter") || rdfType.asResource().getLocalName().equals("CsConverter")) {
            addACDCConverterDCTerminal(profileData, instanceDataModel, xmlBase, 1, cardinalityFlag, true, resource, inputData);
            addACDCConverterDCTerminal(profileData, instanceDataModel, xmlBase, 2, cardinalityFlag, true, resource, inputData);
        }


        return instanceDataModel;
    }

    //identify equipment classes and their number of terminals
    private static Map<String,Integer> identifyClassType(String classURILocalName) {

        Map<String,Integer> terminals=new HashMap<>();

        if (terminalMap.containsKey(classURILocalName)) {
            terminals.put("AC", terminalMap.get(classURILocalName));
        }else if(terminalDCMap.containsKey(classURILocalName)){
            terminals.put("DC", terminalDCMap.get(classURILocalName));
        }else{
            GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"[Warning] The class: "+classURILocalName+" will not have Terminal.",true);
        }
        return terminals;
    }

    //Add attribute to instance data model
    private static Model addAttribute(Model instanceDataModel, Property property, Boolean conform, String datatypeType,
                                      String datatypeValueString, Object datatypeValuesEnum,
                                      String cardinality, Integer cardinalityFlag, String classLocalName, Object concreteValue,Map<String,Boolean> inputData, String classFullURI) {


        Map<String,Object> shaclContraintResult = null;
        Property fullClassPropertyKey = ResourceFactory.createProperty(classFullURI+"propertyFullURI"+property);
        //check if there are some shacl constraints to be considered
        if (inputData.get("shacl")){ //shacl constraints are to be considered
            if (shaclConstraints.containsKey(fullClassPropertyKey)){ //the attribute has some constraints defined
                shaclContraintResult=getShaclConstraint(conform, fullClassPropertyKey);
            }
        }

        Resource classRes;
        if (classLocalName.equals("Terminal") || classLocalName.equals("DCTerminal") || classLocalName.equals("ACDCConverterDCTerminal")){
            classRes = classResourceTerminal;
        }else {
            classRes = classResource;
        }

        if(property.getLocalName().equals("Conductor.length")){
            int k=1;
        }

        Statement stmt = null;
        String attributeValue=null;

        Map<String,Integer> cardinalityCheckResult= cardinalityCheck(cardinality, shaclContraintResult,property);
        int lowerBound = cardinalityCheckResult.get("lowerBound");
        int upperBound =cardinalityCheckResult.get("upperBound");

        List<RDFNode> inValues = new ArrayList<>();

        //cardinalityFlag 1 = required only; 0=optional only ; 3=all - optional and required
        if ((cardinalityFlag==1 && lowerBound>=1 && upperBound>=1) ||
                (cardinalityFlag==0 && lowerBound==0 && upperBound>=1) ||
                cardinalityFlag==3) {

            if (lowerBound>=1 && upperBound>=1){//required
                countOfRequiredAttributes=countOfRequiredAttributes+1;
            }else if(lowerBound==0 && upperBound>=1) {//optional
                countOfOptionalAttributes=countOfOptionalAttributes+1;
            }

            //the attribute is added and need to record this
            ((LinkedList) addedClassPropertyProcessed.get(0)).add(instanceDataModel.getNsURIPrefix(classRes.getNameSpace())+":"+classLocalName);
            ((LinkedList) addedClassPropertyProcessed.get(1)).add(instanceDataModel.getNsURIPrefix(property.getNameSpace())+":"+property.getLocalName());
            ((LinkedList) addedClassPropertyProcessed.get(2)).add("Attribute "+lowerBound+".."+upperBound);

            switch (datatypeType) {
                case "Primitive":
                case "CIMDatatype": {
                    switch (datatypeValueString) {
                        case "Integer":
                            if (concreteValue==null) {
                                Map<String,Integer> valuesInt = new HashMap<>();
                                if (shaclContraintResult!=null) {
                                    if (shaclContraintResult.containsKey("minExclusive")) {
                                        valuesInt.put("minExclusive",((RDFNode) shaclContraintResult.get("minExclusive")).asLiteral().getInt());
                                    }
                                    if (shaclContraintResult.containsKey("minInclusive")) {
                                        valuesInt.put("minInclusive",((RDFNode) shaclContraintResult.get("minInclusive")).asLiteral().getInt());
                                    }
                                    if (shaclContraintResult.containsKey("maxExclusive")) {
                                        valuesInt.put("maxExclusive",((RDFNode) shaclContraintResult.get("maxExclusive")).asLiteral().getInt());
                                    }
                                    if (shaclContraintResult.containsKey("maxInclusive")) {
                                        valuesInt.put("maxInclusive",((RDFNode) shaclContraintResult.get("maxInclusive")).asLiteral().getInt());
                                    }
                                    if (!shaclContraintResult.containsKey("minExclusive") && !shaclContraintResult.containsKey("minInclusive")){
                                        valuesInt.put("minInclusive",0);
                                    }
                                    if (!shaclContraintResult.containsKey("maxExclusive") && !shaclContraintResult.containsKey("maxInclusive")){
                                        valuesInt.put("maxInclusive",999);
                                    }
                                    if (shaclContraintResult.containsKey("in")) {
                                        inValues = (List<RDFNode>) shaclContraintResult.get("in");
                                    }
                                }else{
                                    valuesInt.put("minInclusive",0);
                                    valuesInt.put("maxInclusive",999);
                                }

                                attributeValue = generateAttributeValueInteger(conform, valuesInt, inValues);

                            }else{
                                attributeValue=concreteValue.toString();
                            }
                            break;
                        case "Float":
                            Map<String,Float> valuesFL = new HashMap<>();
                            if (shaclContraintResult!=null) {
                                if (shaclContraintResult.containsKey("minExclusive")) {
                                    valuesFL.put("minExclusive",((RDFNode) shaclContraintResult.get("minExclusive")).asLiteral().getFloat());
                                }
                                if (shaclContraintResult.containsKey("minInclusive")) {
                                    valuesFL.put("minInclusive",((RDFNode) shaclContraintResult.get("minInclusive")).asLiteral().getFloat());
                                }
                                if (shaclContraintResult.containsKey("maxExclusive")) {
                                    valuesFL.put("maxExclusive",((RDFNode) shaclContraintResult.get("maxExclusive")).asLiteral().getFloat());
                                }
                                if (shaclContraintResult.containsKey("maxInclusive")) {
                                    valuesFL.put("maxInclusive",((RDFNode) shaclContraintResult.get("maxInclusive")).asLiteral().getFloat());
                                }
                                if (shaclContraintResult.containsKey("in")) {
                                    inValues = (List<RDFNode>) shaclContraintResult.get("in");
                                }
                            }else{
                                valuesFL.put("minInclusive", (float) 0.1);
                                valuesFL.put("maxInclusive", (float) 999.1);
                            }
                            attributeValue = generateAttributeValueFloat(conform,valuesFL);
                            break;

                        case "String":
                        case "StringFixedLanguage":
                            Map<String,Integer> valuesST = new HashMap<>();
                            if (property.getLocalName().equals("IdentifiedObject.mRID")){
                                attributeValue=classRes.getLocalName().split("_",2)[1];
                            }else {
                                if (shaclContraintResult != null) {
                                    if (shaclContraintResult.containsKey("minLength")) {
                                        valuesST.put("minLength", ((RDFNode) shaclContraintResult.get("minLength")).asLiteral().getInt());
                                    }
                                    if (shaclContraintResult.containsKey("maxLength")) {
                                        valuesST.put("maxLength", ((RDFNode) shaclContraintResult.get("maxLength")).asLiteral().getInt());
                                    }
                                    if (shaclContraintResult.containsKey("in")) {
                                        inValues = (List<RDFNode>) shaclContraintResult.get("in");
                                    }
                                } else {
                                    valuesST.put("minLength", 1);
                                    valuesST.put("maxLength", 10);
                                }
                                attributeValue = generateAttributeValueString(conform,valuesST,inValues);
                            }
                            break;

                        case "Boolean":
                            if (property.getLocalName().equals("ACDCTerminal.connected")){
                                attributeValue="true";
                            }else {
                                attributeValue = generateAttributeValueBoolean(conform);
                            }
                            if(!conform){
                                attributeValue="truee";
                            }
                            break;

                        case "Date":
                            long aDay = TimeUnit.DAYS.toMillis(1);
                            long now = new Date().getTime();
                            Date startInclusive = new Date(now + aDay * 2 ); // starts 2 days from now
                            Date endExclusive = new Date(now + aDay * 365 * 2); //ends 2 years from now
                            attributeValue = generateAttributeValueDate(conform, startInclusive, endExclusive);
                            break;

                        case "DateTime":
                            long aDay1 = TimeUnit.DAYS.toMillis(1);
                            long now1 = new Date().getTime();
                            Date startInclusive1 = new Date(now1 + aDay1 * 2 ); // starts 2 days from now
                            Date endExclusive1 = new Date(now1 + aDay1 * 365 * 2); //ends 2 years from now
                            attributeValue = generateAttributeValueDateTime(conform, startInclusive1, endExclusive1);
                            break;

                        case "Decimal":
                            attributeValue = generateAttributeValueDecimal(conform);
                            break;

                        case "Duration":
                            if (conform) {
                                attributeValue="P5Y2M10DT15H";
                            }else{
                                attributeValue=RandomStringUtils.randomAlphabetic(10);
                            }
                            break;

                        case "MonthDay":
                            long aDay2 = TimeUnit.DAYS.toMillis(1);
                            long now2 = new Date().getTime();
                            Date startInclusive2 = new Date(now2 + aDay2 * 2 ); // starts 2 days from now
                            Date endExclusive2 = new Date(now2 + aDay2 * 365 * 2); //ends 2 years from now
                            attributeValue = generateAttributeValueMonthDay(conform, startInclusive2, endExclusive2);
                            break;

                        case "Time":
                            long aDay3 = TimeUnit.DAYS.toMillis(1);
                            long now3 = new Date().getTime();
                            Date startInclusive3 = new Date(now3 + aDay3 * 2 ); // starts 2 days from now
                            Date endExclusive3 = new Date(now3 + aDay3 * 365 * 2); //ends 2 years from now
                            attributeValue = generateAttributeValueTime(conform, startInclusive3, endExclusive3);
                            break;

                        case "URI":
                        case "URL":
                        case "IRI":
                        case "StringIRI":
                            if (conform) {
                                if (shaclContraintResult != null) {
                                    if (shaclContraintResult.containsKey("in")) {
                                        inValues = (List<RDFNode>) shaclContraintResult.get("in");
                                        attributeValue=inValues.get(0).toString();
                                    }else{
                                        attributeValue = "http://" + RandomStringUtils.randomAlphabetic(10) + ".test/";
                                    }

                                }else {
                                    attributeValue = "http://" + RandomStringUtils.randomAlphabetic(10) + ".test/";
                                }
                            }else{
                                attributeValue=RandomStringUtils.randomAlphabetic(10);
                            }
                            break;
                    }
                    break;
                }
                case "Compound": {
                    //TODO compound at later stage. This is to do a good and bad compound when attributes are generated.
                }
                case "Enumeration": {
                    Map<String, Integer> values = new HashMap<>();
                    values.put("minInclusive", 0);
                    if (shaclContraintResult != null) {
                        if (shaclContraintResult.containsKey("in")) {
                            List<RDFNode> objectTempIn = (List<RDFNode>) shaclContraintResult.get("in");
                            if (conform) {
                                values.put("maxInclusive", objectTempIn.size() - 1);
                                int randomInt = Integer.parseInt(generateAttributeValueInteger(true, values, inValues));
                                attributeValue = objectTempIn.get(randomInt).toString();
                            } else {
                                if (datatypeValuesEnum != null) {
                                    boolean noValue = true;
                                    for (Object item : (ArrayList) datatypeValuesEnum) {
                                        if (!objectTempIn.contains(ResourceFactory.createResource(item.toString()))) {
                                            attributeValue = item.toString();
                                            noValue = false;
                                        }
                                    }
                                    if (noValue) {
                                        attributeValue = objectTempIn.get(0).toString() + "nonConform";
                                    }
                                }
                            }
                        }
                    } else {
                        if (datatypeValuesEnum != null) {
                            values.put("maxInclusive", ((ArrayList) datatypeValuesEnum).size() - 1);
                            int randomInt = Integer.parseInt(generateAttributeValueInteger(true, values, new ArrayList<>()));
                            if (conform) {
                                attributeValue = ((ArrayList) datatypeValuesEnum).get(randomInt).toString();
                            } else {
                                //TODO see if the non conform can be clever e.g. to select from other list;
                                attributeValue = ((ArrayList) datatypeValuesEnum).get(randomInt).toString() + "nonConform";
                            }
                        }
                    }
                    break;
                }
            }

            if (attributeValue!=null && datatypeValuesEnum==null) {
                stmt = addGenerategAttributePlainLiteralToStatement(attributeValue, classRes, property);
                instanceDataModel.add(stmt);
            }else if (datatypeValuesEnum!=null){
                stmt = addEnumerationToStatement(attributeValue, classRes, property);
                instanceDataModel.add(stmt);
            }else {
                GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"[Error] The class.attribute: "+property.getLocalName()+" did not get value.",true);
            }
        }else{
            //the attribute is skipped and need to record this
            ((LinkedList) skippedClassPropertyProcessed.get(0)).add(instanceDataModel.getNsURIPrefix(classRes.getNameSpace())+":"+classLocalName);
            ((LinkedList) skippedClassPropertyProcessed.get(1)).add(instanceDataModel.getNsURIPrefix(property.getNameSpace())+":"+property.getLocalName());
            ((LinkedList) skippedClassPropertyProcessed.get(2)).add("Attribute");
        }
        return instanceDataModel;
    }

    //Add the generated attribute to the model
    public static Statement addGenerategAttributePlainLiteralToStatement(String attributeValue, Resource classRes, Property property) {

        Literal attributeValueLiteral = ResourceFactory.createPlainLiteral(attributeValue);
        Statement stmt = ResourceFactory.createStatement(classRes, property, attributeValueLiteral);

        return stmt;
    }

    //Add the generated attribute to the model
    public static Statement addEnumerationToStatement(String attributeValue, Resource classRes, Property property) {

        RDFNode attributeEnumeration = ResourceFactory.createResource(attributeValue);
        Statement stmt = ResourceFactory.createStatement(classRes, property, attributeEnumeration);


        return stmt;
    }

    //Generate string value for attribute
    public static String generateAttributeValueString(Boolean conform,Map<String,Integer> values, List<RDFNode> inValues) {

        Map<String,Integer> valuesTemp = new HashMap<>();
        int randomInt=0;
        if(inValues.size()!=0) {
            valuesTemp.put("minInclusive", 0);
            valuesTemp.put("maxInclusive", inValues.size() - 1);

                if (inValues.get(0).toString().contains("ThreePhasePower")) {
                    int k = 1;
                }
            randomInt = Integer.parseInt(generateAttributeValueInteger(true, valuesTemp, new ArrayList<>()));
        }

        String attributeValue = "";
        SecureRandom random = new SecureRandom();
        int len=0;
        if (values.containsKey("minLength") && values.containsKey("maxLength")){ //there is min and max
            int minLength=values.get("minLength");
            int maxLength=values.get("maxLength");
            len=minLength + random.nextInt((maxLength - minLength) + 1);
            if (conform) {
                if(inValues.size()==0) {
                    attributeValue=RandomStringUtils.randomAlphabetic(len);
                }else{
                    attributeValue = inValues.get(randomInt).toString();
                }
            }else{
                attributeValue=RandomStringUtils.randomAlphabetic(maxLength+1);
            }
        }
        if (values.containsKey("minLength") && !values.containsKey("maxLength")) { //only min
            int minLength=values.get("minLength");
            len=minLength + random.nextInt((999 - minLength) + 1);
            if (conform) {
                if(inValues.size()==0) {
                    attributeValue=RandomStringUtils.randomAlphabetic(len);
                }else{
                    attributeValue = inValues.get(randomInt).toString();
                }
            }else{
                if (minLength>=1) {
                    attributeValue = RandomStringUtils.randomAlphabetic(minLength - 1);
                }else{
                    attributeValue = RandomStringUtils.randomAlphabetic(minLength);
                    GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"[Error] Please check: non-conform value is not possible with this value of minLength = "+minLength,true);
                }
            }
        }
        if (!values.containsKey("minLength") && values.containsKey("maxLength")) { //only max
            int maxLength=values.get("maxLength");
            len=1 + random.nextInt((maxLength - 1) + 1);
            if (conform) {
                if(inValues.size()==0) {
                    attributeValue=RandomStringUtils.randomAlphabetic(len);
                }else{
                    attributeValue = inValues.get(randomInt).toString();
                }
            }else{
                attributeValue=RandomStringUtils.randomAlphabetic(maxLength+1);
            }
        }
        if (!values.containsKey("minLength") && !values.containsKey("maxLength")){ //there is no min and no max
            len=10;
            if (conform) {
                if(inValues.size()==0) {
                    attributeValue=RandomStringUtils.randomAlphabetic(len);
                }else{
                    attributeValue = inValues.get(randomInt).toString();
                }
            }else{
                attributeValue=RandomStringUtils.randomAlphabetic(len+300);
                //GuiHelper.appendTextToOutputWindow("[Error] Please check: There is no min and max length for the string.",true);
            }
        }

        return attributeValue;
    }

    //Generate integer value as string for attribute
    public static String generateAttributeValueInteger(Boolean conform, Map<String,Integer> values, List<RDFNode> inValues) {

        SecureRandom random = new SecureRandom();

        String attributeValue = null;
        int value;

      /*  if (inValues.size()!=0) {
            if (inValues.get(0).toString().contains("ThreePhasePower")) {
                int k = 1;
            }
        }*/

        if (values.size()==0 && inValues.size()==0){//no limits defined
            if (conform) {
                value= random.nextInt((10)+1);
                attributeValue=String.valueOf(value);
            }else{
                attributeValue="1.1";
            }
        }

        if (values.containsKey("minExclusive") && values.containsKey("maxExclusive") && !values.containsKey("minInclusive") && !values.containsKey("maxInclusive")){//minExclusive,maxExclusive (1,1)
            int minExclusive=values.get("minExclusive");
            int maxExclusive=values.get("maxExclusive");
            if (conform) {
                value=minExclusive+1 + random.nextInt((maxExclusive - minExclusive));
                if(inValues.size()==0) {
                    attributeValue = String.valueOf(value);
                }else{
                    attributeValue = inValues.get(0).toString();
                }
            }else{
                List<Integer> tempIntList=new ArrayList<>();
                tempIntList.add(minExclusive);
                tempIntList.add(maxExclusive);
                tempIntList.add(maxExclusive+1);
                value=random.nextInt(3); //with 3 gives 0,1 or 2
                attributeValue=String.valueOf(tempIntList.get(value));
            }
        }
        if (values.containsKey("minExclusive") && !values.containsKey("maxExclusive") && !values.containsKey("minInclusive") && values.containsKey("maxInclusive")){//minExclusive,maxInclusive (1,1]
            int minExclusive=values.get("minExclusive");
            int maxInclusive=values.get("maxInclusive");
            if (conform) {
                value=minExclusive+1 + random.nextInt((maxInclusive - minExclusive)+1);
                if(inValues.size()==0) {
                    attributeValue = String.valueOf(value);
                }else{
                    attributeValue = inValues.get(0).toString();
                }
            }else{
                List<Integer> tempIntList=new ArrayList<>();
                tempIntList.add(minExclusive);
                tempIntList.add(maxInclusive+1);
                value=random.nextInt(2); //with 2 gives 0,1
                attributeValue=String.valueOf(tempIntList.get(value));
            }
        }
        if (!values.containsKey("minExclusive") && !values.containsKey("maxExclusive") && values.containsKey("minInclusive") && values.containsKey("maxInclusive")){//minInclusive, maxInclusive [1,1]
            int minInclusive=values.get("minInclusive");
            int maxInclusive=values.get("maxInclusive");
            if (conform) {
                value=minInclusive + random.nextInt((maxInclusive - minInclusive)+1);
                if(inValues.size()==0) {
                    attributeValue = String.valueOf(value);
                }else{
                    attributeValue = inValues.get(0).toString();
                }
            }else{
                List<Integer> tempIntList=new ArrayList<>();
                if (minInclusive>=1) {
                    tempIntList.add(minInclusive - 1);
                    tempIntList.add(maxInclusive+1);
                    value=random.nextInt(2); //with 3 gives 0,1
                }else{
                    tempIntList.add(maxInclusive+1);
                    value=0;
                }
                attributeValue=String.valueOf(tempIntList.get(value));
            }
        }
        if (!values.containsKey("minExclusive") && values.containsKey("maxExclusive") && values.containsKey("minInclusive") && !values.containsKey("maxInclusive")){//minInclusive,maxExclusive [1,1)
            int minInclusive=values.get("minInclusive");
            int maxExclusive=values.get("maxExclusive");
            if (conform) {
                value=minInclusive + random.nextInt((maxExclusive - minInclusive));
                if(inValues.size()==0) {
                    attributeValue = String.valueOf(value);
                }else{
                    attributeValue = inValues.get(0).toString();
                }
            }else{
                List<Integer> tempIntList=new ArrayList<>();
                if (minInclusive>=1) {
                    tempIntList.add(minInclusive - 1);
                    tempIntList.add(maxExclusive);
                    tempIntList.add(maxExclusive+1);
                    value=random.nextInt(3); //with 3 gives 0,1,2
                }else{
                    tempIntList.add(maxExclusive);
                    value=0;
                }
                attributeValue=String.valueOf(tempIntList.get(value));
            }
        }
        if (!values.containsKey("minExclusive") && !values.containsKey("maxExclusive") && values.containsKey("minInclusive") && !values.containsKey("maxInclusive")){//minInclusive,NA [1,...
            int minInclusive=values.get("minInclusive");
            if (conform) {
                value=minInclusive + random.nextInt((999 - minInclusive)+1);
                if(inValues.size()==0) {
                    attributeValue = String.valueOf(value);
                }else{
                    attributeValue = inValues.get(0).toString();
                }
            }else{
                attributeValue=String.valueOf(minInclusive - 1);
            }
        }
        if (values.containsKey("minExclusive") && !values.containsKey("maxExclusive") && !values.containsKey("minInclusive") && !values.containsKey("maxInclusive")){//minExclusive,NA (1,...
            int minExclusive=values.get("minExclusive");
            if (conform) {
                value=minExclusive+1 + random.nextInt((999 - minExclusive)+1);
                if(inValues.size()==0) {
                    attributeValue = String.valueOf(value);
                }else{
                    attributeValue = inValues.get(0).toString();
                }
            }else{
                attributeValue=String.valueOf(minExclusive);
            }
        }
        if (!values.containsKey("minExclusive") && !values.containsKey("maxExclusive") && !values.containsKey("minInclusive") && values.containsKey("maxInclusive")){//NA,maxInclusive ...,1]
            int maxInclusive=values.get("maxInclusive");
            if (conform) {
                value=random.nextInt(maxInclusive+1);
                if(inValues.size()==0) {
                    attributeValue = String.valueOf(value);
                }else{
                    attributeValue = inValues.get(0).toString();
                }
            }else{
                attributeValue=String.valueOf(maxInclusive+1);
            }
        }
        if (!values.containsKey("minExclusive") && values.containsKey("maxExclusive") && !values.containsKey("minInclusive") && !values.containsKey("maxInclusive")){//NA,maxExclusive ...,1)
            int maxExclusive=values.get("maxExclusive");
            if (conform) {
                value=random.nextInt(maxExclusive);
                if(inValues.size()==0) {
                    attributeValue = String.valueOf(value);
                }else{
                    attributeValue = inValues.get(0).toString();
                }
            }else{
                attributeValue=String.valueOf(maxExclusive);
            }
        }

        return attributeValue;
    }
    //Generate float value as string for attribute
    public static String generateAttributeValueFloat(Boolean conform, Map<String,Float> values) {

        SecureRandom random = new SecureRandom();

        String attributeValue = null;
        float value;
        int index;

        if (values.size()==0){//no limits defined
            if (conform) {
                value= (float) (0 + random.nextFloat()*(1)+0.000001);
                attributeValue=String.valueOf(value);
            }else{
                attributeValue="1,1";
            }
        }

        if (values.containsKey("minExclusive") && values.containsKey("maxExclusive") && !values.containsKey("minInclusive") && !values.containsKey("maxInclusive")){//minExclusive,maxExclusive (1,1)
            float minExclusive=values.get("minExclusive");
            float maxExclusive=values.get("maxExclusive");
            if (conform) {
                value= (float) (minExclusive+0.000001 + random.nextInt()*(maxExclusive - minExclusive));
                attributeValue=String.valueOf(value);
            }else{
                List<Float> tempIntList=new ArrayList<>();
                tempIntList.add(minExclusive);
                tempIntList.add(maxExclusive);
                tempIntList.add((float) (maxExclusive+0.000001));
                index=random.nextInt(3); //with 3 gives 0,1 or 2
                attributeValue=String.valueOf(tempIntList.get(index));
            }
        }
        if (values.containsKey("minExclusive") && !values.containsKey("maxExclusive") && !values.containsKey("minInclusive") && values.containsKey("maxInclusive")){//minExclusive,maxInclusive (1,1]
            float minExclusive=values.get("minExclusive");
            float maxInclusive=values.get("maxInclusive");
            if (conform) {
                value= (float) (minExclusive+0.000001 + random.nextFloat()*((maxInclusive - minExclusive)+0.000001));
                attributeValue=String.valueOf(value);
            }else{
                List<Float> tempIntList=new ArrayList<>();
                tempIntList.add(minExclusive);
                tempIntList.add((float) (maxInclusive+0.000001));
                index=random.nextInt(2); //with 2 gives 0,1
                attributeValue=String.valueOf(tempIntList.get(index));
            }
        }
        if (!values.containsKey("minExclusive") && !values.containsKey("maxExclusive") && values.containsKey("minInclusive") && values.containsKey("maxInclusive")){//minInclusive, maxInclusive [1,1]
            float minInclusive=values.get("minInclusive");
            float maxInclusive=values.get("maxInclusive");
            if (conform) {
                value= (float) (minInclusive + random.nextFloat()*(maxInclusive - minInclusive)+0.000001);
                attributeValue=String.valueOf(value);
            }else{
                List<Float> tempIntList=new ArrayList<>();
                if (minInclusive>=1) {
                    tempIntList.add((float) (minInclusive - 0.000001));
                    tempIntList.add((float) (maxInclusive+0.000001));
                    index=random.nextInt(2); //with 3 gives 0,1
                }else{
                    tempIntList.add(maxInclusive+1);
                    index=0;
                }
                attributeValue=String.valueOf(tempIntList.get(index));
            }
        }
        if (!values.containsKey("minExclusive") && values.containsKey("maxExclusive") && values.containsKey("minInclusive") && !values.containsKey("maxInclusive")){//minInclusive,maxExclusive [1,1)
            float minInclusive=values.get("minInclusive");
            float maxExclusive=values.get("maxExclusive");
            if (conform) {
                value=minInclusive + random.nextFloat()*(maxExclusive - minInclusive);
                attributeValue=String.valueOf(value);
            }else{
                List<Float> tempIntList=new ArrayList<>();
                if (minInclusive>=1) {
                    tempIntList.add((float) (minInclusive - 0.000001));
                    tempIntList.add(maxExclusive);
                    tempIntList.add((float) (maxExclusive+0.000001));
                    index=random.nextInt(3); //with 3 gives 0,1,2
                }else{
                    tempIntList.add(maxExclusive);
                    index=0;
                }
                attributeValue=String.valueOf(tempIntList.get(index));
            }
        }
        if (!values.containsKey("minExclusive") && !values.containsKey("maxExclusive") && values.containsKey("minInclusive") && !values.containsKey("maxInclusive")){//minInclusive,NA [1,...
            float minInclusive=values.get("minInclusive");
            if (conform) {
                value= (float) (minInclusive + random.nextFloat()*((999 - minInclusive)+0.000001));
                attributeValue=String.valueOf(value);
            }else{
                attributeValue=String.valueOf(minInclusive - 0.000001);
            }
        }
        if (values.containsKey("minExclusive") && !values.containsKey("maxExclusive") && !values.containsKey("minInclusive") && !values.containsKey("maxInclusive")){//minExclusive,NA (1,...
            float minExclusive=values.get("minExclusive");
            if (conform) {
                value= (float) (minExclusive+0.000001 + random.nextFloat()*((999 - minExclusive)+0.000001));
                attributeValue=String.valueOf(value);
            }else{
                attributeValue=String.valueOf(minExclusive);
            }
        }
        if (!values.containsKey("minExclusive") && !values.containsKey("maxExclusive") && !values.containsKey("minInclusive") && values.containsKey("maxInclusive")){//NA,maxInclusive ...,1]
            float maxInclusive=values.get("maxInclusive");
            if (conform) {
                value= (float) (random.nextFloat()*(maxInclusive+0.000001));
                attributeValue=String.valueOf(value);
            }else{
                attributeValue=String.valueOf(maxInclusive+0.000001);
            }
        }
        if (!values.containsKey("minExclusive") && values.containsKey("maxExclusive") && !values.containsKey("minInclusive") && !values.containsKey("maxInclusive")){//NA,maxExclusive ...,1)
            float maxExclusive=values.get("maxExclusive");
            if (conform) {
                //GuiHelper.appendTextToOutputWindow("[maxExclusive]: "+maxExclusive,true);
                value= (float) -0.1;
                if (maxExclusive!=0){
                    value = random.nextInt((int) maxExclusive);
                }
                attributeValue=String.valueOf(value);
            }else{
                attributeValue=String.valueOf(maxExclusive);
            }
        }

        return attributeValue;
    }

    //Generate float value as string for attribute
    public static String generateAttributeValueDecimal(Boolean conform) {

        SecureRandom random = new SecureRandom();

        String attributeValue;

        double value;
        if (conform) {
            value=random.nextDouble();
            attributeValue=String.valueOf(BigDecimal.valueOf(value));
        }else{
            attributeValue="1,1";
        }


        return attributeValue;
    }

    //Generate boolean value as string for attribute
    public static String generateAttributeValueBoolean(Boolean conform) {

        SecureRandom random = new SecureRandom();

        String attributeValue;
        boolean value;
        if (conform) {
            value=random.nextBoolean();
            attributeValue=String.valueOf(value);
        }else{
            attributeValue="truee";
        }


        return attributeValue;
    }

    //Generate date value as string for attribute
    public static String generateAttributeValueDate(Boolean conform, Date startInclusive, Date endExclusive) {

        String attributeValue;
        long startMillis = startInclusive.getTime();
        long endMillis = endExclusive.getTime();
        long randomMillisSinceEpoch = ThreadLocalRandom
                .current()
                .nextLong(startMillis, endMillis);

        Date value = new Date(randomMillisSinceEpoch);
        String isoDatePattern;

        if (conform) {

            //This is with date time ****
            //Calendar date = new GregorianCalendar(2007, 3, 4);

            //date.setTimeZone( TimeZone.getTimeZone("GMT+0") );
            //XSDDateTime xsdDate = new XSDDateTime( date );
            //*****


            //String isoDatePattern = "yyyy-MM-dd'T'HH:mm:ss'Z'";
            isoDatePattern = "yyyy-MM-dd";

        }else{
            isoDatePattern = "yyyy-MM-dd'T'HH:mm:ss'Z'";
            //String isoDatePattern = "yyyy-MM-dd";

        }
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(isoDatePattern);
        attributeValue = simpleDateFormat.format(value);


        return attributeValue;
    }
    //Generate time value as string for attribute
    public static String generateAttributeValueTime(Boolean conform, Date startInclusive, Date endExclusive) {

        String attributeValue;
        long startMillis = startInclusive.getTime();
        long endMillis = endExclusive.getTime();
        long randomMillisSinceEpoch = ThreadLocalRandom
                .current()
                .nextLong(startMillis, endMillis);

        Date value = new Date(randomMillisSinceEpoch);
        String isoDatePattern;

        if (conform) {

            //This is with date time ****
            //Calendar date = new GregorianCalendar(2007, 3, 4);

            //date.setTimeZone( TimeZone.getTimeZone("GMT+0") );
            //XSDDateTime xsdDate = new XSDDateTime( date );
            //*****


            //String isoDatePattern = "yyyy-MM-dd'T'HH:mm:ss'Z'";
            isoDatePattern = "HH:mm:ss'Z'";

        }else{
            //isoDatePattern = "yyyy-MM-dd'T'HH:mm:ss'Z'";
            isoDatePattern = "yyyy-MM-dd";

        }
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(isoDatePattern);
        attributeValue = simpleDateFormat.format(value);


        return attributeValue;
    }

    //Generate date time value as string for attribute
    public static String generateAttributeValueDateTime(Boolean conform, Date startInclusive, Date endExclusive) {

        String attributeValue;
        long startMillis = startInclusive.getTime();
        long endMillis = endExclusive.getTime();
        long randomMillisSinceEpoch = ThreadLocalRandom
                .current()
                .nextLong(startMillis, endMillis);

        Date value = new Date(randomMillisSinceEpoch);
        String isoDatePattern;

        if (conform) {


            //This is with date time ****
            //Calendar date = new GregorianCalendar(2007, 3, 4);

            //date.setTimeZone( TimeZone.getTimeZone("GMT+0") );
            //XSDDateTime xsdDate = new XSDDateTime( date );
            //*****


            isoDatePattern = "yyyy-MM-dd'T'HH:mm:ss'Z'";
            //String isoDatePattern = "yyyy-MM-dd";
            //String isoDatePattern = "--MM-dd";

        }else{
            //String isoDatePattern = "yyyy-MM-dd'T'HH:mm:ss'Z'";
            isoDatePattern = "yyyy-MM-dd";

        }

        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(isoDatePattern);
        attributeValue = simpleDateFormat.format(value);

        return attributeValue;
    }

    //Generate date time value as string for attribute
    public static String generateAttributeValueMonthDay(Boolean conform, Date startInclusive, Date endExclusive) {

        String attributeValue;
        long startMillis = startInclusive.getTime();
        long endMillis = endExclusive.getTime();
        long randomMillisSinceEpoch = ThreadLocalRandom
                .current()
                .nextLong(startMillis, endMillis);

        Date value = new Date(randomMillisSinceEpoch);
        String isoDatePattern;

        if (conform) {

            //This is with date time ****
            //Calendar date = new GregorianCalendar(2007, 3, 4);

            //date.setTimeZone( TimeZone.getTimeZone("GMT+0") );
            //XSDDateTime xsdDate = new XSDDateTime( date );
            //*****

            //String isoDatePattern = "yyyy-MM-dd'T'HH:mm:ss'Z'";
            //String isoDatePattern = "yyyy-MM-dd";
            isoDatePattern = "--MM-dd";
        }else{
            isoDatePattern = "yyyy-MM-dd'T'HH:mm:ss'Z'";
            //String isoDatePattern = "yyyy-MM-dd";
        }

        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(isoDatePattern);
        attributeValue = simpleDateFormat.format(value);

        return attributeValue;
    }

    //Add association to instance data model
    private static Model addAssociation(Model instanceDataModel, Property property, Boolean conform,
                                        String cardinality, Integer cardinalityFlag, RDFNode resource, String classLocalName,Map<String,Boolean> inputData, String classFullURI) {

        Map<String,Object> shaclContraintResult = null;
        Property fullClassPropertyKey = ResourceFactory.createProperty(classFullURI+"propertyFullURI"+property);
        //check if there are some shacl constraints to be considered
        if (inputData.get("shacl")){ //shacl constraints are to be considered
            if (shaclConstraints.containsKey(fullClassPropertyKey)){ //the attribute has some constraints defined
                shaclContraintResult=getShaclConstraint(conform, fullClassPropertyKey);
            }
        }

        Resource classRes;
        if (classLocalName.equals("Terminal") || classLocalName.equals("DCTerminal") || classLocalName.equals("ACDCConverterDCTerminal")){
            classRes=classResourceTerminal;
        }else{
            classRes=classResource;
        }


        Statement stmt = null;
        String attributeValue=null;

        Map<String,Integer> cardinalityCheckResult= cardinalityCheck(cardinality, shaclContraintResult,property);
        int lowerBound = cardinalityCheckResult.get("lowerBound");
        int upperBound =cardinalityCheckResult.get("upperBound");

        //cardinalityFlag 1 = required only; 0=optional only ; 3=all - optional and required
        if ((cardinalityFlag==1 && lowerBound==1 ) ||
                (cardinalityFlag==0 && lowerBound==0 ) ||
                cardinalityFlag==3) {

            if (lowerBound>=1 && upperBound>=1){//required
                countOfRequiredAssociations=countOfRequiredAssociations+1;
            }else if(lowerBound==0 && upperBound>=1) {//optional
                countOfOptionalAssociations=countOfOptionalAssociations+1;
            }

            if (conform){
                stmt = ResourceFactory.createStatement(classRes, property, resource);
            }else{
                //this association will point to the dummy class
                stmt = ResourceFactory.createStatement(classRes, property, resDummyClass);
            }
            instanceDataModel.add(stmt);

        }else{
            //the association is skipped and need to record this
            ((LinkedList) skippedClassPropertyProcessed.get(0)).add(instanceDataModel.getNsURIPrefix(classRes.getNameSpace())+":"+classLocalName);
            ((LinkedList) skippedClassPropertyProcessed.get(1)).add(instanceDataModel.getNsURIPrefix(property.getNameSpace())+":"+property.getLocalName());
            ((LinkedList) skippedClassPropertyProcessed.get(2)).add("Association");
        }

        return instanceDataModel;
    }

    //Add terminal to instance data model
    private static Model addTerminal(ArrayList<Object> profileData, Model instanceDataModel, String xmlBase, Integer sequenceNumber,Integer cardinalityFlag,
                                     Boolean conform, RDFNode resource,Map<String,Boolean> inputData) {

        for (int cl = 0; cl < ((ArrayList) profileData.get(0)).size(); cl++) { //this is to loop on the classes in the profile and add class for each concrete class

            //add a class
            String classFullURI = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(0)).get(0).toString();

            if (classFullURI.split("#",2)[1].equals("Terminal")) {

                classResourceTerminal = ResourceFactory.createResource(xmlBase+"#_"+UUID.randomUUID());

                RDFNode rdfType = ResourceFactory.createResource(classFullURI);
                Statement stmt = ResourceFactory.createStatement(classResourceTerminal, RDF.type,rdfType);
                instanceDataModel.add(stmt);
                String classLocalName="Terminal";


                for (int atas = 1; atas < ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).size(); atas++) {
                    // this is to loop on the attributes and associations (including inherited) for a given class and attributes or associations

                    if (((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(0).toString().equals("Association")) {//if it is an association
                        if (((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(1).toString().equals("Yes")) {

                            String cardinality = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(6).toString();
                            String localNameAssoc = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(5).toString();
                            String propertyFullURI = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(2).toString();
                            Property property = ResourceFactory.createProperty(propertyFullURI);

                            if (localNameAssoc.equals("Terminal.ConductingEquipment")) {
                                instanceDataModel = addAssociation(instanceDataModel, property, conform, cardinality, cardinalityFlag, resource,classLocalName,inputData,classFullURI);
                            }else {
                                //Association info for target class - only for concrete classes in the profile
                                if (((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).size()>10) { //TODO check if this is OK. This is to avoid crash if there are no concrete classes in the profile, but something should be done for the abstract classes which are concrete in another profile
                                    List<Resource> concreteClasses = (List<Resource>) ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(10);

                                    //check if any of the concrete classes are already in the instance model and use them. This is done also in cases where shacl is restricting
                                    instanceDataModel=selectAssociationReferenceAndAddAssociation(instanceDataModel,concreteClasses,inputData,classFullURI,property,conform,profileData,xmlBase,cardinalityFlag,cardinality, classLocalName);


                                }
                            }


                        }

                    } else if (((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(0).toString().equals("Attribute")) {//if it is an attribute
                        //Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                        String localNameAttr = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(4).toString();
                        String propertyFullURI = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(1).toString();
                        Property property = ResourceFactory.createProperty(propertyFullURI);
                        String cardinality = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(5).toString();

                        String datatypeType = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(8).toString();
                        String datatypeValueString = null;
                        Object datatypeValuesEnum = null;

                        switch (datatypeType) {
                            case "Primitive": {
                                datatypeValueString = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(10).toString(); //this is localName e.g. String
                                break;
                            }
                            case "CIMDatatype": {
                                datatypeValueString = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(9).toString(); //this is localName e.g. String
                                break;
                            }
                            case "Enumeration": { // note that some special treatment might be necessary for terminal.phases.Normally will get random
                                datatypeValuesEnum = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(10);
                                break;
                            }
                        }

                        if (localNameAttr.equals("ACDCTerminal.sequenceNumber")) {
                            instanceDataModel = addAttribute(instanceDataModel, property, true, datatypeType, datatypeValueString, datatypeValuesEnum, cardinality, cardinalityFlag, classLocalName, sequenceNumber,inputData,classFullURI);
                        }else{
                            instanceDataModel = addAttribute(instanceDataModel, property, true, datatypeType, datatypeValueString, datatypeValuesEnum, cardinality, cardinalityFlag, classLocalName,null,inputData,classFullURI);
                        }

                    }
                }
                break;
            }
        }


        return instanceDataModel;
    }

    //Add terminal to instance data model
    private static Model addDCTerminal(ArrayList<Object> profileData, Model instanceDataModel, String xmlBase, Integer sequenceNumber,Integer cardinalityFlag,
                                     Boolean conform, RDFNode resource,Map<String,Boolean> inputData) {

        for (int cl = 0; cl < ((ArrayList) profileData.get(0)).size(); cl++) { //this is to loop on the classes in the profile and add class for each concrete class

            //add a class
            String classFullURI = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(0)).get(0).toString();

            if (classFullURI.split("#",2)[1].equals("DCTerminal")) {

                classResourceTerminal = ResourceFactory.createResource(xmlBase+"#_"+UUID.randomUUID());

                RDFNode rdfType = ResourceFactory.createResource(classFullURI);
                Statement stmt = ResourceFactory.createStatement(classResourceTerminal, RDF.type,rdfType);
                instanceDataModel.add(stmt);
                String classLocalName="DCTerminal";


                for (int atas = 1; atas < ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).size(); atas++) {
                    // this is to loop on the attributes and associations (including inherited) for a given class and attributes or associations

                    if (((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(0).toString().equals("Association")) {//if it is an association
                        if (((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(1).toString().equals("Yes")) {

                            String cardinality = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(6).toString();
                            String localNameAssoc = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(5).toString();
                            String propertyFullURI = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(2).toString();
                            Property property = ResourceFactory.createProperty(propertyFullURI);

                            if (localNameAssoc.equals("DCTerminal.DCConductingEquipment")) {
                                instanceDataModel = addAssociation(instanceDataModel, property, conform, cardinality, cardinalityFlag, resource,classLocalName,inputData,classFullURI);
                            }else {
                                //Association info for target class - only for concrete classes in the profile
                                if (((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).size()>10) { //TODO check if this is OK. This is to avoid crash if there are no concrete classes in the profile, but something should be done for the abstract classes which are concrete in another profile
                                    List<Resource> concreteClasses = (List<Resource>) ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(10);

                                    //check if any of the concrete classes are already in the instance model and use them. This is done also in cases where shacl is restricting
                                    instanceDataModel=selectAssociationReferenceAndAddAssociation(instanceDataModel,concreteClasses,inputData,classFullURI,property,conform,profileData,xmlBase,cardinalityFlag,cardinality, classLocalName);


                                }
                            }


                        }

                    } else if (((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(0).toString().equals("Attribute")) {//if it is an attribute
                        //Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                        String localNameAttr = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(4).toString();
                        String propertyFullURI = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(1).toString();
                        Property property = ResourceFactory.createProperty(propertyFullURI);
                        String cardinality = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(5).toString();

                        String datatypeType = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(8).toString();
                        String datatypeValueString = null;
                        Object datatypeValuesEnum = null;

                        switch (datatypeType) {
                            case "Primitive": {
                                datatypeValueString = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(10).toString(); //this is localName e.g. String
                                break;
                            }
                            case "CIMDatatype": {
                                datatypeValueString = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(9).toString(); //this is localName e.g. String
                                break;
                            }
                            case "Enumeration": { // note that some special treatment might be necessary for terminal.phases.Normally will get random
                                datatypeValuesEnum = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(10);
                                break;
                            }
                        }

                        if (localNameAttr.equals("ACDCTerminal.sequenceNumber")) {
                            instanceDataModel = addAttribute(instanceDataModel, property, true, datatypeType, datatypeValueString, datatypeValuesEnum, cardinality, cardinalityFlag, classLocalName, sequenceNumber,inputData,classFullURI);
                        }else{
                            instanceDataModel = addAttribute(instanceDataModel, property, true, datatypeType, datatypeValueString, datatypeValuesEnum, cardinality, cardinalityFlag, classLocalName,null,inputData,classFullURI);
                        }

                    }
                }
                break;
            }
        }


        return instanceDataModel;
    }

    //Add terminal to instance data model
    private static Model addACDCConverterDCTerminal(ArrayList<Object> profileData, Model instanceDataModel, String xmlBase, Integer sequenceNumber,Integer cardinalityFlag,
                                       Boolean conform, RDFNode resource,Map<String,Boolean> inputData) {

        for (int cl = 0; cl < ((ArrayList) profileData.get(0)).size(); cl++) { //this is to loop on the classes in the profile and add class for each concrete class

            //add a class
            String classFullURI = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(0)).get(0).toString();

            if (classFullURI.split("#",2)[1].equals("ACDCConverterDCTerminal")) {

                classResourceTerminal = ResourceFactory.createResource(xmlBase+"#_"+UUID.randomUUID());

                RDFNode rdfType = ResourceFactory.createResource(classFullURI);
                Statement stmt = ResourceFactory.createStatement(classResourceTerminal, RDF.type,rdfType);
                instanceDataModel.add(stmt);
                String classLocalName="ACDCConverterDCTerminal";


                for (int atas = 1; atas < ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).size(); atas++) {
                    // this is to loop on the attributes and associations (including inherited) for a given class and attributes or associations

                    if (((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(0).toString().equals("Association")) {//if it is an association
                        if (((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(1).toString().equals("Yes")) {

                            String cardinality = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(6).toString();
                            String localNameAssoc = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(5).toString();
                            String propertyFullURI = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(2).toString();
                            Property property = ResourceFactory.createProperty(propertyFullURI);

                            if (localNameAssoc.equals("DCTerminal.DCConductingEquipment")) {
                                instanceDataModel = addAssociation(instanceDataModel, property, conform, cardinality, cardinalityFlag, resource,classLocalName,inputData,classFullURI);
                            }else {
                                //Association info for target class - only for concrete classes in the profile
                                if (((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).size()>10) { //TODO check if this is OK. This is to avoid crash if there are no concrete classes in the profile, but something should be done for the abstract classes which are concrete in another profile
                                    List<Resource> concreteClasses = (List<Resource>) ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(10);

                                    //check if any of the concrete classes are already in the instance model and use them. This is done also in cases where shacl is restricting
                                    instanceDataModel=selectAssociationReferenceAndAddAssociation(instanceDataModel,concreteClasses,inputData,classFullURI,property,conform,profileData,xmlBase,cardinalityFlag,cardinality, classLocalName);


                                }
                            }


                        }

                    } else if (((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(0).toString().equals("Attribute")) {//if it is an attribute
                        //Resource nodeShapeResource = shapeModel.getResource(nsURIprofile + localName);
                        String localNameAttr = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(4).toString();
                        String propertyFullURI = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(1).toString();
                        Property property = ResourceFactory.createProperty(propertyFullURI);
                        String cardinality = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(5).toString();

                        String datatypeType = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(8).toString();
                        String datatypeValueString = null;
                        Object datatypeValuesEnum = null;

                        switch (datatypeType) {
                            case "Primitive": {
                                datatypeValueString = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(10).toString(); //this is localName e.g. String
                                break;
                            }
                            case "CIMDatatype": {
                                datatypeValueString = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(9).toString(); //this is localName e.g. String
                                break;
                            }
                            case "Enumeration": { // note that some special treatment might be necessary for terminal.phases.Normally will get random
                                datatypeValuesEnum = ((ArrayList) ((ArrayList) ((ArrayList) profileData.get(0)).get(cl)).get(atas)).get(10);
                                break;
                            }
                        }

                        if (localNameAttr.equals("ACDCTerminal.sequenceNumber")) {
                            instanceDataModel = addAttribute(instanceDataModel, property, true, datatypeType, datatypeValueString, datatypeValuesEnum, cardinality, cardinalityFlag, classLocalName, sequenceNumber,inputData,classFullURI);
                        }else{
                            instanceDataModel = addAttribute(instanceDataModel, property, true, datatypeType, datatypeValueString, datatypeValuesEnum, cardinality, cardinalityFlag, classLocalName,null,inputData,classFullURI);
                        }

                    }
                }
                break;
            }
        }


        return instanceDataModel;
    }






    //create skip classes map
    public static Map<String,Object> getShaclConstraint(Boolean conform,Property property) {
        Map<String,Object> shaclContraintResult = new HashMap<>();

        //shacl properties to considered and could be found in the Maps
        //sh:datatype, sh:nodeKind
        //sh:minCount, sh:maxCount
        //sh:minExclusive, sh:minInclusive, sh:maxExclusive, sh:maxInclusive
        //sh:minLength, sh:maxLength
        //sh:lessThan, sh:lessThanOrEquals
        //sh:in
        //sh:equals, sh:disjoint

        //gets the 1st level structure
        Map mainMap=shaclConstraints.get(property);
        // gets the path content - is it direct, inverse or sequence
       // shaclConstraints.get(property).get("pathContent");
        //gets the content of the property shape where the keys are the shacl properties and the value is the object
       // shaclConstraints.get(property).get("propertyShapeContent");
        //gets the value
       // ((Map) shaclConstraints.get(property).get("propertyShapeContent")).get(SH.nodeKind);
        Map propertyShapeContent = (Map) mainMap.get("propertyShapeContent");

        if (conform){
            shaclContraintResult.put("conform",true);

        }else{
            shaclContraintResult.put("conform",false);
        }
        if (propertyShapeContent.containsKey(SH.datatype)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.datatype)).get(0);
            shaclContraintResult.put("datatype",objectTemp);
        }
        if (propertyShapeContent.containsKey(SH.nodeKind)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.nodeKind)).get(0);
            shaclContraintResult.put("nodeKind",objectTemp);
        }
        if (propertyShapeContent.containsKey(SH.minCount)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.minCount)).get(0);
            shaclContraintResult.put("minCount",objectTemp);
        }
        if (propertyShapeContent.containsKey(SH.maxCount)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.maxCount)).get(0);
            shaclContraintResult.put("maxCount",objectTemp);
        }


        if (propertyShapeContent.containsKey(SH.minExclusive)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.minExclusive)).get(0);
            shaclContraintResult.put("minExclusive",objectTemp);
        }
        if (propertyShapeContent.containsKey(SH.minInclusive)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.minInclusive)).get(0);
            shaclContraintResult.put("minInclusive",objectTemp);
        }
        if (propertyShapeContent.containsKey(SH.maxExclusive)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.maxExclusive)).get(0);
            shaclContraintResult.put("maxExclusive",objectTemp);
        }
        if (propertyShapeContent.containsKey(SH.maxInclusive)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.maxInclusive)).get(0);
            shaclContraintResult.put("maxInclusive",objectTemp);
        }



        if (propertyShapeContent.containsKey(SH.minLength)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.minLength)).get(0);
            shaclContraintResult.put("minLength",objectTemp);
        }
        if (propertyShapeContent.containsKey(SH.maxLength)){
            RDFNode objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.maxLength)).get(0);
            shaclContraintResult.put("maxLength",objectTemp);
        }






        //can be a list
        if (propertyShapeContent.containsKey(SH.lessThan)){
            List<RDFNode> objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.lessThan));
            shaclContraintResult.put("lessThan",objectTemp);
        }
        //can be a list
        if (propertyShapeContent.containsKey(SH.lessThanOrEquals)){
            List<RDFNode> objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.lessThanOrEquals));
            shaclContraintResult.put("lessThanOrEquals",objectTemp);
        }

        //can be a list
        if (propertyShapeContent.containsKey(SH.equals)){
            List<RDFNode> objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.equals));
            shaclContraintResult.put("equals",objectTemp);
        }
        //can be a list
        if (propertyShapeContent.containsKey(SH.disjoint)){
            List<RDFNode> objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.disjoint));
            shaclContraintResult.put("disjoint",objectTemp);
        }
        //can be a list
        if (propertyShapeContent.containsKey(SH.in)){
            List<RDFNode> objectTemp =((List<RDFNode>) propertyShapeContent.get(SH.in));
            shaclContraintResult.put("in",objectTemp);
        }



        return shaclContraintResult;
    }

    //extracts cardinality either form RDFS or SHACL data
    private static Map<String,Integer> cardinalityCheck(String cardinality,Map<String,Object> shaclContraintResult,Property property) {

        Map<String,Integer> cardinalityMap = new HashMap<>();


        String multiplicity ="";
        int lowerBoundRDF = 0;
        int upperBoundRDF =0;
        int lowerBound = 0;
        int upperBound =0;

        if (cardinality.length() == 1) {
            //need to have sh:minCount 1 ; and sh:maxCount 1 ;
            multiplicity = "required";
            lowerBoundRDF = 1;
            upperBoundRDF = 1;


        } else if (cardinality.length() == 4) {
            multiplicity = "seeBounds";
            lowerBoundRDF = 0;
            upperBoundRDF = 0;

            if (Character.isDigit(cardinality.charAt(0))) {
                lowerBoundRDF = Character.getNumericValue(cardinality.charAt(0));
            }
            if (Character.isDigit(cardinality.charAt(3))) {
                upperBoundRDF = Character.getNumericValue(cardinality.charAt(3));

            } else {
                upperBoundRDF = 999; // means that no upper bound is defined when we have upper bound "to many"
            }

              /*  if (lowerBound != 0 && upperBound != 999) { // covers 1..1 x..y excludes 0..n
                    if (lowerBound != 1 && upperBound != 1) {//is they are the same 1..1 "Missing required association" is used
                    }

                } else if (lowerBound == 0 && upperBound != 999) {//need to cover 0..x

                } else if (lowerBound != 0 && upperBound == 999) {//need to cover x..n

                }*/

        }

        if (shaclContraintResult!=null ) {
            int lowerBoundShacl = 0;
            int upperBoundShacl =0;
            //with this SHACL becomes the priority; for instance if RDFS says 0..1 SHACL could be 1..1. In a way it could also override
            if(shaclContraintResult.containsKey("minCount") ){
                lowerBoundShacl = ((RDFNode) shaclContraintResult.get("minCount")).asLiteral().getInt();
                if (lowerBoundRDF>lowerBoundShacl){
                    lowerBound=lowerBoundShacl;
                    GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"[Attention] The class.attribute: "+property.getLocalName()+" has relaxing lower bound cardinality in SHACL. Note that SHACL constraint overrides.",true);
                }else{
                    lowerBound=lowerBoundShacl;
                }
            }else{
                lowerBound = lowerBoundRDF;
            }
            if(shaclContraintResult.containsKey("maxCount")){
                upperBoundShacl = ((RDFNode) shaclContraintResult.get("maxCount")).asLiteral().getInt();
                if (upperBoundRDF<upperBoundShacl){
                    upperBound=upperBoundShacl;
                    GuiHelper.appendTextToOutputWindow(WizardContext.getExecutionTextArea(),"[Attention] The class.attribute: "+property.getLocalName()+" has relaxing upper bound cardinality in SHACL. Note that SHACL constraint overrides.",true);
                }else{
                    upperBound=upperBoundShacl;
                }
            }else{
                upperBound = upperBoundRDF;
            }

        }else{
            lowerBound=lowerBoundRDF;
            upperBound=upperBoundRDF;
        }
        cardinalityMap.put("lowerBound",lowerBound);
        cardinalityMap.put("upperBound",upperBound);
        return cardinalityMap;
    }




    //get the keyword for the profile
    static String getProfileKeyword(Model model) {

        String keyword="";


        if (model.listObjectsOfProperty(DCAT.keyword).hasNext()){
            keyword=model.listObjectsOfProperty(DCAT.keyword).next().toString();
        }

        if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#EquipmentVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))){
            keyword=model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#EquipmentVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
        }else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#EquipmentBoundaryVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#EquipmentBoundaryVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
            keyword="EQBD";
        }else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#TopologyBoundaryVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#TopologyBoundaryVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
            //TODO maybe fix RDFS. Here a quick override
            keyword="TPBD";
        }else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#TopologyVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#TopologyVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
        }else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#SteadyStateHypothesisVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#SteadyStateHypothesisVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
        }else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#StateVariablesVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#StateVariablesVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
        }else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#EDynamicsVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#EDynamicsVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
        }else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#GeographicalLocationVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#GeographicalLocationVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
        }else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#DiagramLayoutVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#DiagramLayoutVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
        }else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#Ontology.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#Ontology.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
        }else if (model.listObjectsOfProperty(ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.profile")).hasNext()){
            List<RDFNode> profileString=model.listObjectsOfProperty(ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.profile")).toList();
            for (RDFNode node: profileString){
                String nodeString=node.toString();
                if (nodeString.equals("http://entsoe.eu/CIM/EquipmentCore/3/1") || nodeString.equals("http://entsoe.eu/CIM/EquipmentOperation/3/1") || nodeString.equals("http://entsoe.eu/CIM/EquipmentShortCircuit/3/1")){
                    keyword="EQ";
                }else if (nodeString.equals("http://entsoe.eu/CIM/SteadyStateHypothesis/1/1")){
                    keyword="SSH";
                }else if (nodeString.equals("http://entsoe.eu/CIM/Topology/4/1")) {
                    keyword = "TP";
                }else if (nodeString.equals("http://entsoe.eu/CIM/StateVariables/4/1")) {
                    keyword = "SV";
                }else if (nodeString.equals("http://entsoe.eu/CIM/EquipmentBoundary/3/1") || nodeString.equals("http://entsoe.eu/CIM/EquipmentBoundaryOperation/3/1")) {
                    keyword = "EQBD";
                }else if (nodeString.equals("http://entsoe.eu/CIM/TopologyBoundary/3/1")) {
                    keyword = "TPBD";
                }else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0")) {
                    keyword = "EQ";
                }else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/Operation-EU/3.0")) {
                    keyword = "OP";
                }else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/ShortCircuit-EU/3.0")) {
                    keyword = "SC";
                }else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/SteadyStateHypothesis-EU/3.0")){
                    keyword="SSH";
                }else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/Topology-EU/3.0")) {
                    keyword = "TP";
                }else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/StateVariables-EU/3.0")) {
                    keyword = "SV";
                }else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/EquipmentBoundary-EU/3.0")) {
                    keyword = "EQBD";
                }else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/DiagramLayout-EU/3.0")) {
                    keyword = "DL";
                }else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/GeographicalLocation-EU/3.0")) {
                    keyword = "GL";
                }else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/Dynamics-EU/1.0")) {
                    keyword = "DY";
                }
            }
        }

        if (keyword.equals("")){
            List<Resource> listRes=model.listSubjectsWithProperty(RDF.type).toList();
            for (Resource res : listRes){
                if (res.getLocalName().contains("Ontology.keyword")){
                    keyword = model.getRequiredProperty(res,ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().asLiteral().getString();
                }
            }
        }





        return keyword;
    }



    //check if the class exists in a model. Returns the 1st one.
    private static Map<Boolean,RDFNode> hasClass(Model instanceDataModel, List<Resource> concreteClasses) {

        Map<Boolean,RDFNode> hasClassMap = new HashMap<>();
        RDFNode resource = null;
        hasClassMap.put(false,resource);

        Set<RDFNode> resInInstanceDataModel= instanceDataModel.listObjectsOfProperty(RDF.type).toSet();
        for (RDFNode concreteClass : concreteClasses) {
            if (resInInstanceDataModel.contains(concreteClass)) {
                hasClassMap.put(true,concreteClass);
                break;
            }
        }

        return hasClassMap;
    }

    //select the right class for association.
    private static Model selectAssociationReferenceAndAddAssociation(Model instanceDataModel, List<Resource> concreteClasses,
                                                                   Map<String, Boolean> inputData, String classFullURI, Property property, Boolean conform,
                                                                   ArrayList<Object> profileData, String xmlBase, Integer cardinalityFlag,
                                                                    String cardinality, String classLocalName) {

        //check if there are shacl restrictions
        Map<String,Object> shaclContraintResult;
        List<Resource> inValues = null;
        Resource resOfconcreteClass=null;
        Property fullClassPropertyKey = ResourceFactory.createProperty(classFullURI+"propertyFullURI"+property);
        Boolean noSHACLinfo=false;
        Map<Boolean,RDFNode> hasClassMapWithoutShacl=hasClass(instanceDataModel,concreteClasses);
        Map<Boolean,RDFNode> hasClassMapWithShacl=null;
        //check if there are some shacl constraints to be considered
        if (inputData.get("shacl")){ //shacl constraints are to be considered
            if (shaclConstraints.containsKey(fullClassPropertyKey)){ //the attribute has some constraints defined
                shaclContraintResult=getShaclConstraint(conform, fullClassPropertyKey);
                if (shaclContraintResult.containsKey("in")) {
                    inValues = (List<Resource>) shaclContraintResult.get("in");

                    //check if the class is in the model
                    if (inValues.contains(ResourceFactory.createProperty(xmlBase+"#"+"VoltageLevel"))){//to force the containment to VoltageLevel
                        List<Resource> inValuesVoltageLevel =new ArrayList<>();
                        inValuesVoltageLevel.add(ResourceFactory.createProperty(xmlBase+"#"+"VoltageLevel"));
                        hasClassMapWithShacl = hasClass(instanceDataModel, inValuesVoltageLevel);
                    }else {
                        hasClassMapWithShacl = hasClass(instanceDataModel, inValues);
                    }
                }else{
                    noSHACLinfo=true;
                }
            }else{
                noSHACLinfo=true;
            }
        }else {
            noSHACLinfo=true;
        }


        // see which resource needs to be added - multiple situations
        if (noSHACLinfo && hasClassMapWithoutShacl.containsKey(true)){ // the case when there is no shacl info and the class exists in the model
            resOfconcreteClass = instanceDataModel.listSubjectsWithProperty(RDF.type, hasClassMapWithoutShacl.get(true)).next();
        } else if (noSHACLinfo && !hasClassMapWithoutShacl.containsKey(true)){ // the case when there is no shacl info and the class does not exist in the model
            Resource originalClassRes = classResource;
            instanceDataModel = addClass(profileData, instanceDataModel, xmlBase, concreteClasses.get(0).toString(), cardinalityFlag,inputData);
            resOfconcreteClass=classResource;
            classResource=originalClassRes;
        } else if (!noSHACLinfo && hasClassMapWithShacl.containsKey(true)){// the case when there is shacl info and the class exists in the model
            resOfconcreteClass = instanceDataModel.listSubjectsWithProperty(RDF.type, hasClassMapWithShacl.get(true)).next();
        } else if (!noSHACLinfo && !hasClassMapWithShacl.containsKey(true)) {// the case when there is shacl info and the class does not exists in the model
            Resource originalClassRes = classResource;
            instanceDataModel = addClass(profileData, instanceDataModel, xmlBase, inValues.get(0).toString(), cardinalityFlag,inputData);
            resOfconcreteClass=classResource;
            classResource=originalClassRes;
        }

        //create the association
        instanceDataModel = addAssociation(instanceDataModel, property, conform, cardinality, cardinalityFlag, resOfconcreteClass,classLocalName,inputData, classFullURI);


        return instanceDataModel;
    }

    public static InputStream unzip(File selectedFile) {
        InputStream inputStream = null;
        try{
            ZipFile zipFile = new ZipFile(selectedFile);

            Enumeration<? extends ZipEntry> entries = zipFile.entries();



            while(entries.hasMoreElements()){
                ZipEntry entry = entries.nextElement();
                if(entry.isDirectory()){
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setContentText("Selected zip file contains folder. This is a violation of data exchange standard.");
                    alert.setHeaderText(null);
                    alert.setTitle("Error - violation of a zip file packaging requirement.");
                    alert.showAndWait();
                } else {
                    String destPath = selectedFile.getParent() + File.separator+ entry.getName();

                    if(! isValidDestPath(selectedFile.getParent(), destPath)){
                        throw new IOException("Final file output path is invalid: " + destPath);
                    }

                    try{
                        inputStream = zipFile.getInputStream(entry);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        } catch(IOException e){
            throw new RuntimeException("Error unzipping file " + selectedFile, e);
        }
        return inputStream;
    }

    public static UnzippedFiles unzipToCustomClass(File selectedFile) {
        UnzippedFiles unzippedFiles = null;
        List<InputStream> inputStreamList = new LinkedList<>();
        List<String> fileNames = new LinkedList<>();
        InputStream inputStream = null;

        try{
            ZipFile zipFile = new ZipFile(selectedFile);

            Enumeration<? extends ZipEntry> entries = zipFile.entries();

            while(entries.hasMoreElements()){
                ZipEntry entry = entries.nextElement();
                if(entry.isDirectory()){
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setContentText("Selected zip file contains folder. This is a violation of data exchange standard.");
                    alert.setHeaderText(null);
                    alert.setTitle("Error - violation of a zip file packaging requirement.");
                    alert.showAndWait();
                } else {
                    String destPath = selectedFile.getParent() + File.separator+ entry.getName();

                    if(! isValidDestPath(selectedFile.getParent(), destPath)){
                        throw new IOException("Final file output path is invalid: " + destPath);
                    }

                    try{
                        inputStream = zipFile.getInputStream(entry);
                        inputStreamList.add(inputStream);
                        fileNames.add(entry.getName());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        } catch(IOException e){
            throw new RuntimeException("Error unzipping file " + selectedFile, e);
        }
        unzippedFiles = new UnzippedFiles(inputStreamList,fileNames,inputStreamList.size()==1);
        return unzippedFiles;
    }

    private static boolean isValidDestPath(String targetDir, String destPathStr) {
        // validate the destination path of a ZipFile entry,
        // and return true or false telling if it's valid or not.

        Path destPath           = Paths.get(destPathStr);
        Path destPathNormalized = destPath.normalize(); //remove ../../ etc.

        return destPathNormalized.toString().startsWith(targetDir + File.separator);
    }


}
