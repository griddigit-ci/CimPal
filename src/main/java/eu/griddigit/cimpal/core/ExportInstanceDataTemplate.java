/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */

package eu.griddigit.cimpal.core;

import eu.griddigit.cimpal.application.MainController;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.griddigit.cimpal.model.RDFAttributeData;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.datatypes.xsd.impl.RDFLangString;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.vocabulary.DCAT;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.topbraid.shacl.vocabulary.SH;

import java.io.*;
import java.util.*;

import static eu.griddigit.cimpal.core.ExportRDFSdescriptions.getRDFDataForClasses;
import static eu.griddigit.cimpal.core.RdfConvert.fileSaveDialog;
import static eu.griddigit.cimpal.core.ShaclTools.*;
import static eu.griddigit.cimpal.util.ExcelTools.*;
import static org.topbraid.shacl.vocabulary.SH.path;


public class ExportInstanceDataTemplate {

    public static void rdfsContent(List<File> file, boolean templateOnly) throws FileNotFoundException {
        String cimsNs = MainController.prefs.get("cimsNamespace", "");
        String concreteNs = "http://iec.ch/TC57/NonStandard/UML#concrete";

        List<String> rdfsClassName = new LinkedList<>(); // list for the name of the class without namespace
        List<String> rdfsClass = new LinkedList<>(); // list for the classes
        List<String> rdfsAttrAssoc = new LinkedList<>(); // list for the property attribute or association
        List<String> rdfsAttrOrAssocFlag = new LinkedList<>(); // list for the identification if is attribute or association
        List<String> rdfsItemAttrDatatype = new LinkedList<>(); // list for the datatype in case of attribute
        List<String> rdfsItemMultiplicity = new LinkedList<>(); // list for the multiplicity of the item
        List<String> rdfsProfileKeyword = new LinkedList<>(); // list for the profile keyword of the item
        List<String> rdfsProfileURI = new LinkedList<>(); // list for the profile URI of the item
        List<String> rdfsXSDdatatype = new LinkedList<>(); // list for the xsd datatype of the item
        List<String> rdfsAttrAssocUnique = new LinkedList<>(); // list for the property attribute or association
        List<String> rdfsXSDdatatypeUnique = new LinkedList<>(); // list for the xsd datatype of the item
        rdfsClassName.add("Class Name");
        rdfsClass.add("Class");
        rdfsAttrAssoc.add("Property-AttributeAssociation");
        rdfsItemMultiplicity.add("Multiplicity");
        rdfsItemAttrDatatype.add("Datatype");
        rdfsAttrOrAssocFlag.add("Type");
        rdfsXSDdatatype.add("XSDdatatype");
        rdfsProfileKeyword.add("ProfileKeyword");
        rdfsProfileURI.add("ProfileURI");

        List<String> associationPerClassClassName = new LinkedList<>(); // the class name that has the association
        List<String> associationPerClassUsedName = new LinkedList<>(); // the end role name for used association
        List<String> associationPerClassInvName = new LinkedList<>(); // the end role name for inverse association
        List<String> associationPerClassInvRoleName = new LinkedList<>(); // the end role name for inverse association


        Map<String, String> prefMap = new HashMap<>();

        for (File fil : file) {
            Model model = ModelFactory.createDefaultModel(); // model is the rdf file
            try {
                RDFDataMgr.read(model, new FileInputStream(fil), Lang.RDFXML);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }

            Map<String, String> prefMapTemp = model.getNsPrefixMap();
            prefMap.putAll(prefMapTemp);
            //get the profile keyword and URI
            List<String> profileKeywordURI = getProfileKeywordURI(model);
            String profileKeyword = profileKeywordURI.getFirst();
            String profileURI = profileKeywordURI.get(1);
            String cgmesVersion = profileKeywordURI.get(2);

            MainController.shapesOnAbstractOption = 0;
            ArrayList<Object> shapeData = ShaclTools.constructShapeData(model, cimsNs, concreteNs);

            for (int cl = 0; cl < ((ArrayList<?>) shapeData.getFirst()).size(); cl++) { //this is to loop on the classes in the profile and record for each concrete class
                //add the Class
                String classLocalName = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).getFirst()).get(2).toString();
                String classFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).getFirst()).getFirst().toString();

                //check if the class has stereotype "Description" and remove IdentifiedObject.name, .description, .mRID in case they are there
                boolean classIsDescription = false;
                for (StmtIterator i = model.listStatements(ResourceFactory.createResource(classFullURI), ResourceFactory.createProperty(cimsNs, "stereotype"), (RDFNode) null); i.hasNext(); ) {
                    Statement stmt = i.next();
                    if (stmt.getObject().toString().equals("Description")) {
                        classIsDescription = true;
                    }
                }

                for (int atas = 1; atas < ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).size(); atas++) {
                    // this is to loop on the attributes and associations (including inherited) for a given class and add PropertyNode for each attribute or association

                /*
                //every time a new property is added the reference is also added to the ShapeNode of the class
                ArrayList<Object> propertyNodeFeatures = new ArrayList<>();
                *//*
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
                     *//*
                for (int i = 0; i < 14; i++) {
                    propertyNodeFeatures.add("");
                }*/

                    if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).getFirst().toString().equals("Association")) {//if it is an association
                        if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(1).toString().equals("Yes")) {
                            //Cardinality check
                            String cardinality = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(6).toString();
                            String propertyFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(2).toString();
                            rdfsClassName.add(classLocalName);
                            rdfsClass.add(classFullURI);
                            rdfsAttrAssoc.add(propertyFullURI);
                            rdfsAttrOrAssocFlag.add("Association");
                            rdfsItemAttrDatatype.add("N/A");
                            rdfsItemMultiplicity.add(cardinality);
                            rdfsXSDdatatype.add("N/A");
                            associationPerClassClassName.add(classLocalName);
                            associationPerClassUsedName.add(model.listStatements(ResourceFactory.createResource(propertyFullURI), RDFS.label, (RDFNode) null).next().getObject().asLiteral().getValue().toString());
                            //find the inverse role
                            Resource invAssocRes = model.listStatements(ResourceFactory.createResource(propertyFullURI), ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#inverseRoleName"), (RDFNode) null).next().getObject().asResource();
                            Resource invAssocResRange = model.listStatements(invAssocRes, RDFS.range, (RDFNode) null).next().getObject().asResource();
                            associationPerClassInvName.add(invAssocResRange.getLocalName());
                            associationPerClassInvRoleName.add(model.listStatements(invAssocRes, RDFS.label, (RDFNode) null).next().getObject().asLiteral().getValue().toString());

                            if (cgmesVersion.equals("2.4.15")) {
                                List<String> keyList = new LinkedList<>();
                                List<String> keyURIList = new LinkedList<>();
                                for (StmtIterator i = model.listStatements(ResourceFactory.createResource(propertyFullURI), ResourceFactory.createProperty(cimsNs, "stereotype"), (RDFNode) null); i.hasNext(); ) {
                                    Statement stmt = i.next();
                                    if (stmt.getObject().toString().equals("Operation")) {
                                        keyList.add("OP");
                                        keyURIList.add("http://entsoe.eu/CIM/EquipmentOperation/3/1");
                                    } else if (stmt.getObject().toString().equals("ShortCircuit")) {
                                        keyList.add("SC");
                                        keyURIList.add("http://entsoe.eu/CIM/EquipmentShortCircuit/3/1");
                                    }
                                }
                                if (keyList.isEmpty()) {
                                    keyList.add(profileKeyword);
                                    keyURIList.add(profileURI);
                                }
                                rdfsProfileKeyword.add(keyList.toString());
                                rdfsProfileURI.add(keyURIList.toString());
                            } else {
                                rdfsProfileKeyword.add(profileKeyword);
                                rdfsProfileURI.add(profileURI);
                            }
                        }

                    } else if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(0).toString().equals("Attribute")) {//if it is an attribute
                        String propertyFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(1).toString();
                        String cardinality = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(5).toString();

                        String propertyLocalName = ResourceFactory.createResource(propertyFullURI).getLocalName();
                        if (classIsDescription && (propertyLocalName.equals("IdentifiedObject.name") || propertyLocalName.equals("IdentifiedObject.description") || propertyLocalName.equals("IdentifiedObject.mRID"))) {
                            continue;
                        }

                        rdfsClassName.add(classLocalName);
                        rdfsClass.add(classFullURI);
                        rdfsAttrAssoc.add(propertyFullURI);

                        if (cgmesVersion.equals("2.4.15")) {
                            List<String> keyList = new LinkedList<>();
                            List<String> keyURIList = new LinkedList<>();

                            for (StmtIterator i = model.listStatements(ResourceFactory.createResource(propertyFullURI), ResourceFactory.createProperty(cimsNs, "stereotype"), (RDFNode) null); i.hasNext(); ) {
                                Statement stmt = i.next();
                                if (stmt.getObject().toString().equals("Operation")) {
                                    keyList.add("OP");
                                    keyURIList.add("http://entsoe.eu/CIM/EquipmentOperation/3/1");
                                } else if (stmt.getObject().toString().equals("ShortCircuit")) {
                                    keyList.add("SC");
                                    keyURIList.add("http://entsoe.eu/CIM/EquipmentShortCircuit/3/1");
                                }
                            }
                            if (keyList.isEmpty()) {
                                for (StmtIterator i = model.listStatements(ResourceFactory.createResource(classFullURI), ResourceFactory.createProperty(cimsNs, "stereotype"), (RDFNode) null); i.hasNext(); ) {
                                    Statement stmt = i.next();
                                    if (stmt.getObject().toString().equals("Operation")) {
                                        keyList.add("OP");
                                        keyURIList.add("http://entsoe.eu/CIM/EquipmentOperation/3/1");
                                    } else if (stmt.getObject().toString().equals("ShortCircuit")) {
                                        keyList.add("SC");
                                        keyURIList.add("http://entsoe.eu/CIM/EquipmentShortCircuit/3/1");
                                    }
                                }
                                if (keyList.isEmpty()) {
                                    keyList.add(profileKeyword);
                                    keyURIList.add(profileURI);
                                }
                            }
                            rdfsProfileKeyword.add(keyList.toString());
                            rdfsProfileURI.add(keyURIList.toString());
                        } else {
                            rdfsProfileKeyword.add(profileKeyword);
                            rdfsProfileURI.add(profileURI);
                        }

                        rdfsItemMultiplicity.add(cardinality);
                        //add datatypes checks depending on it is Primitive, Datatype or Enumeration
                        switch (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(8).toString()) {
                            case "Primitive": {
                                String datatypePrimitive = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(10).toString(); //this is localName e.g. String
                                rdfsItemAttrDatatype.add(datatypePrimitive);
                                rdfsAttrOrAssocFlag.add("Attribute");
                                rdfsXSDdatatype.add(getXSDtype(datatypePrimitive));

                                if (!rdfsAttrAssocUnique.contains(propertyFullURI)) {
                                    rdfsAttrAssocUnique.add(propertyFullURI);
                                    rdfsXSDdatatypeUnique.add(getXSDtype(datatypePrimitive));
                                }

                                break;
                            }
                            case "CIMDatatype": {
                                String datatypePrimitive = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(9).toString(); //this is localName e.g. String
                                rdfsItemAttrDatatype.add(datatypePrimitive);
                                rdfsAttrOrAssocFlag.add("Attribute");
                                rdfsXSDdatatype.add(getXSDtype(datatypePrimitive));
                                if (!rdfsAttrAssocUnique.contains(propertyFullURI)) {
                                    rdfsAttrAssocUnique.add(propertyFullURI);
                                    rdfsXSDdatatypeUnique.add(getXSDtype(datatypePrimitive));
                                }
                                break;
                            }
                            case "Compound": {
                                String datatypeCompound = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(9).toString(); //this is localName e.g. String
                                rdfsItemAttrDatatype.add(datatypeCompound);
                                rdfsAttrOrAssocFlag.add("Compound");
                                rdfsXSDdatatype.add("N/A");
                                break;
                            }
                            case "Enumeration":
                                rdfsItemAttrDatatype.add(((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(10).toString());
                                rdfsAttrOrAssocFlag.add("Enumeration");
                                rdfsXSDdatatype.add("N/A");
                                break;
                        }
                    }
                }
            }

        }

        List<String> orderList = new LinkedList<>(); // list of order
        Map<String, List<String>> rdfsInfo = new HashMap<>();

        if (templateOnly) {
            orderList.add("Class");
            orderList.add("Property-AttributeAssociation");
            orderList.add("Type");
            orderList.add("Datatype");
            orderList.add("Multiplicity");

            rdfsInfo.put("Class", rdfsClass);
            rdfsInfo.put("Property-AttributeAssociation", rdfsAttrAssoc);
            rdfsInfo.put("Multiplicity", rdfsItemMultiplicity);
            rdfsInfo.put("Datatype", rdfsItemAttrDatatype);
            rdfsInfo.put("Type", rdfsAttrOrAssocFlag);

            // do the excel export
            XSSFWorkbook workbook = new XSSFWorkbook();
            workbook = exportMapToExcel("Template", rdfsInfo, orderList, workbook);
            saveExcelFile(workbook, "Save Instance Data Template", "Template");
        } else {
            orderList.add("Class Name");
            orderList.add("Class");
            orderList.add("Property-AttributeAssociation");
            orderList.add("Multiplicity");
            orderList.add("Datatype");
            orderList.add("Type");
            orderList.add("XSDdatatype");
            orderList.add("ProfileKeyword");
            orderList.add("ProfileURI");

            rdfsInfo.put("Class", rdfsClass);
            rdfsInfo.put("Class Name", rdfsClassName);
            rdfsInfo.put("Property-AttributeAssociation", rdfsAttrAssoc);
            rdfsInfo.put("Multiplicity", rdfsItemMultiplicity);
            rdfsInfo.put("Datatype", rdfsItemAttrDatatype);
            rdfsInfo.put("Type", rdfsAttrOrAssocFlag);
            rdfsInfo.put("XSDdatatype", rdfsXSDdatatype);
            rdfsInfo.put("ProfileKeyword", rdfsProfileKeyword);
            rdfsInfo.put("ProfileURI", rdfsProfileURI);
            // do the excel export
            XSSFWorkbook workbook = new XSSFWorkbook();
            workbook = exportMapToExcel("RDFS_info", rdfsInfo, orderList, workbook);
            saveExcelFile(workbook, "Save RDFS info", "rdfs_Info");
            // do the RDF export
            exportDesciptionInRDF(rdfsClass, rdfsAttrAssoc, rdfsAttrOrAssocFlag, rdfsItemAttrDatatype, rdfsItemMultiplicity, prefMap);
            // do the export to JSON
            exportDesciptionToJSON(rdfsInfo);
            // do the export to JSON - only mapping
            Map<String, List<String>> rdfsInfoMapping = new HashMap<>();
            rdfsInfoMapping.put("Property-AttributeAssociation", rdfsAttrAssocUnique);
            rdfsInfoMapping.put("XSDdatatype", rdfsXSDdatatypeUnique);
            exportDesciptionToJSON(rdfsInfoMapping);

            Map<String, List<String>> assocInfo = new HashMap<>();
            assocInfo.put("Class", associationPerClassClassName);
            assocInfo.put("UsedAssociationEndRoleName", associationPerClassUsedName);
            assocInfo.put("InverseAssociationRangeName", associationPerClassInvName);
            assocInfo.put("InverseAssociationLabelName", associationPerClassInvRoleName);

            exportDesciptionToJSON(assocInfo);
        }
    }

    private static void exportDesciptionToJSON(Map<String, List<String>> rdfsInfo) {


        File saveFile = eu.griddigit.cimpal.util.ModelFactory.fileSaveCustom("JSON files", List.of("*.json"), "", "");
        if (saveFile != null) {
            // Export to JSON
            ObjectMapper objectMapper = new ObjectMapper();
            try {
                FileOutputStream outputStream = new FileOutputStream(saveFile);
                objectMapper.writeValue(outputStream, rdfsInfo);
                outputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static String getXSDtype(String datatype) {
        String xsdType = switch (datatype) {
            case "Integer" -> XSDDatatype.XSDinteger.getURI();
            case "Float" -> XSDDatatype.XSDfloat.getURI();
            case "String", "StringFixedLanguage" -> XSDDatatype.XSDstring.getURI();
            case "Boolean" -> XSDDatatype.XSDboolean.getURI();
            case "Date" -> XSDDatatype.XSDdate.getURI();
            case "DateTime" -> XSDDatatype.XSDdateTime.getURI();
            case "DateTimeStamp" -> XSDDatatype.XSDdateTimeStamp.getURI();
            case "Decimal" -> XSDDatatype.XSDdecimal.getURI();
            case "Duration" -> XSDDatatype.XSDduration.getURI();
            case "MonthDay" -> XSDDatatype.XSDgMonthDay.getURI();
            case "Time" -> XSDDatatype.XSDtime.getURI();
            case "URI", "IRI", "StringIRI", "URL" -> XSDDatatype.XSDanyURI.getURI();
            case "LangString" -> RDFLangString.rdfLangString.getURI();
            default -> "";
        };
        return xsdType;
    }

    private static List<String> getProfileKeywordURI(Model model) {

        List<String> profileMap = new LinkedList<>();
        if (model.listStatements(null, RDF.type, OWL2.Ontology).hasNext() && model.listStatements(null, OWL2.versionIRI, (RDFNode) null).hasNext()) { //RDFS 2020 profiles
            Statement headerStmt = model.listStatements(null, RDF.type, OWL2.Ontology).nextStatement();
            if (model.listStatements(headerStmt.getSubject(), OWL2.versionIRI, (RDFNode) null).hasNext() && model.listStatements(headerStmt.getSubject(), DCAT.keyword, (RDFNode) null).hasNext()) {
                String versionIRI = model.listStatements(headerStmt.getSubject(), OWL2.versionIRI, (RDFNode) null).next().getObject().toString();
                String keyword = model.listStatements(headerStmt.getSubject(), DCAT.keyword, (RDFNode) null).next().getObject().toString();
                profileMap.add(keyword);
                profileMap.add(versionIRI);
                profileMap.add("3.0.0");
            }

        } else { //CGMES v2.4 profiles
            String versionNS = "http://entsoe.eu/CIM/SchemaExtension/3/1#";
            Property isFixed = ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed");

            if (model.listStatements(null, RDFS.label, ResourceFactory.createLangLiteral("entsoeURIcore", "en")).hasNext()) {
                Statement entsoeURIStmt = model.listStatements(null, RDFS.label, ResourceFactory.createLangLiteral("entsoeURIcore", "en")).next();
                String versionURI = model.listStatements(entsoeURIStmt.getSubject(), isFixed, (RDFNode) null).next().getObject().toString();
                String keyword = switch (versionURI) {
                    case "http://entsoe.eu/CIM/EquipmentBoundary/3/1" -> "EQ_BD";
                    case "http://entsoe.eu/CIM/EquipmentCore/3/1" -> "EQ";
                    default -> "no key";
                };
                profileMap.add(keyword);
                profileMap.add(versionURI);
                profileMap.add("2.4.15");
            } else if (model.listStatements(null, RDFS.label, ResourceFactory.createLangLiteral("entsoeURI", "en")).hasNext()) {
                Statement entsoeURIStmt = model.listStatements(null, RDFS.label, ResourceFactory.createLangLiteral("entsoeURI", "en")).next();
                String versionURI = model.listStatements(entsoeURIStmt.getSubject(), isFixed, (RDFNode) null).next().getObject().toString();
                String keyword = switch (versionURI) {
                    case "http://entsoe.eu/CIM/TopologyBoundary/3/1" -> "TP_BD";
                    case "http://entsoe.eu/CIM/Topology/4/1" -> "TP";
                    case "http://entsoe.eu/CIM/SteadyStateHypothesis/1/1" -> "SSH";
                    case "http://entsoe.eu/CIM/StateVariables/4/1" -> "SV";
                    case "http://entsoe.eu/CIM/Dynamics/3/1" -> "DY";
                    case "http://entsoe.eu/CIM/GeographicalLocation/2/1" -> "GL";
                    case "http://entsoe.eu/CIM/DiagramLayout/3/1" -> "DL";
                    default -> "no key";
                };
                profileMap.add(keyword);
                profileMap.add(versionURI);
                profileMap.add("2.4.15");
            } else if (model.listStatements(null, RDFS.label, ResourceFactory.createLangLiteral("europeanProfileURI", "en")).hasNext()) {
                Statement entsoeURIStmt = model.listStatements(null, RDFS.label, ResourceFactory.createLangLiteral("europeanProfileURI", "en")).next();
                String versionURI = model.listStatements(entsoeURIStmt.getSubject(), isFixed, (RDFNode) null).next().getObject().toString();
                String keyword = "no key";
                if (versionURI.equals("http://iec.ch/TC57/61970-552/ModelDescription/1#")) {
                    keyword = "DH";
                    profileMap.add(keyword);
                    profileMap.add(versionURI);
                    profileMap.add("header");
                }
            }
        }
        return profileMap;
    }

    private static void exportDesciptionInRDF(List<String> rdfsItem, List<String> rdfsItemDescription, List<String> rdfsItemKind, List<String> rdfsItemtype, List<String> rdfsItemMultiplicity, Map<String, String> prefMap) throws FileNotFoundException {
        // rdfsItem is the class
        // rdfsItemDescription is the property
        // rdfsItemtype - the datatype
        // rdfsItemKind - attribute.association, enum
        // rdfsItemMultiplicity - multiplicity


        Model model = ModelFactory.createDefaultModel(); // model is the rdf file
        model.setNsPrefixes(prefMap);
        model.setNsPrefix("owl", OWL2.NS);
        model.setNsPrefix("cims", "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#");
        int listcount = 0;
        for (String property : rdfsItemDescription) {
            Resource sub = ResourceFactory.createResource(property);
            model.add(ResourceFactory.createStatement(sub, RDF.type, RDF.Property));
            Resource subClass = ResourceFactory.createResource(rdfsItem.get(listcount));
            model.add(ResourceFactory.createStatement(subClass, RDF.type, RDFS.Class));
            model.add(ResourceFactory.createStatement(sub, RDFS.domain, subClass));
            model.add(ResourceFactory.createStatement(subClass, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "stereotype"), ResourceFactory.createPlainLiteral("concrete")));
            RDFNode minC;
            RDFNode maxC;
            if (rdfsItemMultiplicity.get(listcount).contains("..")) {
                minC = ResourceFactory.createPlainLiteral(rdfsItemMultiplicity.get(listcount).split("\\.\\.", 2)[0]);
                maxC = ResourceFactory.createPlainLiteral(rdfsItemMultiplicity.get(listcount).split("\\.\\.", 2)[1]);
            } else {
                minC = ResourceFactory.createPlainLiteral(rdfsItemMultiplicity.get(listcount));
                maxC = ResourceFactory.createPlainLiteral(rdfsItemMultiplicity.get(listcount));
            }
            model.add(ResourceFactory.createStatement(sub, OWL2.minCardinality, minC));
            model.add(ResourceFactory.createStatement(sub, OWL2.maxCardinality, maxC));
            switch (rdfsItemKind.get(listcount)) {
                case "Association" -> model.add(ResourceFactory.createStatement(sub, RDFS.range, RDFS.Resource));
                case "Enumeration" -> model.add(ResourceFactory.createStatement(sub, RDFS.range, RDF.List));
                case "Attribute" -> {
                    model.add(ResourceFactory.createStatement(sub, RDFS.range, RDFS.Literal));
                    model.add(ResourceFactory.createStatement(sub, RDFS.range, ResourceFactory.createProperty(getXSDtype(rdfsItemtype.get(listcount)))));
                    model.add(ResourceFactory.createStatement(sub, RDFS.range, ResourceFactory.createPlainLiteral(rdfsItemtype.get(listcount))));
                }
            }
            listcount = listcount + 1;
        }
        OutputStream outTTL = fileSaveDialog("Save RDF Turtle for: " + "AllProperties", "RDF Turtle", "*.ttl");
        model.write(outTTL, RDFFormat.TURTLE.getLang().getLabel().toUpperCase(), "");
    }

    public static void CreateTemplateFromRDF(List<File> file, List<File> iFiles, String selectedMethod, boolean hide) {
        String cimsNs = MainController.prefs.get("cimsNamespace", "");
        String concreteNs = "http://iec.ch/TC57/NonStandard/UML#concrete";

        List<String> rdfsClassName = new LinkedList<>(); // list for the name of the class without namespace
        List<String> rdfsClassDescription = new LinkedList<>(); // list for the class if it is description
        List<String> rdfsClass = new LinkedList<>(); // list for the classes
        List<String> rdfsAttrAssoc = new LinkedList<>(); // list for the property attribute or association
        List<String> rdfsAttrOrAssocFlag = new LinkedList<>(); // list for the identification if is attribute or association
        List<String> rdfsItemAttrDatatype = new LinkedList<>(); // list for the datatype in case of attribute
        List<String> rdfsItemMultiplicity = new LinkedList<>(); // list for the multiplicity of the item

        Map<String, String> prefMap = new HashMap<>();

        for (File fil : file) {
            Model model = ModelFactory.createDefaultModel(); // model is the rdf file
            try {
                RDFDataMgr.read(model, new FileInputStream(fil), "", Lang.RDFXML);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }

            Map<String, String> prefMapTemp = model.getNsPrefixMap();
            prefMap.putAll(prefMapTemp);

            MainController.shapesOnAbstractOption = 0;
            RDFNode literalYes = ResourceFactory.createPlainLiteral("Yes");
            Property assocUsed = ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#","AssociationUsed");
            Property multiplicity = ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#","multiplicity");

            for (ResIterator i = model.listResourcesWithProperty(RDF.type, RDFS.Class); i.hasNext(); ) {
                Resource resItem = i.next();
                if (classIsNotEnumOrDatatype(resItem, model)) {
                    //check if the class is concrete
                    boolean classDescripStereo = classIsDescription(resItem, model);
                    boolean isConcrete = classIsConcrete(resItem, model);
                    if (isConcrete) {
                        String localName = resItem.getLocalName();
                        String classFullURI = resItem.getURI();

                        //get all local and inherited properties
                        List<Statement> localInheritProperties = new LinkedList<>();

                        int root = 0;
                        Resource classItem = resItem;
                        while (root == 0) {
                            if (model.listStatements(null, RDFS.domain, classItem).hasNext()) {
                                localInheritProperties.addAll(model.listStatements(null, RDFS.domain, classItem).toList());
                                if (classItem.hasProperty(RDFS.subClassOf)) {//has subClassOf
                                    classItem = classItem.getRequiredProperty(RDFS.subClassOf).getResource(); // the resource of the subClassOf
                                } else {
                                    root = 1;
                                }
                            } else {
                                root = 1;
                            }
                        }

                        for (Statement stmt : localInheritProperties) { // loop on the local and inherited properties
                            if (model.listStatements(stmt.getSubject(), assocUsed, (RDFNode) null).hasNext()) { // it is an association
                                if (model.listStatements(stmt.getSubject(), assocUsed, literalYes).hasNext()) { // the association direction exchanged

                                    String propertyFullURI = stmt.getSubject().getURI();
                                    String cardinality = "";
                                    if (model.listStatements(stmt.getSubject(), multiplicity, (RDFNode) null).hasNext()) {
                                        cardinality = model.listStatements(stmt.getSubject(), multiplicity, (RDFNode) null).next().getObject().toString().split("#M:", 2)[1];
                                    }
                                    rdfsClassName.add(localName);
                                    rdfsClassDescription.add(String.valueOf(classDescripStereo));
                                    rdfsClass.add(classFullURI);
                                    rdfsAttrAssoc.add(propertyFullURI);
                                    rdfsAttrOrAssocFlag.add("Association");
                                    rdfsItemAttrDatatype.add("N/A");
                                    rdfsItemMultiplicity.add(cardinality);

                                }
                            } else {//if it is an attribute

                                String localNameAttr = stmt.getSubject().getLocalName();
                                String propertyFullURI = stmt.getSubject().getURI();

                                String propertyLocalName = ResourceFactory.createResource(propertyFullURI).getLocalName();
                                if (classDescripStereo && (propertyLocalName.equals("IdentifiedObject.name") || propertyLocalName.equals("IdentifiedObject.description") || propertyLocalName.equals("IdentifiedObject.mRID"))) {
                                    continue;
                                }

                                rdfsClassName.add(localName);
                                rdfsClassDescription.add(String.valueOf(classDescripStereo));
                                rdfsClass.add(classFullURI);
                                rdfsAttrAssoc.add(propertyFullURI);
                                String cardinality = model.listStatements(stmt.getSubject(),multiplicity,(RDFNode) null).next().getObject().toString().split("#M:", 2)[1];
                                rdfsItemMultiplicity.add(cardinality);
                                //add datatypes checks depending on it is Primitive, Datatype or Enumeration
                                Resource datatypeRes = null;
                                if (model.listStatements(stmt.getSubject(),ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#dataType"),(RDFNode) null).hasNext()) {
                                    datatypeRes = model.listStatements(stmt.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#dataType"), (RDFNode) null).next().getObject().asResource();
                                }else{
                                    datatypeRes = model.listStatements(stmt.getSubject(), RDFS.range, (RDFNode) null).next().getObject().asResource();
                                }
                                if (model.listStatements(datatypeRes,ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"),ResourceFactory.createPlainLiteral("Primitive")).hasNext()){
                                    String datatypePrimitive = model.getRequiredProperty(datatypeRes,RDFS.label).getObject().asLiteral().getString(); //this is localName e.g. String

                                    rdfsItemAttrDatatype.add(datatypePrimitive);
                                    rdfsAttrOrAssocFlag.add("Attribute");
                                }else if (model.listStatements(datatypeRes,ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"),ResourceFactory.createPlainLiteral("CIMDatatype")).hasNext()){
                                    Resource datatypevalue = ResourceFactory.createProperty(datatypeRes.getURI()+".value");

                                    String datatypePrimitive = model.getRequiredProperty(datatypevalue,ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#dataType")).getObject().asResource().getLocalName(); //this is localName e.g. String
                                    rdfsItemAttrDatatype.add(datatypePrimitive);
                                    rdfsAttrOrAssocFlag.add("Attribute");
                                }else if (model.listStatements(datatypeRes,ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"),ResourceFactory.createPlainLiteral("Compound")).hasNext()){

                                    //String datatypeCompound = model.getRequiredProperty(datatypeRes,RDFS.label).getObject().asLiteral().getString();
                                    String datatypeCompound = datatypeRes.getURI();

                                    rdfsItemAttrDatatype.add(datatypeCompound);
                                    rdfsAttrOrAssocFlag.add("Compound");
                                }else if (model.listStatements(datatypeRes,ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"),ResourceFactory.createResource("http://iec.ch/TC57/NonStandard/UML#enumeration")).hasNext()){

                                    //this adds the structure which is a list of possible enumerated values
                                    List<Resource> enumValues = model.listSubjectsWithProperty(RDF.type,datatypeRes).toList();
                                    List<String> enumValuesStr = new ArrayList<>();
                                    for (Resource enumValue : enumValues) {
                                        enumValuesStr.add(enumValue.toString());
                                    }
                                    rdfsItemAttrDatatype.add(enumValuesStr.toString());
                                    rdfsAttrOrAssocFlag.add("Enumeration");
                                }
                            }
                        }
                    }
                }
            }
        }







//
//                        ArrayList<Object> shapeData = ShaclTools.constructShapeData(model, cimsNs, concreteNs);
//
//            for (int cl = 0; cl < ((ArrayList<?>) shapeData.getFirst()).size(); cl++) { //this is to loop on the classes in the profile and record for each concrete class
//                //add the Class
//                String classLocalName = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).getFirst()).get(2).toString();
//                String classFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).getFirst()).getFirst().toString();
//
//                //check if the class has stereotype "Description" and remove IdentifiedObject.name, .description, .mRID in case they are there
//                boolean classIsDescription = false;
//                for (StmtIterator i = model.listStatements(ResourceFactory.createResource(classFullURI), ResourceFactory.createProperty(cimsNs, "stereotype"), (RDFNode) null); i.hasNext(); ) {
//                    Statement stmt = i.next();
//                    if (stmt.getObject().toString().equals("Description")) {
//                        classIsDescription = true;
//                    }
//                }
//
//                for (int atas = 1; atas < ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).size(); atas++) {
//                    // this is to loop on the attributes and associations (including inherited) for a given class and add PropertyNode for each attribute or association
//
//                /*
//                //every time a new property is added the reference is also added to the ShapeNode of the class
//                ArrayList<Object> propertyNodeFeatures = new ArrayList<>();
//                *//*
//                     * propertyNodeFeatures structure
//                     * 0 - type of check: cardinality, datatype, associationValueType
//                     * 1 - message
//                     * 2 - name
//                     * 3 - description
//                     * 4 - severity
//                     * 5 - cardinality
//                     * 6 - the primitive either it is directly a primitive or it is the primitive of the .value attribute of a CIMdatatype
//                     * in case of enumeration 6 is set to Enumeration
//                     * in case of compound 6 is set to Compound
//                     * 7 - is a list of uri of the enumeration attributes
//                     * 8 - order
//                     * 9 - group
//                     * 10 - the list of concrete classes for association - the value type at the used end
//                     * 11 - classFullURI for the targetClass of the NodeShape
//                     * 12 - the uri of the compound class to be used in sh:class
//                     * 13 - path for the attributes of the compound
//                     *//*
//                for (int i = 0; i < 14; i++) {
//                    propertyNodeFeatures.add("");
//                }*/
//
//                    if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).getFirst().toString().equals("Association")) {//if it is an association
//                        if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(1).toString().equals("Yes")) {
//                            String propertyFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(2).toString();
//                            //Cardinality check
//                            String cardinality = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(6).toString();
//                            rdfsClassName.add(classLocalName);
//                            rdfsClassDescription.add(String.valueOf(classIsDescription));
//                            rdfsClass.add(classFullURI);
//                            rdfsAttrAssoc.add(propertyFullURI);
//                            rdfsAttrOrAssocFlag.add("Association");
//                            rdfsItemAttrDatatype.add("N/A");
//                            rdfsItemMultiplicity.add(cardinality);
//                        }
//
//                    } else if (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(0).toString().equals("Attribute")) {//if it is an attribute
//                        String propertyFullURI = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(1).toString();
//                        String cardinality = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(5).toString();
//
//                        String propertyLocalName = ResourceFactory.createResource(propertyFullURI).getLocalName();
//                        if (classIsDescription && (propertyLocalName.equals("IdentifiedObject.name") || propertyLocalName.equals("IdentifiedObject.description") || propertyLocalName.equals("IdentifiedObject.mRID"))) {
//                            continue;
//                        }
//
//                        rdfsClassName.add(classLocalName);
//                        rdfsClassDescription.add(String.valueOf(classIsDescription));
//                        rdfsClass.add(classFullURI);
//                        rdfsAttrAssoc.add(propertyFullURI);
//
//                        rdfsItemMultiplicity.add(cardinality);
//                        //add datatypes checks depending on it is Primitive, Datatype or Enumeration
//                        switch (((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(8).toString()) {
//                            case "Primitive": {
//                                String datatypePrimitive = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(10).toString(); //this is localName e.g. String
//                                rdfsItemAttrDatatype.add(datatypePrimitive);
//                                rdfsAttrOrAssocFlag.add("Attribute");
//                                break;
//                            }
//                            case "CIMDatatype": {
//                                String datatypePrimitive = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(9).toString(); //this is localName e.g. String
//                                rdfsItemAttrDatatype.add(datatypePrimitive);
//                                rdfsAttrOrAssocFlag.add("Attribute");
//                                break;
//                            }
//                            case "Compound": {
//                                String datatypeCompound = ((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(9).toString(); //this is localName e.g. String
//                                rdfsItemAttrDatatype.add(datatypeCompound);
//                                rdfsAttrOrAssocFlag.add("Compound");
//                                break;
//                            }
//                            case "Enumeration":
//                                rdfsItemAttrDatatype.add(((ArrayList<?>) ((ArrayList<?>) ((ArrayList<?>) shapeData.getFirst()).get(cl)).get(atas)).get(10).toString());
//                                rdfsAttrOrAssocFlag.add("Enumeration");
//                                break;
//                        }
//                    }
//                }
//            }

        //}

        Map<String, List<String>> rdfsInfo = new HashMap<>();
        List<String> orderList = new LinkedList<>(); // list of order

        orderList.add("Class");
        orderList.add("Property-AttributeAssociation");
        orderList.add("Type");
        orderList.add("Datatype");
        orderList.add("Multiplicity");

        rdfsInfo.put("Class", rdfsClass);
        rdfsInfo.put("ClassIfDescription", rdfsClassDescription);
        rdfsInfo.put("ClassName", rdfsClassName);
        rdfsInfo.put("Property-AttributeAssociation", rdfsAttrAssoc);
        rdfsInfo.put("Multiplicity", rdfsItemMultiplicity);
        rdfsInfo.put("Datatype", rdfsItemAttrDatatype);
        rdfsInfo.put("Type", rdfsAttrOrAssocFlag);

        int i = 0;
        do {
            try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                String outFileName = "Template";
                switch (selectedMethod) {
                    case "Option 1 (Old)":
                        exportMapToExcel("Template", rdfsInfo, orderList, workbook);
                        break;
                    case "Option 2 (New)":
                        Map<String, List<List<RDFAttributeData>>> instanceClassData = null;
                        File iFile = null;
                        if (!iFiles.isEmpty()) {
                            iFile = iFiles.get(i);
                            if (iFile != null) {
                                Model model = ModelFactory.createDefaultModel();
                                RDFDataMgr.read(model, new FileInputStream(iFile), "", Lang.RDFXML);
                                instanceClassData = getRDFDataForClasses(model);
                                outFileName = iFile.getName()
                                        .replaceAll("(?i)\\.xml$", "")
                                        .trim();
                                outFileName = outFileName + "_Template";
                            }
                        }

                        exportMapToExcelv2(rdfsInfo, prefMap, instanceClassData, workbook, hide);
                        break;
                    case "Option 3 (TBD)":
                        break;
                }
                saveExcelFile(workbook, "Save Instance Data Template", outFileName);
            } catch (RuntimeException | IOException e) {
                throw new RuntimeException(e);
            }
            i++;
        } while (iFiles.size() > i);
    }
}
