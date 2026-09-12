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
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.update.UpdateAction;
import org.apache.jena.update.UpdateFactory;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.HashSet;
import java.util.Set;
import java.util.Collection;

/** Produces a bounded, read-only description of the selected instance-model files. */
public final class AiDatasetInspector {
    private static final String DEFAULT_XML_BASE = "http://iec.ch/TC57/2013/CIM-schema-cim16";
    private static final int MAX_TYPES = 60;
    private static final int MAX_PREFIXES = 40;
    private static final int MAX_PROPERTIES = 80;
    private static String cachedFingerprint;
    private static Model cachedModel;

    private AiDatasetInspector() { }

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

        summary.append("Namespaces:\n");
        model.getNsPrefixMap().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(MAX_PREFIXES)
                .forEach(entry -> summary.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n'));

        TreeSet<String> types = new TreeSet<>();
        StmtIterator iterator = model.listStatements(null, RDF.type, (RDFNode) null);
        try {
            while (iterator.hasNext() && types.size() < MAX_TYPES) {
                Statement statement = iterator.next();
                RDFNode object = statement.getObject();
                if (object.isResource() && object.asResource().isURIResource()) {
                    types.add(model.shortForm(object.asResource().getURI()));
                }
            }
        } finally {
            iterator.close();
        }
        summary.append("Observed RDF types (up to ").append(MAX_TYPES).append("):\n");
        if (types.isEmpty()) summary.append("- none found\n");
        else types.forEach(type -> summary.append("- ").append(type).append('\n'));
        TreeSet<String> properties = new TreeSet<>();
        StmtIterator predicateIterator = model.listStatements();
        try {
            while (predicateIterator.hasNext() && properties.size() < MAX_PROPERTIES) {
                properties.add(model.shortForm(predicateIterator.next().getPredicate().getURI()));
            }
        } finally {
            predicateIterator.close();
        }
        summary.append("Observed predicates (up to ").append(MAX_PROPERTIES).append("):\n");
        properties.forEach(property -> summary.append("- ").append(property).append('\n'));
        return summary.toString();
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
