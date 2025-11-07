package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.vocabulary.RDF;
import org.topbraid.shacl.vocabulary.SH;

import java.util.ArrayList;
import java.util.List;

public class ShaclTools {

    public static List<SHACLValidationResult> extractSHACLValidationResults(ValidationReport report) {
        List<SHACLValidationResult> resultsList = new ArrayList<>();
        Model reportModel = report.getModel();

        ResIterator results = reportModel.listResourcesWithProperty(RDF.type, SH.ValidationResult);

        while (results.hasNext()) {
            Resource result = results.next();

            String sourceShape = getStatementValue(result.getProperty(SH.sourceShape));
            String focusNode = getResourceValue(result.getPropertyResourceValue(SH.focusNode));
            String severity = getResourceValue(result.getPropertyResourceValue(SH.resultSeverity));
            String message = getStatementValue(result.getProperty(SH.resultMessage));
            String value = getStatementValue(result.getProperty(SH.value));
            String path = getStatementValue(result.getProperty(SH.resultPath));

            resultsList.add(new SHACLValidationResult(sourceShape, focusNode, severity, message, value, path));
        }

        return resultsList;
    }

    private static String getResourceValue(Resource resource) {
        return (resource != null) ? resource.toString() : "None";
    }

    private static String getStatementValue(Statement statement) {
        return (statement != null) ? statement.getObject().toString() : "None";
    }
}
