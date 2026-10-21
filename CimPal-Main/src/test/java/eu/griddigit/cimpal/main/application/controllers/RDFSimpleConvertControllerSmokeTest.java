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
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextField;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static eu.griddigit.cimpal.main.gui.FxTestSupport.button;
import static eu.griddigit.cimpal.main.gui.FxTestSupport.dialogs;
import static eu.griddigit.cimpal.main.gui.FxTestSupport.node;
import static org.assertj.core.api.Assertions.assertThat;

/** Convert tab (RDFConvertTab.fxml): an RDF/XML file converted to Turtle through the tab's buttons. */
@GuiSmoke
class RDFSimpleConvertControllerSmokeTest {

    private static final String TAB = "RDFConvertTab.fxml";

    @TempDir
    Path tempDir;

    @Test
    @Tag("smoke")
    void tabLoadsWithTargetFormats() throws Exception {
        Loaded<RDFSimpleConvertController> tab = FxTestSupport.loadTab(TAB);

        assertThat(tab.controller()).isNotNull();
        assertThat(node(tab.root(), "ftargetFormatChoiceBox", ChoiceBox.class).getItems()).contains("CIMXML / IEC 61970-552 (RDF/XML)", "Jena: Turtle");
        assertThat(node(tab.root(), "fsourcePathTextField", TextField.class).getText()).isEmpty();
    }

    @Test
    void convertsRdfXmlToTurtle() throws Exception {
        Path source = Fixtures.copy("gui-smoke", "lines.rdf", tempDir);
        Path output = tempDir.resolve("lines.ttl");
        Loaded<RDFSimpleConvertController> tab = FxTestSupport.loadTab(TAB);

        dialogs().answerOpen(source.toFile());
        FxTestSupport.fire(button(tab.root(), "Browse"));
        assertThat(node(tab.root(), "fsourcePathTextField", TextField.class).getText()).isEqualTo(source.toString());

        @SuppressWarnings("unchecked")
        ChoiceBox<String> targets = node(tab.root(), "ftargetFormatChoiceBox", ChoiceBox.class);
        FxTestSupport.runOnFx(() -> targets.setValue("Jena: Turtle"));
        dialogs().answerSave(output.toFile());
        FxTestSupport.fire(button(tab.root(), "Convert"));

        assertThat(dialogs().errors()).isEmpty();
        assertThat(output).exists();
        Model expected = RDFDataMgr.loadModel(source.toUri().toString());
        Model converted = RDFDataMgr.loadModel(output.toUri().toString());
        assertThat(converted.size()).isEqualTo(4);
        assertThat(converted.isIsomorphicWith(expected)).as("converted graph equals the source graph").isTrue();
    }
}
