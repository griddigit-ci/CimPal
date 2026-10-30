/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.shacl_tools;

import eu.griddigit.cimpal.core.utils.ExcelSheets;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.vocabulary.RDF;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.topbraid.shacl.vocabulary.SH;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Exports SHACL shape files to an Excel workbook, in one of two layouts:
 * <ul>
 *   <li>{@link #summary} — one row per constraint (ID, name, group, type, SPARQL constraint,
 *       severity, message, description); one sheet per file;</li>
 *   <li>{@link #byType} — every predicate of every NodeShape, PropertyShape, SPARQL constraint
 *       and PropertyGroup, one sheet per kind; with several files their rows share the sheets and
 *       a "Source file" column says where each row came from.</li>
 * </ul>
 * A field a shape doesn't have is an empty cell, IRIs without a prefix are written in full, numeric
 * literals are numeric cells, and text a spreadsheet would read as a formula is stored quote-prefixed.
 * Used by the GUI (Tools → Export SHACL constraints information) and by the CLI {@code shacl2xlsx}.
 */
public final class ShaclExcelExport {

    /** A loaded SHACL file: its display name (file base name) and its model. */
    public record Source(String name, Model model) {
    }

    /** A sheet's content: its column headers in order and one list of cell values per row. */
    private record Table(List<String> columns, List<Map<String, Object>> rows) {
        Table() {
            this(new ArrayList<>(), new ArrayList<>());
        }

        void addColumn(String column) {
            if (!columns.contains(column)) columns.add(column);
        }
    }

    static final String SUMMARY_SHEET = "SHACL Constraints info";
    static final String SOURCE_COLUMN = "Source file";
    private static final List<String> SUMMARY_COLUMNS = List.of("PropertyShape/NodeShape ID", "Constraint Name",
            "Group", "Type", "SPARQL constraint ID", "Severity", "Message", "Constraint Description");

    private ShaclExcelExport() {
    }

    /**
     * Reads a SHACL file; the format follows the extension (Turtle when unknown). Parse errors propagate.
     * JSON-LD is refused: its reader may fetch a remote {@code @context}, outside the egress gate.
     */
    public static Source load(Path file) throws IOException {
        Model model = ModelFactory.createDefaultModel();
        Lang lang = RDFLanguages.filenameToLang(file.getFileName().toString());
        if (lang != null && RDFLanguages.sameLang(lang, Lang.JSONLD)) {
            throw new IOException("JSON-LD SHACL files are not supported; convert to Turtle first: " + file.getFileName());
        }
        try (InputStream in = Files.newInputStream(file)) {
            RDFDataMgr.read(model, in, lang != null ? lang : Lang.TURTLE);
        }
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return new Source(dot > 0 ? name.substring(0, dot) : name, model);
    }

    // -------------------------------------------------------------------------
    // Summary layout
    // -------------------------------------------------------------------------

    /** One row per constraint; one sheet named "SHACL Constraints info", or one per source if several. */
    public static XSSFWorkbook summary(List<Source> sources) {
        XSSFWorkbook workbook = new XSSFWorkbook();
        for (Source source : sources) {
            String sheet = sources.size() == 1 ? SUMMARY_SHEET : source.name();
            writeSheet(workbook, sheet, summaryTable(source.model()));
        }
        return workbook;
    }

    private static Table summaryTable(Model model) {
        Table table = new Table();
        SUMMARY_COLUMNS.forEach(table::addColumn);
        for (StmtIterator i = model.listStatements(null, RDF.type, SH.PropertyShape); i.hasNext(); ) {
            Resource shape = i.next().getSubject();
            // skip deactivated shapes and helper shapes (sh:not / sh:qualifiedValueShape guards) that have no sh:name
            if (!model.contains(shape, SH.name, (RDFNode) null)) continue;
            table.rows().add(summaryRow(model, shape));
        }
        for (StmtIterator i = model.listStatements(null, RDF.type, SH.NodeShape); i.hasNext(); ) {
            Resource shape = i.next().getSubject();
            if (model.contains(shape, SH.severity, (RDFNode) null)) {
                table.rows().add(summaryRow(model, shape));
            }
        }
        return table;
    }

    private static Map<String, Object> summaryRow(Model model, Resource shape) {
        Map<String, Object> row = new HashMap<>();
        row.put("PropertyShape/NodeShape ID", toQName(model, shape));
        row.put("Constraint Name", text(model, shape, SH.name));
        row.put("Group", text(model, shape, SH.group));
        row.put("Severity", text(model, shape, SH.severity));
        row.put("Constraint Description", text(model, shape, SH.description));

        List<Resource> sparql = new ArrayList<>();
        model.listObjectsOfProperty(shape, SH.sparql).forEachRemaining(n -> {
            if (n.isResource()) sparql.add(n.asResource());
        });
        if (!sparql.isEmpty()) {
            row.put("Type", "SPARQL");
            row.put("SPARQL constraint ID", String.join(" | ", sparql.stream().map(r -> toQName(model, r)).toList()));
            String message = String.join(" | ", sparql.stream().map(r -> text(model, r, SH.message))
                    .filter(m -> !m.isEmpty()).toList());
            row.put("Message", message.isEmpty() ? "N/A" : message);
        } else {
            row.put("Type", "Regular SHACL");
            row.put("SPARQL constraint ID", "N/A");
            row.put("Message", text(model, shape, SH.message));
        }
        return row;
    }

    /** The values of {@code p} on {@code s} as plain text (literals without their language tag), joined by " | ". */
    private static String text(Model model, Resource s, Property p) {
        List<String> values = new ArrayList<>();
        model.listObjectsOfProperty(s, p).forEachRemaining(n ->
                values.add(n.isLiteral() ? n.asLiteral().getLexicalForm() : nodeToString(model, n)));
        Collections.sort(values);
        return String.join(" | ", values);
    }

    // -------------------------------------------------------------------------
    // By-type layout
    // -------------------------------------------------------------------------

    /** Every predicate of each shape kind, one sheet per kind; several sources share the sheets. */
    public static XSSFWorkbook byType(List<Source> sources) {
        boolean withSource = sources.size() > 1;
        Table nodeShapes = new Table();
        Table propertyShapes = new Table();
        Table sparqlConstraints = new Table();
        Table propertyGroups = new Table();
        for (Table t : List.of(nodeShapes, propertyShapes, sparqlConstraints, propertyGroups)) {
            if (withSource) t.addColumn(SOURCE_COLUMN);
        }
        for (Source source : sources) {
            Model model = source.model();
            addPredicateRows(nodeShapes, source, collectTypedSubjects(model, SH.NodeShape), withSource);
            addPredicateRows(propertyShapes, source, collectTypedSubjects(model, SH.PropertyShape), withSource);
            addPredicateRows(sparqlConstraints, source, collectSparqlConstraints(model), withSource);
            addPredicateRows(propertyGroups, source, collectPropertyGroups(model), withSource);
        }

        XSSFWorkbook workbook = new XSSFWorkbook();
        addKind(workbook, "NodeShapes", nodeShapes);
        addKind(workbook, "PropertyShapes", propertyShapes);
        addKind(workbook, "SPARQLConstraints", sparqlConstraints);
        addKind(workbook, "PropertyGroups", propertyGroups);
        return workbook;
    }

    private static void addKind(XSSFWorkbook workbook, String sheet, Table table) {
        if (!table.rows().isEmpty()) {
            writeSheet(workbook, sheet, table);
        }
    }

    /**
     * Adds one row per subject, sorted by sh:name (else by QName), and a column for every predicate
     * the subjects use; sh:name comes first, the others in QName order.
     */
    private static void addPredicateRows(Table table, Source source, List<Resource> subjects, boolean withSource) {
        Model model = source.model();
        TreeSet<Property> predicates = new TreeSet<>(Comparator.comparing(p -> toQName(model, p)));
        for (Resource s : subjects) {
            model.listStatements(s, null, (RDFNode) null).forEachRemaining(st -> {
                if (!st.getPredicate().equals(RDF.type)) predicates.add(st.getPredicate());
            });
        }
        if (predicates.remove(SH.name)) table.addColumn(toQName(model, SH.name));
        predicates.forEach(p -> table.addColumn(toQName(model, p)));
        // sh:name first even when an earlier source lacked it
        String name = toQName(model, SH.name);
        if (table.columns().remove(name)) table.columns().add(withSource ? 1 : 0, name);

        subjects.sort((a, b) -> {
            String an = firstLexical(model, a, SH.name);
            String bn = firstLexical(model, b, SH.name);
            if (!an.isEmpty() || !bn.isEmpty()) return an.compareTo(bn);
            return toQName(model, a).compareTo(toQName(model, b));
        });
        for (Resource s : subjects) {
            Map<String, Object> row = new HashMap<>();
            if (withSource) row.put(SOURCE_COLUMN, source.name());
            for (Property p : model.listStatements(s, null, (RDFNode) null).mapWith(Statement::getPredicate).toSet()) {
                if (!p.equals(RDF.type)) row.put(toQName(model, p), cellValue(model, model.listObjectsOfProperty(s, p).toList()));
            }
            table.rows().add(row);
        }
    }

    /** A single numeric literal as a number, anything else as text with several values joined by " | ". */
    private static Object cellValue(Model model, List<RDFNode> values) {
        if (values.size() == 1 && values.getFirst().isLiteral()
                && values.getFirst().asLiteral().getValue() instanceof Number n) {
            return n;
        }
        List<String> texts = new ArrayList<>(values.stream().map(v -> nodeToString(model, v)).toList());
        Collections.sort(texts);
        return String.join(" | ", texts);
    }

    private static String firstLexical(Model model, Resource s, Property p) {
        StmtIterator it = model.listStatements(s, p, (RDFNode) null);
        if (!it.hasNext()) return "";
        RDFNode obj = it.next().getObject();
        return obj.isLiteral() ? obj.asLiteral().getString() : nodeToString(model, obj);
    }

    private static List<Resource> collectTypedSubjects(Model model, Resource type) {
        LinkedHashSet<Resource> subjects = new LinkedHashSet<>();
        model.listStatements(null, RDF.type, type).forEachRemaining(st -> subjects.add(st.getSubject()));
        return sorted(model, subjects);
    }

    private static List<Resource> collectPropertyGroups(Model model) {
        LinkedHashSet<Resource> groups = new LinkedHashSet<>();
        model.listStatements(null, RDF.type, SH.PropertyGroup).forEachRemaining(st -> groups.add(st.getSubject()));
        model.listStatements(null, SH.group, (RDFNode) null).forEachRemaining(st -> {
            if (st.getObject().isResource()) groups.add(st.getObject().asResource());
        });
        return sorted(model, groups);
    }

    private static List<Resource> collectSparqlConstraints(Model model) {
        LinkedHashSet<Resource> constraints = new LinkedHashSet<>();
        model.listStatements(null, SH.sparql, (RDFNode) null).forEachRemaining(st -> {
            if (st.getObject().isResource()) constraints.add(st.getObject().asResource());
        });
        model.listStatements(null, RDF.type, SH.SPARQLConstraint).forEachRemaining(st -> constraints.add(st.getSubject()));
        return sorted(model, constraints);
    }

    private static List<Resource> sorted(Model model, Collection<Resource> resources) {
        List<Resource> list = new ArrayList<>(resources);
        list.sort(Comparator.comparing(r -> toQName(model, r)));
        return list;
    }

    // -------------------------------------------------------------------------
    // RDF terms as text
    // -------------------------------------------------------------------------

    /** A literal as its lexical form plus "@lang", a list as "[a; b]", a resource as its QName or full IRI. */
    static String nodeToString(Model model, RDFNode node) {
        if (node == null) return "";
        if (node.isLiteral()) {
            Literal l = node.asLiteral();
            String lang = l.getLanguage();
            return lang == null || lang.isEmpty() ? l.getLexicalForm() : l.getLexicalForm() + "@" + lang;
        }
        Resource r = node.asResource();
        if (r.canAs(RDFList.class)) {
            try {
                List<String> items = new ArrayList<>();
                r.as(RDFList.class).iterator().forEachRemaining(n -> items.add(nodeToString(model, n)));
                return "[" + String.join("; ", items) + "]";
            } catch (RuntimeException notAWellFormedList) {
                // fall through to the node's own name
            }
        }
        return toQName(model, r);
    }

    /** {@code prefix:local} when the model has a prefix for the namespace, else the full IRI. */
    static String toQName(Model model, Resource r) {
        if (r == null) return "";
        if (r.isAnon()) return "_:" + r.getId().getLabelString();
        String ns = r.getNameSpace();
        String prefix = ns == null ? null : model.getNsURIPrefix(ns);
        return prefix != null && !r.getLocalName().isEmpty() ? prefix + ":" + r.getLocalName() : r.getURI();
    }

    // -------------------------------------------------------------------------
    // Workbook
    // -------------------------------------------------------------------------

    private static void writeSheet(XSSFWorkbook workbook, String wantedName, Table table) {
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : table.rows()) {
            rows.add(table.columns().stream().map(row::get).toList());
        }
        ExcelSheets.write(workbook, wantedName, table.columns(), rows);
    }

    /**
     * Writes the workbook to {@code file} and closes it. Parent folders are created; the file is
     * written to a temporary sibling first and moved into place, so a failure leaves no partial file.
     */
    public static void write(Workbook workbook, Path file) throws IOException {
        Path target = file.toAbsolutePath();
        Path parent = target.getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".shacl-export-", ".xlsx");
        try (workbook) {
            try (OutputStream out = Files.newOutputStream(temp)) {
                workbook.write(out);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
