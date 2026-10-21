/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.core.comparators.ComparisonSHACLshapes;
import eu.griddigit.cimpal.core.testsupport.Fixtures;
import eu.griddigit.cimpal.main.gui.FxTestSupport;
import eu.griddigit.cimpal.main.gui.FxTestSupport.Loaded;
import eu.griddigit.cimpal.main.gui.GuiSmoke;
import eu.griddigit.cimpal.main.gui.RDFcomparisonResultModel;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static eu.griddigit.cimpal.main.gui.FxTestSupport.button;
import static eu.griddigit.cimpal.main.gui.FxTestSupport.buttons;
import static eu.griddigit.cimpal.main.gui.FxTestSupport.dialogs;
import static eu.griddigit.cimpal.main.gui.FxTestSupport.node;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dataset Comparison tab (InstanceDataComparisonTab.fxml): two CGMES files that differ in one
 * line's name and resistance; "Show details" opens the result window with those differences.
 */
@GuiSmoke
class InstanceDataComparisonControllerSmokeTest {

    private static final String TAB = "InstanceDataComparisonTab.fxml";

    @TempDir
    Path tempDir;

    private Map<String, String> savedPrefixes;

    @BeforeEach
    void savePrefixMap() {
        // The tab hands its prefixes to the Core comparator through this static.
        savedPrefixes = ComparisonSHACLshapes.modelsABPrefMap;
    }

    @AfterEach
    void restorePrefixMap() {
        ComparisonSHACLshapes.modelsABPrefMap = savedPrefixes;
    }

    @Test
    @Tag("smoke")
    void tabLoadsWithProfileVersions() throws Exception {
        Loaded<InstanceDataComparisonController> tab = FxTestSupport.loadTab(TAB);

        assertThat(tab.controller()).isNotNull();
        assertThat(node(tab.root(), "fcbIDformat", ChoiceBox.class).getItems()).contains("IEC 61970-600-1&2 (CGMES 3.0.0)");
        assertThat(node(tab.root(), "fcbIDmap", ChoiceBox.class).getItems()).contains("No datatypes mapping");
        assertThat(node(tab.root(), "fBTbrowseIDmap", Button.class).isDisabled()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void comparisonShowsDifferenceRows() throws Exception {
        Path datasetA = Fixtures.copy("gui-smoke", "dataset-a.xml", tempDir);
        Path datasetB = Fixtures.copy("gui-smoke", "dataset-b.xml", tempDir);
        Loaded<InstanceDataComparisonController> tab = FxTestSupport.loadTab(TAB);
        Parent root = tab.root();
        List<ButtonBase> browse = buttons(root, "Browse");

        dialogs().answerOpen(datasetA.toFile());
        FxTestSupport.fire(browse.get(0));
        dialogs().answerOpen(datasetB.toFile());
        FxTestSupport.fire(browse.get(1));
        FxTestSupport.runOnFx(() -> {
            ((ChoiceBox<String>) node(root, "fcbIDformat", ChoiceBox.class)).setValue("IEC 61970-600-1&2 (CGMES 3.0.0)");
            ((ChoiceBox<String>) node(root, "fcbIDmap", ChoiceBox.class)).setValue("No datatypes mapping");
            node(root, "fcbIDcompShowDetails", CheckBox.class).setSelected(true);
        });
        assertThat(node(root, "fPathIDmapXmlBase", TextField.class).getText()).isEqualTo("http://iec.ch/TC57/CIM100");

        // The result window is modal: its handler returns only once the window is closed.
        FxTestSupport.fireLater(button(root, "Run"));
        Stage result = FxTestSupport.awaitWindow("Comparison Instance data", Duration.ofSeconds(30));

        List<RDFcomparisonResultModel> rows = FxTestSupport.callOnFx(() -> List.copyOf(
                node((Parent) result.getScene().getRoot(), "tableViewResults", TableView.class).getItems()));
        FxTestSupport.close(result);
        assertThat(rows).as("difference rows").hasSizeGreaterThanOrEqualTo(2);
        assertThat(rows).extracting(RDFcomparisonResultModel::getValueModelB).contains("Line 1 renamed");
        assertThat(dialogs().errors()).isEmpty();
    }
}
