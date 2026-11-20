/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.shacl_tools;

import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFList;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.topbraid.shacl.vocabulary.SH;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Finds the shapes of a base constraint set (for example the CGMES v3 constraints of a profile)
 * that a configuration should deactivate because an override constraint set (for example a
 * national or project extension of them) replaces them.
 * <p>
 * The configuration imports both sets and switches the replaced base shapes off with
 * {@code sh:deactivated true}, which only works while the two sets use different IRIs. A base shape
 * is suggested for deactivation when
 * <ul>
 *   <li>an override shape has the same rule name: the part of {@code sh:name} after the standard and
 *       profile, so {@code C:301:EQ:Equipment.aggregate:notUsed} and
 *       {@code C:EXT:EQ:Equipment.aggregate:notUsed} are the same rule;</li>
 *   <li>an override shape constrains the same {@code sh:path} of an overlapping target class with the
 *       same kind of constraint parameter (both an {@code sh:minInclusive}, say);</li>
 *   <li>an optional reference file, a trimmed copy of a base file, no longer has the shape or has
 *       changed it. Several reference files can be given, one per base file; each judges only the
 *       base shapes in the namespaces it declares shapes in.</li>
 * </ul>
 * A base shape on the same element with a different kind of rule (a {@code valueRange} beside a
 * {@code notUsed}) is listed as related but not suggested. These are suggestions for a person to
 * confirm; the planner never decides alone.
 */
public final class ShapeDeactivationPlanner {

    /** Why a base shape is listed. */
    public enum MatchKind {
        SAME_RULE("Same rule", true),
        SAME_PATH("Same path and constraint", true),
        NOT_IN_REFERENCE("Not in reference", true),
        CHANGED_IN_REFERENCE("Changed in reference", true),
        LISTS_CHANGED_IN_REFERENCE("Lists changed in reference", false),
        RELATED("Related rule", false),
        SAME_IRI("Same IRI in override", false);

        private final String label;
        private final boolean suggests;

        MatchKind(String label, boolean suggests) {
            this.label = label;
            this.suggests = suggests;
        }

        public String label() {
            return label;
        }

        /** Whether this kind of match ticks the shape for deactivation by itself. */
        public boolean suggests() {
            return suggests;
        }
    }

    /** A constraint file read into a model; {@code label} names it in the results (usually the file name). */
    public record SourceModel(String label, Model model) {
        public SourceModel {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(model, "model");
        }
    }

    /**
     * One shape of a constraint set.
     *
     * @param iri        the shape's IRI
     * @param source     label of the file that declares it
     * @param types      its {@code rdf:type}s among {@code sh:NodeShape} and {@code sh:PropertyShape}
     *                   (inferred from {@code sh:path} when it declares none)
     * @param name       its {@code sh:name}, or empty
     * @param path       its {@code sh:path} when that is a single property, else empty
     * @param targets    the classes it applies to: its own {@code sh:targetClass} and those of the node
     *                   shapes that reach it through {@code sh:property}
     * @param parameters its constraint parameters, predicate IRI to sorted values
     * @param description its {@code sh:description}, or empty
     */
    public record ShapeInfo(String iri, String source, List<String> types, String name, String path,
                            Set<String> targets, Map<String, List<String>> parameters, String description) {
    }

    /** One reason a base shape is listed, with the override shape that caused it when there is one. */
    public record Match(MatchKind kind, String detail, String overrideIri, String overrideSource) {
    }

    /**
     * A base shape listed for review.
     *
     * @param shape     the base shape
     * @param matches   why it is listed, strongest first
     * @param suggested whether it starts ticked
     */
    public record Candidate(ShapeInfo shape, List<Match> matches, boolean suggested) {

        /** The reasons as one line, for a table cell or a Turtle comment. */
        public String reasons() {
            List<String> parts = new ArrayList<>();
            for (Match match : matches) {
                parts.add(match.kind().label() + ": " + match.detail());
            }
            return String.join("; ", parts);
        }
    }

    /**
     * The result: the listed candidates, and every shape of the base set so that shapes the
     * planner did not list can be added by hand.
     */
    public record Plan(List<Candidate> candidates, List<ShapeInfo> baseShapes, List<String> warnings) {
    }

    /** Annotations and structure: they change what a shape says or reaches, not what it checks. */
    private static final Set<String> NON_PARAMETERS = Set.of(
            RDF.type.getURI(), RDFS.label.getURI(), RDFS.comment.getURI(),
            SH.name.getURI(), SH.description.getURI(), SH.message.getURI(), SH.group.getURI(),
            SH.order.getURI(), SH.severity.getURI(), SH.path.getURI(), SH.property.getURI(),
            SH.targetClass.getURI(), SH.targetNode.getURI(), SH.targetSubjectsOf.getURI(),
            SH.targetObjectsOf.getURI(), SH.deactivated.getURI());

    /** What may differ in a node shape of the reference without the node shape itself being replaced. */
    private static final Set<String> LIST_PREDICATES = Set.of(
            SH.property.getURI(), SH.targetClass.getURI(), SH.targetNode.getURI(),
            SH.targetSubjectsOf.getURI(), SH.targetObjectsOf.getURI());

    private ShapeDeactivationPlanner() {
    }

    /**
     * Lists the base shapes to review.
     *
     * @param base      the constraint files the configuration imports and partly deactivates
     * @param overrides the constraint files that replace part of them
     * @param references optional trimmed copies of the base files: shapes they dropped or changed
     *                   are suggested too; empty for none. A base shape is compared only with the
     *                   references that declare shapes in its namespace.
     */
    public static Plan plan(List<SourceModel> base, List<SourceModel> overrides, List<SourceModel> references) {
        List<String> warnings = new ArrayList<>();
        List<ShapeInfo> baseShapes = new ArrayList<>();
        Map<String, ShapeInfo> seen = new LinkedHashMap<>();
        for (SourceModel source : base) {
            for (ShapeInfo shape : shapesOf(source)) {
                ShapeInfo previous = seen.putIfAbsent(shape.iri(), shape);
                if (previous == null) {
                    baseShapes.add(shape);
                } else {
                    warnings.add(shape.iri() + " is declared in both " + previous.source() + " and "
                            + shape.source() + "; listed once.");
                }
            }
        }

        List<ShapeInfo> overrideShapes = new ArrayList<>();
        Set<String> overrideSubjects = new HashSet<>();
        for (SourceModel source : overrides) {
            overrideShapes.addAll(shapesOf(source));
            source.model().listSubjects().forEachRemaining(subject -> {
                if (subject.isURIResource()) {
                    overrideSubjects.add(subject.getURI());
                }
            });
        }
        Map<String, List<ShapeInfo>> overridesByRule = new LinkedHashMap<>();
        Map<String, List<ShapeInfo>> overridesByElement = new LinkedHashMap<>();
        Map<String, List<ShapeInfo>> overridesByPath = new LinkedHashMap<>();
        for (ShapeInfo shape : overrideShapes) {
            RuleName rule = RuleName.parse(shape.name());
            if (rule != null) {
                overridesByRule.computeIfAbsent(rule.key(), k -> new ArrayList<>()).add(shape);
                overridesByElement.computeIfAbsent(rule.element(), k -> new ArrayList<>()).add(shape);
            }
            if (!shape.path().isEmpty()) {
                overridesByPath.computeIfAbsent(shape.path(), k -> new ArrayList<>()).add(shape);
            }
        }

        // A reference trims one base file; a base shape is compared only with the references that
        // cover its namespace, so a base file without a reference is not reported as dropped wholesale.
        Map<String, List<SourceModel>> referencesByNamespace = new LinkedHashMap<>();
        for (SourceModel reference : references == null ? List.<SourceModel>of() : references) {
            Set<String> namespaces = new LinkedHashSet<>();
            shapesOf(reference).forEach(shape -> namespaces.add(namespace(shape.iri())));
            if (namespaces.isEmpty()) {
                warnings.add("The reference file " + reference.label() + " declares no shapes; it is ignored.");
            }
            namespaces.forEach(ns -> referencesByNamespace.computeIfAbsent(ns, k -> new ArrayList<>()).add(reference));
        }
        if (!referencesByNamespace.isEmpty()) {
            Set<String> uncovered = new LinkedHashSet<>();
            baseShapes.forEach(shape -> {
                if (!referencesByNamespace.containsKey(namespace(shape.iri()))) {
                    uncovered.add(shape.source());
                }
            });
            if (!uncovered.isEmpty()) {
                warnings.add("No reference file has shapes in the namespace of some shapes of "
                        + String.join(", ", uncovered) + "; those shapes are not compared with a reference.");
            }
        }

        List<Candidate> candidates = new ArrayList<>();
        for (ShapeInfo shape : baseShapes) {
            List<Match> matches = new ArrayList<>();
            RuleName rule = RuleName.parse(shape.name());
            Set<String> sameRule = new HashSet<>();
            if (rule != null) {
                for (ShapeInfo other : overridesByRule.getOrDefault(rule.key(), List.of())) {
                    sameRule.add(other.iri());
                    matches.add(new Match(MatchKind.SAME_RULE, other.name(), other.iri(), other.source()));
                }
            }
            if (!shape.path().isEmpty() && !shape.path().equals(RDF.type.getURI())) {
                for (ShapeInfo other : overridesByPath.getOrDefault(shape.path(), List.of())) {
                    if (sameRule.contains(other.iri()) || !overlaps(shape.targets(), other.targets())) {
                        continue;
                    }
                    String detail = sharedParameterDetail(shape, other);
                    if (detail != null) {
                        matches.add(new Match(MatchKind.SAME_PATH, detail, other.iri(), other.source()));
                    }
                }
            }
            List<SourceModel> covering = referencesByNamespace.get(namespace(shape.iri()));
            if (covering != null) {
                Match referenceMatch = compareWithReference(shape, covering, base);
                if (referenceMatch != null) {
                    matches.add(referenceMatch);
                }
            }
            if (rule != null && sameRule.isEmpty()) {
                for (ShapeInfo other : overridesByElement.getOrDefault(rule.element(), List.of())) {
                    matches.add(new Match(MatchKind.RELATED, other.name(), other.iri(), other.source()));
                }
            }
            boolean collision = overrideSubjects.contains(shape.iri());
            if (collision) {
                matches.add(new Match(MatchKind.SAME_IRI,
                        "an override file declares this IRI too, so deactivating it would switch off the override as well",
                        shape.iri(), ""));
            }
            if (matches.isEmpty()) {
                continue;
            }
            matches.sort(Comparator.comparingInt(match -> match.kind().ordinal()));
            boolean suggested = !collision && matches.stream().anyMatch(match -> match.kind().suggests());
            candidates.add(new Candidate(shape, List.copyOf(matches), suggested));
        }
        return new Plan(List.copyOf(candidates), List.copyOf(baseShapes), List.copyOf(warnings));
    }

    /**
     * The shapes a file declares, sorted by IRI: IRIs typed {@code sh:NodeShape} or
     * {@code sh:PropertyShape}, IRIs with an {@code sh:path}, and IRIs another shape reaches
     * through {@code sh:property} or {@code sh:node} that the file describes. SPARQL constraints
     * are not shapes: they are switched off through the shapes that use them.
     */
    public static List<ShapeInfo> shapesOf(SourceModel source) {
        Model model = source.model();
        Set<Resource> shapes = new LinkedHashSet<>();
        model.listSubjectsWithProperty(RDF.type, SH.NodeShape).forEachRemaining(shapes::add);
        model.listSubjectsWithProperty(RDF.type, SH.PropertyShape).forEachRemaining(shapes::add);
        model.listSubjectsWithProperty(SH.path).forEachRemaining(shapes::add);
        for (var predicate : List.of(SH.property, SH.node)) {
            model.listObjectsOfProperty(predicate).forEachRemaining(node -> {
                if (node.isURIResource() && model.listStatements(node.asResource(), null, (RDFNode) null).hasNext()) {
                    shapes.add(node.asResource());
                }
            });
        }

        List<ShapeInfo> result = new ArrayList<>();
        for (Resource shape : shapes) {
            if (!shape.isURIResource() || shape.hasProperty(RDF.type, SH.SPARQLConstraint)
                    || shape.hasProperty(RDF.type, SH.PropertyGroup) || shape.hasProperty(RDF.type, OWL2.Ontology)) {
                continue;
            }
            result.add(describe(model, shape, source.label()));
        }
        result.sort(Comparator.comparing(ShapeInfo::iri));
        return result;
    }

    private static ShapeInfo describe(Model model, Resource shape, String source) {
        List<String> types = new ArrayList<>();
        if (shape.hasProperty(RDF.type, SH.NodeShape)) {
            types.add(SH.NodeShape.getURI());
        }
        if (shape.hasProperty(RDF.type, SH.PropertyShape)) {
            types.add(SH.PropertyShape.getURI());
        }
        if (types.isEmpty()) {
            types.add(shape.hasProperty(SH.path) ? SH.PropertyShape.getURI() : SH.NodeShape.getURI());
        }

        Statement pathStatement = shape.getProperty(SH.path);
        String path = pathStatement != null && pathStatement.getObject().isURIResource()
                ? pathStatement.getObject().asResource().getURI() : "";

        Set<String> targets = new TreeSet<>();
        addClasses(shape, targets);
        model.listSubjectsWithProperty(SH.property, shape).forEachRemaining(owner -> addClasses(owner, targets));

        Map<String, List<String>> parameters = new TreeMap<>();
        shape.listProperties().forEachRemaining(statement -> {
            String predicate = statement.getPredicate().getURI();
            if (!NON_PARAMETERS.contains(predicate)) {
                parameters.computeIfAbsent(predicate, k -> new ArrayList<>()).add(render(statement.getObject()));
            }
        });
        parameters.values().forEach(values -> values.sort(null));

        return new ShapeInfo(shape.getURI(), source, List.copyOf(types), literal(shape, SH.name), path,
                Set.copyOf(targets), Map.copyOf(parameters), literal(shape, SH.description));
    }

    private static void addClasses(Resource shape, Set<String> targets) {
        shape.listProperties(SH.targetClass).forEachRemaining(statement -> {
            if (statement.getObject().isURIResource()) {
                targets.add(statement.getObject().asResource().getURI());
            }
        });
    }

    private static String literal(Resource shape, org.apache.jena.rdf.model.Property property) {
        Statement statement = shape.getProperty(property);
        if (statement == null || !statement.getObject().isLiteral()) {
            return "";
        }
        return statement.getObject().asLiteral().getLexicalForm().trim();
    }

    /** A value for comparison: IRIs as themselves, literals by value, lists and blank nodes by content. */
    private static String render(RDFNode node) {
        if (node.isURIResource()) {
            return node.asResource().getURI();
        }
        if (node.isLiteral()) {
            Literal literal = node.asLiteral();
            Object value = literal.getValue();
            return value instanceof Number number ? Double.toString(number.doubleValue()) : literal.getLexicalForm();
        }
        Resource blank = node.asResource();
        if (blank.canAs(RDFList.class) && (blank.hasProperty(RDF.first) || blank.equals(RDF.nil))) {
            List<String> items = new ArrayList<>();
            blank.as(RDFList.class).iterator().forEachRemaining(item -> items.add(render(item)));
            return items.toString();
        }
        Map<String, List<String>> content = new TreeMap<>();
        blank.listProperties().forEachRemaining(statement -> content
                .computeIfAbsent(statement.getPredicate().getURI(), k -> new ArrayList<>())
                .add(render(statement.getObject())));
        content.values().forEach(values -> values.sort(null));
        return content.toString();
    }

    private static boolean overlaps(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        for (String value : a) {
            if (b.contains(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Describes the constraint parameters two shapes on the same path share, or {@code null} when
     * they share none: two shapes checking different things on one property do not replace each other.
     */
    private static String sharedParameterDetail(ShapeInfo shape, ShapeInfo other) {
        List<String> same = new ArrayList<>();
        List<String> different = new ArrayList<>();
        for (Map.Entry<String, List<String>> parameter : shape.parameters().entrySet()) {
            List<String> otherValues = other.parameters().get(parameter.getKey());
            if (otherValues == null) {
                continue;
            }
            String name = localName(parameter.getKey());
            if (otherValues.equals(parameter.getValue())) {
                same.add(name);
            } else {
                different.add(name);
            }
        }
        if (same.isEmpty() && different.isEmpty()) {
            return null;
        }
        String overrideName = other.name().isEmpty() ? localName(other.iri()) : other.name();
        StringBuilder detail = new StringBuilder(overrideName).append(" (");
        if (!same.isEmpty()) {
            detail.append("same ").append(String.join(", ", same));
        }
        if (!different.isEmpty()) {
            detail.append(same.isEmpty() ? "" : "; ").append("different ").append(String.join(", ", different));
        }
        return detail.append(')').toString();
    }

    /** Compares a base shape with the first of the covering references that declares it. */
    private static Match compareWithReference(ShapeInfo shape, List<SourceModel> covering, List<SourceModel> base) {
        Resource inReference = null;
        for (SourceModel reference : covering) {
            Resource candidate = reference.model().createResource(shape.iri());
            if (reference.model().listStatements(candidate, null, (RDFNode) null).hasNext()) {
                inReference = candidate;
                break;
            }
        }
        if (inReference == null) {
            String labels = String.join(", ", covering.stream().map(SourceModel::label).toList());
            return new Match(MatchKind.NOT_IN_REFERENCE, labels + (covering.size() == 1 ? " does" : " do")
                    + " not declare it", null, null);
        }
        Model baseModel = base.stream().filter(source -> source.label().equals(shape.source()))
                .findFirst().map(SourceModel::model).orElseThrow();
        Set<String> differing = differingPredicates(baseModel.createResource(shape.iri()), inReference);
        if (differing.isEmpty()) {
            return null;
        }
        List<String> names = differing.stream().map(ShapeDeactivationPlanner::localName).toList();
        if (shape.types().contains(SH.NodeShape.getURI()) && LIST_PREDICATES.containsAll(differing)) {
            return new Match(MatchKind.LISTS_CHANGED_IN_REFERENCE, "only " + String.join(", ", names)
                    + " changed; the property shapes it dropped are listed on their own", null, null);
        }
        return new Match(MatchKind.CHANGED_IN_REFERENCE, String.join(", ", names) + " changed", null, null);
    }

    /** The predicates whose values differ between two descriptions of a shape, blank nodes compared by content. */
    private static Set<String> differingPredicates(Resource a, Resource b) {
        Map<String, List<String>> left = values(a);
        Map<String, List<String>> right = values(b);
        Set<String> predicates = new TreeSet<>(left.keySet());
        predicates.addAll(right.keySet());
        predicates.removeIf(predicate -> Objects.equals(left.get(predicate), right.get(predicate)));
        return predicates;
    }

    private static Map<String, List<String>> values(Resource resource) {
        Map<String, List<String>> values = new TreeMap<>();
        resource.listProperties().forEachRemaining(statement -> values
                .computeIfAbsent(statement.getPredicate().getURI(), k -> new ArrayList<>())
                .add(render(statement.getObject())));
        values.values().forEach(list -> list.sort(null));
        return values;
    }

    /** The IRI up to and including its last {@code #} or {@code /}. */
    static String namespace(String iri) {
        int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return cut >= 0 ? iri.substring(0, cut + 1) : iri;
    }

    static String localName(String iri) {
        int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return cut >= 0 && cut < iri.length() - 1 ? iri.substring(cut + 1) : iri;
    }

    /**
     * An {@code sh:name} of the form {@code C:<standard>:<profile>:<element>:<kind>}, as the CGMES
     * constraint sets, and extensions that follow them, name their complex rules. Other names are
     * not rule names.
     */
    record RuleName(String element, String kind) {

        static RuleName parse(String name) {
            if (name == null) {
                return null;
            }
            String[] parts = name.trim().split(":");
            if (parts.length < 5 || !parts[0].equals("C")) {
                return null;
            }
            String element = parts[3].trim();
            String kind = String.join(":", java.util.Arrays.copyOfRange(parts, 4, parts.length)).trim();
            if (element.isEmpty() || kind.isEmpty()) {
                return null;
            }
            return new RuleName(element.toLowerCase(Locale.ROOT), kind.toLowerCase(Locale.ROOT));
        }

        String key() {
            return element + ":" + kind;
        }
    }
}
