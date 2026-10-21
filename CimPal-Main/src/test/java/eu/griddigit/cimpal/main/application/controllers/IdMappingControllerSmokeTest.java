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
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static eu.griddigit.cimpal.main.gui.FxTestSupport.buttons;
import static eu.griddigit.cimpal.main.gui.FxTestSupport.node;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ID Mapping tab (IdMappingTab.fxml): loads with no files chosen and the default matching options.
 * Load and initial state only; the mapping itself is covered in Core and by the id-map command.
 */
@GuiSmoke
class IdMappingControllerSmokeTest {

    @Test
    @Tag("smoke")
    void tabLoadsWithDefaults() throws Exception {
        Loaded<IdMappingController> tab = FxTestSupport.loadTab("IdMappingTab.fxml");

        assertThat(tab.controller()).isNotNull();
        assertThat(node(tab.root(), "tfModel1Files", TextField.class).getText()).isEmpty();
        assertThat(node(tab.root(), "tfOutput", TextField.class).getText()).isEmpty();
        assertThat(node(tab.root(), "cbSecondName", ChoiceBox.class).getItems())
                .containsExactly("name", "shortName", "description");
        assertThat(node(tab.root(), "cbSecondName", ChoiceBox.class).getValue()).isEqualTo("name");
        assertThat(node(tab.root(), "cbUseNames", CheckBox.class).isSelected()).isTrue();
        assertThat(node(tab.root(), "tfParameterTolerance", TextField.class).getText()).isEqualTo("5");
        assertThat(node(tab.root(), "btnRun", Button.class).getText()).isEqualTo("Map IDs");
        assertThat(buttons(tab.root(), "Browse")).hasSize(5);
    }
}
