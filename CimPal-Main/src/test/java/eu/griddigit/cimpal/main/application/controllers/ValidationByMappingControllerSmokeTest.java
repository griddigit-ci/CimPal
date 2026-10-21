/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.application.controllers;

import eu.griddigit.cimpal.core.testsupport.TestModels;
import eu.griddigit.cimpal.main.gui.FxTestSupport;
import eu.griddigit.cimpal.main.gui.FxTestSupport.Loaded;
import eu.griddigit.cimpal.main.gui.GuiSmoke;
import eu.griddigit.cimpal.main.gui.ScriptedDialogs.ShownAlert;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static eu.griddigit.cimpal.main.gui.FxTestSupport.dialogs;
import static eu.griddigit.cimpal.main.gui.FxTestSupport.node;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SHACL Validation tab (ValidationByMapping.fxml): the "validate selected files together" workflow
 * run on a model that breaks its one rule; the finish dialog reports the violation and the Excel
 * report is written to the chosen folder.
 */
@GuiSmoke
class ValidationByMappingControllerSmokeTest {

    private static final String TAB = "ValidationByMapping.fxml";
    private static final String COMBINED = "Validate selected files together";

    @TempDir
    Path tempDir;

    @Test
    @Tag("smoke")
    void tabLoadsWithMappingWorkflow() throws Exception {
        Loaded<ValidationByMappingController> tab = FxTestSupport.loadTab(TAB);

        assertThat(tab.controller()).isNotNull();
        @SuppressWarnings("unchecked")
        ChoiceBox<String> workflow = node(tab.root(), "cbValidationWorkflow", ChoiceBox.class);
        assertThat(workflow.getItems()).hasSize(3).contains(COMBINED);
        assertThat(workflow.getValue()).isEqualTo("Validate by mapping file");
        assertThat(node(tab.root(), "btnBrowseShaclConstraintFiles", Button.class).isDisabled())
                .as("constraint files are for the combined workflow only").isTrue();
        assertThat(node(tab.root(), "tfXmlBaseUri", TextField.class).getText()).isNotBlank();
    }

    @Test
    void combinedValidationWritesReport() throws Exception {
        Path shapes = Files.writeString(tempDir.resolve("thing-shapes.ttl"), TestModels.THING_SHAPES);
        Path data = Files.writeString(tempDir.resolve("thing-data.xml"), TestModels.VIOLATING_THING_MODEL);
        Path output = Files.createDirectories(tempDir.resolve("out"));
        Loaded<ValidationByMappingController> tab = FxTestSupport.loadTab(TAB);
        Parent root = tab.root();

        @SuppressWarnings("unchecked")
        ChoiceBox<String> workflow = node(root, "cbValidationWorkflow", ChoiceBox.class);
        FxTestSupport.runOnFx(() -> workflow.setValue(COMBINED));
        dialogs().answerOpen(shapes.toFile());
        FxTestSupport.fire(node(root, "btnBrowseShaclConstraintFiles", Button.class));
        dialogs().answerOpen(data.toFile());
        FxTestSupport.fire(node(root, "btnBrowseInstanceDataFiles", Button.class));
        dialogs().answerDirectory(output.toFile());
        FxTestSupport.fire(node(root, "btnBrowseOutputFolder", Button.class));

        FxTestSupport.fire(node(root, "btnRunValidationByMapping", Button.class));
        // The run is on a worker thread; it ends with an info (or error) dialog.
        ShownAlert finished = dialogs().awaitAlert(alert -> alert.title().startsWith("Validation")
                || alert.type() == Alert.AlertType.ERROR, Duration.ofSeconds(45));

        assertThat(finished.title()).as(finished.toString()).isEqualTo("Validation finished");
        assertThat(finished.content()).contains("1 result(s)");
        List<Path> workbooks;
        try (Stream<Path> files = Files.list(output)) {
            workbooks = files.filter(file -> file.getFileName().toString().endsWith(".xlsx")).toList();
        }
        assertThat(workbooks).as("Excel report in the output folder").hasSize(1);
        assertThat(Files.size(workbooks.getFirst())).isPositive();
        assertThat(finished.content()).contains(workbooks.getFirst().toString());
        assertThat(node(root, "btnRunValidationByMapping", Button.class).isDisabled()).isFalse();
    }
}
