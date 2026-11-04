/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The rules of a QoCDC 3.2.1 rule file ({@code UMLRestrictionRule} instances, RDF/XML) as a workbook:
 * name, severity, description, message and level, one row per rule, sorted by name; a missing value is
 * "NA". GUI: Tools ▸ Quality Reports ▸ QoCDC 3.2.1 xml to Excel; CLI: {@code qocdc2xlsx}.
 */
public final class QocdcRules {

    /** The base the rule file's relative IRIs ({@code #UMLRestrictionRule.name}) resolve against. */
    static final String BASE = "http://entsoe.eu/CIM/Extensions/CGM-BP/2020";
    static final String SHEET = "QoCDC321";
    static final List<String> HEADERS = List.of("Rule name", "Rule severity", "Rule description", "Rule message", "Rule level");

    private static final Resource RULE = ResourceFactory.createResource(BASE + "#UMLRestrictionRule");
    private static final List<Property> COLUMNS = List.of("name", "severity", "description", "message", "level").stream()
            .map(p -> ResourceFactory.createProperty(BASE + "#UMLRestrictionRule." + p)).toList();

    private QocdcRules() {
    }

    /** Reads a QoCDC rule file (RDF/XML) with the base its relative IRIs need. Parse errors propagate. */
    public static Model load(Path file) throws IOException {
        Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        try (InputStream in = Files.newInputStream(file)) {
            RDFDataMgr.read(model, in, BASE, Lang.RDFXML);
        }
        return model;
    }

    /**
     * @throws IllegalArgumentException when the file has no UMLRestrictionRule: an empty rule list
     *                                  would hide a wrong or differently versioned file
     */
    public static XSSFWorkbook workbook(Model rules) {
        List<List<Object>> rows = rows(rules);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("No UMLRestrictionRule in the file (is it a QoCDC 3.2.1 rule file?)");
        }
        XSSFWorkbook workbook = new XSSFWorkbook();
        ExcelSheets.write(workbook, SHEET, HEADERS, rows);
        return workbook;
    }

    static List<List<Object>> rows(Model rules) {
        List<List<Object>> rows = new ArrayList<>();
        for (Resource rule : rules.listSubjectsWithProperty(RDF.type, RULE).toList()) {
            List<Object> row = new ArrayList<>();
            for (Property p : COLUMNS) {
                Statement st = rule.getProperty(p);
                row.add(st == null ? "NA" : st.getObject().isLiteral() ? st.getObject().asLiteral().getLexicalForm() : st.getObject().toString());
            }
            rows.add(row);
        }
        rows.sort(Comparator.comparing(r -> (String) r.getFirst()));
        return rows;
    }
}
