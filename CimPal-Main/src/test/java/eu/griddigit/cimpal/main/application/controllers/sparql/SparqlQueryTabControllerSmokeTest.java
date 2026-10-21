/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.application.controllers.sparql;

import eu.griddigit.cimpal.core.testsupport.Fixtures;
import eu.griddigit.cimpal.main.gui.FxTestSupport;
import eu.griddigit.cimpal.main.gui.FxTestSupport.Loaded;
import eu.griddigit.cimpal.main.gui.GuiSmoke;
import eu.griddigit.cimpal.main.workspace.WorkspaceRdfStore;
import javafx.scene.Parent;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static eu.griddigit.cimpal.main.gui.FxTestSupport.button;
import static eu.griddigit.cimpal.main.gui.FxTestSupport.dialogs;
import static eu.griddigit.cimpal.main.gui.FxTestSupport.node;
import static org.assertj.core.api.Assertions.assertThat;

/** SPARQL Query tab (SparqlQueryTab.fxml): a SELECT over a selected model file, shown in the results window. */
@GuiSmoke
class SparqlQueryTabControllerSmokeTest {

    private static final String TAB = "SparqlQueryTab.fxml";
    /** The workspace key the tab publishes its loaded input under. */
    private static final String WORKSPACE_KEY = "sparql-selected-input";

    @TempDir
    Path tempDir;

    @AfterEach
    void releaseWorkspaceModel() {
        WorkspaceRdfStore.removeStoredArtifact(WORKSPACE_KEY);
    }

    @Test
    @Tag("smoke")
    void tabLoadsWithEmptyEditor() throws Exception {
        Loaded<SparqlQueryTabController> tab = FxTestSupport.loadTab(TAB);

        assertThat(tab.controller()).isNotNull();
        assertThat(node(tab.root(), "txtSparqlQuery", TextArea.class).getText()).isEmpty();
        assertThat(node(tab.root(), "cbRepairOutputFormat", ChoiceBox.class).getValue())
                .isEqualTo("CIM XML / RDF/XML (IEC 61970-552)");
        assertThat(node(tab.root(), "lblRepairRepository", Label.class).getText()).isEqualTo("No output repository selected");
    }

    @Test
    void selectQueryShowsResultRows() throws Exception {
        Path model = Fixtures.copy("gui-smoke", "lines.rdf", tempDir);
        Loaded<SparqlQueryTabController> tab = FxTestSupport.loadTab(TAB);
        Parent root = tab.root();

        dialogs().answerOpen(model.toFile());
        FxTestSupport.fire(button(root, "Select model files..."));
        FxTestSupport.runOnFx(() -> node(root, "txtSparqlQuery", TextArea.class).setText("""
                PREFIX cim: <http://iec.ch/TC57/CIM100#>
                SELECT ?name WHERE { ?line a cim:ACLineSegment ; cim:IdentifiedObject.name ?name } ORDER BY ?name
                """));
        // Loading runs in a worker Task; the results window opens when the query has run.
        FxTestSupport.fire(button(root, "Run query / update"));
        Stage results = FxTestSupport.awaitWindow("SPARQL Query Results", Duration.ofSeconds(30));

        @SuppressWarnings("unchecked")
        List<Map<String, String>> rows = FxTestSupport.callOnFx(() -> List.copyOf(
                node((Parent) results.getScene().getRoot(), "tableResults", TableView.class).getItems()));
        FxTestSupport.close(results);
        assertThat(rows).extracting(row -> row.get("name")).containsExactly("Line 1", "Line 2");
        assertThat(dialogs().errors()).isEmpty();
    }
}
