/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package eu.griddigit.cimpal.main.application;

import eu.griddigit.cimpal.main.gui.ThemeManager;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.apache.commons.io.FileUtils;

import java.net.URL;
import java.util.ResourceBundle;

public class PreferencesController implements Initializable {
    @FXML
    private TextField fCIMnamespace;
    @FXML
    private TextField fcimsnamespace;
    @FXML
    private TextField frdfnamespace;
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
    private ToggleGroup themeToggleGroup;
    @FXML
    private VBox themeRadioContainer;

    /** The theme that was active when the dialog opened, restored if the user cancels. */
    private ThemeManager.Theme themeOnOpen;
    /** Suppresses the toggle listener while the radio buttons are set programmatically. */
    private boolean syncingThemeSelection;

    public static Stage guiPrefStage;



    public PreferencesController() {

    }
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        buildThemeRadios();

        themeOnOpen = ThemeManager.get().getCurrent();

        themeToggleGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (syncingThemeSelection || newToggle == null) {
                return;
            }
            ThemeManager.get().apply((ThemeManager.Theme) newToggle.getUserData());
        });

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

        //keep the theme that is currently previewed
        ThemeManager.get().save();

        //close the eu.griddigit.cimpal.gui
        guiPrefStage.close();
    }

    @FXML
    //action button Cancel
    private void actionBtnCancel(ActionEvent actionEvent) {
        //undo the live theme preview
        ThemeManager.get().apply(themeOnOpen);
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
        MainController.prefs.put("LastWorkingFolder", String.valueOf(FileUtils.getUserDirectory())); // it was "C:" before but this was causing issue for MAC
        MainController.prefs.put(ThemeManager.PREF_KEY, ThemeManager.Theme.DEFAULT.id());

    }


    //set the preferences to the GUI
    private void prefToGui(){

        //the Default button rewrites the stored theme, so re-read and apply it
        ThemeManager.Theme storedTheme = ThemeManager.Theme.fromId(
                MainController.prefs.get(ThemeManager.PREF_KEY, ThemeManager.get().getCurrent().id()));
        if (storedTheme != ThemeManager.get().getCurrent()) {
            ThemeManager.get().apply(storedTheme);
        }
        selectThemeRadio(storedTheme);

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

    //build one radio button per ThemeManager.Theme, grouped under its group() heading
    private void buildThemeRadios() {
        themeRadioContainer.getChildren().clear();
        String currentGroup = null;
        for (ThemeManager.Theme theme : ThemeManager.Theme.values()) {
            if (!theme.group().equals(currentGroup)) {
                currentGroup = theme.group();
                Label heading = new Label(currentGroup);
                heading.setStyle("-fx-font-weight: bold;");
                VBox.setMargin(heading, new Insets(currentGroup.equals(
                        ThemeManager.Theme.values()[0].group()) ? 0 : 10, 0, 2, 0));
                themeRadioContainer.getChildren().add(heading);
            }
            RadioButton radio = new RadioButton(theme.displayName());
            radio.setUserData(theme);
            radio.setToggleGroup(themeToggleGroup);
            VBox.setMargin(radio, new Insets(0, 0, 0, 12));
            themeRadioContainer.getChildren().add(radio);
        }
    }

    //tick the radio button for the given theme without re-triggering the preview listener
    private void selectThemeRadio(ThemeManager.Theme theme) {
        syncingThemeSelection = true;
        try {
            for (Toggle toggle : themeToggleGroup.getToggles()) {
                if (toggle.getUserData() == theme) {
                    themeToggleGroup.selectToggle(toggle);
                    return;
                }
            }
        } finally {
            syncingThemeSelection = false;
        }
    }
}
