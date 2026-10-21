/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.main.gui.FxTestSupport;
import eu.griddigit.cimpal.main.gui.FxTestSupport.Loaded;
import eu.griddigit.cimpal.main.gui.GuiSmoke;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TreeView;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static eu.griddigit.cimpal.main.gui.FxTestSupport.node;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Visualisation tab (RDFVisualisationTab.fxml): loads with nothing to show. Load and initial state
 * only; its logic still lives in Main (see the extraction candidates in TEST-6.md).
 */
@GuiSmoke
class RDFVisualisationControllerSmokeTest {

    @Test
    @Tag("smoke")
    void tabLoadsEmpty() throws Exception {
        Loaded<RDFVisualisationController> tab = FxTestSupport.loadTab("RDFVisualisationTab.fxml");
        // initialize() defers building the grid view with runLater; let it run before looking.
        FxTestSupport.runOnFx(() -> { });

        assertThat(tab.controller()).isNotNull();
        assertThat(((Enum<?>) node(tab.root(), "cbGrouping", ComboBox.class).getValue()).name()).isEqualTo("FLAT");
        assertThat(((Enum<?>) node(tab.root(), "cbBrowserMode", ComboBox.class).getValue()).name()).isEqualTo("RDF");
        assertThat(node(tab.root(), "cbAutoRefresh", CheckBox.class).isSelected()).isTrue();
        assertThat(node(tab.root(), "tvGraph", TreeView.class).getRoot().getChildren()).isEmpty();
        assertThat(node(tab.root(), "btnClearAll", Button.class).isDisabled()).as("nothing loaded to clear").isTrue();
    }
}
