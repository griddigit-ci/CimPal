package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import org.apache.jena.rdf.model.*;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.vocabulary.RDF;
import org.topbraid.shacl.vocabulary.SH;

import java.util.ArrayList;
import java.util.List;

public class ShaclTools {

    /**
     * Extract results and enrich with fields coming from the source shape.
     *
     * @param report validation report from Jena
     * @param shapesModel the shapes model used for validation (so we can read sh:name/sh:order/sh:group and use prefixes)
     */
    public static List<SHACLValidationResult> extractSHACLValidationResults(ValidationReport report, Model shapesModel) {
        List<SHACLValidationResult> resultsList = new ArrayList<>();
        Model reportModel = report.getModel();

        // Prefer shapes model for prefixing, fall back to report model
        Model prefixModel = (shapesModel != null) ? shapesModel : reportModel;

        ResIterator results = reportModel.listResourcesWithProperty(RDF.type, SH.ValidationResult);

        while (results.hasNext()) {
            Resource result = results.next();

            Resource sourceShapeRes = result.getPropertyResourceValue(SH.sourceShape);

            String sourceShape = compact(getResourceValue(sourceShapeRes), prefixModel);
            String focusNode = compact(getResourceValue(result.getPropertyResourceValue(SH.focusNode)), prefixModel);
            String severity = compact(getResourceValue(result.getPropertyResourceValue(SH.resultSeverity)), prefixModel);

            String message = getAllLiteralValues(result, SH.resultMessage);
            String value = compact(getStatementValue(result.getProperty(SH.value)), prefixModel);

            // resultPath is typically a resource (property URI)
            String path = compact(getResourceValue(result.getPropertyResourceValue(SH.resultPath)), prefixModel);

            String constraintComponent = compact(getResourceValue(result.getPropertyResourceValue(SH.sourceConstraintComponent)), prefixModel);

            // details can be multiple result resources
            String details = compact(getAllResourceValues(result, SH.detail), prefixModel);

            // ---- fields from the SOURCE SHAPE (not from the result) ----
            String description = "";
            String order = "";
            String name = "";
            String group = "";

            if (sourceShapeRes != null && shapesModel != null) {
                // important: read from shapes model to ensure we see all shape metadata/prefixes
                Resource shape = shapesModel.getResource(sourceShapeRes.isAnon()
                        ? sourceShapeRes.getId().toString()
                        : sourceShapeRes.getURI());

                // If the exact resource instance isn't found like above (blank nodes / different model identity),
                // fall back to using the result's sourceShapeRes directly:
                if (shape == null) shape = sourceShapeRes;

                description = getAllLiteralValues(shape, SH.description);
                order = getStatementValue(shape.getProperty(SH.order));
                name = getAllLiteralValues(shape, SH.name);

                // sh:group is usually a resource
                group = compact(getResourceValue(shape.getPropertyResourceValue(SH.group)), prefixModel);
            } else if (sourceShapeRes != null) {
                // still try from the resource we have
                description = getAllLiteralValues(sourceShapeRes, SH.description);
                order = getStatementValue(sourceShapeRes.getProperty(SH.order));
                name = getAllLiteralValues(sourceShapeRes, SH.name);
                group = compact(getResourceValue(sourceShapeRes.getPropertyResourceValue(SH.group)), prefixModel);
            }

            resultsList.add(new SHACLValidationResult(
                    sourceShape, focusNode, severity, message, value, path,
                    constraintComponent, details,
                    description, order, name, group
            ));
        }

        return resultsList;
    }

    // ---------------- helpers ----------------

    private static String getResourceValue(Resource resource) {
        if (resource == null) return "";
        return resource.isAnon() ? resource.getId().toString() : resource.toString();
    }

    private static String getStatementValue(Statement statement) {
        if (statement == null) return "";
        RDFNode o = statement.getObject();
        if (o == null) return "";
        if (o.isLiteral()) return o.asLiteral().getLexicalForm();
        if (o.isResource()) return getResourceValue(o.asResource());
        return o.toString();
    }

    private static String getAllLiteralValues(Resource subject, Property p) {
        if (subject == null) return "";
        StringBuilder sb = new StringBuilder();
        StmtIterator it = subject.listProperties(p);
        while (it.hasNext()) {
            RDFNode o = it.next().getObject();
            if (o == null) continue;
            if (sb.length() > 0) sb.append(" | ");
            if (o.isLiteral()) sb.append(o.asLiteral().getString());
            else sb.append(o.toString());
        }
        return sb.toString();
    }

    private static String getAllResourceValues(Resource subject, Property p) {
        if (subject == null) return "";
        StringBuilder sb = new StringBuilder();
        StmtIterator it = subject.listProperties(p);
        while (it.hasNext()) {
            RDFNode o = it.next().getObject();
            if (o == null) continue;
            if (sb.length() > 0) sb.append(" | ");
            if (o.isResource()) sb.append(getResourceValue(o.asResource()));
            else sb.append(o.toString());
        }
        return sb.toString();
    }

    /**
     * Converts full URIs to prefix form if prefix exists in prefixModel.
     * Fallback: last segment after '#' or '/'.
     */
    private static String compact(String s, Model prefixModel) {
        if (s == null || s.isBlank()) return "";
        if (prefixModel != null && (s.startsWith("http://") || s.startsWith("https://"))) {
            String qn = prefixModel.qnameFor(s);
            if (qn != null) return qn;
            int hash = s.lastIndexOf('#');
            int slash = s.lastIndexOf('/');
            int cut = Math.max(hash, slash);
            if (cut >= 0 && cut + 1 < s.length()) return s.substring(cut + 1);
        }
        return s;
    }
}