package eu.griddigit.cimpal.main.application.tasks;

import eu.griddigit.cimpal.main.application.services.TaskStateUpdater;
import eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.WizardContext;
import eu.griddigit.cimpal.main.application.datagenerator.DataGeneratorModel;
import eu.griddigit.cimpal.main.application.datagenerator.GuiHelper;
import eu.griddigit.cimpal.main.application.datagenerator.resources.BaseInstanceModel;
import eu.griddigit.cimpal.main.core.ModelManipulationFactory;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import javafx.scene.control.TextArea;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;

public class AddMRIDAndRDFID implements ITask {
    // The profile that describes the classes other profiles only reference. In CGMES that is
    // the equipment profile, and it is the one consulted for the mRID of an rdf:about class.
    private static final String DEFINING_PROFILE_KEYWORD = "EQ";

    private String name;
    private String pathToFXML;
    private String status;
    private String info;
    private TaskStateUpdater taskUpdater;
    private boolean saveResult;

    public AddMRIDAndRDFID() {
        this.name = "Replaces mRID with rdf:ID";
        this.pathToFXML = "/fxml/wizardPages/taskElements/replaceMRIDWithRDFID.fxml";
        this.status = "Queued";
        this.info = "0%";
        this.taskUpdater = new TaskStateUpdater();
        this.saveResult = true;
    }

    @Override
    public void execute(SelectedTask parent) throws IOException {
        System.out.println("Executing task: " + this.name);
        WizardContext wizardContext = WizardContext.getInstance();
        taskUpdater.updateState(parent,"Loading Base Data", "1%", wizardContext);
        wizardContext.getDataGeneratorModel().loadProfileAndBaseModelData();
        taskUpdater.updateState(parent, "Executing..", "11%", wizardContext);
        String xmlBase = wizardContext.getDataGeneratorModel().getRdfsProfileVersion().getBaseNamespace();

        DataGeneratorModel dataModel = wizardContext.getDataGeneratorModel();
        Map<String, BaseInstanceModel> instanceModel = dataModel.getBaseInstanceModel();
        Map<String, Model> profileDataMapAsModel = dataModel.getProfileDataMapAsModel();
        Property mridProperty = ResourceFactory.createProperty(xmlBase + "#IdentifiedObject.mRID");

        // The profile that defines the classes an instance file only references. A class that is
        // serialised with rdf:about in, say, SSH is described in EQ, so EQ is where its mRID is
        // declared - the referencing profile's own RDFS says nothing about it.
        Model definingProfile = profileDataMapAsModel.get(DEFINING_PROFILE_KEYWORD);
        if (definingProfile == null) {
            report("No " + DEFINING_PROFILE_KEYWORD + " profile among the selected RDFS files - mRID cannot be added to "
                    + "classes that are only referenced by an instance file; existing mRIDs are still aligned "
                    + "with rdf:ID.");
        }

        // rdf:about sets are per profile and the same profile keyword repeats across the input
        // files, so each one is loaded once.
        Map<String, Set<Resource>> rdfAboutByProfile = new HashMap<>();

        int iCount = 0;
        int totalSize = instanceModel.size();

        for (Map.Entry<String, BaseInstanceModel> entry : instanceModel.entrySet()) {

            Model model = entry.getValue().getBaseInstanceModel();
            // The profile keyword out of the CGMES file name - EQ, SSH, TP, SV. It is what both
            // the rdf:about serialisation rules and the loaded RDFS models are keyed by, which is
            // why it, and not the file name, is the right lookup here.
            String profileKeyword = entry.getValue().getProfile();

            iCount++;
            int progress = 11 + (int) ((double) iCount / totalSize * 79);
            taskUpdater.updateState(parent, "Processing: " + entry.getKey(), progress + "%", wizardContext);

            // replace mRID with rdf:ID
            List<Statement> removeStmtList = new LinkedList<>();
            List<Statement> addStmtList = new LinkedList<>();
            for (StmtIterator stmt = model.listStatements(null, mridProperty, (RDFNode) null); stmt.hasNext(); ) {
                Statement stmtItem = stmt.next();
                Resource subject = stmtItem.getSubject();
                Property predicate = stmtItem.getPredicate();
                removeStmtList.add(stmtItem);
                addStmtList.add(ResourceFactory.createStatement(subject, predicate, ResourceFactory.createPlainLiteral(mridFromRdfId(subject))));
            }

            // Classes this profile serialises as rdf:about, i.e. the ones it only refers to.
            Set<Resource> rdfAboutList = rdfAboutByProfile.computeIfAbsent(profileKeyword,
                    keyword -> loadRdfAbout(xmlBase, keyword));

            Model ownProfile = profileDataMapAsModel.get(profileKeyword);

            for (StmtIterator stmt = model.listStatements(null, RDF.type, (RDFNode) null); stmt.hasNext();) {
                Statement stmtItem = stmt.next();
                Resource subject = stmtItem.getSubject();
                RDFNode object = stmtItem.getObject();

                if (!object.isResource()) {
                    continue;
                }

                // A class carried over with rdf:about is described in the defining profile, the
                // rest are described here, so each is asked of the RDFS that actually holds it.
                Model profileToAsk = rdfAboutList.contains(object.asResource()) ? definingProfile : ownProfile;

                if (profileToAsk == null) {
                    continue;
                }

                if (profileToAsk.contains(mridProperty, RDFS.domain, object)
                        && !model.contains(subject, mridProperty)) {
                    addStmtList.add(ResourceFactory.createStatement(subject, mridProperty, ResourceFactory.createPlainLiteral(mridFromRdfId(subject))));
                }
            }

            model.remove(removeStmtList);
            model.add(addStmtList);
            entry.getValue().setBaseInstanceModel(model);
        }


        taskUpdater.updateState(parent,"Processing Completed, saving result", "90%", wizardContext);
        // saving the result
        wizardContext.saveModel(parent, saveResult);
        taskUpdater.updateState(parent, "Completed", "100%", wizardContext);
    }

    // The output window is only wired up once the status page is open; a task run from
    // anywhere else would otherwise fail on the message rather than on the work.
    private static void report(String message) {
        TextArea output = WizardContext.getExecutionTextArea();
        if (output != null) {
            GuiHelper.appendTextToOutputWindow(output, message, true);
        }
    }

    /**
     * The mRID that matches a resource's rdf:ID: the local name with the leading underscore of
     * the rdf:ID form removed, so {@code #_3a3b27be-...} becomes {@code 3a3b27be-...}.
     */
    private static String mridFromRdfId(Resource subject) {
        String localName = subject.getLocalName();
        return localName.startsWith("_") ? localName.substring(1) : localName;
    }

    /**
     * The classes the given profile serialises with rdf:about, from the bundled serialisation
     * rules. Returns an empty set - rather than failing the task - for a base namespace those
     * rules do not cover, which is the case for a custom "Other CIM version" profile.
     */
    private static Set<Resource> loadRdfAbout(String xmlBase, String profileKeyword) {
        try {
            return ModelManipulationFactory.LoadRDFAbout(xmlBase, profileKeyword);
        } catch (FileNotFoundException e) {
            report("No bundled rdf:about rules for base namespace " + xmlBase + " - every class in the "
                    + profileKeyword + " files is treated as described by that profile.");
            return Set.of();
        }
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

    @Override
    public String validateInputs() {
        return "";
    }

    @Override
    public boolean getSaveResult() {
        return this.saveResult;
    }
}

