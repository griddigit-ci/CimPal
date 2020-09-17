/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package gui;

import application.MainController;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.util.Pair;
import org.apache.jena.rdf.model.Model;

import java.util.ArrayList;
import java.util.HashMap;

public class GUIhelper {

    public static Alert expandableAlert(String title, String header, String contextText, String labelText, String detailsText){
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(contextText);

        //Exception ex = new FileNotFoundException(exception);

        // Create expandable Exception.
        //StringWriter sw = new StringWriter();
        //PrintWriter pw = new PrintWriter(sw);
        //ex.printStackTrace(pw);
        //String exceptionText = sw.toString();

        Label label = new Label(labelText);

        TextArea textArea = new TextArea(detailsText);
        textArea.setEditable(false);
        textArea.setWrapText(true);

        textArea.setMaxWidth(Double.MAX_VALUE);
        textArea.setMaxHeight(Double.MAX_VALUE);
        GridPane.setVgrow(textArea, Priority.ALWAYS);
        GridPane.setHgrow(textArea, Priority.ALWAYS);

        GridPane expContent = new GridPane();
        expContent.setMaxWidth(Double.MAX_VALUE);
        expContent.add(label, 0, 0);
        expContent.add(textArea, 0, 1);

        // Set expandable Exception into the dialog pane.
        alert.getDialogPane().setExpandableContent(expContent);

        return alert;
    }
    public static Dialog<HashMap<String, String>> choiceDialog2Choices(String title, String header, String choiceLabelText1, String nameComboBox1, String[] choiceText1, String choiceLabelText2, String nameComboBox2, String[] choiceText2, String choiceLabelText3, String nameComboBox3, String[] choiceText3, String resultTextField, String nameLabelText){
        Dialog<HashMap<String, String>> dialog = new Dialog<>();
        Label img = new Label();
        img.getStyleClass().addAll("alert", "confirmation", "dialog-pane");
        dialog.setGraphic(img);
        dialog.setTitle(title);
        dialog.setHeaderText(header);

        // Set the button types.
        ButtonType btnConfirm = new ButtonType("Confirm", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(btnConfirm, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));

        ComboBox choice1 = new ComboBox();
        choice1.getItems().addAll(choiceText1);
        choice1.getSelectionModel().selectLast();


        ComboBox choice2 = new ComboBox();
        choice2.getItems().addAll(choiceText2);
        choice2.getSelectionModel().selectFirst();

        ComboBox choice3 = new ComboBox();
        choice3.getItems().addAll(choiceText3);
        choice3.getSelectionModel().selectFirst();

        TextField textModelName = new TextField();
        textModelName.setDisable(true); // because of choice1.getSelectionModel().selectLast()

        //the listener for the combobox 3 Select task
        ChangeListener<String> changeListener = (observable, oldValue, newValue) -> {
            if (newValue.equals("A zip file (with many XML) representing an assembled model")) { //newValue != null maybe useful in case of any change to do something
                textModelName.setDisable(true);
                textModelName.setText("");
                choice3.getSelectionModel().select(0);
                choice3.setDisable(true);
            }else if (newValue.equals("Collection of non assembled files or single file")){
                textModelName.setDisable(true);
                textModelName.setText("");
                choice3.setDisable(false);
                choice3.getSelectionModel().select(0);
            }else if (newValue.equals("XML files representing an assembled model")){
                textModelName.setDisable(false);
                textModelName.setText("My assembled model");
                choice3.getSelectionModel().select(1);
                choice3.setDisable(true);
            }else if (newValue.equals("Zip files (1 XML per Zip file) representing an assembled model")){
                textModelName.setDisable(false);
                textModelName.setText("My assembled model");
                choice3.getSelectionModel().select(0);
                choice3.setDisable(true);
            }
        };
        // Selected Item Changed for the combo box.
        choice1.getSelectionModel().selectedItemProperty().addListener(changeListener);


        grid.add(new Label(choiceLabelText1), 0, 0);
        grid.add(choice1, 1, 0);
        grid.add(new Label(nameLabelText), 0, 1);
        grid.add(textModelName, 1, 1);
        grid.add(new Label(choiceLabelText2), 0, 2);
        grid.add(choice2, 1, 2);
        grid.add(new Label(choiceLabelText3), 0, 3);
        grid.add(choice3, 1, 3);


        dialog.getDialogPane().setContent(grid);

        // Convert the result to the desired data structure
        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == btnConfirm) {
                HashMap<String, String> result = new HashMap<>();
                result.put(nameComboBox1, choice1.getValue().toString());
                result.put(nameComboBox2, choice2.getValue().toString());
                result.put(nameComboBox3, choice3.getValue().toString());
                result.put(resultTextField, textModelName.getText());
                return result;
            }
            return null;
        });

        return dialog;
    }



    public static Pair<Integer,Model> getShapeModel(String shapeModelName){
        Model shapeModel = null;
        int index=0;
        for (int i = 0; i < MainController.shapeModelsNames.size(); i++) {
            if (((ArrayList) MainController.shapeModelsNames.get(i)).get(0).equals(shapeModelName)) {
                shapeModel = (Model) MainController.shapeModels.get(i);
                index=i;
                break;
            }
        }

        Pair<Integer,Model> result = new Pair(index, shapeModel);
        return result;
    }
}
