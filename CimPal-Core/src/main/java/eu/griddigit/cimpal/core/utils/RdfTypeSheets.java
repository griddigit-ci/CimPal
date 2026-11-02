/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.util.*;

/**
 * Any RDF model as a workbook: one sheet per rdf:type, one row per subject of that type, one column
 * per property ({@code prefix:name}), after a first {@code rdf:id} column (the part of the subject
 * IRI after '#'). GUI: Tools ▸ RDFS Profiles ▸ Export RDF triples to xls; CLI: {@code rdf2xlsx}.
 *
 * <p>A subject with several types goes to the sheet of the first type in IRI order; its other types
 * are listed in an {@code rdf:type} column. Several values of one property are joined by " | ". A
 * literal is its lexical form ("@lang" appended); a numeric literal is a number cell. An IRI is
 * {@code prefix:local} when the model has a prefix for its namespace, else the full IRI; a
 * {@code file://} namespace is dropped, leaving the local name. Sheets, rows and columns are sorted.
 */
public final class RdfTypeSheets {

    static final String ID_COLUMN = "rdf:id";
    static final String TYPE_COLUMN = "rdf:type";

    private RdfTypeSheets() {
    }

    /**
     * @throws IllegalArgumentException when no subject has an rdf:type: a workbook without sheets is
     *                                  not a valid file, and an empty result would hide wrong input
     */
    public static XSSFWorkbook workbook(Model model) {
        // type -> rows (column -> value), both sorted
        TreeMap<String, List<SortedMap<String, Object>>> byType = new TreeMap<>();
        List<Resource> subjects = new ArrayList<>(model.listSubjectsWithProperty(RDF.type).toList());
        if (subjects.isEmpty()) {
            throw new IllegalArgumentException("No subject has an rdf:type, so there is nothing to export");
        }
        subjects.sort(Comparator.comparing(RdfTypeSheets::subjectId));
        for (Resource subject : subjects) {
            List<String> types = new ArrayList<>();
            model.listObjectsOfProperty(subject, RDF.type).forEachRemaining(t -> types.add(t.isURIResource() ? t.asResource().getURI() : t.toString()));
            Collections.sort(types);
            String type = types.getFirst();

            SortedMap<String, Object> row = new TreeMap<>();
            row.put(ID_COLUMN, subjectId(subject));
            if (types.size() > 1) {
                row.put(TYPE_COLUMN, String.join(" | ", types.subList(1, types.size())));
            }
            Map<String, List<RDFNode>> values = new TreeMap<>();
            subject.listProperties().forEachRemaining(st -> {
                if (!st.getPredicate().equals(RDF.type)) {
                    values.computeIfAbsent(qName(model, st.getPredicate()), k -> new ArrayList<>()).add(st.getObject());
                }
            });
            values.forEach((column, nodes) -> row.put(column, cellValue(model, nodes)));
            byType.computeIfAbsent(type, k -> new ArrayList<>()).add(row);
        }

        XSSFWorkbook workbook = new XSSFWorkbook();
        byType.forEach((type, rows) -> {
            TreeSet<String> others = new TreeSet<>();
            rows.forEach(r -> others.addAll(r.keySet()));
            others.remove(ID_COLUMN);
            List<String> headers = new ArrayList<>();
            headers.add(ID_COLUMN);
            headers.addAll(others);
            List<List<Object>> cells = new ArrayList<>();
            for (SortedMap<String, Object> r : rows) {
                cells.add(headers.stream().map(r::get).toList());
            }
            ExcelSheets.write(workbook, localName(type), headers, cells);
        });
        return workbook;
    }

    private static Object cellValue(Model model, List<RDFNode> nodes) {
        if (nodes.size() == 1 && nodes.getFirst().isLiteral()) {
            Literal l = nodes.getFirst().asLiteral();
            // an ill-formed typed literal ("abc"^^xsd:int) has no value; it is written as its text
            if (l.getDatatype() != null && l.getDatatype().isValid(l.getLexicalForm()) && l.getValue() instanceof Number n) {
                return n;
            }
        }
        List<String> texts = new ArrayList<>();
        for (RDFNode node : nodes) {
            if (node.isLiteral()) {
                Literal l = node.asLiteral();
                texts.add(l.getLanguage().isEmpty() ? l.getLexicalForm() : l.getLexicalForm() + "@" + l.getLanguage());
            } else if (node.isAnon()) {
                texts.add("_:" + node.asResource().getId().getLabelString());
            } else {
                Resource r = node.asResource();
                String ns = r.getNameSpace();
                String prefix = model.getNsURIPrefix(ns);
                texts.add(prefix != null ? prefix + ":" + r.getLocalName() : ns.startsWith("file://") ? r.getLocalName() : r.getURI());
            }
        }
        Collections.sort(texts);
        return String.join(" | ", texts);
    }

    private static String qName(Model model, Property p) {
        String prefix = model.getNsURIPrefix(p.getNameSpace());
        return prefix != null ? prefix + ":" + p.getLocalName() : p.getURI();
    }

    private static String subjectId(Resource subject) {
        if (subject.isAnon()) return "_:" + subject.getId().getLabelString();
        String uri = subject.getURI();
        return uri.substring(uri.lastIndexOf('#') + 1);
    }

    private static String localName(String typeUri) {
        int cut = Math.max(typeUri.lastIndexOf('#'), typeUri.lastIndexOf('/'));
        String name = cut >= 0 && cut < typeUri.length() - 1 ? typeUri.substring(cut + 1) : typeUri;
        return name.isBlank() ? "Type" : name;
    }
}
