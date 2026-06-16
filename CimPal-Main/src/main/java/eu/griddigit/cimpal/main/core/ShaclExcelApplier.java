package eu.griddigit.cimpal.main.core;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.topbraid.shacl.vocabulary.SH;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ShaclExcelApplier {

    private ShaclExcelApplier() {}

    /**
     * Applies rows of the form:
     *   [0]=Group, [1]=Name, [2]=Property, [3]=Value
     * for r = startRow .. end, with exact, case-sensitive matching.
     *
     * Supported Property values (exact): "severity", "message", "description".
     * - severity values accepted: "VIOLATION","WARNING","INFO" (also "ERROR" -> Violation)
     * - message: written on first sh:sparql node if present, else on shape
     * - description/severity: written on the shape
     *
     * @param model     Jena model (mutated in place and returned)
     * @param excelRows list of rows; each row is a List<?> of cells
     * @param startRow  first row index to process (0 if no header)
     */
    @SuppressWarnings("unchecked")
    public static Model applyPropsFromExcelSimple(Model model, List<?> excelRows, int startRow) {
        if (excelRows == null) return model;

        // severity mapping (exact, case-sensitive keys as you have them)
        Map<String, Resource> sevMap = new HashMap<>();
        sevMap.put("VIOLATION", SH.Violation);
        sevMap.put("WARNING",   SH.Warning);
        sevMap.put("INFO",      SH.Info);
        sevMap.put("ERROR",     SH.Violation); // optional

        // Iterate all sh:PropertyShape
        StmtIterator it = model.listStatements(null, RDF.type, SH.PropertyShape);
        while (it.hasNext()) {
            Resource ps = it.nextStatement().getSubject();

            // shape name (exact match)
            String shapeName = firstLiteral(model, ps, SH.name);
            if (shapeName == null) continue;

            // group representations (for matching) + a single label for logging
            Resource grp = firstResource(model, ps, SH.group);
            String groupQName = qname(model, grp);
            String groupLocal = grp != null ? nz(grp.getLocalName()) : "";
            String groupURI   = grp != null ? nz(grp.getURI())       : "";
            String logGroup   = !groupQName.isEmpty() ? groupQName : (!groupURI.isEmpty() ? groupURI : groupLocal);

            // scan Excel rows
            for (int r = startRow; r < excelRows.size(); r++) {
                Object rowObj = excelRows.get(r);
                if (!(rowObj instanceof List)) continue;
                List<?> row = (List<?>) rowObj;

                String group = getCell(row, 0);   // Group (used for contains-match)
                String name  = getCell(row, 1);   // Name (sh:name, exact match)
                String prop  = getCell(row, 2);   // Property Type (severity|message|description)
                String value = getCell(row, 3);   // Value (header says "Severity", but used for any prop)


                if (name == null || prop == null || value == null || group == null) continue;

                // Name must equal (case-sensitive)
                if (!shapeName.equals(name)) continue;

                // Group contains-match (case-sensitive), any representation
                boolean groupMatches =
                        (!groupQName.isEmpty() && groupQName.contains(group)) ||
                                (!groupLocal.isEmpty() && groupLocal.contains(group)) ||
                                (!groupURI.isEmpty()   && groupURI.contains(group));
                if (!groupMatches) continue;

                // Apply property ONLY if it changes something
                switch (prop) {
                    case "severity": {
                        Resource sev = sevMap.get(value);
                        if (sev == null) continue; // unknown -> skip
                        Resource currentSev = firstResource(model, ps, SH.severity);
                        boolean changed = (currentSev == null) || !currentSev.equals(sev);
                        if (changed) {
                            model.removeAll(ps, SH.severity, null);
                            model.add(ps, SH.severity, sev);
                            System.out.println("[APPLIED] name=" + shapeName + " group=" + logGroup + " prop=severity");
                        }
                        break;
                    }
                    case "message": {
                        // target = first sh:sparql node if present, else the shape
                        Resource msgTarget = null;
                        StmtIterator spq = model.listStatements(ps, SH.sparql, (RDFNode) null);
                        if (spq.hasNext()) {
                            RDFNode n = spq.nextStatement().getObject();
                            if (n.isResource()) msgTarget = n.asResource();
                        }
                        if (msgTarget == null) msgTarget = ps;

                        String oldMsg = firstLiteral(model, msgTarget, SH.message);
                        boolean changed = (oldMsg == null) || !oldMsg.equals(value);
                        if (changed) {
                            model.removeAll(msgTarget, SH.message, null);
                            model.add(msgTarget, SH.message, value);
                            System.out.println("[APPLIED] name=" + shapeName + " group=" + logGroup + " prop=message");
                        }
                        break;
                    }
                    case "description": {
                        String oldDesc = firstLiteral(model, ps, SH.description);
                        boolean changed = (oldDesc == null) || !oldDesc.equals(value);
                        if (changed) {
                            model.removeAll(ps, SH.description, null);
                            model.add(ps, SH.description, value);
                            System.out.println("[APPLIED] name=" + shapeName + " group=" + logGroup + " prop=description");
                        }
                        break;
                    }
                    default:
                        // ignore others
                        break;
                }
            }
        }
        return model;
    }


    /* ==== tiny helpers: no normalization, no case changes ==== */

    private static String getCell(List<?> row, int idx) {
        if (idx < 0 || idx >= row.size()) return null;
        Object v = row.get(idx);
        return v == null ? null : String.valueOf(v); // no trim, no lowercase
    }

    private static String firstLiteral(Model m, Resource s, Property p) {
        StmtIterator it = m.listStatements(s, p, (RDFNode) null);
        try {
            while (it.hasNext()) {
                RDFNode o = it.next().getObject();
                if (o.isLiteral()) return o.asLiteral().getString();
            }
        } finally { it.close(); }
        return null;
    }

    private static Resource firstResource(Model m, Resource s, Property p) {
        StmtIterator it = m.listStatements(s, p, (RDFNode) null);
        try {
            while (it.hasNext()) {
                RDFNode o = it.next().getObject();
                if (o.isResource()) return o.asResource();
            }
        } finally { it.close(); }
        return null;
    }

    private static String qname(Model m, Resource r) {
        if (r == null || r.isAnon()) return "";
        String uri = r.getURI();
        if (uri == null) return "";
        String q = m.qnameFor(uri);
        return q != null ? q : uri;
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
