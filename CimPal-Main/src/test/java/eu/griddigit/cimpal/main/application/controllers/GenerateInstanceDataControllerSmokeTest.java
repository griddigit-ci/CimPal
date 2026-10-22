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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static eu.griddigit.cimpal.main.gui.FxTestSupport.node;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generate tab (GenerateInstanceDataTab.fxml): loads with the advanced template method selected.
 * Load and initial state only; template generation still lives in Main (see the extraction
 * candidates in TEST-6.md).
 */
@GuiSmoke
class GenerateInstanceDataControllerSmokeTest {

    @Test
    @Tag("smoke")
    void tabLoadsWithAdvancedTemplate() throws Exception {
        Loaded<GenerateInstanceDataController> tab = FxTestSupport.loadTab("GenerateInstanceDataTab.fxml");

        assertThat(tab.controller()).isNotNull();
        assertThat(node(tab.root(), "fcbGenMethodOptions", ChoiceBox.class).getValue()).isEqualTo("Advanced template");
        assertThat(node(tab.root(), "fcb_giVersion", ChoiceBox.class).getValue())
                .isEqualTo("IEC 61970-600-1&2 (CGMES 3.0.0)");
    }
}
