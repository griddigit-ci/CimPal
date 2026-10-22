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
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TitledPane;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static eu.griddigit.cimpal.main.gui.FxTestSupport.buttons;
import static eu.griddigit.cimpal.main.gui.FxTestSupport.dialogs;
import static eu.griddigit.cimpal.main.gui.FxTestSupport.node;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Constraints Operations tab (ExcelToSHACLTab.fxml, which includes ShaclRuleTestPane.fxml). Its main
 * action under test is the SHACL rule test: one rule whose Conform model passes and whose NonConform
 * model triggers it, so the rule passes and the results workbook is written into the suite.
 */
@GuiSmoke
class ExcelToSHACLControllerSmokeTest {

    private static final String TAB = "ExcelToSHACLTab.fxml";

    private static final String SHAPES = """
            @prefix sh:  <http://www.w3.org/ns/shacl#> .
            @prefix cim: <http://iec.ch/TC57/CIM100#> .
            @prefix ex:  <http://example.org/rules#> .

            ex:ACLineSegment a sh:NodeShape ;
                sh:targetClass cim:ACLineSegment ;
                sh:property ex:ACLineSegment.name-required .

            ex:ACLineSegment.name-required a sh:PropertyShape ;
                sh:name "NameRequired" ;
                sh:path cim:IdentifiedObject.name ;
                sh:minCount 1 ;
                sh:severity sh:Violation .
            """;

    @TempDir
    Path tempDir;

    @Test
    @Tag("smoke")
    void tabLoadsAllRoutines() throws Exception {
        Loaded<ExcelToSHACLController> tab = FxTestSupport.loadTab(TAB);

        assertThat(tab.controller()).isNotNull();
        for (String routine : new String[]{"paneGenerateConstraints", "paneModifyConstraints", "paneSplitConstraints", "paneTestRules"}) {
            assertThat(node(tab.root(), routine, TitledPane.class).getContent()).as(routine).isNotNull();
        }
        assertThat(node(tab.root(), "fcbRDFSformatForExcel", ChoiceBox.class).getValue())
                .isEqualTo("RDFS (augmented) by CimSyntaxGen");
        Parent ruleTest = node(tab.root(), "paneTestRules", Parent.class);
        assertThat(node(ruleTest, "btnRunRuleTest", Button.class).isDisabled()).isFalse();
        assertThat(node(ruleTest, "cbRuleTestDatatypeMap", ChoiceBox.class).getValue()).isNotNull();
    }

    @Test
    void ruleTestPassesAndWritesWorkbook() throws Exception {
        Path shapes = Files.writeString(tempDir.resolve("rules.ttl"), SHAPES);
        Path suite = tempDir.resolve("suite");
        String namedLine = TestModels.eq().resource("ACLineSegment", "_l1")
                .literal("IdentifiedObject.name", "Line 1").toRdfXml();
        String unnamedLine = TestModels.eq().resource("ACLineSegment", "_l1").toRdfXml();
        zip(suite.resolve("NameRequired/Conform/TC1_Conform.zip"), namedLine);
        zip(suite.resolve("NameRequired/NonConform/TC1_NonConform.zip"), unnamedLine);

        Loaded<ExcelToSHACLController> tab = FxTestSupport.loadTab(TAB);
        Parent ruleTest = node(tab.root(), "paneTestRules", Parent.class);
        List<ButtonBase> browse = buttons(ruleTest, "Browse");
        dialogs().answerOpen(shapes.toFile());
        FxTestSupport.fire(browse.get(0));
        dialogs().answerDirectory(suite.toFile());
        FxTestSupport.fire(browse.get(1));

        FxTestSupport.fire(node(ruleTest, "btnRunRuleTest", Button.class));
        // The test runs on a worker thread and ends with an info, warning or error dialog.
        ShownAlert finished = dialogs().awaitAlert(alert -> alert.title().startsWith("Rule test")
                || alert.type() == Alert.AlertType.ERROR, Duration.ofSeconds(45));

        assertThat(finished.title()).as(finished.toString()).isEqualTo("Rule test passed");
        assertThat(finished.content()).contains("1 of 1 rule(s) passed");
        try (Stream<Path> files = Files.list(suite)) {
            assertThat(files.map(file -> file.getFileName().toString()))
                    .anyMatch(name -> name.startsWith("rule_test_results_") && name.endsWith(".xlsx"));
        }
    }

    /** Writes a model archive holding one RDF/XML file, as the rule test expects. */
    private static void zip(Path archive, String rdfXml) throws Exception {
        Files.createDirectories(archive.getParent());
        try (OutputStream out = Files.newOutputStream(archive); ZipOutputStream zip = new ZipOutputStream(out)) {
            ZipEntry entry = new ZipEntry("EQ.xml");
            entry.setTime(946_684_800_000L);
            zip.putNextEntry(entry);
            zip.write(rdfXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
