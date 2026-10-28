/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.shacl_tools;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** SHACL → Excel export (GUI "Export SHACL constraints information" and CLI {@code shacl2xlsx}). */
class ShaclExcelExportTest {

    private static final String PREFIXES = """
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix cim: <http://iec.ch/TC57/CIM100#> .
            @prefix eq: <http://example.org/eq/constraints#> .
            """;

    /** A regular shape, a SPARQL shape and a node-level shape, every field filled in. */
    private static final String COMPLETE = PREFIXES + """
            eq:G1 a sh:PropertyGroup ; sh:order 1 .
            eq:Line a sh:NodeShape ; sh:targetClass cim:ACLineSegment ;
                sh:property eq:Line.r-cardinality , eq:Line.r-valueRange .
            eq:Line.r-cardinality a sh:PropertyShape ; sh:name "Line.r-cardinality" ; sh:path cim:ACLineSegment.r ;
                sh:minCount 1 ; sh:maxCount 1 ; sh:group eq:G1 ; sh:severity sh:Violation ;
                sh:message "Missing r" ; sh:description "r is required." .
            eq:Line.r-valueRange a sh:PropertyShape ; sh:name "Line.r-valueRange" ; sh:path cim:ACLineSegment.r ;
                sh:group eq:G1 ; sh:severity sh:Warning ; sh:description "r >= 0" ; sh:sparql eq:rRange .
            eq:rRange a sh:SPARQLConstraint ; sh:message "r negative" ;
                sh:select "SELECT $this WHERE { $this cim:ACLineSegment.r ?r FILTER(?r < 0) }" .
            eq:Count a sh:NodeShape ; sh:targetNode cim:ACLineSegment ; sh:name "Count" ; sh:group eq:G1 ;
                sh:severity sh:Info ; sh:description "count" ; sh:message "too many" .
            """;

    @TempDir
    Path tempDir;

    private static ShaclExcelExport.Source source(String name, String turtle) {
        Model model = ModelFactory.createDefaultModel();
        RDFParser.fromString(turtle, Lang.TURTLE).parse(model);
        return new ShaclExcelExport.Source(name, model);
    }

    /** The workbook as written to disk and read back, so the test sees what Excel sees. */
    private Workbook roundTrip(Workbook workbook) throws Exception {
        Path file = tempDir.resolve("out-" + System.nanoTime() + ".xlsx");
        ShaclExcelExport.write(workbook, file);
        try (InputStream in = Files.newInputStream(file)) {
            return WorkbookFactory.create(in);
        }
    }

    private static List<List<String>> rows(Sheet sheet) {
        DataFormatter fmt = new DataFormatter();
        List<List<String>> rows = new ArrayList<>();
        for (Row row : sheet) {
            List<String> cells = new ArrayList<>();
            for (int c = 0; c < row.getLastCellNum(); c++) {
                Cell cell = row.getCell(c);
                cells.add(cell == null ? "" : fmt.formatCellValue(cell));
            }
            rows.add(cells);
        }
        return rows;
    }

    /** The data row whose first column (or the given column) equals {@code key}. */
    private static List<String> row(Sheet sheet, int column, String key) {
        return rows(sheet).stream().filter(r -> r.size() > column && r.get(column).equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("no row " + key + " in " + rows(sheet)));
    }

    private static String cell(Sheet sheet, List<String> row, String header) {
        return row.get(rows(sheet).getFirst().indexOf(header));
    }

    // ---- summary layout -----------------------------------------------------

    @Test
    void summaryHasOneRowPerConstraint() throws Exception {
        try (Workbook book = roundTrip(ShaclExcelExport.summary(List.of(source("eq", COMPLETE))))) {
            Sheet sheet = book.getSheet("SHACL Constraints info");

            assertThat(rows(sheet).getFirst()).containsExactly("PropertyShape/NodeShape ID", "Constraint Name",
                    "Group", "Type", "SPARQL constraint ID", "Severity", "Message", "Constraint Description");
            assertThat(rows(sheet)).hasSize(4);
            assertThat(row(sheet, 0, "eq:Line.r-cardinality")).containsExactly("eq:Line.r-cardinality",
                    "Line.r-cardinality", "eq:G1", "Regular SHACL", "N/A", "sh:Violation", "Missing r", "r is required.");
            assertThat(row(sheet, 0, "eq:Line.r-valueRange")).containsExactly("eq:Line.r-valueRange",
                    "Line.r-valueRange", "eq:G1", "SPARQL", "eq:rRange", "sh:Warning", "r negative", "r >= 0");
            assertThat(row(sheet, 0, "eq:Count")).contains("Count", "sh:Info", "too many");
        }
    }

    /** Regression: a named shape without sh:group threw NoSuchElementException and nothing was saved. */
    @Test
    void aShapeWithoutGroupGetsAnEmptyGroupCell() throws Exception {
        String turtle = COMPLETE.replace("sh:group eq:G1 ; sh:severity sh:Warning ;", "sh:severity sh:Warning ;");

        try (Workbook book = roundTrip(ShaclExcelExport.summary(List.of(source("eq", turtle))))) {
            Sheet sheet = book.getSheetAt(0);
            assertThat(cell(sheet, row(sheet, 0, "eq:Line.r-valueRange"), "Group")).isEmpty();
            assertThat(rows(sheet)).hasSize(4);
        }
    }

    /** Regression: a regular shape without sh:message threw NoSuchElementException. */
    @Test
    void aRegularShapeWithoutMessageGetsAnEmptyMessageCell() throws Exception {
        String turtle = COMPLETE.replace("sh:message \"Missing r\" ;", "");

        try (Workbook book = roundTrip(ShaclExcelExport.summary(List.of(source("eq", turtle))))) {
            Sheet sheet = book.getSheetAt(0);
            assertThat(cell(sheet, row(sheet, 0, "eq:Line.r-cardinality"), "Message")).isEmpty();
        }
    }

    /** Regression: a node shape with sh:severity but no sh:name, sh:group or sh:description threw. */
    @Test
    void aNodeShapeWithOnlySeverityIsListed() throws Exception {
        String turtle = PREFIXES + """
                eq:N a sh:NodeShape ; sh:targetClass cim:Terminal ; sh:severity sh:Violation ; sh:sparql eq:q .
                eq:q sh:select "SELECT $this WHERE {}" ; sh:message "m" .
                """;

        try (Workbook book = roundTrip(ShaclExcelExport.summary(List.of(source("eq", turtle))))) {
            Sheet sheet = book.getSheetAt(0);
            assertThat(row(sheet, 0, "eq:N")).containsExactly("eq:N", "", "", "SPARQL", "eq:q", "sh:Violation", "m", "");
        }
    }

    /** Regression: a namespace without a prefix was written as "null:Name". */
    @Test
    void aNamespaceWithoutPrefixIsWrittenAsTheFullUri() throws Exception {
        String turtle = COMPLETE.replace("@prefix eq: <http://example.org/eq/constraints#> .", "")
                .replaceAll("eq:([A-Za-z0-9.-]+)", "<http://example.org/eq/constraints#$1>");

        try (Workbook book = roundTrip(ShaclExcelExport.summary(List.of(source("eq", turtle))))) {
            Sheet sheet = book.getSheetAt(0);
            List<String> r = row(sheet, 0, "http://example.org/eq/constraints#Line.r-valueRange");
            assertThat(cell(sheet, r, "Group")).isEqualTo("http://example.org/eq/constraints#G1");
            assertThat(cell(sheet, r, "SPARQL constraint ID")).isEqualTo("http://example.org/eq/constraints#rRange");
            assertThat(rows(sheet).toString()).doesNotContain("null:");
        }
    }

    /** Regression: a language-tagged message was written as {@code "Missing r"@en}, quotes and all. */
    @Test
    void aLanguageTaggedMessageIsWrittenAsItsText() throws Exception {
        String turtle = COMPLETE.replace("\"Missing r\"", "\"Missing r\"@en").replace("\"r is required.\"", "\"r is required.\"@en");

        try (Workbook book = roundTrip(ShaclExcelExport.summary(List.of(source("eq", turtle))))) {
            Sheet sheet = book.getSheetAt(0);
            List<String> r = row(sheet, 0, "eq:Line.r-cardinality");
            assertThat(cell(sheet, r, "Message")).isEqualTo("Missing r");
            assertThat(cell(sheet, r, "Constraint Description")).isEqualTo("r is required.");
        }
    }

    /**
     * Regression: two files whose names agree in their first 31 characters (Excel's sheet name limit)
     * failed with "The workbook already contains a sheet named ...". CGMES file names do.
     */
    @Test
    void filesWithLongSimilarNamesGetDistinctSheets() throws Exception {
        List<ShaclExcelExport.Source> sources = List.of(
                source("IEC61970-600-2_CGMES_3_0_0_EQ_Constraints_v1", COMPLETE),
                source("IEC61970-600-2_CGMES_3_0_0_EQ_Constraints_v2", COMPLETE));

        try (Workbook book = roundTrip(ShaclExcelExport.summary(sources))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(2);
            assertThat(book.getSheetName(0)).isNotEqualTo(book.getSheetName(1));
            assertThat(book.getSheetName(0).length()).isLessThanOrEqualTo(31);
            assertThat(book.getSheetName(1).length()).isLessThanOrEqualTo(31);
        }
    }

    /** Regression: a file name with a character Excel refuses in sheet names ([ ] * ? / \ :) threw. */
    @Test
    void aFileNameWithCharactersExcelRefusesStillGetsASheet() throws Exception {
        try (Workbook book = roundTrip(ShaclExcelExport.summary(List.of(
                source("rules[v2]", COMPLETE), source("rules*draft", COMPLETE))))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(2);
        }
    }

    // ---- by-type layout -----------------------------------------------------

    @Test
    void byTypeHasOneSheetPerKindWithEveryPredicate() throws Exception {
        try (Workbook book = roundTrip(ShaclExcelExport.byType(List.of(source("eq", COMPLETE))))) {
            assertThat(book.getSheet("NodeShapes")).isNotNull();
            assertThat(book.getSheet("SPARQLConstraints")).isNotNull();
            assertThat(book.getSheet("PropertyGroups")).isNotNull();
            Sheet shapes = book.getSheet("PropertyShapes");
            assertThat(rows(shapes).getFirst()).startsWith("sh:name")
                    .contains("sh:path", "sh:minCount", "sh:severity", "sh:sparql");
            List<String> r = row(shapes, 0, "Line.r-cardinality");
            assertThat(cell(shapes, r, "sh:path")).isEqualTo("cim:ACLineSegment.r");
            assertThat(cell(shapes, r, "sh:severity")).isEqualTo("sh:Violation");
        }
    }

    /** Regression: integers were written as the text "1.0". */
    @Test
    void numbersAreWrittenAsNumbers() throws Exception {
        try (Workbook book = roundTrip(ShaclExcelExport.byType(List.of(source("eq", COMPLETE))))) {
            Sheet shapes = book.getSheet("PropertyShapes");
            int minCount = rows(shapes).getFirst().indexOf("sh:minCount");
            int rowIndex = rows(shapes).indexOf(row(shapes, 0, "Line.r-cardinality"));
            Cell cell = shapes.getRow(rowIndex).getCell(minCount);

            assertThat(cell.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(cell.getNumericCellValue()).isEqualTo(1.0);
            assertThat(new DataFormatter().formatCellValue(cell)).isEqualTo("1");
        }
    }

    /** Regression: a string literal that looked like a number ("007") became the text "7.0". */
    @Test
    void textThatLooksLikeANumberStaysText() throws Exception {
        String turtle = COMPLETE.replace("sh:name \"Count\"", "sh:name \"007\"");

        try (Workbook book = roundTrip(ShaclExcelExport.summary(List.of(source("eq", turtle))))) {
            Sheet sheet = book.getSheetAt(0);
            assertThat(cell(sheet, row(sheet, 0, "eq:Count"), "Constraint Name")).isEqualTo("007");
        }
    }

    /**
     * Regression: with several files in one workbook every file wrote over the same sheets, so rows
     * of one file were lost or left under another file's headers. Now the rows accumulate, with the
     * file they came from in the first column.
     */
    @Test
    void byTypeOfSeveralFilesKeepsEveryRowAndItsFile() throws Exception {
        String other = PREFIXES + "eq:Only a sh:NodeShape ; sh:targetClass cim:Terminal ; sh:closed true .\n";

        try (Workbook book = roundTrip(ShaclExcelExport.byType(List.of(source("eq", COMPLETE), source("other", other))))) {
            Sheet nodes = book.getSheet("NodeShapes");
            assertThat(rows(nodes).getFirst()).startsWith("Source file").contains("sh:closed", "sh:targetClass", "sh:targetNode");
            assertThat(rows(nodes)).hasSize(4); // header + Line, Count from eq + Only from other
            List<String> only = row(nodes, rows(nodes).getFirst().indexOf("sh:targetClass"), "cim:Terminal");
            assertThat(only.getFirst()).isEqualTo("other");
            assertThat(cell(nodes, only, "sh:closed")).isEqualTo("true");
            List<String> line = row(nodes, rows(nodes).getFirst().indexOf("sh:targetClass"), "cim:ACLineSegment");
            assertThat(line.getFirst()).isEqualTo("eq");
            assertThat(cell(nodes, line, "sh:closed")).isEmpty();
        }
    }

    // ---- cells --------------------------------------------------------------

    /** Text a spreadsheet would read as a formula is stored quote-prefixed (security rule: reports). */
    @Test
    void formulaLikeTextIsQuotePrefixed() throws Exception {
        String turtle = COMPLETE.replace("\"too many\"", "\"=HYPERLINK(\\\"http://x\\\")\"");

        try (Workbook book = roundTrip(ShaclExcelExport.summary(List.of(source("eq", turtle))))) {
            Sheet sheet = book.getSheetAt(0);
            int rowIndex = rows(sheet).indexOf(row(sheet, 0, "eq:Count"));
            Cell cell = sheet.getRow(rowIndex).getCell(rows(sheet).getFirst().indexOf("Message"));

            assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
            assertThat(cell.getStringCellValue()).startsWith("=HYPERLINK");
            assertThat(cell.getCellStyle().getQuotePrefixed()).isTrue();
        }
    }

    /** Regression: a SPARQL query longer than an Excel cell holds made POI throw. */
    @Test
    void textLongerThanACellHoldsIsCutToFit() throws Exception {
        String longQuery = "SELECT $this WHERE { " + "#".repeat(40_000) + "\n}";
        String turtle = COMPLETE.replace("\"SELECT $this WHERE { $this cim:ACLineSegment.r ?r FILTER(?r < 0) }\"",
                "\"\"\"" + longQuery + "\"\"\"");

        try (Workbook book = roundTrip(ShaclExcelExport.byType(List.of(source("eq", turtle))))) {
            Sheet sparql = book.getSheet("SPARQLConstraints");
            String select = cell(sparql, row(sparql, 0, "r negative"), "sh:select");
            assertThat(select).hasSize(32_767).endsWith("…");
        }
    }

    // ---- loading ------------------------------------------------------------

    /** Every formula trigger, in both layouts. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"=1+1", "+1", "-1", "@SUM(1)", "\\tx"})
    void everyFormulaTriggerIsQuotePrefixedInBothLayouts(String message) throws Exception {
        String turtle = COMPLETE.replace("\"too many\"", "\"" + message + "\"");
        String expected = message.replace("\\t", "\t");

        try (Workbook summary = roundTrip(ShaclExcelExport.summary(List.of(source("eq", turtle))));
             Workbook byType = roundTrip(ShaclExcelExport.byType(List.of(source("eq", turtle))))) {
            for (Sheet sheet : List.of(summary.getSheetAt(0), byType.getSheet("NodeShapes"))) {
                int column = rows(sheet).getFirst().indexOf(sheet == summary.getSheetAt(0) ? "Message" : "sh:message");
                Cell cell = null;
                for (Row row : sheet) {
                    Cell c = row.getCell(column);
                    if (c != null && c.getCellType() == CellType.STRING && c.getStringCellValue().equals(expected)) cell = c;
                }
                assertThat(cell).as(sheet.getSheetName() + " has the message " + message).isNotNull();
                assertThat(cell.getCellStyle().getQuotePrefixed()).as(sheet.getSheetName()).isTrue();
            }
        }
    }

    /** A file that doesn't parse is an error, never an empty export. */
    @Test
    void loadFailsOnMalformedTurtle() throws Exception {
        Path file = Files.writeString(tempDir.resolve("broken.ttl"), "this is not turtle .");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ShaclExcelExport.load(file))
                .isInstanceOf(org.apache.jena.riot.RiotException.class);
    }

    /** JSON-LD is refused before parsing: its reader could fetch a remote @context (security review). */
    @Test
    void loadRefusesJsonLdWithoutReadingIt() throws Exception {
        Path file = Files.writeString(tempDir.resolve("shapes.jsonld"),
                "{\"@context\": \"http://127.0.0.1:9/never-fetched\", \"@id\": \"http://example.org/s\"}");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ShaclExcelExport.load(file))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("JSON-LD");
    }

    @Test
    void loadNamesTheSourceAfterTheFile() throws Exception {
        Path file = Files.writeString(tempDir.resolve("EQ_Constraints.ttl"), COMPLETE);

        ShaclExcelExport.Source source = ShaclExcelExport.load(file);

        assertThat(source.name()).isEqualTo("EQ_Constraints");
        assertThat(source.model().size()).isGreaterThan(0);
    }
}
