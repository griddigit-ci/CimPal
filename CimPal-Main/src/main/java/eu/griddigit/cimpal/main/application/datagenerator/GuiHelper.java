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
