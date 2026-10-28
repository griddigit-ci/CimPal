/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.core.testsupport.Fixtures;
import eu.griddigit.cimpal.main.gui.FxTestSupport;
import eu.griddigit.cimpal.main.gui.FxTestSupport.Loaded;
import eu.griddigit.cimpal.main.gui.GuiSmoke;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static eu.griddigit.cimpal.main.gui.FxTestSupport.buttons;
import static eu.griddigit.cimpal.main.gui.FxTestSupport.dialogs;
import static eu.griddigit.cimpal.main.gui.FxTestSupport.node;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * RDF Comparison tab (RDFComparisonTab.fxml): two SHACL files compared with the universal method;
 * the differences appear as rows of the result window.
 */
@GuiSmoke
class RDFComparisonControllerSmokeTest {

    private static final String TAB = "RDFComparisonTab.fxml";
    private static final String UNIVERSAL = "Universal method inlc. SHACL Shapes";

    @TempDir
    Path tempDir;

    @Test
    @Tag("smoke")
    void tabLoadsWithRunDisabled() throws Exception {
        Loaded<RDFComparisonController> tab = FxTestSupport.loadTab(TAB);

        assertThat(tab.controller()).isNotNull();
        assertThat(node(tab.root(), "fcbRDFSformat", ChoiceBox.class).getValue()).isEqualTo(UNIVERSAL);
        assertThat(node(tab.root(), "btnRunRDFcompare", Button.class).isDisabled()).isTrue();
    }

    @Test
    void comparisonShowsDifferenceRows() throws Exception {
        Path modelA = Fixtures.copy("gui-smoke", "shapes-a.ttl", tempDir);
        Path modelB = Fixtures.copy("gui-smoke", "shapes-b.ttl", tempDir);
        Loaded<RDFComparisonController> tab = FxTestSupport.loadTab(TAB);
        List<ButtonBase> browse = buttons(tab.root(), "Browse");
        assertThat(browse).hasSize(2);

        dialogs().answerOpen(modelA.toFile());
        FxTestSupport.fire(browse.get(0));
        dialogs().answerOpen(modelB.toFile());
        FxTestSupport.fire(browse.get(1));

        Button run = node(tab.root(), "btnRunRDFcompare", Button.class);
        assertThat(run.isDisabled()).as("Run is enabled once both files are chosen").isFalse();
        // The result window is modal: its handler returns only once the window is closed.
        FxTestSupport.fireLater(run);
        Stage result = FxTestSupport.awaitWindow("Comparison RDFS profiles", Duration.ofSeconds(30));

        int rows = FxTestSupport.callOnFx(() ->
                node((Parent) result.getScene().getRoot(), "tableViewResults", TableView.class).getItems().size());
        FxTestSupport.close(result);
        assertThat(rows).as("difference rows").isGreaterThanOrEqualTo(2);
        assertThat(dialogs().errors()).isEmpty();
    }
}
