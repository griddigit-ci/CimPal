/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2022, gridDigIt Kft. All rights reserved.
 * @author Radoslav Gabrovski
 */
package eu.griddigit.cimpal.main.application.controllers.taskWizardControllers;

import PssePFcompare.comparePssePF;
import application.AboutController;
import application.services.TaskExecutionService;
import datagenerator.ExportFactory;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.BorderPane;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;

import java.io.*;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;


public class CimPalWizardController implements Initializable {

    @FXML
    private AnchorPane mainWindowAnchor;
    @FXML
    private AnchorPane mainWindowWizardAnchor;
    @FXML
    private Button forwardWizardButton;
    @FXML
    private Button wizardTaskSelectionButton;
    @FXML
    private Button wizardTaskInputButton;
    @FXML
    private Button wizardTaskStatusButton;
    @FXML
    private BorderPane wizardBorderPane;
    @FXML
    private IController wizardPaneController;
    @FXML
    private TaskInputController taskInputController;

    public static Preferences prefs;

    private WizardContext wizardContext;
    private TaskExecutionService taskExecutionService;

    public CimPalWizardController() {
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        try {
            if (!Preferences.userRoot().nodeExists("CimPal")){
                prefs = Preferences.userRoot().node("CimPal");
                //set the default preferences
                PreferencesController.prefDefault();
            }else{
                prefs = Preferences.userRoot().node("CimPal");
            }
        } catch (BackingStoreException e) {
            e.printStackTrace();
        }
        wizardContext = WizardContext.getInstance();
    }

    @FXML
    public void forwardWizardPane(ActionEvent actionEvent) throws IOException {
        //FXMLLoader currentController = new FXMLLoader(getClass().getResource(wizardContext.getCurrentWizardPane()));
        //currentController.load();
        //var controller = currentController.getController();
        //var validInputs = wizardPaneController.validateInputs();

        /*
        switch (wizardContext.getCurrentWizardPaneIndex()) {
            case 0:
                validInputs = wizardPaneController.validateInputs();
                break;
            case 1:
                TaskInputController controller2 = (TaskInputController) controller;
                validInputs = controller2.validateInputs();
                break;
            default:
                validInputs = true;
        }
        */
        if (wizardContext.getCurrentController().validateInputs()) {
            wizardContext.forwardWizardPane();

            wizardBorderPane.setCenter(FXMLLoader.load(Objects.requireNonNull(getClass().getResource(wizardContext.getCurrentWizardPane()))));

            this.updateWizardProgressButtons();
        }

    }

    @FXML
    public void backWizardPane(ActionEvent actionEvent) throws IOException {
        wizardContext.backWizardPane();
        wizardBorderPane.setCenter(FXMLLoader.load(Objects.requireNonNull(getClass().getResource(wizardContext.getCurrentWizardPane()))));
        this.updateWizardProgressButtons();
    }

    private void updateWizardProgressButtons() {
        switch (wizardContext.getCurrentWizardPaneIndex()) {
            case 0:
                wizardTaskSelectionButton.setDefaultButton(true);
                wizardTaskSelectionButton.setDisable(false);
                wizardTaskInputButton.setDefaultButton(false);
                wizardTaskInputButton.setDisable(true);
                wizardTaskStatusButton.setDefaultButton(false);
                wizardTaskStatusButton.setDisable(true);
                forwardWizardButton.setText("Next");
                forwardWizardButton.setDisable(false);
                break;
            case 1:
                wizardTaskSelectionButton.setDefaultButton(false);
                wizardTaskSelectionButton.setDisable(true);
                wizardTaskInputButton.setDefaultButton(true);
                wizardTaskInputButton.setDisable(false);
                wizardTaskStatusButton.setDefaultButton(false);
                wizardTaskStatusButton.setDisable(true);
                forwardWizardButton.setText("Execute");
                forwardWizardButton.setDisable(false);
                break;
            case 2:
                wizardTaskSelectionButton.setDefaultButton(false);
                wizardTaskSelectionButton.setDisable(true);
                wizardTaskInputButton.setDefaultButton(false);
                wizardTaskInputButton.setDisable(true);
                wizardTaskStatusButton.setDefaultButton(true);
                wizardTaskStatusButton.setDisable(false);
                forwardWizardButton.setDisable(true);
                break;
        }
    }

    @FXML
    //Action for menu item "Quit"
    private void menuQuit(ActionEvent actionEvent) {
        Platform.exit(); // Exit the application
    }

    @FXML
    // action on menu Preferences
    private void actionMenuPreferences(ActionEvent actionEvent) {
        try {
            Stage guiPrefStage = new Stage();
            //Scene for the menu Preferences
           //FXMLLoader fxmlLoader=new FXMLLoader();
            Parent rootPreferences = FXMLLoader.load(Objects.requireNonNull(getClass().getResource("/fxml/preferencesGui.fxml")));
            Scene preferences = new Scene(rootPreferences);
            guiPrefStage.setScene(preferences);
            guiPrefStage.setTitle("Preferences");
            guiPrefStage.initModality(Modality.APPLICATION_MODAL);
            //PreferencesController PreferencesController=fxmlLoader.getController();
            PreferencesController.initData(guiPrefStage);
            guiPrefStage.showAndWait();

        }catch (IOException e) {
            e.printStackTrace();
        }
    }

    @FXML
    // action on menu PSSE-PowerFactory compare
    private void actionMenuPSSEPF() throws FileNotFoundException {
        comparePssePF.comparePssePFresults();
    }

    @FXML
    // action on menu QoCDC 3.2.1 xml to Excel
    private void actionMenuQoCDCxls() throws FileNotFoundException {
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Select QoCDC xml", "*.xml"));
        filechooser.setInitialDirectory(new File(CimPalWizardController.prefs.get("LastWorkingFolder","")));
        File file = filechooser.showOpenDialog(null);

        if (file!=null) {// the file is selected
            CimPalWizardController.prefs.put("LastWorkingFolder", file.getParent());

            Model model = ModelFactory.createDefaultModel();
            InputStream inputStream = new FileInputStream(file.toString());
            RDFDataMgr.read(model, inputStream, "http://entsoe.eu/CIM/Extensions/CGM-BP/2020", Lang.RDFXML);

            List<String> ruleName = new LinkedList<>();
            List<String> ruleSeverity = new LinkedList<>();
            List<String> ruleLevel = new LinkedList<>();
            List<String> ruleDescription = new LinkedList<>();
            List<String> ruleMessage = new LinkedList<>();

            Property propName = ResourceFactory.createProperty("http://entsoe.eu/CIM/Extensions/CGM-BP/2020","#UMLRestrictionRule.name");
            Property propSeverity = ResourceFactory.createProperty("http://entsoe.eu/CIM/Extensions/CGM-BP/2020","#UMLRestrictionRule.severity");
            Property propDescription = ResourceFactory.createProperty("http://entsoe.eu/CIM/Extensions/CGM-BP/2020","#UMLRestrictionRule.description");
            Property propLevel = ResourceFactory.createProperty("http://entsoe.eu/CIM/Extensions/CGM-BP/2020","#UMLRestrictionRule.level");
            Property propMessage = ResourceFactory.createProperty("http://entsoe.eu/CIM/Extensions/CGM-BP/2020","#UMLRestrictionRule.message");

            List<Statement> ruleStmts = model.listStatements(null, RDF.type, ResourceFactory.createProperty("http://entsoe.eu/CIM/Extensions/CGM-BP/2020", "#UMLRestrictionRule")).toList();
            for (Statement ruleStmt : ruleStmts) {
                if (model.contains(ruleStmt.getSubject(),propName)){
                    ruleName.add(model.getRequiredProperty(ruleStmt.getSubject(),propName).getObject().toString());
                }else{
                    ruleName.add("NA");
                }

                if (model.contains(ruleStmt.getSubject(),propSeverity)){
                    ruleSeverity.add(model.getRequiredProperty(ruleStmt.getSubject(),propSeverity).getObject().toString());
                }else{
                    ruleSeverity.add("NA");
                }

                if (model.contains(ruleStmt.getSubject(),propLevel)){
                    ruleLevel.add(model.getRequiredProperty(ruleStmt.getSubject(),propLevel).getObject().toString());
                }else{
                    ruleLevel.add("NA");
                }

                if (model.contains(ruleStmt.getSubject(),propDescription)){
                    ruleDescription.add(model.getRequiredProperty(ruleStmt.getSubject(),propDescription).getObject().toString());
                }else{
                    ruleDescription.add("NA");
                }

                if (model.contains(ruleStmt.getSubject(),propMessage)){
                    ruleMessage.add(model.getRequiredProperty(ruleStmt.getSubject(),propMessage).getObject().toString());
                }else{
                    ruleMessage.add("NA");
                }


            }
            ExportFactory.exportQoCDC(ruleName,ruleSeverity,ruleDescription,ruleMessage,ruleLevel,"QoCDC321","QoCDC321","Save QoCDC");


        }
    }

    @FXML
    // action on menu About
    private void actionMenuAbout() {
        try {
            Stage guiAboutStage = new Stage();
            //Scene for the menu Preferences
            //FXMLLoader fxmlLoader=new FXMLLoader();
            Parent rootAbout = FXMLLoader.load(Objects.requireNonNull(getClass().getResource("/fxml/aboutGui.fxml")));
            Scene about = new Scene(rootAbout);
            guiAboutStage.setScene(about);
            guiAboutStage.setTitle("About");
            guiAboutStage.initModality(Modality.APPLICATION_MODAL);
            AboutController.initData(guiAboutStage);
            guiAboutStage.showAndWait();

        }catch (IOException e) {
            e.printStackTrace();
        }
    }
}



