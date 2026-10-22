/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.application.controllers.ai;

import eu.griddigit.cimpal.main.application.MainController;
import eu.griddigit.cimpal.main.gui.FxTestSupport;
import eu.griddigit.cimpal.main.gui.FxTestSupport.Loaded;
import eu.griddigit.cimpal.main.gui.GuiSmoke;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static eu.griddigit.cimpal.main.gui.FxTestSupport.node;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI Assistant tab (AiAssistantTab.fxml): loads without contacting anything. Remote knowledge
 * sources are off in the (empty, in-memory) preferences, so the tab starts no refresh, and nothing
 * here presses Send or checks the local model. Load and initial state only.
 */
@GuiSmoke
class AiAssistantTabControllerSmokeTest {

    @Test
    @Tag("smoke")
    void tabLoadsWithoutContactingAModel() throws Exception {
        assertThat(MainController.prefs.getBoolean("ai.knowledge.includeRemote", false))
                .as("remote knowledge refresh must stay off in this test").isFalse();

        Loaded<AiAssistantTabController> tab = FxTestSupport.loadTab("AiAssistantTab.fxml");

        assertThat(tab.controller()).isNotNull();
        assertThat(node(tab.root(), "lblConnection", Label.class).getText()).isEqualTo("Local model not checked");
        assertThat(node(tab.root(), "tfEndpoint", TextField.class).getText()).startsWith("http://");
        assertThat(node(tab.root(), "cbTask", ChoiceBox.class).getValue()).isEqualTo("Ask CimPal");
        assertThat(node(tab.root(), "cbResponseMode", ChoiceBox.class).getValue()).isEqualTo("Normal");
        assertThat(Thread.getAllStackTraces().keySet()).extracting(Thread::getName)
                .as("no AI worker thread started").doesNotContain("cimpal-ai");
    }
}
