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
import javafx.scene.control.ListView;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static eu.griddigit.cimpal.main.gui.FxTestSupport.node;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task Wizard tab (TaskWizardTab.fxml, which includes wizardPages/taskSelection.fxml): loads on the
 * task selection page with tasks to choose from. Load and initial state only; the wizard's tasks
 * still live in Main (see the extraction candidates in TEST-6.md).
 */
@GuiSmoke
class TaskWizardControllerSmokeTest {

    @Test
    @Tag("smoke")
    void tabLoadsOnTaskSelection() throws Exception {
        Loaded<TaskWizardController> tab = FxTestSupport.loadTab("TaskWizardTab.fxml");

        assertThat(tab.controller()).isNotNull();
        assertThat(node(tab.root(), "wizardTaskSelectionButton", Button.class).isDisabled()).isFalse();
        assertThat(node(tab.root(), "wizardTaskInputButton", Button.class).isDisabled()).isTrue();
        assertThat(node(tab.root(), "forwardWizardButton", Button.class).getText()).isEqualTo("Next");
        assertThat(node(tab.root(), "tasksForSelection", ListView.class).getItems()).isNotEmpty();
    }
}
