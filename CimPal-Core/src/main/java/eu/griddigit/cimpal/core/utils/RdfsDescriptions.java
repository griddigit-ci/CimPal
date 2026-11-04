/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One row per subject of an RDFS profile: its description (rdfs:comment), multiplicity, kind
 * (Class, Attribute, Association, Package or Enum), whether an association is used, its stereotypes
 * and whether a class is concrete. GUI: Tools ▸ RDFS Profiles ▸ Export RDFS descriptions; CLI:
 * {@code rdfs-describe}.
 */
public final class RdfsDescriptions {

    static final String SHEET = "RDFS descriptions";
    static final List<String> HEADERS = List.of("Item", "Description", "Multiplicity", "Type", "Association used",
            "Stereotype", "Concrete class");

    private static final String CIMS = "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#";
    private static final Property MULTIPLICITY = ResourceFactory.createProperty(CIMS, "multiplicity");
    private static final Property STEREOTYPE = ResourceFactory.createProperty(CIMS, "stereotype");
    private static final Property ASSOCIATION_USED = ResourceFactory.createProperty(CIMS, "AssociationUsed");
    private static final Resource CLASS_CATEGORY = ResourceFactory.createResource(CIMS + "ClassCategory");
    private static final Resource CONCRETE = ResourceFactory.createResource("http://iec.ch/TC57/NonStandard/UML#concrete");
    private static final List<String> NOT_CLASS_LIKE = List.of("Primitive", "CIMDatatype", "Compound");

    private RdfsDescriptions() {
    }

    /** The workbook: one sheet, "RDFS descriptions", rows sorted by item name. */
    public static XSSFWorkbook workbook(Model rdfs) {
        XSSFWorkbook workbook = new XSSFWorkbook();
        ExcelSheets.write(workbook, SHEET, HEADERS, rows(rdfs));
        return workbook;
    }

    /** The rows, in {@link #HEADERS} order, sorted by item name. */
    static List<List<Object>> rows(Model rdfs) {
        List<Resource> subjects = new ArrayList<>(rdfs.listSubjects().toList());
        subjects.sort(Comparator.comparing(RdfsDescriptions::itemName));
        List<List<Object>> rows = new ArrayList<>();
        for (Resource item : subjects) {
            rows.add(row(rdfs, item));
        }
        return rows;
    }

    private static List<Object> row(Model rdfs, Resource item) {
        String type;
        String associationUsed = "N/A";
        String concrete = "N/A";
        if (rdfs.contains(item, RDF.type, RDFS.Class)) {
            type = "Class";
            if (rdfs.contains(item, STEREOTYPE, CONCRETE)) {
                concrete = "Yes";
            } else if (NOT_CLASS_LIKE.stream().noneMatch(s -> rdfs.contains(item, STEREOTYPE, s))) {
                concrete = "No";
            }
        } else if (rdfs.contains(item, RDF.type, RDF.Property)) {
            if (rdfs.contains(item, ASSOCIATION_USED, "Yes")) {
                type = "Association";
                associationUsed = "Yes";
            } else if (rdfs.contains(item, ASSOCIATION_USED, "No")) {
                type = "Association";
                associationUsed = "No";
            } else {
                type = "Attribute";
            }
        } else if (rdfs.contains(item, RDF.type, CLASS_CATEGORY)) {
            type = "Package";
        } else {
            type = "Enum";
        }

        List<String> stereotypes = new ArrayList<>();
        rdfs.listObjectsOfProperty(item, STEREOTYPE).forEachRemaining(n -> {
            if (n.isLiteral()) stereotypes.add(n.asLiteral().getLexicalForm());
        });

        return List.of(itemName(item), description(item), multiplicity(rdfs, item), type, associationUsed,
                stereotypes.isEmpty() ? "N/A" : String.join("; ", stereotypes), concrete);
    }

    private static String itemName(Resource item) {
        return item.isAnon() ? "_:" + item.getId().getLabelString() : item.getLocalName();
    }

    private static String description(Resource item) {
        Statement comment = item.getProperty(RDFS.comment);
        if (comment == null) return "No description defined.";
        RDFNode value = comment.getObject();
        return value.isLiteral() ? value.asLiteral().getLexicalForm() : value.toString();
    }

    /** The first cims:multiplicity of the form {@code ...#M:x}, as {@code x}; else "N/A". */
    private static String multiplicity(Model rdfs, Resource item) {
        for (RDFNode n : rdfs.listObjectsOfProperty(item, MULTIPLICITY).toList()) {
            String[] parts = n.toString().split("#M:", 2);
            if (parts.length == 2) return parts[1];
        }
        return "N/A";
    }
}
