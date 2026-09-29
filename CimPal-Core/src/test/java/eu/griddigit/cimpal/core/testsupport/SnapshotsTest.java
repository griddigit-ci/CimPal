/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.testsupport;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotsTest {

    @TempDir
    Path tempDir;

    /** The run's own -Dsnapshot.update value, restored after each test so other classes still see it. */
    private String runUpdateMode;

    @BeforeEach
    void startInNormalMode() {
        runUpdateMode = System.clearProperty(Snapshots.UPDATE_PROPERTY);
    }

    @AfterEach
    void restoreRunUpdateMode() {
        if (runUpdateMode == null) {
            System.clearProperty(Snapshots.UPDATE_PROPERTY);
        } else {
            System.setProperty(Snapshots.UPDATE_PROPERTY, runUpdateMode);
        }
    }

    @Test
    void missingSnapshotFailsAndPointsAtUpdateFlag() {
        Snapshots snapshots = Snapshots.at(tempDir);
        assertThatThrownBy(() -> snapshots.assertTextEquals("absent.txt", "x"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("does not exist")
                .hasMessageContaining("-Dsnapshot.update=true");
        assertThat(tempDir.resolve("absent.txt")).doesNotExist();
    }

    @Test
    void updateModeWritesSnapshotThenNormalModeCompares() throws Exception {
        Snapshots snapshots = Snapshots.at(tempDir.resolve("feature"));
        System.setProperty(Snapshots.UPDATE_PROPERTY, "true");
        snapshots.assertTextEquals("out.txt", "line 1\r\nline 2\n");
        System.clearProperty(Snapshots.UPDATE_PROPERTY);

        assertThat(Files.readString(tempDir.resolve("feature/out.txt"))).isEqualTo("line 1\nline 2\n");
        snapshots.assertTextEquals("out.txt", "line 1\nline 2\n");
    }

    @Test
    void mismatchFailsWithReadableLineDiff() throws Exception {
        Files.writeString(tempDir.resolve("out.txt"), "a\nb\nc\n");
        assertThatThrownBy(() -> Snapshots.at(tempDir).assertTextEquals("out.txt", "a\nB\nc\n"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("-2: b")
                .hasMessageContaining("+2: B")
                .hasMessageContaining("-Dsnapshot.update=true");
    }

    @Test
    void rdfSnapshotsCompareByIsomorphismSoBlankNodeLabelsDoNotMatter() throws Exception {
        Files.writeString(tempDir.resolve("graph.ttl"), """
                @prefix ex: <urn:test:> .
                ex:a ex:p [ ex:q "1" ] .
                """);
        Model sameShapeDifferentLabels = Snapshots.turtle("""
                @prefix ex: <urn:test:> .
                ex:a ex:p _:other . _:other ex:q "1" .
                """);
        Snapshots.at(tempDir).assertIsomorphic("graph", sameShapeDifferentLabels);

        Model different = Snapshots.turtle("@prefix ex: <urn:test:> . ex:a ex:p [ ex:q \"2\" ] .");
        assertThatThrownBy(() -> Snapshots.at(tempDir).assertIsomorphic("graph", different))
                .hasMessageContaining("only in expected")
                .hasMessageContaining("\"1\"")
                .hasMessageContaining("\"2\"");
    }

    @Test
    void jsonIsKeySortedAndTimestampsAndPathsAreNormalized() {
        Path base = tempDir.resolve("run");
        String json = """
                {"z": 1, "report": "%s", "created": "2026-09-25T10:15:30Z", "a": ["C:\\\\other\\\\x.ttl"]}
                """.formatted(base.resolve("out").resolve("r.xlsx").toString().replace("\\", "\\\\"));

        String normalized = Snapshots.normalizeJson(json, Normalizer.paths(base));

        assertThat(normalized.indexOf("\"a\"")).isLessThan(normalized.indexOf("\"z\""));
        assertThat(normalized).contains("\"<BASE>/out/r.xlsx\"", "\"<TIMESTAMP>\"", "\"<PATH>\"")
                .doesNotContain("2026-09-25");
    }

    @Test
    void workbookIsFlattenedPerSheetWithNormalizedCells() throws Exception {
        Path xlsx = tempDir.resolve("book.xlsx");
        try (XSSFWorkbook book = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(xlsx)) {
            XSSFSheet sheet = book.createSheet("Summary");
            sheet.createRow(0).createCell(0).setCellValue("Created");
            sheet.getRow(0).createCell(1).setCellValue("2026-09-25 10:15:30");
            sheet.createRow(1).createCell(0).setCellValue("a,b");
            sheet.getRow(1).createCell(1).setCellValue(3);
            book.write(out);
        }

        String flat = Snapshots.flattenWorkbook(xlsx, Normalizer.timestamps());

        assertThat(flat).isEqualTo("## Summary\nCreated,<TIMESTAMP>\n\"a,b\",3\n");
    }

    @Test
    void literalsAreNormalizedSoRunSpecificBlankNodeLabelsStillMatch() {
        String turtle = """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                [] sh:sourceShape "%s" ; sh:resultMessage "kept"@en ; sh:focusNode <urn:x> .
                """;
        Model first = RDFParser.fromString(turtle.formatted("40e334d5bede7e358912c392307b6855"), Lang.TURTLE).toModel();
        Model second = RDFParser.fromString(turtle.formatted("99d51991b491463347e07ac1bb6f16e9"), Lang.TURTLE).toModel();

        assertThat(first.isIsomorphicWith(second)).isFalse();
        Snapshots.assertIsomorphic(Snapshots.normalizeLiterals(first, Normalizer.blankNodeLabels()),
                Snapshots.normalizeLiterals(second, Normalizer.blankNodeLabels()));
        assertThat(Snapshots.normalizeLiterals(first, Normalizer.blankNodeLabels())
                .contains(null, null, first.createLiteral("kept", "en"))).isTrue();
    }
}
