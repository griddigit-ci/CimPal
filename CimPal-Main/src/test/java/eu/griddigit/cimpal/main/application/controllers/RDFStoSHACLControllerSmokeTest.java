/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.main.gui.FxTestSupport;
import eu.griddigit.cimpal.main.gui.FxTestSupport.Loaded;
import eu.griddigit.cimpal.main.gui.GuiSmoke;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TreeView;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static eu.griddigit.cimpal.main.gui.FxTestSupport.node;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * RDFS to SHACL tab (RDFStoSHACLTab.fxml): loads with its option lists filled. Load and initial
 * state only; RDFS-to-SHACL generation itself is covered in Core (SHACLFromRDFTest).
 */
@GuiSmoke
class RDFStoSHACLControllerSmokeTest {

    @Test
    @Tag("smoke")
    void tabLoadsWithOptions() throws Exception {
        Loaded<RDFStoSHACLController> tab = FxTestSupport.loadTab("RDFStoSHACLTab.fxml");

        assertThat(tab.controller()).isNotNull();
        assertThat(node(tab.root(), "fselectDatatypeMapDefineConstraints", ChoiceBox.class).getItems())
                .containsExactly("No map; No save", "All profiles in one map", "Per profile");
        assertThat(node(tab.root(), "cbProfilesVersionCreateCompleteSMTab", ChoiceBox.class).getItems())
                .contains("IEC 61970-600-1&2 (CGMES 3.0.0)");
        TreeView<?> profiles = node(tab.root(), "treeViewProfileConstraints", TreeView.class);
        assertThat(profiles.getRoot() == null || profiles.getRoot().getChildren().isEmpty())
                .as("no profile loaded yet").isTrue();
    }
}
