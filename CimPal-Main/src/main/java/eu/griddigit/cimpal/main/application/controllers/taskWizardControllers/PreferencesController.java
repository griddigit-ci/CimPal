/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package eu.griddigit.cimpal.main.application.controllers.taskWizardControllers;

import eu.griddigit.cimpal.main.application.controllers.taskWizardControllers.CimPalWizardController;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.net.URL;
import java.util.ResourceBundle;

public class PreferencesController implements Initializable {
    @FXML
    private Button btnOK;
    @FXML
    private TextField fCIMnamespace;
    @FXML
    private TextField fcimsnamespace;
    @FXML
    private TextField frdfnamespace;
    @FXML
    private Button btnCancel;
    @FXML
    private TextField fIOprefix;
    @FXML
    private TextField fIOuri;
    @FXML
    private TextField fprefixEU;
    @FXML
    private TextField fprefixOther;
    @FXML
    private TextField furiEU;
    @FXML
    private TextField furiOther;
    @FXML
    private TextField textWorkingDir;
    @FXML
    private TextField textOutputDir;

    public static Stage guiPrefStage;



    public PreferencesController() {

    }
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        prefToGui();
    }

    @FXML
    //action button OK
    private void actionBtnOK(ActionEvent actionEvent) {
        CimPalWizardController.prefs.put("CIMnamespace", fCIMnamespace.getText());
        CimPalWizardController.prefs.put("rdfNamespace", frdfnamespace.getText());
        CimPalWizardController.prefs.put("cimsNamespace", fcimsnamespace.getText());
        CimPalWizardController.prefs.put("IOprefix", fIOprefix.getText());
        CimPalWizardController.prefs.put("IOuri", fIOuri.getText());

        CimPalWizardController.prefs.put("prefixEU", fprefixEU.getText());
        CimPalWizardController.prefs.put("uriEU", furiEU.getText());
        CimPalWizardController.prefs.put("prefixOther", fprefixOther.getText());
        CimPalWizardController.prefs.put("uriOther", furiOther.getText());

        //close the gui
        guiPrefStage.close();
    }

    @FXML
    //action button Cancel
    private void actionBtnCancel(ActionEvent actionEvent) {
        guiPrefStage.close();
    }

    @FXML
    //action button Default
    private void actionBtnDefault(ActionEvent actionEvent) {
        prefDefault();
        prefToGui();
    }


    //used for the cancel button on the preferences GUI
    public static void initData(Stage stage) {
        guiPrefStage=stage;
    }

    //set the default preferences
    public static void prefDefault(){
        CimPalWizardController.prefs.put("CIMnamespace", "http://iec.ch/TC57/CIM100#");
        CimPalWizardController.prefs.put("rdfNamespace", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        CimPalWizardController.prefs.put("cimsNamespace", "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#");
        CimPalWizardController.prefs.put("IOprefix", "ido");
        CimPalWizardController.prefs.put("IOuri", "http://iec.ch/TC57/ns/CIM/IdentifiedObject/constraints/3.0#");

        CimPalWizardController.prefs.put("prefixEU", "eu");
        CimPalWizardController.prefs.put("uriEU", "http://iec.ch/TC57/CIM100-European#");
        CimPalWizardController.prefs.put("prefixOther", "");
        CimPalWizardController.prefs.put("uriOther", "");
        CimPalWizardController.prefs.put("LastWorkingFolder", String.valueOf(FileUtils.getUserDirectory()));

        CimPalWizardController.prefs.put("DefWorkingDir" , System.getProperty("user.home") + File.separator + "Working_Directory");
        CimPalWizardController.prefs.put("DefOutputDir" , System.getProperty("user.home") + File.separator + "Output_Directory");
    }


    //set the preferences to the GUI
    private void prefToGui(){

        fCIMnamespace.setText(CimPalWizardController.prefs.get("CIMnamespace",""));
        fcimsnamespace.setText(CimPalWizardController.prefs.get("cimsNamespace",""));
        frdfnamespace.setText(CimPalWizardController.prefs.get("rdfNamespace",""));
        fIOprefix.setText(CimPalWizardController.prefs.get("IOprefix",""));
        fIOuri.setText(CimPalWizardController.prefs.get("IOuri",""));

        fprefixEU.setText(CimPalWizardController.prefs.get("prefixEU",""));
        furiEU.setText(CimPalWizardController.prefs.get("uriEU",""));
        fprefixOther.setText(CimPalWizardController.prefs.get("prefixOther",""));
        furiOther.setText(CimPalWizardController.prefs.get("uriOther",""));

        textWorkingDir.setText(CimPalWizardController.prefs.get("DefWorkingDir",""));
        textOutputDir.setText(CimPalWizardController.prefs.get("DefOutputDir",""));
    }

    public void selectDefWorkingDir(ActionEvent actionEvent) {
        DirectoryChooser folderChooser = new DirectoryChooser();
        folderChooser.setTitle("Select working directory");
        File selectFolder = folderChooser.showDialog(null);
        CimPalWizardController.prefs.put("DefWorkingDir" , selectFolder.getAbsolutePath());
        textWorkingDir.setText(CimPalWizardController.prefs.get("DefWorkingDir",""));
    }
    public void selectDefOutputDir(ActionEvent actionEvent) {
        DirectoryChooser folderChooser = new DirectoryChooser();
        folderChooser.setTitle("Select output directory");
        File selectFolder = folderChooser.showDialog(null);
        CimPalWizardController.prefs.put("DefOutputDir" , selectFolder.getAbsolutePath());
        textOutputDir.setText(CimPalWizardController.prefs.get("DefOutputDir",""));
    }
}
