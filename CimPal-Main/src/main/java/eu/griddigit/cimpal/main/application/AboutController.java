/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package eu.griddigit.cimpal.main.application;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.awt.*;
import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Date;
import java.util.ResourceBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AboutController implements Initializable {

    private static final Logger LOG = LoggerFactory.getLogger(AboutController.class);

    @FXML
    private Button btnOK;
    @FXML
    private ImageView fImage;
    @FXML
    private VBox faPane;
    @FXML
    private Hyperlink fsupportemail;
    @FXML
    private Hyperlink fwebsite;
    @FXML
    private Hyperlink fgitHub;

    public static Stage guiAboutStage;





    public AboutController() {

    }
    @Override
    public void initialize(URL location, ResourceBundle resources) {
//Creating a hyper link
        Hyperlink link = new Hyperlink();

        // setGraphic re-parents fImage out of faPane, so the link takes its place at the top.
        link.setGraphic(fImage);
        link.setOnAction(ev -> {
                    try {
                        Desktop.getDesktop().browse(new URL("https://griddigit.eu").toURI());
                    } catch (IOException | URISyntaxException e) {
                        LOG.error("Unhandled exception", e);
                    }
        });

        faPane.getChildren().add(0, link);
        fsupportemail.setOnAction(ev -> {
            try {
                Desktop.getDesktop().browse(new URL("mailto:cimpal@griddigit.eu").toURI());
            } catch (IOException | URISyntaxException e) {
                LOG.error("Unhandled exception", e);
            }
        });

        fwebsite.setOnAction(ev -> {
            try {
                Desktop.getDesktop().browse(new URL("https://cimpal.app").toURI());
            } catch (IOException | URISyntaxException e) {
                LOG.error("Unhandled exception", e);
            }
        });

        fgitHub.setOnAction(ev -> {
            try {
                Desktop.getDesktop().browse(new URL("https://github.com/griddigit/CimPal").toURI());
            } catch (IOException | URISyntaxException e) {
                LOG.error("Unhandled exception", e);
            }
        });


    }


    @FXML
    //action button Close
    private void actionBtnOK(ActionEvent actionEvent) {

        //close the eu.griddigit.cimpal.gui
        guiAboutStage.close();
    }

    @FXML
    //action button License
    private void actionBtnLicense(ActionEvent actionEvent) {
        try {
            Desktop.getDesktop().browse(new URL("https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12").toURI());
        } catch (IOException | URISyntaxException e) {
            LOG.error("Unhandled exception", e);
        }
    }

    @FXML
    //action button License
    private void actionBtnLicenseLocal(ActionEvent actionEvent) throws URISyntaxException {
        //URL res = getClass().getResource("/license/license.txt");




        File file = null;
        String resource = "/license/license.txt" ;
        URL res = getClass().getResource(resource);
        if (res.toString().startsWith("jar:")) {
            try {
                InputStream input = getClass().getResourceAsStream(resource);
                file = File.createTempFile(new Date().getTime()+"", ".txt");
                OutputStream out = new FileOutputStream(file);
                int read;
                byte[] bytes = new byte[1024];

                while ((read = input.read(bytes)) != -1) {
                    out.write(bytes, 0, read);
                }
                out.flush();
                out.close();
                input.close();
                file.deleteOnExit();

            } catch (IOException ex) {
                LOG.error("Unhandled exception", ex);
            }
        } else {

            file = Paths.get(res.toURI()).toFile();

        }

        try {
            Desktop.getDesktop().edit(file);
        } catch (IOException e) {
            LOG.error("Unhandled exception", e);
        }







    }

    //used for the cancel button on the preferences GUI
    public static void initData(Stage stage) {
        guiAboutStage=stage;
    }



}
