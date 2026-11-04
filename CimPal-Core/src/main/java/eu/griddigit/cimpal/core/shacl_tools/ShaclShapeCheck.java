/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.shacl_tools;

import org.apache.jena.rdf.model.*;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.vocabulary.RDF;
import org.topbraid.shacl.vocabulary.SH;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Checks SHACL shape files against the SHACL-SHACL shapes (the W3C shapes that describe well-formed
 * SHACL), so a malformed shape is found before it silently never fires. GUI: Tools ▸ SHACL
 * Constraints ▸ Validate SHACL shapes; CLI: {@code shacl-check}.
 */
public final class ShaclShapeCheck {

    /** One problem in a shape file: the shape node, the property, the severity, message and value. */
    public record Finding(String focusNode, String path, String severity, String message, String value) {
    }

    /** The outcome for one file. */
    public record Result(String name, boolean conforms, List<Finding> findings) {
    }

    private ShaclShapeCheck() {
    }

    /**
     * Checks one shapes model.
     *
     * @param name      how the file is named in the result
     * @param shaclShacl the SHACL-SHACL shapes ({@code ModelFactory.LoadSHACLSHACL()}), loaded once for many files
     */
    public static Result check(String name, Model shapes, Model shaclShacl) {
        ValidationReport report = ShaclValidator.get().validate(shaclShacl.getGraph(), shapes.getGraph());
        if (report.conforms()) {
            return new Result(name, true, List.of());
        }
        Model reportModel = report.getModel();
        List<Finding> findings = new ArrayList<>();
        for (Resource r : reportModel.listResourcesWithProperty(RDF.type, SH.ValidationResult).toList()) {
            findings.add(new Finding(text(reportModel, r, SH.focusNode), text(reportModel, r, SH.resultPath),
                    text(reportModel, r, SH.resultSeverity), text(reportModel, r, SH.resultMessage),
                    text(reportModel, r, SH.value)));
        }
        findings.sort(Comparator.comparing(Finding::focusNode).thenComparing(Finding::path).thenComparing(Finding::message));
        return new Result(name, false, findings);
    }

    private static String text(Model model, Resource result, Property p) {
        Statement st = result.getProperty(p);
        if (st == null) return "";
        RDFNode n = st.getObject();
        if (n.isLiteral()) return n.asLiteral().getLexicalForm();
        if (n.isAnon()) return "_:" + n.asResource().getId().getLabelString();
        Resource r = n.asResource();
        String prefix = model.getNsURIPrefix(r.getNameSpace());
        return prefix != null ? prefix + ":" + r.getLocalName() : r.getURI();
    }
}
