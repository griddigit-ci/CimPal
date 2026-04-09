/**
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2023, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */

package eu.griddigit.cimpal.main.application.CGMESConverter;

import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.writer.formats.*;
import javafx.scene.control.Alert;
import javafx.stage.FileChooser;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.SysRIOT;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.vocabulary.DCAT;
import org.apache.jena.vocabulary.RDF;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class InstanceDataFactory {


    //Loads one or many models
    public static Map<String,Model> modelLoad(List<File> files, String xmlBase, Lang rdfSourceFormat) throws FileNotFoundException {

        Map<String,Model> unionModelMap=new HashMap<>();
        Model modelUnion = ModelFactory.createDefaultModel();
        Model modelUnionWithoutHeader = ModelFactory.createDefaultModel();
        Map<String, String> prefixMap = modelUnion.getNsPrefixMap();
        Map<String, String> prefixMapWithoutHeader = modelUnionWithoutHeader.getNsPrefixMap();

        List<InputStream> inputStreamList = null;
        InputStream inputStream = null;
        boolean singlezip = false;

        for (File file : files) {
            String extension = FilenameUtils.getExtension(file.toString());
            if (extension.equals("zip")){
                inputStreamList=InstanceDataFactory.unzip(file);
                rdfSourceFormat = Lang.RDFXML;
                if (inputStreamList.size()==1){
                    singlezip=true;
                    inputStream=inputStreamList.get(0);
                }else{
                    singlezip=false;
                }
            }else {
                switch (extension) {
                    case "rdf":
                    case "xml":
                        rdfSourceFormat = Lang.RDFXML;
                        break;
                    case "ttl":
                        rdfSourceFormat = Lang.TURTLE;
                        break;
                    case "jsonld":
                        rdfSourceFormat = Lang.JSONLD;
                        break;
                }
                inputStream = new FileInputStream(file.toString());
                inputStreamList = new LinkedList<>();
                inputStreamList.add(inputStream);
                singlezip=false;
            }

            if (singlezip){
                Model model = ModelFactory.createDefaultModel();
                RDFDataMgr.read(model, inputStream, xmlBase, rdfSourceFormat);
                prefixMap.putAll(model.getNsPrefixMap());
                prefixMapWithoutHeader.putAll(model.getNsPrefixMap());

                //get profile short name for CGMES v2.4, keyword for CGMES v3
                String keyword=getProfileKeyword(model);
                if (FilenameUtils.getName(file.toString()).equals("FileHeader.rdf")){
                    keyword="FH";
                }
                if (!keyword.equals("")) {
                    unionModelMap.put(keyword, model);
                }else{
                    unionModelMap.put(FilenameUtils.getName(file.toString()), model);
                }

                if (!keyword.equals("FH")) {
                    modelUnionWithoutHeader.add(model);
                }
                modelUnion.add(model);
            }else{
                for (InputStream inputStreamItem : inputStreamList) {
                    Model model = ModelFactory.createDefaultModel();
                    RDFDataMgr.read(model, inputStreamItem, xmlBase, rdfSourceFormat);
                    prefixMap.putAll(model.getNsPrefixMap());
                    prefixMapWithoutHeader.putAll(model.getNsPrefixMap());

                    if (!extension.equals("rdf")) {
                        String filename = FilenameUtils.getName(file.toString());
                        if (filename.contains("_EQ") && !filename.contains("_EQBD") && !filename.contains("_EQ_BD")) {
                            ModelManipulationFactory.nameMap.put("EQ", filename);
                        } else if (filename.contains("_SSH")) {
                            ModelManipulationFactory.nameMap.put("SSH", filename);
                        } else if (filename.contains("_SV")) {
                            ModelManipulationFactory.nameMap.put("SV", filename);
                        } else if (filename.contains("_TP") && !filename.contains("_TPBD") && !filename.contains("_TP_BD")) {
                            ModelManipulationFactory.nameMap.put("TP", filename);
                        }
                    }

                    //get profile short name for CGMES v2.4, keyword for CGMES v3
                    String keyword=getProfileKeyword(model);
                    if (FilenameUtils.getName(file.toString()).equals("FileHeader.rdf")){
                        keyword="FH";
                    }
                    if (!keyword.equals("")) {
                        unionModelMap.put(keyword, model);
                    }else{
                        unionModelMap.put(FilenameUtils.getName(file.toString()), model);
                    }

                    if (!keyword.equals("FH")) {
                        modelUnionWithoutHeader.add(model);
                    }
                    modelUnion.add(model);
                }
            }
        }
        modelUnion.setNsPrefixes(prefixMap);
        modelUnionWithoutHeader.setNsPrefixes(prefixMap);


        unionModelMap.put("unionModel",modelUnion);
        unionModelMap.put("modelUnionWithoutHeader",modelUnionWithoutHeader);
        return unionModelMap;

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
                switch (nodeString) {
                    case "http://entsoe.eu/CIM/EquipmentCore/3/1":
                    case "http://entsoe.eu/CIM/EquipmentOperation/3/1":
                    case "http://entsoe.eu/CIM/EquipmentShortCircuit/3/1":
                    case "http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0":
                        keyword = "EQ";
                        break;
                    case "http://entsoe.eu/CIM/SteadyStateHypothesis/1/1":
                    case "http://iec.ch/TC57/ns/CIM/SteadyStateHypothesis-EU/3.0":
                        keyword = "SSH";
                        break;
                    case "http://entsoe.eu/CIM/Topology/4/1":
                    case "http://iec.ch/TC57/ns/CIM/Topology-EU/3.0":
                        keyword = "TP";
                        break;
                    case "http://entsoe.eu/CIM/StateVariables/4/1":
                    case "http://iec.ch/TC57/ns/CIM/StateVariables-EU/3.0":
                        keyword = "SV";
                        break;
                    case "http://entsoe.eu/CIM/EquipmentBoundary/3/1":
                    case "http://entsoe.eu/CIM/EquipmentBoundaryOperation/3/1":
                    case "http://iec.ch/TC57/ns/CIM/EquipmentBoundary-EU/3.0":
                        keyword = "EQBD";
                        break;
                    case "http://entsoe.eu/CIM/TopologyBoundary/3/1":
                        keyword = "TPBD";
                        break;
                    case "http://iec.ch/TC57/ns/CIM/Operation-EU/3.0":
                        keyword = "OP";
                        break;
                    case "http://iec.ch/TC57/ns/CIM/ShortCircuit-EU/3.0":
                        keyword = "SC";
                        break;
                    case "http://iec.ch/TC57/ns/CIM/DiagramLayout-EU/3.0":
                        keyword = "DL";
                        break;
                    case "http://iec.ch/TC57/ns/CIM/GeographicalLocation-EU/3.0":
                        keyword = "GL";
                        break;
                    case "http://iec.ch/TC57/ns/CIM/Dynamics-EU/1.0":
                        keyword = "DY";
                        break;
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

    //save instance data
    public static void saveInstanceData(Model instanceDataModel, Map<String,Object> saveProperties) throws IOException {
        //register custom format
        CustomRDFFormat.RegisterCustomFormatWriters();

        String filename=saveProperties.get("filename").toString().toUpperCase();
        String showXmlDeclaration=saveProperties.get("showXmlDeclaration").toString();
        String showDoctypeDeclaration=saveProperties.get("showDoctypeDeclaration").toString();
        String tab=saveProperties.get("tab").toString();
        String relativeURIs=saveProperties.get("relativeURIs").toString();
        String showXmlEncoding=saveProperties.get("showXmlEncoding").toString();
        String xmlBase=saveProperties.get("xmlBase").toString();
        RDFFormat rdfFormat=(RDFFormat) saveProperties.get("rdfFormat");
        boolean useAboutRules = (boolean) saveProperties.get("useAboutRules");   //switch to trigger file chooser and adding the property
        boolean useEnumRules = (boolean) saveProperties.get("useEnumRules");   //switch to trigger special treatment when Enum is referenced
        boolean useFileDialog=(boolean) saveProperties.get("useFileDialog");
        String instanceData = saveProperties.get("instanceData").toString();
        String fileFolder=saveProperties.get("fileFolder").toString();
        boolean dozip=(boolean) saveProperties.get("dozip");
        String showXmlBaseDeclaration = saveProperties.get("showXmlBaseDeclaration").toString();
        String sortRDF = saveProperties.get("sortRDF").toString();
        String sortRDFprefix = saveProperties.get("sortRDFprefix").toString();

        if (!dozip) {
            if (filename.endsWith(".ZIP")){
                filename = filename.replace(".ZIP", ".xml");
            }else {
                filename = filename.replace(".XML", ".xml"); //TODO make this more intelligent and bring it to GUI
            }
        }

        //Set<Resource> rdfAboutList = null;
        //Set<Resource> rdfEnumList = null;
        Set<Resource> rdfAboutList = (Set<Resource>) saveProperties.get("rdfAboutList");
        Set<Resource> rdfEnumList = (Set<Resource>) saveProperties.get("rdfEnumList");
        boolean putHeaderOnTop = (boolean) saveProperties.get("putHeaderOnTop");
        String headerClassResource=saveProperties.get("headerClassResource").toString();
        String extensionName=saveProperties.get("extensionName").toString();
        String fileExtension=saveProperties.get("fileExtension").toString();
        String fileDialogTitle=saveProperties.get("fileDialogTitle").toString();

        //save file
        OutputStream outXML;

        if(useFileDialog) {
            outXML = fileSaveDialog(fileDialogTitle, filename, extensionName, fileExtension);
        }else{
            outXML = new FileOutputStream(fileFolder+"\\"+filename);

        }
        if (outXML!=null) {
            try {
                if (rdfFormat == CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY || rdfFormat == CustomRDFFormat.RDFXML_CUSTOM_PLAIN) {
                    Map<String, Object> properties = new HashMap<>();
                    properties.put("showXmlDeclaration", showXmlDeclaration);
                    properties.put("showDoctypeDeclaration", showDoctypeDeclaration);
                    properties.put("showXmlEncoding", showXmlEncoding); // works only with the custom format
                    //properties.put("blockRules", "daml:collection,parseTypeLiteralPropertyElt,"
                    //        +"parseTypeResourcePropertyElt,parseTypeCollectionPropertyElt"
                    //        +"sectionReification,sectionListExpand,idAttr,propertyAttr"); //???? not sure
                    if (putHeaderOnTop) {
                        properties.put("prettyTypes", new Resource[]{ResourceFactory.createResource(headerClassResource)});
                    }
                    properties.put("xmlbase", xmlBase);
                    properties.put("tab", tab);
                    properties.put("relativeURIs", relativeURIs);
                    properties.put("instanceData", instanceData);
                    properties.put("showXmlBaseDeclaration", showXmlBaseDeclaration);
                    properties.put("sortRDF", sortRDF);
                    properties.put("sortRDFprefix", sortRDFprefix);

                    if (useAboutRules) {
                        properties.put("aboutRules", rdfAboutList);
                    }

                    if (useEnumRules) {
                        properties.put("enumRules", rdfEnumList);
                    }


                    // Put a properties object into the Context.
                    Context cxt = new Context();
                    cxt.set(SysRIOT.sysRdfWriterProperties, properties);


                    org.apache.jena.riot.RDFWriter.create()
                            .base(xmlBase)
                            .format(rdfFormat)
                            .context(cxt)
                            .source(instanceDataModel)
                            .output(outXML);

                } else {
                    instanceDataModel.write(outXML, rdfFormat.getLang().getLabel().toUpperCase(), xmlBase);
                }
            } finally {
                outXML.flush();
                outXML.close();


            }
            if (dozip) {
                String sourceFile = fileFolder + "\\" + filename;
                FileOutputStream fos = new FileOutputStream(fileFolder + "\\" + filename.replace(".xml", ".zip"));
                ZipOutputStream zipOut = new ZipOutputStream(fos);
                File fileToZip = new File(sourceFile);
                FileInputStream fis = new FileInputStream(fileToZip);
                ZipEntry zipEntry = new ZipEntry(fileToZip.getName());
                //zipEntry.setMethod(ZipEntry.STORED); // no compression, deflated - with
                zipOut.putNextEntry(zipEntry);
                byte[] bytes = new byte[1024];
                int length;
                while ((length = fis.read(bytes)) >= 0) {
                    zipOut.write(bytes, 0, length);
                }
                zipOut.close();
                fis.close();
                fos.close();
                //delete the xml
                File xmlTodelete = new File(fileFolder + "\\" + filename);
                xmlTodelete.delete();
            }
        }


    }

    //File save dialog
    private static OutputStream fileSaveDialog(String title, String filename, String extensionName, String extension) throws FileNotFoundException {
        File saveFile;
        FileChooser filechooserS = new FileChooser();
        filechooserS.getExtensionFilters().addAll(new FileChooser.ExtensionFilter(extensionName, extension));
        filechooserS.setInitialFileName(filename);
        filechooserS.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder","")));
        filechooserS.setTitle(title);
        saveFile = filechooserS.showSaveDialog(null);
        OutputStream out=null;
        if (saveFile!=null) {
            MainController.prefs.put("LastWorkingFolder", saveFile.getParent());
            out = new FileOutputStream(saveFile);
        }
        return out;
    }

    public static List<InputStream> unzip(File selectedFile) {
        List<InputStream> inputstreamlist = new LinkedList<>();
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
                        inputstreamlist.add(inputStream);
                        if (entry.getName().contains("_EQ") && !entry.getName().contains("_EQBD") && !entry.getName().contains("_EQ_BD")){
                            ModelManipulationFactory.nameMap.put("EQ", entry.getName());
                        }else if (entry.getName().contains("_SSH")){
                            ModelManipulationFactory.nameMap.put("SSH", entry.getName());
                        }else if (entry.getName().contains("_SV")){
                            ModelManipulationFactory.nameMap.put("SV", entry.getName());
                        }else if (entry.getName().contains("_TP") && !entry.getName().contains("_TPBD") && !entry.getName().contains("_TP_BD")){
                            ModelManipulationFactory.nameMap.put("TP", entry.getName());
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        } catch(IOException e){
            throw new RuntimeException("Error unzipping file " + selectedFile, e);
        }
        return inputstreamlist;
    }

    private static boolean isValidDestPath(String targetDir, String destPathStr) {
        // validate the destination path of a ZipFile entry,
        // and return true or false telling if it's valid or not.

        Path destPath           = Paths.get(destPathStr);
        Path destPathNormalized = destPath.normalize(); //remove ../../ etc.

        return destPathNormalized.toString().startsWith(targetDir + File.separator);
    }

    //File(s) selection Filechooser
    public static File filesavecustom(String titleExtensionFilter , List<String> extExtensionFilter, String Dialogtitle, String filename) {

        File file = null;

        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter(titleExtensionFilter, extExtensionFilter));
        filechooser.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder", "")));
        filechooser.setTitle(Dialogtitle);
        filechooser.setInitialFileName(filename);

        try {
            file = filechooser.showSaveDialog(null);
        } catch (Exception e) {
            filechooser.setInitialDirectory(new File(String.valueOf(FileUtils.getUserDirectory())));
            file = filechooser.showSaveDialog(null);
        }

        return file;
    }

}

