/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.shacl_tools;

import eu.griddigit.cimpal.core.utils.AtomicFiles;
import eu.griddigit.cimpal.core.utils.LogSanitizer;
import eu.griddigit.cimpal.core.utils.PathPolicy;
import eu.griddigit.cimpal.core.utils.ValidationTools;
import eu.griddigit.cimpal.core.utils.ValidationTools.ShapesClosure;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFWriter;
import org.apache.jena.riot.RDFWriterBuilder;
import org.apache.jena.riot.RIOT;
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.topbraid.shacl.vocabulary.SH;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Merges a deactivation configuration (see {@link ShapeDeactivationConfig}) into one constraint
 * file: the files it imports, read with their own {@code owl:imports}, without the shapes it
 * deactivates.
 * <p>
 * A deactivated shape is removed with its blank nodes and the SPARQL constraints only it used, and
 * {@code sh:property} references to it are dropped. A shape that is also reached another way
 * ({@code sh:node}, {@code sh:not}, a logical list, ...) is kept with {@code sh:deactivated true}
 * instead, which validates the same, and is reported. The {@code owl:imports} that were read are
 * dropped, since their files are now in the merged file; vocabulary imports such as {@code sh:} stay.
 * The source files' own headers (resources whose local name is {@code Ontology}, such as
 * {@code eq:Ontology}) and the configuration's are left out; the merged file gets one header of
 * its own ({@code merged:Ontology}), written first.
 * <p>
 * Files are read through {@link ValidationTools#loadShapesClosure}, so the same import resolution,
 * egress policy and failures apply as in validation.
 */
public final class ShapeConfigurationMerger {

    /** The prefix of the merged file's header namespace when none is given. */
    public static final String DEFAULT_HEADER_PREFIX = "merged";

    /** A resource with this local name is a source file's own header and is left out of the merge. */
    static final String ONTOLOGY_LOCAL_NAME = "Ontology";

    private static final Pattern PREFIX = Pattern.compile("[A-Za-z][A-Za-z0-9_\\-]*");

    /**
     * The merged file's ontology header and {@code @base}. The header is written first in the
     * file, under {@code prefix} (its namespace is the part of {@code ontologyIri} up to the last
     * {@code #} or {@code /}). A blank prefix means {@link #DEFAULT_HEADER_PREFIX}, a null
     * {@code issued} now, a null {@code identifier} a random {@code urn:uuid}, a blank description
     * one naming the files, and a blank {@code base} no {@code @base} line.
     *
     * @throws IllegalArgumentException when {@code base} is given but is not an absolute IRI, or the
     *                                  prefix is not a plain Turtle prefix
     */
    public record Header(String ontologyIri, String prefix, String title, String description, String version,
                         Instant issued, String identifier, String base) {
        public Header {
            prefix = prefix == null || prefix.isBlank() ? DEFAULT_HEADER_PREFIX : prefix.strip();
            if (!PREFIX.matcher(prefix).matches()) {
                throw new IllegalArgumentException("The header prefix must start with a letter and hold only "
                        + "letters, digits, '_' and '-'.");
            }
            base = base == null || base.isBlank() ? null : base.strip();
            if (base != null) {
                String problem = ShapeDeactivationConfig.importProblem(base);
                if (problem != null) {
                    throw new IllegalArgumentException("The @base " + problem + ".");
                }
            }
        }

        /** This header with another {@code @base} (blank for none). */
        public Header withBase(String newBase) {
            return new Header(ontologyIri, prefix, title, description, version, issued, identifier, newBase);
        }
    }

    /**
     * The merged model and what was done: the documents read, the shapes removed, the
     * deactivated shapes kept because something else references them, the {@code sh:property}
     * references dropped, any warnings, the local files merged (which the merged file must not
     * replace), the {@code @base} to write and the header's IRI (written first).
     */
    public record Result(Model model, List<String> documents, int removedShapes, List<String> keptDeactivated,
                         int droppedReferences, List<String> warnings, List<Path> inputs, String base,
                         String headerIri) {
    }

    private ShapeConfigurationMerger() {
    }

    /**
     * Merges a saved configuration. Every {@code owl:imports} of the configuration must be read;
     * one that is skipped (a web URL without an RDF file extension) is an error, because the
     * merged file would silently lack that file.
     *
     * @param header the merged file's header, or null to derive it from the configuration's
     */
    public static Result mergeConfiguration(Path configuration, Header header) throws IOException {
        // Checked before anything touches the file: under serve/mcp/run it must lie in the roots.
        Path checked = PathPolicy.checkReadIfActive(configuration.toAbsolutePath().normalize());
        Model config;
        try {
            config = RDFDataMgr.loadModel(checked.toUri().toString());
        } catch (RuntimeException ex) {
            throw new IOException("The configuration " + LogSanitizer.forLog(checked.getFileName().toString())
                    + " could not be read: " + LogSanitizer.forLog(ex.getMessage()), ex);
        }
        List<String> directImports = new ArrayList<>();
        config.listObjectsOfProperty(OWL.imports).forEachRemaining(node -> {
            if (node.isURIResource()) {
                directImports.add(node.asResource().getURI());
            }
        });
        if (directImports.isEmpty()) {
            throw new IOException("The configuration has no owl:imports to merge");
        }

        ShapesClosure closure = ValidationTools.loadShapesClosure(List.of(checked));
        List<String> notRead = directImports.stream().filter(iri -> !closure.followedImports().contains(iri)).toList();
        if (!notRead.isEmpty()) {
            // Import IRIs come from the file: credentials and query strings are not repeated.
            throw new IOException("These imports were not read, so the merged file would lack them: "
                    + notRead.stream().map(LogSanitizer::urlForLog).collect(Collectors.joining(", "))
                    + ". CimPal only downloads a web import whose URL ends in an RDF file extension such as .ttl.");
        }

        Set<String> ontologies = new HashSet<>();
        config.listSubjectsWithProperty(RDF.type, OWL.Ontology).forEachRemaining(subject -> {
            if (subject.isURIResource()) {
                ontologies.add(subject.getURI());
            }
        });
        Set<String> deactivated = new LinkedHashSet<>();
        config.listSubjectsWithProperty(SH.deactivated, config.createTypedLiteral(true)).forEachRemaining(subject -> {
            if (subject.isURIResource()) {
                deactivated.add(subject.getURI());
            }
        });

        Header used = header != null ? header : headerFor(config, checked);
        List<String> documents = closure.documents().subList(1, closure.documents().size());
        return merge(closure.model(), closure.followedImports(), deactivated, ontologies, documents, used,
                closure.localFiles());
    }

    /**
     * As {@link #mergeConfiguration(Path, Header)}, with the header derived from the
     * configuration's and the given {@code @base} (blank for none).
     */
    public static Result mergeConfigurationWithBase(Path configuration, String base) throws IOException {
        Path checked = PathPolicy.checkReadIfActive(configuration.toAbsolutePath().normalize());
        Model config;
        try {
            config = RDFDataMgr.loadModel(checked.toUri().toString());
        } catch (RuntimeException ex) {
            throw new IOException("The configuration " + LogSanitizer.forLog(checked.getFileName().toString())
                    + " could not be read: " + LogSanitizer.forLog(ex.getMessage()), ex);
        }
        return mergeConfiguration(checked, headerFor(config, checked).withBase(base));
    }

    /**
     * Merges local constraint files, read with their own imports, without the given shapes; used
     * where the configuration itself names web URLs that cannot be read yet.
     */
    public static Result mergeFiles(List<Path> files, Collection<String> deactivated, Header header) throws IOException {
        ShapesClosure closure = ValidationTools.loadShapesClosure(files);
        return merge(closure.model(), closure.followedImports(), new LinkedHashSet<>(deactivated), Set.of(),
                closure.documents(), header, closure.localFiles());
    }

    /**
     * The header of the file merged from a configuration with this IRI, title and version, with
     * the given {@code @base} (blank for none). The configuration {@code http://example.com/EQ}
     * (or its header {@code http://example.com/EQ#Ontology}) gives
     * {@code merged:Ontology}, with {@code merged:} for {@code http://example.com/EQ-merged#}.
     */
    public static Header mergedHeader(String configurationIri, String title, String version, String base) {
        String name = title == null || title.isBlank() ? "Constraints" : title.strip();
        return new Header(mergedNamespace(configurationIri) + ONTOLOGY_LOCAL_NAME, DEFAULT_HEADER_PREFIX,
                name + " (merged)", null, version, null, null, base);
    }

    /** {@code http://example.com/EQ} and {@code http://example.com/EQ#Ontology} give {@code http://example.com/EQ-merged#}. */
    static String mergedNamespace(String configurationIri) {
        String stem = configurationIri.strip();
        if (stem.endsWith("#" + ONTOLOGY_LOCAL_NAME) || stem.endsWith("/" + ONTOLOGY_LOCAL_NAME)) {
            stem = stem.substring(0, stem.length() - ONTOLOGY_LOCAL_NAME.length() - 1);
        }
        while (stem.endsWith("#") || stem.endsWith("/")) {
            stem = stem.substring(0, stem.length() - 1);
        }
        return stem + "-merged#";
    }

    /** The part of an IRI after its last {@code #} or {@code /}. */
    static String localName(String iri) {
        int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return iri.substring(cut + 1);
    }

    /**
     * Whether {@code target} is one of {@code inputs}, by the file system's judgement (so another
     * spelling, letter case, short name or link of the same file counts). A file that does not
     * exist yet is none of them.
     */
    public static boolean isInput(Path target, Collection<Path> inputs) throws IOException {
        if (!Files.exists(target)) {
            return false;
        }
        for (Path input : inputs) {
            if (Files.exists(input) && Files.isSameFile(target, input)) {
                return true;
            }
        }
        return false;
    }

    static Header headerFor(Model config, Path configuration) {
        Resource ontology = config.listSubjectsWithProperty(RDF.type, OWL.Ontology).toList().stream()
                .filter(Resource::isURIResource).findFirst()
                .orElse(config.createResource(configuration.toAbsolutePath().normalize().toUri().toString()));
        Statement title = ontology.getProperty(DCTerms.title);
        Statement version = ontology.getProperty(OWL.versionInfo);
        return mergedHeader(ontology.getURI(), title == null ? null : title.getString(),
                version == null ? null : version.getString(), null);
    }

    static Result merge(Model closure, Set<String> followedImports, Set<String> deactivated,
                        Set<String> droppedSubjects, List<String> documents, Header header, List<Path> inputs) {
        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefixes(closure.getNsPrefixMap());
        model.add(closure);
        List<String> warnings = new ArrayList<>();

        for (String subject : droppedSubjects) {
            model.removeAll(model.createResource(subject), null, null);
        }
        List<Statement> imports = model.listStatements(null, OWL.imports, (RDFNode) null).toList().stream()
                .filter(st -> st.getObject().isURIResource() && followedImports.contains(st.getResource().getURI()))
                .toList();
        model.remove(imports);
        dropSourceHeaders(model, warnings);

        Set<Resource> sparqlConstraints = new HashSet<>(model.listSubjectsWithProperty(RDF.type, SH.SPARQLConstraint).toList());
        Set<Resource> remove = new LinkedHashSet<>();
        Set<String> missing = new TreeSet<>();
        for (String iri : deactivated) {
            Resource shape = model.createResource(iri);
            if (model.contains(shape, null, (RDFNode) null)) {
                remove.add(shape);
            } else {
                missing.add(iri);
            }
        }
        if (!missing.isEmpty()) {
            warnings.add(missing.size() + " deactivated shape(s) are not in the merged files: "
                    + missing.stream().map(LogSanitizer::forLog).collect(Collectors.joining(", ")));
        }

        // A shape reached other than through sh:property stays, deactivated; until nothing changes,
        // since keeping one shape keeps what it references too.
        List<String> kept = new ArrayList<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Resource shape : List.copyOf(remove)) {
                if (referencedFromOutside(model, shape, remove)) {
                    remove.remove(shape);
                    kept.add(shape.getURI());
                    changed = true;
                }
            }
        }
        for (String iri : kept) {
            Resource shape = model.createResource(iri);
            model.removeAll(shape, SH.deactivated, null);
            model.add(shape, SH.deactivated, model.createTypedLiteral(true));
        }
        if (!kept.isEmpty()) {
            kept.sort(null);
            warnings.add(kept.size() + " deactivated shape(s) are kept with sh:deactivated true, because another "
                    + "shape uses them other than through sh:property: "
                    + kept.stream().map(LogSanitizer::forLog).collect(Collectors.joining(", ")));
        }

        int dropped = 0;
        Deque<RDFNode> orphans = new ArrayDeque<>();
        for (Resource shape : remove) {
            List<Statement> references = model.listStatements(null, SH.property, shape).toList();
            dropped += references.size();
            model.remove(references);
            model.listObjectsOfProperty(shape, null).forEachRemaining(orphans::add);
            model.removeAll(shape, null, null);
        }
        // Blank nodes and SPARQL constraints nothing points to any more go with the shapes.
        while (!orphans.isEmpty()) {
            RDFNode node = orphans.pop();
            if (!(node.isAnon() || sparqlConstraints.contains(node)) || model.contains(null, null, node)) {
                continue;
            }
            Resource resource = node.asResource();
            model.listObjectsOfProperty(resource, null).forEachRemaining(orphans::add);
            model.removeAll(resource, null, null);
        }

        removeRepeatedPrefixDeclarations(model);
        addHeader(model, header, documents);
        return new Result(model, List.copyOf(documents), remove.size(), List.copyOf(kept), dropped, List.copyOf(warnings),
                List.copyOf(inputs), header.base(), header.ontologyIri());
    }

    /**
     * Leaves out the source files' own headers: every resource whose local name is
     * {@code Ontology} ({@code eq:Ontology}, {@code equ:Ontology}, ...), with its blank nodes. The
     * merged file gets one header of its own instead. Resources that are only typed
     * {@code owl:Ontology} under another name, such as {@code cim:} carrying SPARQL prefix
     * declarations, stay. So does a header that a SPARQL constraint names in {@code sh:prefixes}:
     * its queries need its declarations, so it is kept and reported.
     */
    private static void dropSourceHeaders(Model model, List<String> warnings) {
        Set<RDFNode> prefixSources = new HashSet<>(model.listObjectsOfProperty(SH.prefixes).toList());
        List<String> kept = new ArrayList<>();
        for (Resource subject : model.listSubjects().toList()) {
            if (!subject.isURIResource() || !ONTOLOGY_LOCAL_NAME.equals(localName(subject.getURI()))) {
                continue;
            }
            if (prefixSources.contains(subject)) {
                kept.add(subject.getURI());
                continue;
            }
            removeWithBlankNodes(model, subject);
        }
        if (!kept.isEmpty()) {
            kept.sort(null);
            warnings.add(kept.size() + " source header(s) are kept because SPARQL constraints take their prefixes "
                    + "from them (sh:prefixes): "
                    + kept.stream().map(LogSanitizer::forLog).collect(Collectors.joining(", ")));
        }
    }

    /** Removes a resource's statements and the blank nodes below it that nothing else references. */
    private static void removeWithBlankNodes(Model model, Resource resource) {
        Deque<RDFNode> todo = new ArrayDeque<>(model.listObjectsOfProperty(resource, null).toList());
        model.removeAll(resource, null, null);
        while (!todo.isEmpty()) {
            RDFNode node = todo.pop();
            if (!node.isAnon() || model.contains(null, null, node)) {
                continue;
            }
            todo.addAll(model.listObjectsOfProperty(node.asResource(), null).toList());
            model.removeAll(node.asResource(), null, null);
        }
    }

    /**
     * Several source files often declare the same SPARQL prefixes on the same ontology, each with
     * its own blank nodes; in one file they are the same declaration repeated. Keeps one of each
     * (prefix, namespace) pair per ontology. A declaration with anything else on it is left alone.
     */
    private static void removeRepeatedPrefixDeclarations(Model model) {
        for (Resource ontology : model.listSubjectsWithProperty(SH.declare).toList()) {
            Set<List<String>> seen = new HashSet<>();
            for (RDFNode node : model.listObjectsOfProperty(ontology, SH.declare).toList()) {
                if (!node.isAnon()) {
                    continue;
                }
                Resource declaration = node.asResource();
                List<Statement> statements = declaration.listProperties().toList();
                boolean plain = statements.stream().allMatch(st -> st.getPredicate().equals(SH.prefix)
                        || st.getPredicate().equals(SH.namespace)
                        || (st.getPredicate().equals(RDF.type) && st.getObject().equals(SH.PrefixDeclaration)));
                Statement prefix = declaration.getProperty(SH.prefix);
                Statement namespace = declaration.getProperty(SH.namespace);
                if (!plain || prefix == null || namespace == null
                        || model.listStatements(null, null, declaration).toList().size() != 1) {
                    continue;
                }
                if (!seen.add(List.of(prefix.getString(), namespace.getString()))) {
                    model.remove(ontology, SH.declare, declaration);
                    model.remove(statements);
                }
            }
        }
    }

    /**
     * Whether something that stays references the shape other than through {@code sh:property}.
     * A blank node counts as part of the shapes it hangs from; one that hangs from nothing counts
     * as staying.
     */
    private static boolean referencedFromOutside(Model model, Resource shape, Set<Resource> remove) {
        for (Statement st : model.listStatements(null, null, shape).toList()) {
            if (st.getPredicate().equals(SH.property) && !st.getSubject().isAnon()) {
                continue;
            }
            if (!remove.containsAll(owners(model, st.getSubject()))) {
                return true;
            }
        }
        return false;
    }

    /** The named resources a node hangs from: itself, or for a blank node the subjects above it. */
    private static Set<Resource> owners(Model model, Resource node) {
        Set<Resource> owners = new HashSet<>();
        Set<Resource> seen = new HashSet<>();
        Deque<Resource> todo = new ArrayDeque<>(List.of(node));
        while (!todo.isEmpty()) {
            Resource current = todo.pop();
            if (!seen.add(current)) {
                continue;
            }
            if (!current.isAnon()) {
                owners.add(current);
                continue;
            }
            List<Resource> parents = model.listStatements(null, null, current).toList().stream()
                    .map(Statement::getSubject).toList();
            if (parents.isEmpty()) {
                owners.add(current);
            }
            todo.addAll(parents);
        }
        return owners;
    }

    private static void addHeader(Model model, Header header, List<String> documents) {
        Resource ontology = model.createResource(header.ontologyIri());
        model.add(ontology, RDF.type, OWL.Ontology);
        model.add(ontology, DCTerms.identifier, header.identifier() != null
                ? header.identifier() : "urn:uuid:" + UUID.randomUUID());
        Instant issued = (header.issued() != null ? header.issued() : Instant.now()).truncatedTo(ChronoUnit.SECONDS);
        model.add(ontology, DCTerms.issued, model.createTypedLiteral(issued.toString(), XSDDatatype.XSDdateTime));
        if (header.title() != null && !header.title().isBlank()) {
            model.add(ontology, DCTerms.title, model.createLiteral(header.title().strip(), "en"));
        }
        String description = header.description() != null && !header.description().isBlank()
                ? header.description().strip()
                : "Merged from " + String.join(", ", documents) + ", without the deactivated shapes.";
        model.add(ontology, DCTerms.description, model.createLiteral(description, "en"));
        if (header.version() != null && !header.version().isBlank()) {
            model.add(ontology, OWL.versionInfo, header.version().strip());
        }
        if (model.getNsPrefixURI("dcterms") == null && model.getNsURIPrefix(DCTerms.NS) == null) {
            model.setNsPrefix("dcterms", DCTerms.NS);
        }
        if (model.getNsURIPrefix(OWL.NS) == null) {
            model.setNsPrefix("owl", OWL.NS);
        }
        // The header's own prefix, so it is written as merged:Ontology rather than as an IRI
        // (relative to @base, <Constraints-merged>). A prefix a source file already uses for
        // another namespace is not taken over: a number is added.
        String iri = header.ontologyIri();
        String namespace = iri.substring(0, Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/')) + 1);
        if (!namespace.isEmpty() && model.getNsURIPrefix(namespace) == null) {
            String prefix = header.prefix();
            for (int n = 1; model.getNsPrefixURI(prefix) != null; n++) {
                prefix = header.prefix() + n;
            }
            model.setNsPrefix(prefix, namespace);
        }
    }

    /**
     * The merged model as Turtle, with its {@code @base} line when one was given, and the merged
     * file's header first, right after the prefixes.
     */
    public static String toTurtle(Result result) {
        Resource header = result.model().createResource(result.headerIri());
        Model head = ModelFactory.createDefaultModel().add(result.model().listStatements(header, null, (RDFNode) null));
        Model rest = ModelFactory.createDefaultModel().add(result.model()).remove(head);
        head.setNsPrefixes(result.model().getNsPrefixMap());
        rest.setNsPrefixes(result.model().getNsPrefixMap());
        // Both parts are written with the same prefixes and @base, so the header block can be
        // placed after the directives of the rest: one Turtle document.
        String[] restParts = splitDirectives(turtle(rest, result.base()));
        String headBody = splitDirectives(turtle(head, result.base()))[1].strip();
        return restParts[0] + (headBody.isEmpty() ? "" : headBody + "\n\n") + restParts[1];
    }

    private static String turtle(Model model, String base) {
        RDFWriterBuilder writer = RDFWriter.create()
                .set(RIOT.symTurtleIndentStyle, "wide")
                .set(RIOT.symTurtleDirectiveStyle, "rdf10")
                .set(RIOT.symTurtleMultilineLiterals, true)
                .lang(Lang.TURTLE)
                .source(model);
        if (base != null) {
            writer.base(base).set(RIOT.symTurtleOmitBase, false);
        }
        return writer.asString();
    }

    /** Splits Turtle into its leading {@code @base}/{@code @prefix} lines (with the blank line after them) and the rest. */
    static String[] splitDirectives(String turtle) {
        List<String> lines = turtle.lines().toList();
        int i = 0;
        while (i < lines.size() && (lines.get(i).isBlank() || lines.get(i).startsWith("@prefix")
                || lines.get(i).startsWith("@base"))) {
            i++;
        }
        String directives = lines.subList(0, i).stream().map(line -> line + "\n").collect(Collectors.joining());
        if (!directives.isEmpty() && !directives.endsWith("\n\n")) {
            directives += "\n";
        }
        String body = lines.subList(i, lines.size()).stream().map(line -> line + "\n").collect(Collectors.joining());
        return new String[]{directives, body};
    }

    /**
     * Writes the merged model as Turtle, replacing the file atomically.
     *
     * @throws IOException when {@code file} is one of the files merged into it (the configuration
     *                     or a file it imports): that file would be lost
     */
    public static void write(Result result, Path file) throws IOException {
        if (isInput(file, result.inputs())) {
            throw new IOException("The merged file would replace " + LogSanitizer.forLog(file.getFileName().toString())
                    + ", which is one of the files merged into it. Choose another file name.");
        }
        AtomicFiles.writeString(file, toTurtle(result));
    }
}
