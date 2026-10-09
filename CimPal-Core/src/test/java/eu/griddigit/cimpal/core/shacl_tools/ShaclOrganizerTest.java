/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.shacl_tools;

import eu.griddigit.cimpal.core.testsupport.Snapshots;
import eu.griddigit.cimpal.core.utils.ExcelTools;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFParser;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden-master tests (TEST-3) for splitting SHACL files by an Excel template, driven the way
 * {@code organize} drives it (template sheet 0 through {@link ExcelTools#importXLSX}).
 * Snapshots: {@code snapshots/organize/}.
 */
class ShaclOrganizerTest {

    private static final Snapshots SNAPSHOTS = Snapshots.forFeature("organize");
    private static final List<String> HEADER = List.of("constraintName", "subfolder", "filename", "newPrefix",
            "newPrefixNS", "newBaseURI", "newGroupURI", "newGroupName");

    /**
     * Three constraints: a property shape with an {@code sh:in} list, a SPARQL constraint (whose
     * file also gets the owl:Ontology header), and a shape whose {@code sh:name} lists two names.
     */
    private static final String SHAPES = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix cim: <http://iec.ch/TC57/CIM100#> .
            @prefix ex: <http://example.com/constraints#> .
            ex:Ontology a owl:Ontology ; rdfs:label "Constraints" .
            ex:Values a sh:PropertyGroup ; rdfs:label "Values" ; sh:order 1 .
            ex:Terminal a sh:NodeShape ; sh:targetClass cim:Terminal ;
                sh:property ex:Terminal.phases-in , ex:Terminal.sequence-sparql .
            ex:Terminal.phases-in a sh:PropertyShape ; sh:path cim:Terminal.phases ; sh:name "Terminal.phases-in" ;
                sh:group ex:Values ; sh:in ( cim:PhaseCode.A cim:PhaseCode.ABC ) .
            ex:Terminal.sequence-sparql a sh:PropertyShape ; sh:path cim:ACDCTerminal.sequenceNumber ;
                sh:name "Terminal.sequence-sparql" ; sh:sparql ex:SequenceQuery .
            ex:SequenceQuery a sh:SPARQLConstraint ; sh:select "SELECT $this WHERE { }" .
            ex:Line a sh:NodeShape ; sh:targetClass cim:ACLineSegment ; sh:property ex:Line.r-range .
            ex:Line.r-range a sh:PropertyShape ; sh:path cim:ACLineSegment.r ; sh:name "Line.r|Line.x" ;
                sh:minInclusive 0 .
            """;

    @TempDir
    Path tempDir;

    private Path out() {
        return tempDir.resolve("out");
    }

    private ArrayList<Object> template(List<List<String>> rows) throws Exception {
        Path file = tempDir.resolve("template.xlsx");
        try (XSSFWorkbook book = new XSSFWorkbook(); OutputStream stream = Files.newOutputStream(file)) {
            Sheet sheet = book.createSheet("Template");
            List<List<String>> all = new ArrayList<>();
            all.add(HEADER);
            all.addAll(rows);
            for (int r = 0; r < all.size(); r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < all.get(r).size(); c++) {
                    row.createCell(c).setCellValue(all.get(r).get(c));
                }
            }
            book.write(stream);
        }
        return ExcelTools.importXLSX(file.toString(), 0);
    }

    private static List<String> row(String constraint, String folder, String file) {
        return List.of(constraint, folder, file, "keep", "http://example.com/new#", "http://example.com/base/" + file, "", "");
    }

    private static Model shapes() {
        return RDFParser.fromString(SHAPES, Lang.TURTLE).toModel();
    }

    private String writtenFiles() throws Exception {
        if (!Files.exists(out())) {
            return "";
        }
        try (Stream<Path> files = Files.walk(out())) {
            return files.filter(Files::isRegularFile)
                    .map(p -> out().relativize(p).toString().replace('\\', '/') + "\n")
                    .sorted().reduce("", String::concat);
        }
    }

    private void assertFileGolden(String snapshot, String relative) {
        SNAPSHOTS.assertIsomorphic(snapshot, RDFDataMgr.loadModel(out().resolve(relative).toString()));
    }

    @Test
    void constraintsAreSplitIntoTheTemplateFiles() throws Exception {
        ShaclOrganizer.splitShaclPerXlsInput(template(List.of(
                row("Terminal.phases-in", "terminal", "values.ttl"),
                row("Terminal.sequence-sparql", "terminal", "values.ttl"),
                row("Line.r", "line", "ranges.ttl"))), List.of(shapes()), out());

        assertEquals("line/ranges.ttl\nterminal/values.ttl\n", writtenFiles());
        assertFileGolden("split__terminal-values", "terminal/values.ttl");
        assertFileGolden("split__line-ranges", "line/ranges.ttl");
        // The base URI from the template is written into the file.
        assertTrue(Files.readString(out().resolve("line/ranges.ttl")).contains("http://example.com/base/ranges.ttl"));
    }

    @Test
    void skippedUnknownAndShortRowsWriteNothing() throws Exception {
        List<String> skip = new ArrayList<>(row("Terminal.phases-in", "terminal", "skipped.ttl"));
        skip.set(4, "skip");
        ShaclOrganizer.splitShaclPerXlsInput(template(List.of(
                skip,
                row("No.such-constraint", "missing", "missing.ttl"),
                List.of("Line.r", "line", "short.ttl"))), List.of(shapes()), out());

        assertEquals("", writtenFiles());
    }

    @Test
    void prefixOtherThanKeepCopiesNothing() throws Exception {
        List<String> rename = new ArrayList<>(row("Terminal.phases-in", "terminal", "renamed.ttl"));
        rename.set(3, "newprefix");
        ShaclOrganizer.splitShaclPerXlsInput(template(List.of(rename)), List.of(shapes()), out());

        // Only "keep" is implemented: the constraint is found, but its file stays empty and is not written.
        assertEquals("", writtenFiles());
    }

    @Test
    void emptyTemplateWritesNothing() throws Exception {
        ShaclOrganizer.splitShaclPerXlsInput(template(List.of()), List.of(shapes()), out());

        assertEquals("", writtenFiles());
    }

    @Test
    void constraintIsTakenFromEveryShapesModelThatHasIt() throws Exception {
        ShaclOrganizer.splitShaclPerXlsInput(template(List.of(row("Terminal.phases-in", "terminal", "values.ttl"))),
                List.of(shapes(), RDFParser.fromString(SHAPES.replace("cim:PhaseCode.A ", ""), Lang.TURTLE).toModel()),
                out());

        assertFileGolden("two-models__terminal-values", "terminal/values.ttl");
    }
}
