/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.shacl_tools;

import eu.griddigit.cimpal.core.presets.RDFtoSHACLOptionsPresets;
import eu.griddigit.cimpal.core.testsupport.Fixtures;
import eu.griddigit.cimpal.core.testsupport.Snapshots;
import eu.griddigit.cimpal.core.utils.ExcelTools;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden-master tests (TEST-3) for Excel → SHACL, driven the way {@code excel2shacl} drives it:
 * the RDFS profile goes through {@link ShapeDataBuilder}, the workbook through
 * {@link ExcelTools#importXLSX}. Snapshots: {@code snapshots/excel2shacl/}.
 */
class ShaclFromXlsTest {

    private static final Snapshots SNAPSHOTS = Snapshots.forFeature("excel2shacl");
    private static final String CONCRETE = "http://iec.ch/TC57/NonStandard/UML#concrete";
    private static final String PROFILE_NS = "http://example.com/constraints/MiniEquipment#";
    private static final String CIM100 = "http://iec.ch/TC57/CIM100#";
    private static final String CIM16 = "http://iec.ch/TC57/2013/CIM-schema-cim16#";

    /** Data sheet header; the generator reads columns 2-5 and 7-12, the others are free text. */
    private static final List<Object> HEADER = List.of("Class", "Id", "Name", "Description", "Message", "Severity",
            "Type", "Shape", "Path", "Constraint1", "Value1", "Constraint2", "Value2");

    @TempDir
    Path tempDir;

    private static ArrayList<Object> shapeData(String profileFile) {
        Model rdfs = ModelFactory.createDefaultModel();
        RDFDataMgr.read(rdfs, new ByteArrayInputStream(Fixtures.bytes("shacl-generation", profileFile)), "", Lang.RDFXML);
        return ShapeDataBuilder.constructShapeData(rdfs, RDFtoSHACLOptionsPresets.DEFAULT_CIMS_NAMESPACE, CONCRETE);
    }

    private static List<Object> row(String name, String path, String c1, Object v1) {
        return List.of("", "", name, "Description of " + name, "Message of " + name, "Violation",
                "", name, path, c1, v1);
    }

    private static List<Object> row(String name, String path, String c1, Object v1, String c2, Object v2) {
        List<Object> row = new ArrayList<>(row(name, path, c1, v1));
        row.add(c2);
        row.add(v2);
        return row;
    }

    /** Writes a workbook whose first sheet holds {@code rows} under {@link #HEADER}, plus an optional Config sheet. */
    private Path workbook(List<List<Object>> rows, List<List<Object>> config) throws Exception {
        Path file = tempDir.resolve("constraints.xlsx");
        try (XSSFWorkbook book = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            fill(book.createSheet("Constraints"), HEADER, rows);
            if (config != null) {
                fill(book.createSheet("Config"), List.of("Prefix", "URI"), config);
            }
            book.write(out);
        }
        return file;
    }

    private static void fill(Sheet sheet, List<Object> header, List<List<Object>> rows) {
        List<List<Object>> all = new ArrayList<>();
        all.add(header);
        all.addAll(rows);
        for (int r = 0; r < all.size(); r++) {
            Row row = sheet.createRow(r);
            for (int c = 0; c < all.get(r).size(); c++) {
                Object value = all.get(r).get(c);
                if (value instanceof Number number) {
                    row.createCell(c).setCellValue(number.doubleValue());
                } else {
                    row.createCell(c).setCellValue(value.toString());
                }
            }
        }
    }

    private static Map<String, String> fallbacks(String cimNs) {
        return Map.of("prefixEU", "eu", "uriEU", "http://iec.ch/TC57/CIM100-European#",
                "cimsNamespace", RDFtoSHACLOptionsPresets.DEFAULT_CIMS_NAMESPACE, "CIMnamespace", cimNs,
                "prefixOther", "", "uriOther", "");
    }

    private static Model generate(Path workbook, String profileFile, String cimNs) {
        return ShaclFromXls.generateShaclFromXls(fallbacks(cimNs),
                ExcelTools.importXLSX(workbook.toString(), 0), ExcelTools.importXLSX(workbook.toString(), "Config"),
                shapeData(profileFile), "meqc", PROFILE_NS);
    }

    /**
     * Compares the model as {@code excel2shacl} writes it: literals built from Java doubles only
     * compare equal to their Turtle form after a round trip through Turtle.
     */
    private static void assertGolden(String name, Model shapes) {
        java.io.StringWriter turtle = new java.io.StringWriter();
        RDFDataMgr.write(turtle, shapes, Lang.TURTLE);
        SNAPSHOTS.assertIsomorphic(name, org.apache.jena.riot.RDFParser.fromString(turtle.toString(), Lang.TURTLE).toModel());
    }

    private static List<List<Object>> constraintRows(String cimNs) {
        return List.of(
                row("ACLineSegment.r-range", cimNs + "ACLineSegment.r", "minInclusive", 0, "maxExclusive", 1000),
                row("IdentifiedObject.name-length", cimNs + "IdentifiedObject.name", "minLength", 1, "maxLength", 32),
                row("Equipment.aggregate-equals", cimNs + "Equipment.aggregate", "equals", "cim:Equipment.aggregate"),
                row("Unknown.attribute-range", cimNs + "Unknown.attribute", "minInclusive", 0));
    }

    @Test
    void cgmes30ConstraintsWithAConfigSheet() throws Exception {
        Path xlsx = workbook(constraintRows(CIM100), List.of(
                List.of("cim", CIM100), List.of("sh", "http://www.w3.org/ns/shacl#"),
                List.of("xsd", "http://www.w3.org/2001/XMLSchema#")));

        Model shapes = generate(xlsx, "mini-equipment-2020-cim100.rdf", CIM100);

        // Attributes the profile does not define are skipped with a warning on stdout.
        assertFalse(shapes.containsResource(shapes.createResource(PROFILE_NS + "Unknown.attribute-range")));
        assertGolden("cgmes30-config-sheet", shapes);
        // The Config sheet's prefixes, the profile's, and rdf/rdfs/owl from the TopBraid default model.
        assertEquals(Map.of("cim", CIM100, "sh", "http://www.w3.org/ns/shacl#",
                "xsd", "http://www.w3.org/2001/XMLSchema#", "meqc", PROFILE_NS,
                "rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#", "rdfs", "http://www.w3.org/2000/01/rdf-schema#",
                "owl", "http://www.w3.org/2002/07/owl#"), shapes.getNsPrefixMap());
    }

    @Test
    void cgmes30ConstraintsWithoutAConfigSheetUseTheFallbackNamespaces() throws Exception {
        Model shapes = generate(workbook(constraintRows(CIM100), null), "mini-equipment-2020-cim100.rdf", CIM100);

        assertGolden("cgmes30-fallback-namespaces", shapes);
        assertEquals(CIM100, shapes.getNsPrefixURI("cim"));
        assertEquals("http://iec.ch/TC57/CIM100-European#", shapes.getNsPrefixURI("eu"));
    }

    @Test
    void cgmes24Constraints() throws Exception {
        Model shapes = generate(workbook(constraintRows(CIM16), null), "mini-equipment-2019-cim16.rdf", CIM16);

        assertGolden("cgmes24-fallback-namespaces", shapes);
    }

    @Test
    void headerOnlySheetGivesOnlyTheValueConstraintsGroup() throws Exception {
        Model shapes = generate(workbook(List.of(), null), "mini-equipment-2020-cim100.rdf", CIM100);

        assertGolden("header-only", shapes);
    }

    @Test
    void pathWithoutANamespaceSeparatorFails() throws Exception {
        Path xlsx = workbook(List.of(row("Bad.path", "no-hash-here", "minInclusive", 0)), null);

        assertThrows(ArrayIndexOutOfBoundsException.class, () -> generate(xlsx, "mini-equipment-2020-cim100.rdf", CIM100));
    }

    // ---- Suspected bugs: each test asserts the correct behaviour and fails today ----

    /**
     * Numeric cells are read as doubles, so {@code sh:minLength}/{@code sh:maxLength} become
     * {@code "32.0"^^xsd:integer}: an ill-formed literal, not the integer 32.
     */
    @Disabled("Suspected bug (TEST-3): numeric length limits are written as ill-formed xsd:integer literals like \"32.0\"")
    @Test
    void lengthLimitsAreValidIntegers() throws Exception {
        Model shapes = generate(workbook(constraintRows(CIM100), null), "mini-equipment-2020-cim100.rdf", CIM100);

        org.apache.jena.rdf.model.Literal maxLength = shapes.listObjectsOfProperty(
                org.topbraid.shacl.vocabulary.SH.maxLength).next().asLiteral();
        assertEquals("32", maxLength.getLexicalForm());
        assertTrue(maxLength.getDatatype().isValid(maxLength.getLexicalForm()));
    }

    /**
     * {@link ExcelTools#importXLSX} reports a missing or unreadable workbook on stderr and returns
     * no rows, so the generator writes a shapes file with only the group in it, and no error.
     */
    @Disabled("Suspected bug (TEST-3): an unreadable workbook gives an empty shapes model instead of an error")
    @Test
    void missingWorkbookIsAnError() {
        Path missing = tempDir.resolve("missing.xlsx");

        assertThrows(Exception.class, () -> generate(missing, "mini-equipment-2020-cim100.rdf", CIM100));
    }
}
