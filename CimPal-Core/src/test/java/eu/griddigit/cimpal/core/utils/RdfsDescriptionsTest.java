/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.testsupport.Fixtures;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** RDFS → description table (GUI Export RDFS descriptions, CLI {@code rdfs-describe}). */
class RdfsDescriptionsTest {

    private static Model miniEquipment() throws Exception {
        Model m = ModelFactory.createDefaultModel();
        try (InputStream in = Fixtures.open("shacl-generation", "mini-equipment-2020-cim100.rdf")) {
            RDFDataMgr.read(m, in, Lang.RDFXML);
        }
        return m;
    }

    private static List<Object> row(List<List<Object>> rows, String item) {
        return rows.stream().filter(r -> r.getFirst().equals(item)).findFirst()
                .orElseThrow(() -> new AssertionError("no row " + item));
    }

    @Test
    void eachKindOfItemIsDescribed() throws Exception {
        List<List<Object>> rows = RdfsDescriptions.rows(miniEquipment());

        assertThat(row(rows, "IdentifiedObject.name")).containsExactly("IdentifiedObject.name", "No description defined.",
                "1..1", "Attribute", "N/A", "N/A", "N/A");
        assertThat(row(rows, "Terminal.ConductingEquipment")).containsSubsequence("1", "Association", "Yes");
        assertThat(row(rows, "ConductingEquipment.Terminals")).containsSubsequence("0..n", "Association", "No");
        assertThat(row(rows, "Resistance")).containsSubsequence("Class", "N/A", "CIMDatatype", "N/A");
        assertThat(row(rows, "Package_MiniEquipment").get(3)).isEqualTo("Package");
    }

    @Test
    void concreteClassesAreMarked() throws Exception {
        List<List<Object>> rows = RdfsDescriptions.rows(miniEquipment());

        assertThat(row(rows, "ACLineSegment").get(6)).isEqualTo("Yes");
        assertThat(row(rows, "Equipment").get(6)).isEqualTo("No");
    }

    @Test
    void rowsAreSortedByItemSoTheOutputIsStable() throws Exception {
        List<String> items = RdfsDescriptions.rows(miniEquipment()).stream().map(r -> (String) r.getFirst()).toList();

        assertThat(items).isSorted();
    }

    /** A language-tagged rdfs:comment is written as its text, without "@en". */
    @Test
    void aCommentIsWrittenAsItsText() {
        Model m = ModelFactory.createDefaultModel();
        m.createResource("http://example.org/cim#Thing")
                .addProperty(org.apache.jena.vocabulary.RDF.type, org.apache.jena.vocabulary.RDFS.Class)
                .addProperty(org.apache.jena.vocabulary.RDFS.comment, "A thing.", "en");

        assertThat(RdfsDescriptions.rows(m).getFirst().get(1)).isEqualTo("A thing.");
    }

    /** Regression: two cims:multiplicity values shifted every later row's multiplicity by one. */
    @Test
    void aSecondMultiplicityDoesNotShiftTheRows() {
        String cims = "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#";
        Model m = ModelFactory.createDefaultModel();
        org.apache.jena.rdf.model.Property mult = m.createProperty(cims, "multiplicity");
        m.createResource("http://example.org/cim#A.x").addProperty(mult, m.createResource(cims + "M:1"))
                .addProperty(mult, m.createResource(cims + "M:0..1"));
        m.createResource("http://example.org/cim#B.y").addProperty(mult, m.createResource(cims + "M:0..n"));

        List<List<Object>> rows = RdfsDescriptions.rows(m);

        assertThat(rows).hasSize(2);
        assertThat(row(rows, "B.y").get(2)).isEqualTo("0..n");
    }

    @Test
    void theWorkbookHasTheHeaderAndARowPerItem() throws Exception {
        Model m = miniEquipment();
        try (XSSFWorkbook book = RdfsDescriptions.workbook(m)) {
            Sheet sheet = book.getSheet("RDFS descriptions");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Item");
            assertThat(sheet.getLastRowNum()).isEqualTo(m.listSubjects().toList().size());
        }
    }
}
