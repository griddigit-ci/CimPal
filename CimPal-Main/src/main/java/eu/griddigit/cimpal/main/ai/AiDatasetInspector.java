/* Licensed under the EUPL-1.2-or-later. Copyright (c) 2026, gridDigIt Kft. */
package eu.griddigit.cimpal.main.ai;

import eu.griddigit.cimpal.main.application.MainController;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import eu.griddigit.cimpal.core.utils.SparqlTools;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.update.UpdateAction;
import org.apache.jena.update.UpdateFactory;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Set;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Comparator;

/** Produces a bounded, read-only description of the selected instance-model files. */
public final class AiDatasetInspector {
    private static final String DEFAULT_XML_BASE = "http://iec.ch/TC57/2013/CIM-schema-cim16";
    private static final int MAX_TYPES = 60;
    private static final int MAX_PREFIXES = 40;
    private static final int MAX_PROPERTIES = 80;
    private static final int MAX_PROFILES = 20;
    private static String cachedFingerprint;
    private static Model cachedModel;

    private AiDatasetInspector() { }

    public static boolean hasSelectedModels() {
        List<File> files = MainController.IDModel1;
        return files != null && !files.isEmpty();
    }

    public static String inspectSelectedModels() throws Exception {
        List<File> files = MainController.IDModel1;
        if (files == null || files.isEmpty()) {
            throw new IllegalStateException("No instance model files are selected. Select model files in the SPARQL Query tab first.");
        }
        Model model = selectedModel(files);
        if (model == null || model.isEmpty()) {
            throw new IllegalStateException("The selected model files did not produce any RDF statements.");
        }

        StringBuilder summary = new StringBuilder("Selected CimPal dataset summary (read-only):\n");
        summary.append("Files: ");
        summary.append(files.stream().map(File::getName).sorted().reduce((a, b) -> a + ", " + b).orElse("none"));
        summary.append("\nTriples: ").append(model.size()).append('\n');

        summary.append("Declared namespace prefixes:\n");
        model.getNsPrefixMap().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(MAX_PREFIXES)
                .forEach(entry -> summary.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n'));

        Map<String, Long> typeCounts = new HashMap<>();
        Map<String, Long> propertyCounts = new HashMap<>();
        Map<String, Long> namespaceCounts = new HashMap<>();
        TreeSet<String> declaredProfiles = new TreeSet<>();
        StmtIterator iterator = model.listStatements();
        try {
            while (iterator.hasNext()) {
                Statement statement = iterator.next();
                String predicateUri = statement.getPredicate().getURI();
                increment(propertyCounts, model.shortForm(predicateUri));
                increment(namespaceCounts, namespaceOf(predicateUri));
                RDFNode object = statement.getObject();
                if (RDF.type.getURI().equals(predicateUri) && object.isResource() && object.asResource().isURIResource()) {
                    String typeUri = object.asResource().getURI();
                    increment(typeCounts, model.shortForm(typeUri));
                    increment(namespaceCounts, namespaceOf(typeUri));
                }
                if ("profile".equalsIgnoreCase(statement.getPredicate().getLocalName())) {
                    declaredProfiles.add(object.isURIResource() ? object.asResource().getURI() : object.toString());
                }
            }
        } finally {
            iterator.close();
        }
        summary.append("Observed namespaces by predicate/type use (up to ").append(MAX_PREFIXES).append("):\n");
        appendCounts(summary, namespaceCounts, MAX_PREFIXES);
        summary.append("Declared model profiles (up to ").append(MAX_PROFILES).append("):\n");
        if (declaredProfiles.isEmpty()) summary.append("- none declared in RDF; selected files are the model scope\n");
        else declaredProfiles.stream().limit(MAX_PROFILES).forEach(profile -> summary.append("- ").append(profile).append('\n'));
        summary.append("Observed RDF classes with instance counts (up to ").append(MAX_TYPES).append("):\n");
        appendCounts(summary, typeCounts, MAX_TYPES);
        summary.append("Observed predicates with triple counts (up to ").append(MAX_PROPERTIES).append("):\n");
        appendCounts(summary, propertyCounts, MAX_PROPERTIES);
        return summary.toString();
    }

    private static void increment(Map<String, Long> counts, String value) {
        counts.merge(value, 1L, Long::sum);
    }

    private static String namespaceOf(String uri) {
        int separator = Math.max(uri.lastIndexOf('#'), uri.lastIndexOf('/'));
        return separator >= 0 ? uri.substring(0, separator + 1) : uri;
    }

    private static void appendCounts(StringBuilder summary, Map<String, Long> counts, int limit) {
        if (counts.isEmpty()) {
            summary.append("- none found\n");
            return;
        }
        List<Map.Entry<String, Long>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Comparator.<Map.Entry<String, Long>, Long>comparing(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));
        entries.stream().limit(limit).forEach(entry -> summary.append("- ").append(entry.getKey())
                .append(" (").append(entry.getValue()).append(")\n"));
    }

    /** Resolves the CIM namespace from the selected data instead of assuming a CIM version. */
    public static String selectedCimNamespace() throws Exception {
        Model model = selectedModel(requireSelectedFiles());
        String mapped = model.getNsPrefixURI("cim");
        if (mapped != null && !mapped.isBlank()) return mapped;
        StmtIterator iterator = model.listStatements();
        try {
            while (iterator.hasNext()) {
                String uri = iterator.next().getPredicate().getURI();
                int hash = uri.lastIndexOf('#');
                if (hash > 0 && uri.substring(0, hash).toLowerCase().contains("cim")) return uri.substring(0, hash + 1);
            }
        } finally {
            iterator.close();
        }
        throw new IllegalStateException("Could not identify a CIM namespace in the selected model.");
    }

    public static int countQueryResults(String query) throws Exception {
        return SparqlTools.executeSparqlQuery(query, selectedModel(requireSelectedFiles())).rows.size();
    }

    /** Executes a bounded SELECT locally and returns evidence suitable for a draft revision prompt. */
    public static String inspectReadOnlyQuery(String query, int maxRows) throws Exception {
        org.apache.jena.query.Query parsed = org.apache.jena.query.QueryFactory.create(query);
        if (!parsed.isSelectType()) throw new IllegalArgumentException("Only SELECT queries can be inspected.");
        long originalLimit = parsed.getLimit();
        if (originalLimit < 0 || originalLimit > maxRows) parsed.setLimit(maxRows);
        SparqlTools.QueryResults results = SparqlTools.executeSparqlQuery(parsed.toString(), selectedModel(requireSelectedFiles()));
        StringBuilder evidence = new StringBuilder("Local read-only execution evidence:\n")
                .append("Rows returned").append(originalLimit > maxRows || originalLimit < 0 ? " (capped at " + maxRows + ")" : "")
                .append(": ").append(results.rows.size()).append('\n')
                .append("Columns: ").append(String.join(", ", results.columns)).append('\n');
        int samples = Math.min(3, results.rows.size());
        for (int index = 0; index < samples; index++) {
            evidence.append("Sample ").append(index + 1).append(": ").append(results.rows.get(index)).append('\n');
        }
        return evidence.toString();
    }

    /**
     * Parses a proposed Turtle shapes graph and evaluates it against the selected data in memory.
     * The selected files and the cached model are never changed.
     */
    public static String testShaclAgainstSelectedModel(String turtle) throws Exception {
        Model shapes = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        RDFParser.fromString(turtle, Lang.TURTLE).parse(shapes);
        ValidationReport report = ShaclValidator.get().validate(selectedModel(requireSelectedFiles()).getGraph(), shapes.getGraph());
        long reportStatements = report.getModel().size();
        return report.conforms()
                ? "SHACL Turtle is valid and the selected model conforms to this proposed shape. No data was changed."
                : "SHACL Turtle is valid, but the selected model does not conform to this proposed shape ("
                + reportStatements + " report statement(s)). No data was changed.";
    }

    /** Counts unique resources having an rdf:type whose local name equals {@code className}. */
    public static long countInstancesOf(String className) throws Exception {
        Model model = selectedModel(requireSelectedFiles());
        Set<String> instances = new HashSet<>();
        StmtIterator iterator = model.listStatements(null, RDF.type, (RDFNode) null);
        try {
            while (iterator.hasNext()) {
                Statement statement = iterator.next();
                RDFNode type = statement.getObject();
                if (type.isResource() && className.equalsIgnoreCase(type.asResource().getLocalName())) {
                    instances.add(statement.getSubject().isURIResource()
                            ? statement.getSubject().getURI() : statement.getSubject().getId().getLabelString());
                }
            }
        } finally {
            iterator.close();
        }
        return instances.size();
    }

    /** Returns a small, read-only property neighbourhood for validation-report focus nodes. */
    public static String describeResources(Collection<String> resourceUris, int maxResources, int maxProperties) throws Exception {
        if (resourceUris == null || resourceUris.isEmpty()) return "No URI focus nodes were available in the validation report.";
        Model model = selectedModel(requireSelectedFiles());
        StringBuilder description = new StringBuilder("Local RDF context for validation focus nodes:\n");
        int described = 0;
        for (String uri : resourceUris) {
            if (described++ >= maxResources) break;
            org.apache.jena.rdf.model.Resource resource = model.getResource(uri);
            StmtIterator statements = resource.listProperties();
            int count = 0;
            description.append("\n").append(model.shortForm(uri)).append(':').append('\n');
            try {
                while (statements.hasNext() && count++ < maxProperties) {
                    Statement statement = statements.next();
                    RDFNode object = statement.getObject();
                    String value = object.isURIResource() ? model.shortForm(object.asResource().getURI()) : object.toString();
                    description.append("- ").append(model.shortForm(statement.getPredicate().getURI())).append(": ").append(value).append('\n');
                }
                if (count == 0) description.append("- not present in the selected model\n");
            } finally {
                statements.close();
            }
        }
        return description.toString();
    }

    /** Applies a repair only to an in-memory copy and reports the proposed graph delta. */
    public static String previewSparqlRepair(String updateText) throws Exception {
        Model original = selectedModel(requireSelectedFiles());
        Model repaired = org.apache.jena.rdf.model.ModelFactory.createDefaultModel().add(original);
        UpdateAction.execute(UpdateFactory.create(updateText), repaired);
        Model removed = original.difference(repaired);
        Model added = repaired.difference(original);
        StringBuilder preview = new StringBuilder("Repair preview — no selected file or loaded model was changed.\n")
                .append("Triples removed: ").append(removed.size()).append('\n')
                .append("Triples added: ").append(added.size()).append('\n');
        appendPreviewStatements(preview, "Removed", removed);
        appendPreviewStatements(preview, "Added", added);
        return preview.toString();
    }

    /** Applies a repair only to a copy, then compares SHACL conformance before and after it. */
    public static String previewAndValidateSparqlRepair(String updateText, File shapesFile) throws Exception {
        if (shapesFile == null || !shapesFile.isFile()) throw new IllegalArgumentException("Choose the SHACL shapes file used for validation first.");
        Model original = selectedModel(requireSelectedFiles());
        Model repaired = org.apache.jena.rdf.model.ModelFactory.createDefaultModel().add(original);
        Model shapes = RDFDataMgr.loadModel(shapesFile.toURI().toString());
        ValidationReport before = ShaclValidator.get().validate(original.getGraph(), shapes.getGraph());
        UpdateAction.execute(UpdateFactory.create(updateText), repaired);
        ValidationReport after = ShaclValidator.get().validate(repaired.getGraph(), shapes.getGraph());
        Model removed = original.difference(repaired);
        Model added = repaired.difference(original);
        StringBuilder preview = new StringBuilder("Repair preview and SHACL validation — no selected file or loaded model was changed.\n")
                .append("Shapes: ").append(shapesFile.getName()).append('\n')
                .append("Before repair: ").append(validationOutcome(before)).append('\n')
                .append("After repair: ").append(validationOutcome(after)).append('\n')
                .append("Triples removed: ").append(removed.size()).append('\n')
                .append("Triples added: ").append(added.size()).append('\n');
        appendPreviewStatements(preview, "Removed", removed);
        appendPreviewStatements(preview, "Added", added);
        return preview.toString();
    }

    private static String validationOutcome(ValidationReport report) {
        return report.conforms() ? "conforms" : "does not conform (" + report.getModel().size() + " report statement(s))";
    }

    private static void appendPreviewStatements(StringBuilder preview, String title, Model model) {
        if (model.isEmpty()) return;
        preview.append('\n').append(title).append(" triples (up to 20):\n");
        StmtIterator iterator = model.listStatements();
        int count = 0;
        try {
            while (iterator.hasNext() && count++ < 20) {
                Statement statement = iterator.next();
                preview.append("- ").append(statement).append('\n');
            }
        } finally {
            iterator.close();
        }
    }

    private static List<File> requireSelectedFiles() {
        List<File> files = MainController.IDModel1;
        if (files == null || files.isEmpty()) {
            throw new IllegalStateException("No instance model files are selected. Select model files in the SPARQL Query tab first.");
        }
        return files;
    }

    private static synchronized Model selectedModel(List<File> files) throws Exception {
        String fingerprint = files.stream().map(file -> file.getAbsolutePath() + ":" + file.length() + ":" + file.lastModified())
                .sorted().reduce((left, right) -> left + "|" + right).orElse("");
        if (cachedModel != null && fingerprint.equals(cachedFingerprint)) return cachedModel;
        Model loaded = eu.griddigit.cimpal.core.utils.ModelFactory.loadCombinedModelForSparql(files, DEFAULT_XML_BASE);
        if (loaded == null || loaded.isEmpty()) {
            throw new IllegalStateException("The selected model files did not produce any RDF statements.");
        }
        cachedModel = loaded;
        cachedFingerprint = fingerprint;
        return cachedModel;
    }
}
