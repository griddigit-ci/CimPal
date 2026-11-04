/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.core;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression: the EA constraint CSV export ({@link ExportSHACLInformation}) neutralises formulas by
 * putting an apostrophe before a leading @, so every header payload ({@code @prefix ...}) is stored as
 * {@code '@prefix ...}. Tools ▸ Restore ttl from constraint csv wrote that apostrophe back into the
 * .ttl, which then did not parse. Restoring an exported CSV must give valid Turtle again.
 */
class ConstraintCsvRoundTripTest {

    @TempDir
    Path tempDir;

    private static String csvEscape(String value) throws Throwable {
        Method m = ExportSHACLInformation.class.getDeclaredMethod("csvEscape", String.class);
        m.setAccessible(true);
        try {
            return (String) m.invoke(null, value);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void aCsvWrittenByTheEaExportRestoresToValidTurtle() throws Throwable {
        String header = "@prefix sh: <http://www.w3.org/ns/shacl#> .\n@prefix ex: <http://example.org/s#> .";
        String shape = "ex:Line a sh:NodeShape ;\n    sh:targetClass ex:ACLineSegment .";
        String csv = String.join(",", "SourceFile", "PropertyShape/NodeShape ID", "Payload") + "\n"
                + String.join(",", csvEscape("EQ.ttl"), csvEscape("__HEADER__"), csvEscape(header)) + "\n"
                + String.join(",", csvEscape("EQ.ttl"), csvEscape("ex:Line"), csvEscape(shape)) + "\n";
        Path csvFile = Files.writeString(tempDir.resolve("EQ_EA_Import.csv"), csv);
        Path out = Files.createDirectories(tempDir.resolve("restored"));

        ShaclTools.restoreTtlFromSingleCsv(csvFile.toFile(), out.toFile());

        Path ttl = out.resolve("EQ.ttl");
        assertThat(Files.readString(ttl)).doesNotStartWith("'");
        Model restored = RDFDataMgr.loadModel(ttl.toString());
        assertThat(restored.size()).isEqualTo(2);
        assertThat(List.of(new File(out.toString()).list())).containsExactly("EQ.ttl");
    }
}
