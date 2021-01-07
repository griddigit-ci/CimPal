/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package application;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

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
        MainController.prefs.put("CIMnamespace", fCIMnamespace.getText());
        MainController.prefs.put("rdfNamespace", frdfnamespace.getText());
        MainController.prefs.put("cimsNamespace", fcimsnamespace.getText());
        MainController.prefs.put("IOprefix", fIOprefix.getText());
        MainController.prefs.put("IOuri", fIOuri.getText());

        MainController.prefs.put("prefixEU", fprefixEU.getText());
        MainController.prefs.put("uriEU", furiEU.getText());
        MainController.prefs.put("prefixOther", fprefixOther.getText());
        MainController.prefs.put("uriOther", furiOther.getText());

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
        MainController.prefs.put("CIMnamespace", "http://iec.ch/TC57/CIM100#");
        MainController.prefs.put("rdfNamespace", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        MainController.prefs.put("cimsNamespace", "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#");
        MainController.prefs.put("IOprefix", "ido");
        MainController.prefs.put("IOuri", "http://iec.ch/TC57/ns/CIM/IdentifiedObject/constraints/3.0#");

        MainController.prefs.put("prefixEU", "eu");
        MainController.prefs.put("uriEU", "http://iec.ch/TC57/CIM100-European#");
        MainController.prefs.put("prefixOther", "");
        MainController.prefs.put("uriOther", "");
        MainController.prefs.put("LastWorkingFolder", "C:");

    }


    //set the preferences to the GUI
    private void prefToGui(){

        fCIMnamespace.setText(MainController.prefs.get("CIMnamespace",""));
        fcimsnamespace.setText(MainController.prefs.get("cimsNamespace",""));
        frdfnamespace.setText(MainController.prefs.get("rdfNamespace",""));
        fIOprefix.setText(MainController.prefs.get("IOprefix",""));
        fIOuri.setText(MainController.prefs.get("IOuri",""));

        fprefixEU.setText(MainController.prefs.get("prefixEU",""));
        furiEU.setText(MainController.prefs.get("uriEU",""));
        fprefixOther.setText(MainController.prefs.get("prefixOther",""));
        furiOther.setText(MainController.prefs.get("uriOther",""));
    }
}
