/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** RDF → one sheet per rdf:type (GUI Export RDF triples to xls, CLI {@code rdf2xlsx}). */
class RdfTypeSheetsTest {

    private static Model turtle(String body) {
        Model m = ModelFactory.createDefaultModel();
        RDFParser.fromString("""
                @prefix cim: <http://iec.ch/TC57/CIM100#> .
                @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
                @prefix d: <http://example.org/data#> .
                """ + body, Lang.TURTLE).parse(m);
        return m;
    }

    private static List<List<String>> rows(Sheet sheet) {
        DataFormatter fmt = new DataFormatter();
        List<List<String>> rows = new ArrayList<>();
        for (Row row : sheet) {
            List<String> cells = new ArrayList<>();
            for (int c = 0; c < row.getLastCellNum(); c++) cells.add(fmt.formatCellValue(row.getCell(c)));
            rows.add(cells);
        }
        return rows;
    }

    private static final String LINES = """
            d:_L2 a cim:ACLineSegment ; cim:IdentifiedObject.name "Line 2" ; cim:ACLineSegment.r 1.5 .
            d:_L1 a cim:ACLineSegment ; cim:IdentifiedObject.name "Line 1"@en ;
                cim:Equipment.EquipmentContainer d:_C1 .
            d:_T1 a cim:Terminal ; cim:Terminal.ConductingEquipment d:_L1 .
            """;

    @Test
    void oneSheetPerTypeWithSortedRowsAndColumns() throws Exception {
        try (XSSFWorkbook book = RdfTypeSheets.workbook(turtle(LINES))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(2);
            Sheet lines = book.getSheet("ACLineSegment");
            List<List<String>> rows = rows(lines);
            assertThat(rows.getFirst()).containsExactly("rdf:id", "cim:ACLineSegment.r", "cim:Equipment.EquipmentContainer",
                    "cim:IdentifiedObject.name");
            assertThat(rows.get(1)).containsExactly("_L1", "", "d:_C1", "Line 1@en");
            assertThat(rows.get(2)).containsExactly("_L2", "1.5", "", "Line 2");
            assertThat(lines.getRow(2).getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(rows(book.getSheet("Terminal")).get(1)).containsExactly("_T1", "d:_L1");
        }
    }

    /** Regression: the column order came from a HashSet, so it changed between runs and JVMs. */
    @Test
    void theOutputIsTheSameEveryTime() throws Exception {
        try (XSSFWorkbook a = RdfTypeSheets.workbook(turtle(LINES)); XSSFWorkbook b = RdfTypeSheets.workbook(turtle(LINES))) {
            assertThat(rows(a.getSheet("ACLineSegment"))).isEqualTo(rows(b.getSheet("ACLineSegment")));
        }
    }

    /** Regression: several values of one property overwrote each other in the same cell. */
    @Test
    void severalValuesOfAPropertyAreJoined() throws Exception {
        try (XSSFWorkbook book = RdfTypeSheets.workbook(turtle("d:_X a cim:Thing ; cim:Thing.v \"b\" , \"a\" ."))) {
            assertThat(rows(book.getSheet("Thing")).get(1)).containsExactly("_X", "a | b");
        }
    }

    /** Regression: a blank-node subject threw NullPointerException. */
    @Test
    void aBlankNodeSubjectGetsALabel() throws Exception {
        try (XSSFWorkbook book = RdfTypeSheets.workbook(turtle("[] a cim:Thing ; cim:Thing.v \"x\" ."))) {
            assertThat(rows(book.getSheet("Thing")).get(1).getFirst()).startsWith("_:");
        }
    }

    /**
     * Regression: two types with the same local name in different namespaces (or a local name over
     * 31 characters) made sheet creation throw.
     */
    @Test
    void typesWithTheSameLocalNameGetTheirOwnSheets() throws Exception {
        String body = """
                d:_A a cim:Thing .
                d:_B a <http://example.org/other#Thing> .
                d:_C a <http://example.org/other#AVeryLongTypeNameThatExcelWouldNotAccept> .
                """;
        try (XSSFWorkbook book = RdfTypeSheets.workbook(turtle(body))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(3);
        }
    }

    /** Security review: an ill-formed typed literal threw DatatypeFormatException. */
    @Test
    void anIllFormedTypedLiteralIsWrittenAsText() throws Exception {
        try (XSSFWorkbook book = RdfTypeSheets.workbook(turtle("d:_X a cim:Thing ; cim:Thing.n \"abc\"^^xsd:int ."))) {
            assertThat(rows(book.getSheet("Thing")).get(1)).containsExactly("_X", "abc");
        }
    }

    /** A model without typed subjects would give a workbook without sheets, which is not a valid file. */
    @Test
    void aModelWithoutTypedSubjectsIsAnError() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RdfTypeSheets.workbook(turtle("d:_X cim:Thing.n 1 .")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Security review: a header (a property IRI) that looks like a formula is stored as text too. */
    @Test
    void headersThatLookLikeFormulasAreQuotePrefixed() throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            Sheet sheet = ExcelSheets.write(book, "S", List.of("=SUM(1)", "ok"), List.of(List.of("a", "b")));
            assertThat(sheet.getRow(0).getCell(0).getCellStyle().getQuotePrefixed()).isTrue();
            assertThat(sheet.getRow(0).getCell(1).getCellStyle().getQuotePrefixed()).isFalse();
        }
    }

    @Test
    void aTableLargerThanASheetIsAnError() {
        List<String> tooManyColumns = java.util.stream.IntStream.range(0, 16_385).mapToObj(i -> "c" + i).toList();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ExcelSheets.write(new XSSFWorkbook(), "S", tooManyColumns, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void otherTypesOfASubjectAreListed() throws Exception {
        try (XSSFWorkbook book = RdfTypeSheets.workbook(turtle("d:_A a cim:B , cim:A ."))) {
            List<List<String>> rows = rows(book.getSheet("A"));
            assertThat(rows.getFirst()).containsExactly("rdf:id", "rdf:type");
            assertThat(rows.get(1)).containsExactly("_A", "http://iec.ch/TC57/CIM100#B");
        }
    }
}
