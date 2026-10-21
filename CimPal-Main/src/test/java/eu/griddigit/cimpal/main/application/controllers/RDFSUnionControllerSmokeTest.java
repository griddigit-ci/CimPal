/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.main.gui.FxTestSupport;
import eu.griddigit.cimpal.main.gui.FxTestSupport.Loaded;
import eu.griddigit.cimpal.main.gui.GuiSmoke;
import javafx.scene.Node;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.RadioButton;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static eu.griddigit.cimpal.main.gui.FxTestSupport.node;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * RDFS Union tab (RDFSUnionTab.fxml): loads in simple-union mode. Load and initial state only; the
 * union logic still lives in Main (see the extraction candidates in TEST-6.md).
 */
@GuiSmoke
class RDFSUnionControllerSmokeTest {

    @Test
    @Tag("smoke")
    void tabLoadsInSimpleUnionMode() throws Exception {
        Loaded<RDFSUnionController> tab = FxTestSupport.loadTab("RDFSUnionTab.fxml");

        assertThat(tab.controller()).isNotNull();
        assertThat(node(tab.root(), "simpleUnionRadio", RadioButton.class).isSelected()).isTrue();
        assertThat(node(tab.root(), "simpleUnionInput", Node.class).isVisible()).isTrue();
        assertThat(node(tab.root(), "detailedUnionInput", Node.class).isVisible()).isFalse();
        assertThat(node(tab.root(), "targetFormatChoiceBox", ChoiceBox.class).getItems())
                .containsExactly("RDF XML (.rdf or .xml)", "RDF Turtle (.ttl)", "JSON-LD (.jsonld)");
    }
}
