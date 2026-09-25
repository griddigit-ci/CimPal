/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.application.datagenerator;

import javafx.application.Platform;
import javafx.scene.control.TextArea;


public class GuiHelper {

    //Append text to output window overloaded to get the text area to output
    public static void appendTextToOutputWindow(TextArea textArea, String valueOf, Boolean nextLine) {
        // Thread safe update of the UI element
        Platform.runLater(() ->  {
            if (nextLine){
                textArea.appendText("\n");
            }
            textArea.appendText(valueOf);});
    }

}
