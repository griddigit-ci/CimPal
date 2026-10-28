/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.application;

import eu.griddigit.cimpal.main.gui.FxTestSupport;
import eu.griddigit.cimpal.main.gui.GuiSmoke;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loads the whole main window, as the application does, and checks that every tab got its content.
 * MainController loads each tab's FXML itself and reports a failure in an error dialog rather than
 * an exception, so a broken tab shows up here as a recorded error alert and an empty tab.
 */
@GuiSmoke
@Tag("smoke")
class MainGuiFxmlLoadTest {

    /** fx:id to title of the tabs of CimPalGui.fxml, in the order they appear. */
    private static final Map<String, String> TABS = new LinkedHashMap<>();

    static {
        TABS.put("tabRDFVisualisation", "Visualisation");
        TABS.put("tabRDFConvert", "Convert");
        TABS.put("tabRdfsUnion", "RDFS Union");
        TABS.put("tabSPARQLQuery", "SPARQL Query");
        TABS.put("tabAiAssistant", "AI Assistant");
        TABS.put("tabRDFStoSHACL", "RDFS to SHACL");
        TABS.put("tabExcelToSHACL", "Constraints Operations");
        TABS.put("tabCreateCompleteSM1", "RDF Comparison");
        TABS.put("tabInstanceDataComparison", "Dataset Comparison");
        TABS.put("tabIdMapping", "ID Mapping");
        TABS.put("tabGenerateInstanceData", "Generate");
        TABS.put("tabTaskWizard", "Task Wizard");
        TABS.put("tabValidationByMapping", "SHACL Validation");
    }

    @Test
    void mainGuiLoadsAllTabs() throws Exception {
        List<Tab> tabs = FxTestSupport.callOnFx(() -> {
            Parent root = new FXMLLoader(getClass().getResource("/fxml/CimPalGui.fxml")).load();
            // A scene and a CSS pass create the skins, so the tab pane inside the split pane can be found.
            new Scene(root);
            root.applyCss();
            return List.copyOf(((TabPane) root.lookup("#tabPaneWorkspace")).getTabs());
        });

        assertThat(FxTestSupport.dialogs().errors()).as("tab loading errors").isEmpty();
        assertThat(tabs).extracting(Tab::getId).containsExactlyElementsOf(TABS.keySet());
        for (Tab tab : tabs) {
            assertThat(tab.getText()).as("title of " + tab.getId()).isEqualTo(TABS.get(tab.getId()));
            assertThat(tab.getContent()).as("content of tab '" + tab.getText() + "'").isNotNull();
        }
    }
}
