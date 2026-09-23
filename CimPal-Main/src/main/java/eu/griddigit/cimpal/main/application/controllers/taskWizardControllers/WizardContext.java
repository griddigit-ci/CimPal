package eu.griddigit.cimpal.main.application.controllers.taskWizardControllers;

import eu.griddigit.cimpal.main.application.services.TaskStateUpdater;
import eu.griddigit.cimpal.main.gui.PathMemory;
import eu.griddigit.cimpal.main.application.tasks.*;
import eu.griddigit.cimpal.writer.formats.CustomRDFFormat;
import eu.griddigit.cimpal.main.application.datagenerator.DataGeneratorModel;
import eu.griddigit.cimpal.main.application.datagenerator.resources.BaseInstanceModel;
import eu.griddigit.cimpal.main.core.ModelManipulationFactory;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.SysRIOT;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.vocabulary.RDF;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.TaskInputController.getSaveInZip;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WizardContext {

    private static final String STEREOTYPE_PROPERTY = "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#stereotype";
    private static final String UML_ENUMERATION = "http://iec.ch/TC57/NonStandard/UML#enumeration";

    private static final Logger LOG = LoggerFactory.getLogger(WizardContext.class);

    // Static helper objects for singleton pattern implementation
    private static volatile WizardContext instance;
    private static Object mutex = new Object();

    // Actual Wizard Content Objects for UI Flow and Data State Management
    private HashMap<String, ITask> taskElements;
    private HashMap<Integer, String> wizardPanesMap;
    private ObservableList<SelectedTask> selectedTasks;
    private Integer currentWizardPane;
    private TableView tableForTaskStatus;
    private DataGeneratorModel dataGeneratorModel;
    private File workingDirectory;
    private File outputDirectory;
    private File lastOpenedDir;
    private static TextArea executionTextArea;
    private IController currentController;
    private ArrayList<Object> inputXLSdataCGM;

    // Serialisation rules per "<base namespace>|<profile keyword>". They are the same for every
    // file of a profile and for every task in a run, and working them out means parsing a bundled
    // Turtle file, so it is done once. clearContext() drops them with the rest of the run.
    private final Map<String, Set<Resource>> aboutRulesCache = new HashMap<>();
    private final Map<String, Set<Resource>> enumRulesCache = new HashMap<>();

    private WizardContext() {
        currentWizardPane = 0;
        selectedTasks = FXCollections.observableArrayList();
        this.initialiseTaskInputElements();
        this.initialiseWizardPanes();
        lastOpenedDir = new File(System.getProperty("user.home"));
    }

    public static WizardContext getInstance() {
        WizardContext result = instance;
        if (result == null) {
            synchronized (mutex) {
                result = instance;
                if (result == null)
                    instance = result = new WizardContext();
            }
        }

        return result;
    }

    public ObservableList<SelectedTask> getSelectedTasks() {
        return this.selectedTasks;
    }

    public void addSelectedTasks(SelectedTask selectedTask) {
        this.selectedTasks.add(selectedTask);
    }

    public void removeSelectedTask(Integer taskIndex) {
        for (int i = taskIndex + 1; i < selectedTasks.size(); i++) {
            selectedTasks.get(i).decrementIndex();
        }
    }

    private void initialiseTaskInputElements() {
        // TODO: MOVE TO TASK FACTORY!
        var regenerateRDFIDAndMRIDTask = new RegenerateRDFIDAndMRID();
        var replacesMRIDWithRdfIDTask = new AddMRIDAndRDFID();
        var deleteRequiredPropertiesTask = new DeleteRequiredProperties();
        var makeNonConformDatatypes = new MakeNonConformDatatypes();
        var rename = new Rename();
        var multiplyBaseModelNTimes = new MultiplyBaseModelNTimes();
        var multiplyAndConnect = new MultiplyAndConnect();
        var multiplyIGMFromConfigXls = new MultiplyIGMFromConfigXLS();
        var multiplyCGMFromConfigXls = new MultiplyCGMFromConfigXLS();
        var changeModelHeaderDescription = new ChangeModelHeaderDescription();
        var modifyMASOfSVHeader = new ModifyMASOfSVHeader();
        var generateInstanceDataModel = new GenerateInstanceDataModel();

        this.taskElements = new HashMap<>();
        this.taskElements.put(regenerateRDFIDAndMRIDTask.getName(), regenerateRDFIDAndMRIDTask);
        this.taskElements.put(replacesMRIDWithRdfIDTask.getName(), replacesMRIDWithRdfIDTask);
        this.taskElements.put(deleteRequiredPropertiesTask.getName(), deleteRequiredPropertiesTask);
        this.taskElements.put(makeNonConformDatatypes.getName(), makeNonConformDatatypes);
        this.taskElements.put(rename.getName(), rename);
        this.taskElements.put(multiplyBaseModelNTimes.getName(), multiplyBaseModelNTimes);
        this.taskElements.put(multiplyAndConnect.getName(), multiplyAndConnect);
        this.taskElements.put(multiplyIGMFromConfigXls.getName(), multiplyIGMFromConfigXls);
        this.taskElements.put(multiplyCGMFromConfigXls.getName(), multiplyCGMFromConfigXls);
        this.taskElements.put(changeModelHeaderDescription.getName(), changeModelHeaderDescription);
        this.taskElements.put(modifyMASOfSVHeader.getName(), modifyMASOfSVHeader);
        this.taskElements.put(generateInstanceDataModel.getName(), generateInstanceDataModel);
    }

    // Linear page index for wizard screens - at each index position is the respective page of the wizard
    private void initialiseWizardPanes() {
        wizardPanesMap = new HashMap<>();
        wizardPanesMap.put(0, "/fxml/wizardPages/taskSelection.fxml");
        wizardPanesMap.put(1, "/fxml/wizardPages/taskInput.fxml");
        wizardPanesMap.put(2, "/fxml/wizardPages/taskStatus.fxml");
    }

    // See if all UI elements required are populated by the user - don't allow forward and send a message to the user
    public String checkWizardForwardPossible() {
        var message = "";
        if ( currentWizardPane == 0) {

        }
        if ( currentWizardPane == 1) {

        }
        return "";
    }

    public void setTableForTaskStatus(TableView tableForTaskStatus){
        this.tableForTaskStatus = tableForTaskStatus;
    }

    public TableView getTableForTaskStatus() {
        return tableForTaskStatus;
    }

    public String getCurrentWizardPane() {
        return wizardPanesMap.get(currentWizardPane);
    }

    public HashMap<String, ITask> getTaskElements() {
        return this.taskElements;
    }

    public Integer getCurrentWizardPaneIndex() {
        return this.currentWizardPane;
    }

    // Update context
    public void forwardWizardPane() {
        if (this.validateInputs()){
            if (currentWizardPane < 2){
                this.currentWizardPane = this.currentWizardPane + 1;
            }
        }
    }

    private boolean validateInputs() {
        var validInputs = true;

        switch (this.currentWizardPane) {
            case 0:

        }
        return validInputs;
    }

    // Update context
    public void backWizardPane() {
        if (currentWizardPane > 0){
            this.currentWizardPane = this.currentWizardPane - 1;
        }
    }

    public DataGeneratorModel getDataGeneratorModel() {
        return dataGeneratorModel;
    }

    public void setDataGeneratorModel(DataGeneratorModel dataGeneratorModel) {
        this.dataGeneratorModel = dataGeneratorModel;
    }

    public File getWorkingDirectory() {
        return this.workingDirectory;
    }

    public void setWorkingDirectory(File workingDirectory){
        this.workingDirectory = workingDirectory;
    }


    public File getOutputDirectory() {
        return this.outputDirectory;
    }

    public void setOutputDirectory(File outputDirectory){
        this.outputDirectory = outputDirectory;
    }

    //the wizard shares one remembered folder with the rest of the application. The field
    //is still kept in step so nothing that reads it directly changes behaviour, but the
    //value handed out is validated: if the folder has been deleted, the nearest existing
    //parent is returned instead of a path that would make the dialog throw.
    public File getLastOpenedDir() {
        return PathMemory.resolveDirectory("wizard.lastDir");
    }

    public void setLastOpenedDir(File lastOpenedDir) {
        this.lastOpenedDir = lastOpenedDir;
        PathMemory.remember("wizard.lastDir", lastOpenedDir);
    }

    /**
     * Where a task writes its result, or null when it writes nothing.
     * <p>
     * The last task in the chain produces the run's result, so it goes to the output directory -
     * and only there, even when "save result" is ticked for it, because the run's result is not an
     * interim copy of anything. Any earlier task writes into its own folder under the working
     * directory, and only when that tick asks for one.
     * <p>
     * The last task used to write into an output/&lt;task name&gt; subfolder while the chain wrote
     * the same models again into the output root, so a finished run left two copies of its result
     * in two places - and the second copy was written with a different set of rdf:about rules.
     */
    private File resolveSaveDirectory(String taskName, boolean saveToWorkingDir) throws IOException {
        File directory;
        if (isLastSelectedTask(taskName)) {
            directory = outputDirectory;
        } else if (saveToWorkingDir) {
            directory = new File(workingDirectory, taskName.replaceAll("[^a-zA-Z0-9]", " "));
        } else {
            return null;
        }

        // mkdir() used to be called without looking at what it returned, so an output folder that
        // could not be created surfaced as a FileNotFoundException on the first model instead.
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create the folder to save into: " + directory);
        }
        return directory;
    }

    private boolean isLastSelectedTask(String taskName) {
        return !selectedTasks.isEmpty() && selectedTasks.getLast().getName().equals(taskName);
    }

    // Save instance model based
    public void saveModel(Map<String, Object> saveProperties, Model model ,  String fileName,String taskName, boolean saveToWorkingDir) throws IOException {
        File pathToSaveModel = resolveSaveDirectory(taskName, saveToWorkingDir);
        if (pathToSaveModel == null) {
            return;
        }


            //register custom format
        CustomRDFFormat.RegisterCustomFormatWriters();

        String showXmlDeclaration = saveProperties.get("showXmlDeclaration").toString();
        String showDoctypeDeclaration = saveProperties.get("showDoctypeDeclaration").toString();
        String tab = saveProperties.get("tab").toString();
        String relativeURIs = saveProperties.get("relativeURIs").toString();
        String showXmlEncoding = saveProperties.get("showXmlEncoding").toString();
        String xmlBase = this.dataGeneratorModel.getRdfsProfileVersion().getBaseNamespace();
        RDFFormat rdfFormat = (RDFFormat) saveProperties.get("rdfFormat");
        boolean useAboutRules = (boolean) saveProperties.get("useAboutRules");   //switch to trigger file chooser and adding the property
        boolean useEnumRules = (boolean) saveProperties.get("useEnumRules");   //switch to trigger special treatment when Enum is referenced

        String instanceData = saveProperties.get("instanceData").toString();
        String sortRDF = saveProperties.get("sortRDF").toString();
        String sortRDFprefix = saveProperties.get("sortRDFprefix").toString();
        String showXmlBaseDeclaration = saveProperties.get("showXmlBaseDeclaration").toString();
        boolean dozip = getSaveInZip();

        //Set<Resource> rdfAboutList = null;
        //Set<Resource> rdfEnumList = null;
        Set<Resource> rdfAboutList = (Set<Resource>) saveProperties.get("rdfAboutList");
        Set<Resource> rdfEnumList = (Set<Resource>) saveProperties.get("rdfEnumList");
        boolean putHeaderOnTop = (boolean) saveProperties.get("putHeaderOnTop");
        String headerClassResource=saveProperties.get("headerClassResource").toString();

        //save file
        OutputStream outXML = new FileOutputStream(pathToSaveModel.toString() + "\\" + fileName);
        ZipOutputStream outzip = null;

        if (outXML != null) {
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
                    properties.put("sortRDF",sortRDF);
                    properties.put("sortRDFprefix",sortRDFprefix);
                    properties.put("showXmlBaseDeclaration", showXmlBaseDeclaration);
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
                            .source(model)
                            .output(outXML);

                } else {
                    model.write(outXML, rdfFormat.getLang().getLabel().toUpperCase());
                }
            } finally {
                outXML.flush();
                outXML.close();


            }
            if (dozip) {
                String sourceFile = pathToSaveModel + "\\" + fileName;
                FileOutputStream fos = new FileOutputStream(pathToSaveModel + "\\" + fileName.replace(".xml", ".zip"));
                ZipOutputStream zipOut = new ZipOutputStream(fos);
                File fileToZip = new File(sourceFile);
                FileInputStream fis = new FileInputStream(fileToZip);
                ZipEntry zipEntry = new ZipEntry(fileToZip.getName());
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
                File xmlTodelete = new File(pathToSaveModel + "\\" + fileName);
                xmlTodelete.delete();
            }
        }
    }

    public void saveModel(SelectedTask selectedTask, boolean saveToWorkingDir) throws IOException {

        Map<String,BaseInstanceModel> instanceModel = dataGeneratorModel.getBaseInstanceModel();
        var saveProperties = dataGeneratorModel.getSaveProperties();
        String taskName = selectedTask.getName();
        TaskStateUpdater taskUpdater = new TaskStateUpdater();

        File pathToSaveInstanceModel = resolveSaveDirectory(taskName, saveToWorkingDir);
        if (pathToSaveInstanceModel == null) {
            return;
        }

        int iCount = 0;
        int totalSize = instanceModel.size();
        taskUpdater.updateState(selectedTask, "Saving instance model", "0%", this);

        for (Map.Entry<String, BaseInstanceModel> entry : instanceModel.entrySet()) {
            //register custom format
            CustomRDFFormat.RegisterCustomFormatWriters();

            String filename = entry.getKey();
            String showXmlDeclaration = saveProperties.get("showXmlDeclaration").toString();
            String showDoctypeDeclaration = saveProperties.get("showDoctypeDeclaration").toString();
            String tab = saveProperties.get("tab").toString();
            String relativeURIs = saveProperties.get("relativeURIs").toString();
            String showXmlEncoding = saveProperties.get("showXmlEncoding").toString();
            String xmlBase = this.dataGeneratorModel.getRdfsProfileVersion().getBaseNamespace();
            RDFFormat rdfFormat = (RDFFormat) saveProperties.get("rdfFormat");
            boolean useAboutRules = (boolean) saveProperties.get("useAboutRules");
            boolean useEnumRules = (boolean) saveProperties.get("useEnumRules");

            //boolean dozip = (boolean) saveProperties.get("dozip");
            boolean dozip = getSaveInZip();
            boolean putHeaderOnTop = (boolean) saveProperties.get("putHeaderOnTop");
            String headerClassResource=saveProperties.get("headerClassResource").toString();

            // The rdf:about and enum rules belong to the profile of the file being written, so they
            // are worked out per file. They used to be accumulated into one pair of sets before the
            // loop, and the rdf:about set was overwritten on each round rather than added to, so
            // every file was written with whichever profile the map happened to yield last.
            String profileKeyword = entry.getValue().getProfile();
            Set<Resource> rdfAboutList = new HashSet<>();
            if (useAboutRules) {
                rdfAboutList.addAll(aboutRulesFor(xmlBase, profileKeyword));
                // The model header is written with rdf:about whatever the profile says.
                rdfAboutList.add(ResourceFactory.createResource(headerClassResource));
            }
            Set<Resource> rdfEnumList = useEnumRules ? enumRulesFor(xmlBase, profileKeyword) : Set.of();

            //save file
            OutputStream outXML = new FileOutputStream(pathToSaveInstanceModel.toString() + "\\" + filename);
            String sortRDF = saveProperties.get("sortRDF").toString();
            String sortRDFprefix = saveProperties.get("sortRDFprefix").toString();
            String instanceData = saveProperties.get("instanceData").toString();
            String showXmlBaseDeclaration = saveProperties.get("showXmlBaseDeclaration").toString();

            iCount++;
            int progress = (int) ((double) iCount / totalSize * 100);
            taskUpdater.updateState(selectedTask, "Saving " + filename,  progress + "%", this);

            try {
                if (rdfFormat == CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY || rdfFormat == CustomRDFFormat.RDFXML_CUSTOM_PLAIN) {
                    Map<String, Object> properties = new HashMap<>();
                    properties.put("showXmlDeclaration", showXmlDeclaration);
                    properties.put("showDoctypeDeclaration", showDoctypeDeclaration);
                    properties.put("showXmlEncoding", showXmlEncoding); // works only with the custom format
                    if (putHeaderOnTop) {
                        properties.put("prettyTypes", new Resource[]{ResourceFactory.createResource(headerClassResource)});
                    }
                    properties.put("xmlbase", xmlBase);
                    properties.put("tab", tab);
                    properties.put("relativeURIs", relativeURIs);
                    properties.put("instanceData", instanceData);
                    properties.put("sortRDF",sortRDF);
                    properties.put("sortRDFprefix",sortRDFprefix);
                    properties.put("showXmlBaseDeclaration", showXmlBaseDeclaration);
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
                            .source(entry.getValue().getBaseInstanceModel())
                            .output(outXML);

                } else {
                    entry.getValue().getBaseInstanceModel().write(outXML, rdfFormat.getLang().getLabel().toUpperCase(), xmlBase);
                }
            } finally {
                outXML.flush();
                outXML.close();
            }
            if (dozip) {
                String sourceFile = pathToSaveInstanceModel + "\\" + filename;
                FileOutputStream fos = new FileOutputStream(pathToSaveInstanceModel + "\\" + filename.replace(".xml", ".zip"));
                ZipOutputStream zipOut = new ZipOutputStream(fos);
                File fileToZip = new File(sourceFile);
                FileInputStream fis = new FileInputStream(fileToZip);
                ZipEntry zipEntry = new ZipEntry(fileToZip.getName());
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
                boolean deleted = fileToZip.delete();
            }
        }

    }

    public void exportFileNameIDsToDefaultDir(ArrayList<Object> items, String sheetname, String initialFileName, String title, String taskName) {

        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet(sheetname);
        XSSFRow firstRow= sheet.createRow(0);

        ///set titles of columns
        firstRow.createCell(0).setCellValue("FileName");
        firstRow.createCell(1).setCellValue("FileProfile");
        firstRow.createCell(2).setCellValue("MAS");
        firstRow.createCell(3).setCellValue("FileID");

        for (int row=0; row<((LinkedList) items.get(0)).size(); row++){
            XSSFRow xssfRow= sheet.createRow(row+1);

            Object celValue = ((LinkedList) items.get(0)).get(row);
            try {
                if (celValue != null && Double.parseDouble(celValue.toString()) != 0.0) {
                    xssfRow.createCell(0).setCellValue(Double.parseDouble(celValue.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(0).setCellValue(celValue.toString());
            }

            Object celValue1 = ((LinkedList) items.get(1)).get(row);
            try {
                if (celValue1 != null && Double.parseDouble(celValue1.toString()) != 0.0) {
                    xssfRow.createCell(1).setCellValue(Double.parseDouble(celValue1.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(1).setCellValue(celValue1.toString());
            }

            Object celValue2 = ((LinkedList) items.get(2)).get(row);
            try {
                if (celValue2 != null && Double.parseDouble(celValue2.toString()) != 0.0) {
                    xssfRow.createCell(2).setCellValue(Double.parseDouble(celValue2.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(2).setCellValue(celValue2.toString());
            }

            Object celValue3 = ((LinkedList) items.get(3)).get(row);
            try {
                if (celValue3 != null && Double.parseDouble(celValue3.toString()) != 0.0) {
                    xssfRow.createCell(3).setCellValue(Double.parseDouble(celValue3.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(3).setCellValue(celValue3.toString());
            }
        }

        File pathToFile = this.workingDirectory;
        if (null != taskName) {
            File taskFolderPath = new File(this.workingDirectory.toString() + "\\" + taskName);
            taskFolderPath.mkdir();
            pathToFile = taskFolderPath;
        }
        try {
            FileOutputStream outputStream = new FileOutputStream(pathToFile.toString() + "\\" + initialFileName);
            workbook.write(outputStream);
            workbook.close();
            outputStream.flush();
            outputStream.close();
        } catch (FileNotFoundException e) {
            LOG.error("Unhandled exception", e);
        } catch (IOException e) {
            LOG.error("Unhandled exception", e);
        }
    }

    /**
     * The classes the given profile writes with rdf:about.
     * <p>
     * Read from the bundled serialisation rules by profile keyword. For a base namespace those
     * rules do not cover - a custom "Other CIM version" profile - it falls back to the classes the
     * profile RDFS marks with the Description stereotype, which is where this used to be derived
     * from and which matches the serialisation rules exactly for every bundled profile.
     */
    private Set<Resource> aboutRulesFor(String xmlBase, String profileKeyword) {
        return aboutRulesCache.computeIfAbsent(xmlBase + "|" + profileKeyword, key -> {
            try {
                return ModelManipulationFactory.LoadRDFAbout(xmlBase, profileKeyword);
            } catch (FileNotFoundException e) {
                Model profile = dataGeneratorModel.getProfileModelMap().get(profileKeyword);
                return profile == null
                        ? Set.of()
                        : profile.listSubjectsWithProperty(ResourceFactory.createProperty(STEREOTYPE_PROPERTY), "Description").toSet();
            }
        });
    }

    /**
     * The enumeration values the given profile writes as a full URI rather than a relative one.
     * Same source, and same fallback, as {@link #aboutRulesFor}.
     */
    private Set<Resource> enumRulesFor(String xmlBase, String profileKeyword) {
        return enumRulesCache.computeIfAbsent(xmlBase + "|" + profileKeyword, key -> {
            try {
                return ModelManipulationFactory.LoadRDFEnum(xmlBase, profileKeyword);
            } catch (FileNotFoundException e) {
                Model profile = dataGeneratorModel.getProfileModelMap().get(profileKeyword);
                if (profile == null) {
                    return Set.of();
                }
                Set<Resource> values = new HashSet<>();
                for (ResIterator classes = profile.listSubjectsWithProperty(
                        ResourceFactory.createProperty(STEREOTYPE_PROPERTY),
                        ResourceFactory.createProperty(UML_ENUMERATION)); classes.hasNext(); ) {
                    values.addAll(profile.listSubjectsWithProperty(RDF.type, classes.next()).toSet());
                }
                return values;
            }
        });
    }

    public void clearContext() {
        aboutRulesCache.clear();
        enumRulesCache.clear();
        selectedTasks = FXCollections.observableArrayList();
        this.initialiseTaskInputElements();
        this.initialiseWizardPanes();
        lastOpenedDir = new File(System.getProperty("user.home"));
        dataGeneratorModel = new DataGeneratorModel();
    }

    public static TextArea getExecutionTextArea() {
        return executionTextArea;
    }

    public void setExecutionTextArea(TextArea executionTextArea) {
        this.executionTextArea = executionTextArea;
    }

    public IController getCurrentController() {
        return currentController;
    }

    public void setCurrentController(IController currentController) {
        this.currentController = currentController;
    }

    public ArrayList<Object> getMasForCGM() {
        return inputXLSdataCGM;
    }

    public void setMasForCGM(ArrayList<Object> inputXLSdataCGM) {
        this.inputXLSdataCGM = inputXLSdataCGM;
    }
}
