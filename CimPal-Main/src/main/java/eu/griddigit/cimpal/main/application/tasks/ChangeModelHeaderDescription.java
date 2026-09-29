/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.application.tasks;

import eu.griddigit.cimpal.main.application.services.TaskStateUpdater;
import eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.WizardContext;
import eu.griddigit.cimpal.main.application.tasks.ITask;
import eu.griddigit.cimpal.main.application.tasks.SelectedTask;
import eu.griddigit.cimpal.main.application.datagenerator.resources.BaseInstanceModel;
import eu.griddigit.cimpal.main.application.datagenerator.resources.ProfileFamily;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.RDF;

import java.io.IOException;
import java.util.Map;

public class ChangeModelHeaderDescription implements ITask {
    private String name;
    private String pathToFXML;
    private String status;
    private String info;
    private TaskStateUpdater taskUpdater;
    private String newHeaderDescription;
    private boolean saveResult;

    public ChangeModelHeaderDescription() {
        this.name = "Change Model Header Description";
        this.pathToFXML = "/fxml/wizardPages/taskElements/changeModelHeaderDescription.fxml";
        this.status = "Queued";
        this.info = "0%";
        this.taskUpdater = new TaskStateUpdater();
        // Matches the "save result" checkbox on this task's page, which starts out ticked.
        this.saveResult = true;
    }

    @Override
    public void execute(SelectedTask parent) throws IOException {

        WizardContext wizardContext = WizardContext.getInstance();
        taskUpdater.updateState(parent,"Loading Base Data", "1%", wizardContext);
        wizardContext.getDataGeneratorModel().loadProfileAndBaseModelData();
        taskUpdater.updateState(parent,"In Progress", "10%", wizardContext);

        // The header and its description property depend on the family: md:FullModel and
        // md:Model.description for CGMES, dcat:Dataset and dcterms:description for NCP.
        ProfileFamily family = wizardContext.getDataGeneratorModel().getRdfsProfileVersion().getFamily();
        Resource headerClass = ResourceFactory.createResource(family.getHeaderClass());
        Property descriptionProperty = ResourceFactory.createProperty(family.getHeaderDescriptionProperty());
        // "fixChars|<find>|<replace>" edits the existing description, anything else replaces it.
        boolean fixChars = newHeaderDescription.startsWith("fixChars|");
        boolean replaceAll = !newHeaderDescription.isBlank() && !fixChars;

        var baseInstanceModel = wizardContext.getDataGeneratorModel().getBaseInstanceModel();
        for (Map.Entry<String, BaseInstanceModel> entry : baseInstanceModel.entrySet()) {

            var model = entry.getValue().getBaseInstanceModel();
            ResIterator headers = model.listSubjectsWithProperty(RDF.type, headerClass);
            if (!headers.hasNext()) {
                throw new IOException(entry.getKey() + " has no " + model.shortForm(headerClass.getURI()) + " header to change.");
            }
            Resource header = headers.next();

            //check if there is description
            Statement descrStmt = model.getProperty(header, descriptionProperty);
            if (descrStmt != null && (replaceAll || fixChars)) {
                String newValue;
                if (replaceAll) {
                    //use the description provided by user, i.e. replace all
                    newValue = newHeaderDescription;
                } else {
                    String[] stringParts = newHeaderDescription.split("\\|",3);
                    newValue = descrStmt.getLiteral().getLexicalForm().replaceAll(stringParts[1],stringParts[2]);
                }
                model.remove(descrStmt);
                model.add(header, descriptionProperty, description(family, newValue, descrStmt.getLiteral().getLanguage()));
            }
            else if (descrStmt == null && replaceAll) {
                model.add(header, descriptionProperty, description(family, newHeaderDescription, ""));
            }

            entry.getValue().setBaseInstanceModel(model);
        }

        taskUpdater.updateState(parent,"Processing Completed, saving result", "90%", wizardContext);
        // saving the result
        wizardContext.saveModel(parent, saveResult);
        taskUpdater.updateState(parent,"Completed", "100%", wizardContext);
    }

    @Override
    public String validateInputs() {
        if (newHeaderDescription == null || newHeaderDescription.isEmpty()) {
            return "No new header description set for task: " + name + "\n";
        }
        if (newHeaderDescription.startsWith("fixChars|") && newHeaderDescription.split("\\|", 3).length < 3) {
            return "The header description for task " + name + " starts with fixChars| but is not of the form "
                    + "fixChars|<find>|<replace>\n";
        }
        return "";
    }

    // NCP's dcterms:description is an rdf:langString, so it keeps the language it had, English when
    // there was none. md:Model.description is a plain string.
    private static Literal description(ProfileFamily family, String value, String language) {
        return family == ProfileFamily.NCP
                ? ResourceFactory.createLangLiteral(value, language.isEmpty() ? "en" : language)
                : ResourceFactory.createPlainLiteral(value);
    }

    public void setNewHeaderDescription(String newHeaderDescription) {
        this.newHeaderDescription = newHeaderDescription;
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
    public boolean getSaveResult() {
        return this.saveResult;
    }

    @Override
    public boolean supportsNcp() {
        return true;
    }
}
