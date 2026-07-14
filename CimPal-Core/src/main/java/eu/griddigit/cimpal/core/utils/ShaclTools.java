package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.*;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.topbraid.shacl.vocabulary.SH;

import java.util.*;

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
            RDFNode resultPathNode = getStatementObject(result.getProperty(SH.resultPath));

            String path = renderResultPath(
                    resultPathNode,
                    sourceShapeRes,
                    shapesModel,
                    reportModel,
                    prefixModel
            );
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
                Resource shape = findEquivalentResourceInModel(shapesModel, sourceShapeRes);

                if (shape == null) {
                    shape = sourceShapeRes;
                }

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

    private static final String SH_NS = "http://www.w3.org/ns/shacl#";

    private static final Property SH_INVERSE_PATH =
            ResourceFactory.createProperty(SH_NS + "inversePath");

    private static final Property SH_ZERO_OR_MORE_PATH =
            ResourceFactory.createProperty(SH_NS + "zeroOrMorePath");

    private static final Property SH_ONE_OR_MORE_PATH =
            ResourceFactory.createProperty(SH_NS + "oneOrMorePath");

    private static final Property SH_ZERO_OR_ONE_PATH =
            ResourceFactory.createProperty(SH_NS + "zeroOrOnePath");

    private static String renderShaclPath(Model shapesModel, RDFNode pathNode) {
        return renderShaclPath(shapesModel, pathNode, new HashSet<>(), shapesModel);
    }

    private static String renderShaclPath(Model shapesModel,
                                          RDFNode pathNode,
                                          Set<Resource> visited,
                                          Model prefixModel) {
        if (pathNode == null) {
            return "";
        }

        if (pathNode.isLiteral()) {
            return pathNode.asLiteral().getLexicalForm();
        }

        if (!pathNode.isResource()) {
            return pathNode.toString();
        }

        Resource r = pathNode.asResource();

        if (!visited.add(r)) {
            return shortResourceName(r, prefixModel);
        }

        Statement inverse = shapesModel == null ? null : shapesModel.getProperty(r, SH_INVERSE_PATH);
        if (inverse != null) {
            return "[ sh:inversePath " + renderShaclPath(shapesModel, inverse.getObject(), visited, prefixModel) + " ]";
        }

        Statement zeroOrMore = shapesModel == null ? null : shapesModel.getProperty(r, SH_ZERO_OR_MORE_PATH);
        if (zeroOrMore != null) {
            return "[ sh:zeroOrMorePath " + renderShaclPath(shapesModel, zeroOrMore.getObject(), visited, prefixModel) + " ]";
        }

        Statement oneOrMore = shapesModel == null ? null : shapesModel.getProperty(r, SH_ONE_OR_MORE_PATH);
        if (oneOrMore != null) {
            return "[ sh:oneOrMorePath " + renderShaclPath(shapesModel, oneOrMore.getObject(), visited, prefixModel) + " ]";
        }

        Statement zeroOrOne = shapesModel == null ? null : shapesModel.getProperty(r, SH_ZERO_OR_ONE_PATH);
        if (zeroOrOne != null) {
            return "[ sh:zeroOrOnePath " + renderShaclPath(shapesModel, zeroOrOne.getObject(), visited, prefixModel) + " ]";
        }

        return shortResourceName(r, prefixModel);
    }

    private static String shortResourceName(Resource r, Model prefixModel) {
        if (r == null) {
            return "";
        }

        if (r.isAnon()) {
            return r.toString();
        }

        String uri = r.getURI();

        if (uri == null || uri.isBlank()) {
            return r.toString();
        }

        if (prefixModel != null) {
            String qname = prefixModel.qnameFor(uri);
            if (qname != null && !qname.isBlank()) {
                return qname;
            }
        }

        String ns = r.getNameSpace();
        String local = r.getLocalName();

        if (ns != null && local != null) {
            if (ns.toLowerCase(Locale.ROOT).contains("cim")) {
                return "cim:" + local;
            }

            if (ns.toLowerCase(Locale.ROOT).contains("shacl")) {
                return "sh:" + local;
            }
        }

        int hash = uri.lastIndexOf('#');
        int slash = uri.lastIndexOf('/');
        int idx = Math.max(hash, slash);

        return idx >= 0 && idx + 1 < uri.length()
                ? uri.substring(idx + 1)
                : uri;
    }

    private static String resolvePathFromSourceShape(Model shapesModel,
                                                     Resource sourceShapeRes,
                                                     Model prefixModel) {
        if (shapesModel == null || sourceShapeRes == null) {
            return "";
        }

        Property shPath = ResourceFactory.createProperty(SH_NS + "path");

        Resource sourceInShapesModel = findEquivalentResourceInModel(shapesModel, sourceShapeRes);

        Statement st = null;

        if (sourceInShapesModel != null) {
            st = shapesModel.getProperty(sourceInShapesModel, shPath);
        }

        if (st == null) {
            st = shapesModel.getProperty(sourceShapeRes, shPath);
        }

        if (st == null) {
            return "";
        }

        return renderShaclPath(shapesModel, st.getObject(), new HashSet<>(), prefixModel);
    }

    private static Resource findEquivalentResourceInModel(Model model, Resource resource) {
        if (model == null || resource == null) {
            return null;
        }

        if (resource.isURIResource()) {
            return model.getResource(resource.getURI());
        }

        if (resource.isAnon()) {
            return model.createResource(resource.getId());
        }

        return resource;
    }

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

    //add a PropertyGroup to a shape model including all necessary properties
    public static Model addPropertyGroup(Model shapeModel, String nsURIprofile, String localName, ArrayList<Object> groupFeatures){
        /*shapeModel - is the shape model
         * nsURIprofile - is the namespace of the PropertyGroup
         * localName - is the name of the PropertyGroup
         * rdfURI - is the URI of the rdf
         * shaclURI - is the URI of the shacl
         * groupFeatures / is a structure with the details
         * rdfsURI - is the URI of the rdfs
         */
        /*
         * groupFeatures structure
         * 0 - name
         * 1 - description
         * 2 - the value for rdfs:label
         * 3 - order
         */

        //creates the resource
        Resource r = shapeModel.createResource(nsURIprofile + localName);
        //creates the property
        //Property p = shapeModel.createProperty(rdfURI, "type");
        //creates the object
        //RDFNode o = shapeModel.createResource(shaclURI + "PropertyGroup");
        //adds the property
        r.addProperty(RDF.type, SH.PropertyGroup);
        //up to here this defines e.g. eqbd:MyGroup a sh:PropertyGroup ;

        //Property p1 = shapeModel.createProperty(shaclURI, "order");
        RDFNode o1 = shapeModel.createTypedLiteral(groupFeatures.get(3), "http://www.w3.org/2001/XMLSchema#integer");
        r.addProperty(SH.order, o1);

        //Property p2 = shapeModel.createProperty(shaclURI, "name");
        //r.addProperty(SH.name, groupFeatures.get(0).toString()); // this is ok if the group needs to be a name

        //Property p3 = shapeModel.createProperty(shaclURI, "description");
        //r.addProperty(SH.description, groupFeatures.get(1).toString()); // this is ok if the group needs to be a description

        //Property p4 = shapeModel.createProperty(rdfsURI, "label");
        //creates the object which is the CIM class
        r.addProperty(RDFS.label, groupFeatures.get(2).toString());

        //RDFDataMgr.write(System.out, shapeModel, RDFFormat.TURTLE);
        return shapeModel;
    }

    //add a NodeShape to a shape model including all necessary properties
    public static Model addNodeShape(Model shapeModel, String nsURIprofile, String localName, String classFullURI){
        /*shapeModel - is the shape model
         * nsURIprofile - is the namespace of the NodeShape
         * localName - is the name of the NodeShape
         * classFullURI - is the full URI of the CIM class
         */

        //creates the resource
        Resource r = shapeModel.createResource(nsURIprofile + localName);
        //creates the property
        //Property p = shapeModel.createProperty(rdfURI, "type");
        //creates the object
        //RDFNode o = shapeModel.createResource(shaclURI + "NodeShape");
        //adds the property
        r.addProperty(RDF.type, SH.NodeShape);
        //up to here this defines e.g. eqbd:GeographicalRegion a sh:NodeShape ;

        //creates property sh:targetClass
        //Property p1 = shapeModel.createProperty(shaclURI, "targetClass");
        //creates the object which is the CIM class
        RDFNode o1 = shapeModel.createResource(classFullURI);
        r.addProperty(SH.targetClass, o1);
        //up to here this defines e.g. sh:targetClass cim:GeographicalRegion ;

        return shapeModel;
    }

    private static String renderResultPath(RDFNode resultPathNode,
                                           Resource sourceShapeRes,
                                           Model shapesModel,
                                           Model reportModel,
                                           Model prefixModel) {
        String path = "";

        if (resultPathNode != null) {
            path = renderShaclPathSafe(shapesModel, resultPathNode, prefixModel);

            if (isGeneratedOrUnreadablePath(path)) {
                path = renderShaclPathSafe(reportModel, resultPathNode, prefixModel);
            }
        }

        if (isGeneratedOrUnreadablePath(path) || path.isBlank()) {
            String fallback = resolvePathFromSourceShape(shapesModel, sourceShapeRes, prefixModel);

            if (!fallback.isBlank()) {
                path = fallback;
            }
        }

        if (path.isBlank() && resultPathNode != null) {
            path = compact(getNodeValue(resultPathNode), prefixModel);
        }

        return path;
    }

    private static String renderShaclPathSafe(Model model, RDFNode pathNode, Model prefixModel) {
        if (pathNode == null) {
            return "";
        }

        if (model == null) {
            return compact(getNodeValue(pathNode), prefixModel);
        }

        try {
            return renderShaclPath(model, pathNode, new HashSet<>(), prefixModel);
        } catch (Exception ex) {
            return compact(getNodeValue(pathNode), prefixModel);
        }
    }

    private static boolean isGeneratedOrUnreadablePath(String path) {
        String s = path == null ? "" : path.trim().toLowerCase(Locale.ROOT);

        return s.isBlank()
                || s.startsWith("urn:uuid:")
                || s.contains("urn:x-arq:")
                || s.contains("genid")
                || s.startsWith("_:")
                || s.matches("^[a-f0-9\\-]{20,}$");
    }

    private static RDFNode getStatementObject(Statement statement) {
        return statement == null ? null : statement.getObject();
    }

    private static String getNodeValue(RDFNode node) {
        if (node == null) {
            return "";
        }

        if (node.isLiteral()) {
            return node.asLiteral().getLexicalForm();
        }

        if (node.isResource()) {
            return getResourceValue(node.asResource());
        }

        return node.toString();
    }
}
