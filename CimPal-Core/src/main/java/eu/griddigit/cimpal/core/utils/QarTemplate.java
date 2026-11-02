/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The QAR template workbook: one row per model file with its name, profile keyword, modelling
 * authority set and model id ({@code md:FullModel} IRI), the input of "Generate QAR based on xls".
 * Rows are sorted by MAS, then file name, so the files of one IGM are next to each other.
 * GUI: Tools ▸ Quality Reports ▸ Create xlsx template from QAR; CLI: {@code qar-template}.
 */
public final class QarTemplate {

    static final String SHEET = "FileData";
    static final List<String> HEADERS = List.of("FileName", "FileProfile", "MAS", "FileID");

    private static final String MD = "http://iec.ch/TC57/61970-552/ModelDescription/1#";
    private static final Resource FULL_MODEL = ResourceFactory.createResource(MD + "FullModel");
    private static final Property MAS = ResourceFactory.createProperty(MD, "Model.modelingAuthoritySet");

    private QarTemplate() {
    }

    /** @param models file name → model, as {@code ModelFactory.modelLoadPerFiles} returns them */
    public static XSSFWorkbook workbook(Map<String, Model> models) {
        XSSFWorkbook workbook = new XSSFWorkbook();
        ExcelSheets.write(workbook, SHEET, HEADERS, rows(models));
        return workbook;
    }

    static List<List<Object>> rows(Map<String, Model> models) {
        List<List<Object>> rows = new ArrayList<>();
        models.forEach((fileName, model) -> rows.add(List.of(fileName, ModelFactory.getProfileKeyword(model), mas(model), fileId(model))));
        rows.sort(Comparator.<List<Object>, String>comparing(r -> (String) r.get(2)).thenComparing(r -> (String) r.get(0)));
        return rows;
    }

    private static String mas(Model model) {
        for (Statement st : model.listStatements(null, MAS, (RDFNode) null).toList()) {
            RDFNode o = st.getObject();
            return o.isLiteral() ? o.asLiteral().getLexicalForm() : o.toString();
        }
        return "";
    }

    private static String fileId(Model model) {
        for (Resource header : model.listSubjectsWithProperty(RDF.type, FULL_MODEL).toList()) {
            if (header.isURIResource()) return header.getURI();
        }
        return "";
    }
}
