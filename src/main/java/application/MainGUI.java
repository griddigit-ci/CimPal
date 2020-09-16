/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package application;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.application.Preloader;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class MainGUI extends Application {

    private Stage MainAppStage;
    private static Scene mainApp;

    @Override
    public void init() throws Exception {
        super.init();
        // Scene for the Main App
        // Load root layout from fxml file.
        Parent rootMainApp = FXMLLoader.load(getClass().getResource("/fxml/CimPalGui.fxml"));
        mainApp = new Scene(rootMainApp);

    }

    @Override
    public void start(Stage primaryStage) {
        try {

            MainAppStage=primaryStage;

           //Parent root = loader.load();

            primaryStage.setTitle("gridDigIt: CimPal");

            // Show the scene containing the root layout.
            primaryStage.setScene(mainApp);
            primaryStage.setMaximized(false);

            /*// Get current screen of the stage
            ObservableList<Screen> screens = Screen.getScreensForRectangle(new Rectangle2D(primaryStage.getX(), primaryStage.getY(), primaryStage.getWidth(), primaryStage.getHeight()));

            // Change stage properties
            Rectangle2D bounds = screens.get(0).getVisualBounds();
            primaryStage.setX(bounds.getMinX());
            primaryStage.setY(bounds.getMinY());
            primaryStage.setWidth(bounds.getWidth());
            primaryStage.setHeight(bounds.getHeight());
            */


            //Scene for the menu Preferences
            //Parent rootPreferences = FXMLLoader.load(getClass().getResource("/fxml/preferencesGui.fxml"));
            //Scene preferences = new Scene(rootPreferences);

            //primaryStage.setScene(mainApp);
            primaryStage.initStyle(StageStyle.DECORATED);

            primaryStage.show();
            notifyPreloader(new Preloader.ProgressNotification(0.99));



        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void stop() throws Exception {
        super.stop();
        Platform.exit(); // Exit the application
    }

    public static void main(String[] args) {
        //Application.launch(args);
        System.setProperty("javafx.preloader", "preload.PreloadApp");
        Application.launch(MainGUI.class, args);
    }
}
