package eu.griddigit.cimpal.main.application.tasks;

import application.services.TaskStateUpdater;
import application.taskControllers.WizardContext;
import common.customWriter.CustomRDFFormat;
import datagenerator.DataGeneratorModel;
import datagenerator.resources.BaseInstanceModel;
import org.apache.commons.io.FilenameUtils;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import util.ExcelTools;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

public class GenerateInstanceDataModel implements ITask {
    private String name;
    private String pathToFXML;
    private String status;
    private String info;
    private TaskStateUpdater taskUpdater;
    private String templateDataFilePath;
    private String formatGeneratedModel;
    private boolean saveResult;

    public GenerateInstanceDataModel() {
        this.name = "Generate Instance Data Model";
        this.pathToFXML = "/fxml/wizardPages/taskElements/generateInstanceDataModel.fxml";
        this.status = "Queued";
        this.info = "0%";
        this.taskUpdater = new TaskStateUpdater();
        this.saveResult = true;
    }
    @Override
    public void execute(SelectedTask parent) throws IOException {

        WizardContext wizardContext = WizardContext.getInstance();
        taskUpdater.updateState(parent,"Loading Input Data", "1%", wizardContext);

        DataGeneratorModel dataModel = wizardContext.getDataGeneratorModel();
        Map<String, Model> profileModelMap = dataModel.getProfileModelMap();
        HashMap<String, Object> saveProperties = this.getSavePreferences();

        // load input data xls
        ArrayList<Object> inputXLSdata;
        File excel = new File(templateDataFilePath);
        FileInputStream fis = new FileInputStream(excel);
        XSSFWorkbook book = new XSSFWorkbook(fis);
        int Num = book.getNumberOfSheets();

        HashMap<String, BaseInstanceModel> instanceModel = new HashMap<>();
        for (int sheetnum = 0; sheetnum < Num; sheetnum++) {
            XSSFSheet sheet = book.getSheetAt(sheetnum);
            String sheetname = sheet.getSheetName();
            saveProperties.put("filename", sheetname + ".xml");
            if (sheetnum == 0){
                saveProperties.put("useFileDialog", true);
            }else{
                saveProperties.put("useFileDialog", false);
                saveProperties.put("fileFolder", wizardContext.getOutputDirectory());
            }
            inputXLSdata = ExcelTools.importXLSX(templateDataFilePath, sheetnum);


            Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
            model.setNsPrefix("eu", "http://iec.ch/TC57/CIM100-European#");
            model.setNsPrefix("nc", "http://entsoe.eu/ns/nc#");
            //model.setNsPrefix("cims", MainController.prefs.get("cimsNamespace",""));
            model.setNsPrefix("rdf", RDF.uri);
            //model.setNsPrefix("owl", OWL.NS);
            model.setNsPrefix("cim", "http://iec.ch/TC57/CIM100#");
            //model.setNsPrefix("xsd", XSD.NS);
            model.setNsPrefix("md", "http://iec.ch/TC57/61970-552/ModelDescription/1#");
            model.setNsPrefix("dcat", "http://www.w3.org/ns/dcat#");
            model.setNsPrefix("dcterms", "http://purl.org/dc/terms/#");
            model.setNsPrefix("eumd", "http://entsoe.eu/ns/Metadata-European#");
            model.setNsPrefix("prov", "http://www.w3.org/ns/prov#");
            model.setNsPrefix("time", "http://www.w3.org/2006/time#");


            for (int row = 1; row < inputXLSdata.size(); row++) {
                String classURI = ((LinkedList<?>) inputXLSdata.get(row)).getFirst().toString();
                String classNS = ResourceFactory.createResource(classURI).asResource().getNameSpace();
                String propertyURI = ((LinkedList<?>) inputXLSdata.get(row)).get(1).toString();
                String propertyType = ((LinkedList<?>) inputXLSdata.get(row)).get(2).toString();
                String datatype = ((LinkedList<?>) inputXLSdata.get(row)).get(3).toString();
                String multiplicity = ((LinkedList<?>) inputXLSdata.get(row)).get(4).toString();
                if (((LinkedList<?>) inputXLSdata.get(row)).size() > 5) {
                    for (int col = 5; col < ((LinkedList<?>) inputXLSdata.get(row)).size(); col += 2) {
                        //System.out.println(((LinkedList) inputXLSdata.get(row)).get(col).toString());
                        //System.out.println(((LinkedList) inputXLSdata.get(row)).get(col + 1).toString());
                        String rdfid = ((LinkedList<?>) inputXLSdata.get(row)).get(col).toString();
                        String object = ((LinkedList<?>) inputXLSdata.get(row)).get(col + 1).toString();

                        //Add triples to the model

                        //Add the Class type
                        if (rdfid.startsWith("urn:uuid:")) {
                            model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), RDF.type, ResourceFactory.createProperty(classURI)));
                        } else {
                            model.add(ResourceFactory.createStatement(ResourceFactory.createResource(classNS + rdfid), RDF.type, ResourceFactory.createProperty(classURI)));
                        }


                        if (propertyType.equals("Attribute")) {//add literal
                            if (rdfid.startsWith("urn:uuid:")) {
                                model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createPlainLiteral(object)));
                            } else {
                                model.add(ResourceFactory.createStatement(ResourceFactory.createResource(classNS + rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createPlainLiteral(object)));
                            }
                        } else if (propertyType.equals("Association")) {//add resource
                            if (rdfid.startsWith("urn:uuid:")) {
                                model.add(ResourceFactory.createStatement(ResourceFactory.createResource(rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createProperty(object)));
                            } else {
                                model.add(ResourceFactory.createStatement(ResourceFactory.createResource(classNS + rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createProperty(object)));
                            }
                        } else if (propertyType.equals("Enumeration")) {//add enum
                            model.add(ResourceFactory.createStatement(ResourceFactory.createResource(classNS + rdfid), ResourceFactory.createProperty(propertyURI), ResourceFactory.createResource(object)));
                        }

                    }
                }

            }


            //save xml
            if (sheetnum==0) {
                //this is related to the save of the data
                Set<Resource> rdfAboutList = new HashSet<>();
                Set<Resource> rdfEnumList = new HashSet<>();
                String[] originalNameInParts = FilenameUtils.getName(templateDataFilePath).split("_", 2)[1].split(".xlsx", 2);


                if ((boolean) saveProperties.get("useAboutRules")) {
                    if (profileModelMap.get(originalNameInParts[0]).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), "Description").hasNext()) {
                        rdfAboutList = profileModelMap.get(originalNameInParts[0]).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"), "Description").toSet();
                    }
                    rdfAboutList.add(ResourceFactory.createResource(saveProperties.get("headerClassResource").toString()));
                }

                if ((boolean) saveProperties.get("useEnumRules")) {
                    for (ResIterator ii = profileModelMap.get(originalNameInParts[0]).listSubjectsWithProperty(ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype"),
                            ResourceFactory.createProperty("http://iec.ch/TC57/NonStandard/UML#enumeration")); ii.hasNext(); ) {
                        Resource resItem = ii.next();
                        for (ResIterator j = profileModelMap.get(originalNameInParts[0]).listSubjectsWithProperty(RDF.type, resItem); j.hasNext(); ) {
                            Resource resItemProp = j.next();
                            rdfEnumList.add(resItemProp);
                        }
                    }
                }

                if (saveProperties.containsKey("rdfAboutList")) {
                    saveProperties.replace("rdfAboutList", rdfAboutList);
                } else {
                    saveProperties.put("rdfAboutList", rdfAboutList);
                }
                if (saveProperties.containsKey("rdfEnumList")) {
                    saveProperties.replace("rdfEnumList", rdfEnumList);
                } else {
                    saveProperties.put("rdfEnumList", rdfEnumList);
                }

                //if (extension.equals("zip")) {
                //    fileName = fileName.replace(".zip", ".xml");
                // }
                // saveProperties.replace("filename", fileName);
            }

            // Add new base instance model generated in an object and add to dictionary - used for saving and further processing
            var newBaseInstanceModel = new BaseInstanceModel(sheetname);
            newBaseInstanceModel.setBaseInstanceModel(model);
            instanceModel.put(sheetname, newBaseInstanceModel);

        }

        // Set instance model for the Context of the generator and save it
        wizardContext.getDataGeneratorModel().setBaseInstanceModel(instanceModel);
        // saving the result
        wizardContext.saveModel(parent, saveResult);

        // TODO: review below code - copied from source commit
       /* Map<String,String> headerIdMap= new HashMap<>();
        // load all base instance models
        if (inputData.get("baseModel")) {
            List<File> baseInstanceModelFiles = new LinkedList<>();
            if (!instanceModelUnionFlag && MainController.instanceBaseModelFile != null) {
                baseInstanceModelFiles.add(MainController.instanceBaseModelFile);
            } else {
                baseInstanceModelFiles = MainController.instanceBaseModelList;
            }


            //select output folder
            saveProperties.replace("useFileDialog",false);
            DirectoryChooser folderchooser = new DirectoryChooser();
            folderchooser.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder","")));
            folderchooser.setTitle("Select output folder");
            File selectFolder = folderchooser.showDialog(null);
            saveProperties.replace("fileFolder",selectFolder.getPath());
            saveProperties.put("dozip", true);

            Lang rdfSourceFormat=null;
            InputStream inputStream;


            for (File file : baseInstanceModelFiles) {

                String extension = FilenameUtils.getExtension(file.toString());
                if (extension.equals("zip")){
                    inputStream=InstanceDataFactory.unzip(file);
                    rdfSourceFormat = Lang.RDFXML;
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
                }
                String originalName=FilenameUtils.getName(file.toString());
                String fileName=originalName;
                String[] originalNameInParts=originalName.split("_",5); //indexes 0=datetime; 1=process; 2=TSO; 3=Profile; 4=version

                Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
                RDFDataMgr.read(model, inputStream, xmlBase, rdfSourceFormat);

                //check if there is description
                Statement fileIDhead1 = model.listStatements(new SimpleSelector(null, RDF.type, (RDFNode) ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel"))).next();
                if (model.listStatements(new SimpleSelector(fileIDhead1.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.description"), (RDFNode) null)).hasNext()) {
                    if (!newDescription.isBlank() && !newDescription.startsWith("fixChars|")) {
                        //use the description provided by user, i.e. replace all
                        Statement  descrStmt = model.listStatements(new SimpleSelector(fileIDhead1.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.description"), (RDFNode) null)).next();
                        model.remove(descrStmt);
                        model.add(ResourceFactory.createStatement(fileIDhead1.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.description"), ResourceFactory.createPlainLiteral(newDescription)));
                    }else if (!newDescription.isBlank() || newDescription.startsWith("fixChars|")){
                        String[] stringParts = newDescription.split("\\|",3);
                        Statement  descrStmt = model.listStatements(new SimpleSelector(fileIDhead1.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.description"), (RDFNode) null)).next();
                        model.remove(descrStmt);
                        model.add(ResourceFactory.createStatement(fileIDhead1.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.description"), ResourceFactory.createPlainLiteral(descrStmt.getObject().toString().replaceAll(stringParts[1],stringParts[2]))));
                    }
                }else {
                    if (!newDescription.isBlank() && !newDescription.startsWith("fixChars|")) {
                        model.add(ResourceFactory.createStatement(fileIDhead1.getSubject(), ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.description"), ResourceFactory.createPlainLiteral(newDescription)));
                    }
                }





            }

        }*/


    }

    private HashMap<String, Object> getSavePreferences() {
        // Hardcoded generation xml Base - Not sure if used with consecutive tasks how it would look like
        String xmlBase = "http://entsoe.eu/ns/nc";
        HashMap<String, Object> saveProperties = new HashMap<>();
        if (formatGeneratedModel.equals("61970-552 CIM XML (.xml)")) {
            saveProperties.put("filename", "test");
            saveProperties.put("showXmlDeclaration", "true");
            saveProperties.put("showDoctypeDeclaration", "false");
            saveProperties.put("tab", "2");
            saveProperties.put("relativeURIs", "same-document");
            saveProperties.put("showXmlEncoding", "true");
            saveProperties.put("xmlBase", xmlBase);
            saveProperties.put("rdfFormat", CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY);
            saveProperties.put("useAboutRules", true); //switch to trigger file chooser and adding the property
            saveProperties.put("useEnumRules", true); //switch to trigger special treatment when Enum is referenced
            saveProperties.put("useFileDialog", true);
            saveProperties.put("fileFolder", "C:");
            saveProperties.put("dozip", false);

            saveProperties.put("putHeaderOnTop", true);
            saveProperties.put("headerClassResource", "http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel");
            saveProperties.put("extensionName", "RDF XML");
            saveProperties.put("fileExtension", "*.xml");
            saveProperties.put("fileDialogTitle", "Save RDF XML for");
            //RDFFormat rdfFormat=RDFFormat.RDFXML;
            //RDFFormat rdfFormat=RDFFormat.RDFXML_PLAIN;
            //RDFFormat rdfFormat = RDFFormat.RDFXML_ABBREV;
            //RDFFormat rdfFormat = CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY;
            //RDFFormat rdfFormat = CustomRDFFormat.RDFXML_CUSTOM_PLAIN;

        } else if (formatGeneratedModel.equals("Custom RDF XML Plain (.xml)")) {
            saveProperties.put("filename", "test");
            saveProperties.put("showXmlDeclaration", "true");
            saveProperties.put("showDoctypeDeclaration", "false");
            saveProperties.put("tab", "2");
            saveProperties.put("relativeURIs", "same-document");
            saveProperties.put("showXmlEncoding", "true");
            saveProperties.put("xmlBase", xmlBase);
            saveProperties.put("rdfFormat", CustomRDFFormat.RDFXML_CUSTOM_PLAIN);
            saveProperties.put("useAboutRules", true); //switch to trigger file chooser and adding the property
            saveProperties.put("useEnumRules", true); //switch to trigger special treatment when Enum is referenced
            saveProperties.put("useFileDialog", true);
            saveProperties.put("fileFolder", "C:");
            saveProperties.put("dozip", false);

            saveProperties.put("putHeaderOnTop", true);
            saveProperties.put("headerClassResource", "http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel");
            saveProperties.put("extensionName", "RDF XML");
            saveProperties.put("fileExtension", "*.xml");
            saveProperties.put("fileDialogTitle", "Save RDF XML for");
        }

        return saveProperties;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public String getPathToFXMLComponent() {
        return this.pathToFXML;
    }

    @Override
    public String getStatus() {
        return this.status;
    }

    @Override
    public String getInfo() {
        return this.info;
    }

    public void setSaveResult(boolean saveResult) {
        this.saveResult = saveResult;
    }

    public void setTemplateDataFilePath(String templateDataFilePath) {
        this.templateDataFilePath = templateDataFilePath;
    }

    public void setFormatGeneratedModel(String formatGeneratedModel) {
        this.formatGeneratedModel = formatGeneratedModel;
    }

    @Override
    public String validateInputs() {
        var message = "";
        if (templateDataFilePath == null || templateDataFilePath.isEmpty()) {
            message += "No XML template selected for task: " + name + "\n";
        }

        if (formatGeneratedModel == null || formatGeneratedModel.isEmpty() ) {
            message += "No format for generated model selected for task: " + name + "\n";
        }

        return message;
    }

    @Override
    public boolean getSaveResult() {
        return this.saveResult;
    }
}
