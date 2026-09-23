/* Licensed under the EUPL-1.2-or-later. Copyright (c) 2026, gridDigIt Kft. */
package eu.griddigit.cimpal.main.ai;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.shacl.vocabulary.SHACL;
import org.apache.jena.vocabulary.RDF;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Converts a SHACL validation report into bounded, local evidence for the assistant. */
public final class AiValidationReportInspector {
    private static final int MAX_RESULTS = 20;

    private AiValidationReportInspector() { }

    public static String summarize(File reportFile) {
        return summarize(reportFile, -1, MAX_RESULTS);
    }

    public static String summarize(File reportFile, int selectedProblem, int maxResults) {
        Model report = RDFDataMgr.loadModel(reportFile.toURI().toString());
        StringBuilder summary = new StringBuilder("Selected local SHACL validation report: ").append(reportFile.getName()).append('\n');
        int total = 0;
        ResIterator results = report.listResourcesWithProperty(RDF.type, SHACL.ValidationResult);
        try {
            while (results.hasNext()) {
                Resource result = results.next();
                total++;
                if ((selectedProblem >= 0 && total - 1 != selectedProblem) || (selectedProblem < 0 && total > maxResults)) continue;
                summary.append("\nViolation ").append(total).append(':').append('\n');
                append(summary, report, "focus node", result.getProperty(property(report, SHACL.focusNode)));
                append(summary, report, "path", result.getProperty(property(report, SHACL.resultPath)));
                append(summary, report, "constraint", result.getProperty(property(report, SHACL.sourceConstraintComponent)));
                append(summary, report, "shape", result.getProperty(property(report, SHACL.sourceShape)));
                append(summary, report, "severity", result.getProperty(property(report, SHACL.resultSeverity)));
                append(summary, report, "message", result.getProperty(property(report, SHACL.resultMessage)));
                append(summary, report, "value", result.getProperty(property(report, SHACL.value)));
            }
        } finally {
            results.close();
        }
        summary.insert(summary.indexOf("\n") + 1, "Validation results: " + total
                + (selectedProblem >= 0 ? " (selected violation " + (selectedProblem + 1) + " included)"
                : total > maxResults ? " (first " + maxResults + " included)" : "") + "\n");
        return summary.toString();
    }

    public static List<String> problemLabels(File reportFile) {
        Model report = RDFDataMgr.loadModel(reportFile.toURI().toString());
        List<String> labels = new ArrayList<>();
        ResIterator results = report.listResourcesWithProperty(RDF.type, SHACL.ValidationResult);
        try {
            while (results.hasNext() && labels.size() < 200) {
                Resource result = results.next();
                Statement focus = result.getProperty(property(report, SHACL.focusNode));
                Statement message = result.getProperty(property(report, SHACL.resultMessage));
                String focusText = focus == null ? "no focus node" : display(report, focus.getObject());
                String messageText = message == null ? "no message" : display(report, message.getObject());
                labels.add("Violation " + (labels.size() + 1) + " — " + focusText + " — " + messageText);
            }
        } finally {
            results.close();
        }
        return labels;
    }

    /** Focus-node URIs can be used to retrieve relevant local instance-data context. */
    public static List<String> focusNodeUris(File reportFile) {
        return focusNodeUris(reportFile, -1, 10);
    }

    public static List<String> focusNodeUris(File reportFile, int selectedProblem, int maxFocusNodes) {
        Model report = RDFDataMgr.loadModel(reportFile.toURI().toString());
        List<String> uris = new ArrayList<>();
        ResIterator results = report.listResourcesWithProperty(RDF.type, SHACL.ValidationResult);
        try {
            int index = 0;
            while (results.hasNext() && uris.size() < maxFocusNodes) {
                Resource result = results.next();
                if (selectedProblem >= 0 && index++ != selectedProblem) continue;
                Statement focus = result.getProperty(property(report, SHACL.focusNode));
                if (focus != null && focus.getObject().isURIResource()) uris.add(focus.getResource().getURI());
                if (selectedProblem >= 0) break;
            }
        } finally {
            results.close();
        }
        return uris;
    }

    private static void append(StringBuilder summary, Model model, String label, Statement statement) {
        if (statement == null) return;
        summary.append("- ").append(label).append(": ").append(display(model, statement.getObject())).append('\n');
    }

    private static String display(Model model, RDFNode value) {
        return value.isURIResource() ? model.shortForm(value.asResource().getURI()) : value.toString();
    }

    private static Property property(Model model, org.apache.jena.graph.Node node) {
        return model.createProperty(node.getURI());
    }
}
