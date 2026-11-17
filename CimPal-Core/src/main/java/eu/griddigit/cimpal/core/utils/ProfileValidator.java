/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.ProfileValidationOptions;
import eu.griddigit.cimpal.core.models.ProfileValidationSummary;
import eu.griddigit.cimpal.core.models.ProfileValidationSummary.Finding;
import eu.griddigit.cimpal.core.models.ProfileValidationSummary.GroupResult;
import eu.griddigit.cimpal.core.models.SHACLValidationOptions;
import eu.griddigit.cimpal.core.models.SHACLValidationReport;
import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import eu.griddigit.cimpal.core.profiles.InstanceHeader;
import eu.griddigit.cimpal.core.profiles.InstanceHeaderReader;
import eu.griddigit.cimpal.core.profiles.ProfileCatalog;
import eu.griddigit.cimpal.core.profiles.ProfileGroup;
import eu.griddigit.cimpal.core.profiles.ProfileGroupPlanner;
import eu.griddigit.cimpal.core.stats.RunStats;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.compose.MultiUnion;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.riot.system.StreamRDFLib;
import org.apache.jena.riot.system.StreamRDFWrapper;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shared.JenaException;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.vocabulary.RDF;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Validates the instance documents of a folder against the shapes their declared profiles name.
 *
 * <p>Each document says in its header which profiles it follows; a profile library says, in its
 * DX-PROF descriptors, which SHACL files constrain a profile. So no mapping of constraint files to
 * model files is written by hand. Documents are validated in groups, so that references between
 * them resolve, in two passes:
 * <ul>
 *   <li>the documents of a group together, against all their shapes, for everything except
 *       cardinality and closed-shape constraints;
 *   <li>each reported document on its own, for those constraints only. Counted over a group, a
 *       property an object repeats in a second document would break {@code sh:maxCount}, and the
 *       list of allowed properties of one profile would reject what another profile adds.
 * </ul>
 * A finding is reported for the documents that hold its focus node, and only in the group that
 * reports them; documents loaded as context are reported in their own group.
 *
 * <p>Nothing that should have been checked is dropped silently: a document without a usable
 * profile, shapes that cannot be loaded and a constraint whose SPARQL cannot be parsed all make
 * the run {@linkplain ProfileValidationSummary#complete() incomplete}.
 */
public final class ProfileValidator {

    private static final String SH = "http://www.w3.org/ns/shacl#";
    private static final Property SH_SPARQL = ResourceFactory.createProperty(SH, "sparql");
    private static final Property SH_TARGET = ResourceFactory.createProperty(SH, "target");
    private static final Property SH_SELECT = ResourceFactory.createProperty(SH, "select");
    private static final Property SH_ASK = ResourceFactory.createProperty(SH, "ask");
    private static final Property SH_PATH = ResourceFactory.createProperty(SH, "path");
    private static final Property SH_TARGET_CLASS = ResourceFactory.createProperty(SH, "targetClass");
    private static final Property SH_RESULT = ResourceFactory.createProperty(SH, "result");
    private static final Property SH_CONFORMS = ResourceFactory.createProperty(SH, "conforms");
    private static final Property SH_DETAIL = ResourceFactory.createProperty(SH, "detail");
    private static final Property SH_MIN_COUNT = ResourceFactory.createProperty(SH, "minCount");
    private static final Property SH_MAX_COUNT = ResourceFactory.createProperty(SH, "maxCount");
    private static final Property SH_CLOSED = ResourceFactory.createProperty(SH, "closed");
    private static final String WORKBOOK_PREFIX = "validation_report__";
    private static final Property SH_RESULT_PATH = ResourceFactory.createProperty(SH, "resultPath");
    private static final Property SH_FOCUS_NODE = ResourceFactory.createProperty(SH, "focusNode");
    private static final Property SH_COMPONENT = ResourceFactory.createProperty(SH, "sourceConstraintComponent");
    private static final Resource SH_NODE_SHAPE = ResourceFactory.createResource(SH + "NodeShape");
    private static final Resource SH_VALIDATION_REPORT = ResourceFactory.createResource(SH + "ValidationReport");
    private static final Resource SH_VALIDATION_RESULT = ResourceFactory.createResource(SH + "ValidationResult");
    /** The declarations a SPARQL constraint's prefixes are taken from. */
    private static final Set<String> PREFIX_PREDICATES = Set.of(SH + "declare", SH + "prefix", SH + "namespace");

    /** The constraints that are evaluated per document, never over a group. */
    private static final List<String> PER_DATASET = List.of(
            "MinCountConstraintComponent", "MaxCountConstraintComponent", "ClosedConstraintComponent");
    /** The component of the finding that stands for a constraint that could not be parsed. */
    public static final String INVALID_SPARQL_COMPONENT = "cimpal:InvalidSparqlConstraint";
    /** A models folder with more files than this is refused. */
    static final int MAX_MODEL_FILES = 100_000;

    private final ProfileValidationOptions options;

    public ProfileValidator(ProfileValidationOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    /** What a run would do, without loading a model or validating. */
    public record Preview(ProfileGroupPlanner.Plan plan, List<String> gaps) {
        public Preview {
            gaps = List.copyOf(gaps);
        }
    }

    /**
     * Reads the profile libraries and the headers of the documents and plans the groups.
     *
     * @throws IOException              if a folder or file cannot be read
     * @throws IllegalArgumentException if the inputs are inconsistent: an unknown profile that is
     *                                  not allowed, a profile described twice, a common shape
     *                                  that does not exist
     */
    public Preview plan() throws IOException {
        Prepared prepared = prepare();
        return new Preview(prepared.plan, prepared.gaps);
    }

    /**
     * Validates.
     *
     * @throws IOException              if an input cannot be read, shapes cannot be parsed even
     *                                  without their broken constraints, or the engine fails
     * @throws IllegalArgumentException as {@link #plan()}
     */
    public ProfileValidationSummary validate() throws IOException {
        Prepared prepared = prepare();
        Run run = new Run(prepared);
        return run.execute();
    }

    // ---- preparation ---------------------------------------------------------------------

    private record Prepared(ProfileGroupPlanner.Plan plan, List<String> gaps) {
    }

    private Prepared prepare() throws IOException {
        if (options.getOutputDir() != null) {
            // Refused before any work is done, not after the run.
            PathPolicy.checkWriteIfActive(options.getOutputDir().resolve(WORKBOOK_PREFIX + ".xlsx"));
        }
        ProfileCatalog catalog = ProfileCatalog.load(options.getProfileLibraries());
        List<String> gaps = new ArrayList<>(catalog.gaps());

        // serve/mcp/run: checked before anything touches the folder, and read as checked.
        Path modelsDir = PathPolicy.checkReadIfActive(options.getModelsDir());
        if (!Files.isDirectory(modelsDir)) {
            throw new FileNotFoundException("Models folder not found: "
                    + LogSanitizer.forLog(String.valueOf(options.getModelsDir().getFileName())));
        }
        InstanceHeaderReader reader = new InstanceHeaderReader(catalog, options.getProfileNamespaces(),
                options.getXmlBase());
        List<InstanceHeader> headers = new ArrayList<>();
        for (Path file : modelFiles(modelsDir, gaps)) {
            headers.addAll(reader.read(file));
        }
        if (headers.isEmpty()) {
            throw new IOException("No instance documents (.xml, or .zip archives of them) found under "
                    + LogSanitizer.forLog(String.valueOf(modelsDir.getFileName())));
        }

        // Declared dependencies that no loaded document satisfies.
        Set<String> models = new HashSet<>();
        headers.forEach(h -> {
            if (h.modelIri() != null) {
                models.add(h.modelIri());
            }
        });
        for (InstanceHeader header : headers) {
            for (String dependency : header.dependsOn()) {
                if (!models.contains(dependency)) {
                    gaps.add("declared model dependency not loaded: " + LogSanitizer.forLog(header.source())
                            + " -> " + LogSanitizer.forLog(dependency));
                }
            }
        }

        // The paths of explicit groups go through the policy like the models folder did, so they are
        // refused outside the roots and spelled the way the documents' paths are.
        List<ProfileGroupPlanner.GroupSpec> groups = new ArrayList<>();
        for (ProfileGroupPlanner.GroupSpec spec : options.getGroups()) {
            groups.add(new ProfileGroupPlanner.GroupSpec(spec.name(),
                    spec.reported().stream().map(PathPolicy::checkReadIfActive).toList(),
                    spec.context().stream().map(PathPolicy::checkReadIfActive).toList(),
                    spec.boundary(),
                    spec.extraShapes().stream().map(PathPolicy::checkReadIfActive).toList(),
                    spec.assumeProfiles()));
        }
        ProfileGroupPlanner.Plan plan = ProfileGroupPlanner.plan(headers, catalog, new ProfileGroupPlanner.Settings(
                modelsDir, options.getBoundaryFolders(), options.getBoundaryShapesPattern(), commonShapes(catalog),
                options.isAllowUnmapped(), groups));
        if (plan.groups().isEmpty()) {
            // Nothing to validate must never read as "nothing found".
            throw new IllegalArgumentException("None of the " + headers.size() + " instance documents can be"
                    + " validated: " + plan.skipped().stream().map(ProfileGroupPlanner.Skipped::reason).distinct()
                    .reduce((a, b) -> a + "; " + b).orElse("no profile declared"));
        }
        // Each group gets a report file named after it: two groups must not share one.
        Set<String> fileNames = new HashSet<>();
        for (ProfileGroup group : plan.groups()) {
            if (!fileNames.add(fileName(group.name()).toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Two groups would share the report file name "
                        + LogSanitizer.forLog(fileName(group.name())) + "; rename one of them");
            }
        }
        return new Prepared(plan, gaps);
    }

    /** The {@code .xml} and {@code .zip} files under the models folder, sorted; links are not followed. */
    private static List<Path> modelFiles(Path modelsDir, List<String> gaps) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(modelsDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(modelsDir)) {
                    return FileVisitResult.CONTINUE;
                }
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (name.startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (attrs.isOther() || attrs.isSymbolicLink() || !PathPolicy.mayReadIfActive(dir)) {
                    // Said, so that "the folder conforms" is not read as covering it.
                    gaps.add("folder not read (a link, or not allowed): " + LogSanitizer.forLog(name));
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (attrs.isRegularFile() && (lower.endsWith(".xml") || lower.endsWith(".zip"))) {
                    if (files.size() >= MAX_MODEL_FILES) {
                        throw new IOException("The models folder holds more than " + MAX_MODEL_FILES + " model files");
                    }
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(null);
        return files;
    }

    /** The shapes no descriptor refers to: the defaults where the libraries have them, or what was asked for. */
    private List<Path> commonShapes(ProfileCatalog catalog) {
        boolean defaults = options.getCommonShapes() == null;
        List<Path> shapes = new ArrayList<>();
        for (String entry : defaults ? ProfileValidationOptions.DEFAULT_COMMON_SHAPES : options.getCommonShapes()) {
            List<Path> named = catalog.filesNamed(entry);
            if (!named.isEmpty()) {
                shapes.addAll(named);
            } else if (!defaults) {
                Path file;
                try {
                    file = PathPolicy.checkReadIfActive(Path.of(entry).toAbsolutePath());
                } catch (java.nio.file.InvalidPathException ex) {
                    throw new IllegalArgumentException("Common shapes: not a file name or path: "
                            + LogSanitizer.forLog(entry));
                }
                if (!Files.isRegularFile(file)) {
                    throw new IllegalArgumentException("Common shapes not found, neither as a file of a profile"
                            + " library nor as a path: " + LogSanitizer.forLog(entry));
                }
                shapes.add(file);
            }
        }
        return shapes;
    }

    // ---- one run -------------------------------------------------------------------------

    /** Shapes ready to validate with, and what was wrong with them. */
    private record LoadedShapes(Model model, List<String> warnings, List<SHACLValidationResult> broken) {
        /** True when the shapes did not all load, or lost a constraint: they check less than they should. */
        boolean lessThanAsked() {
            return !warnings.isEmpty() || !broken.isEmpty();
        }
    }

    private final class Run {
        private final ProfileGroupPlanner.Plan plan;
        private final List<String> gaps;
        private final Map<String, RDFDatatype> dataTypeMap;
        private final Map<String, Model> documents = new HashMap<>();
        private final Map<String, Integer> lastUse = new HashMap<>();
        private final Map<List<Path>, LoadedShapes> shapesCache = new HashMap<>();
        private final Map<Path, Boolean> perDatasetFiles = new HashMap<>();
        private final List<Finding> findings = new ArrayList<>();
        /** Findings that belong to no document, with the first group that met each. */
        private final Map<SHACLValidationResult, String> shapeFindings = new LinkedHashMap<>();
        private final Set<String> warnings = new LinkedHashSet<>();
        private final List<Path> turtleReports = new ArrayList<>();
        private boolean complete;
        private boolean partial;

        Run(Prepared prepared) throws IOException {
            this.plan = prepared.plan;
            this.gaps = prepared.gaps;
            this.complete = prepared.plan.complete();
            this.dataTypeMap = CompleteDatatypeMapLoader.resolve(options.getDatatypeMapPreset(),
                    options.getDatatypeMapFile(), options.getDatatypeMap());
            for (int i = 0; i < plan.groups().size(); i++) {
                ProfileGroup group = plan.groups().get(i);
                for (InstanceHeader header : group.reported()) {
                    lastUse.put(header.id(), i);
                }
                for (InstanceHeader header : group.context()) {
                    lastUse.put(header.id(), i);
                }
            }
        }

        ProfileValidationSummary execute() throws IOException {
            if (dataTypeMap.isEmpty()) {
                warnings.add("No datatype map was applied: untyped literals were validated as xsd:string, "
                        + "so datatype and value-range constraints on them may report incorrectly.");
            }
            List<GroupResult> results = new ArrayList<>();
            for (int i = 0; i < plan.groups().size(); i++) {
                results.add(validateGroup(plan.groups().get(i)));
                int index = i;
                documents.keySet().removeIf(id -> lastUse.getOrDefault(id, -1) <= index);
            }
            // Findings that belong to no document, once per run however many groups met them.
            shapeFindings.forEach((result, group) -> findings.add(new Finding(group, List.of(), result)));
            Path workbook = options.getOutputDir() == null ? null : writeWorkbook(results);
            return new ProfileValidationSummary(results, findings, plan.skipped(), gaps, new ArrayList<>(warnings),
                    complete, partial, workbook, turtleReports);
        }

        private GroupResult validateGroup(ProfileGroup group) throws IOException {
            long started = System.nanoTime();
            List<InstanceHeader> all = new ArrayList<>(group.reported());
            all.addAll(group.context());
            MultiUnion union = new MultiUnion();
            for (InstanceHeader header : all) {
                union.addGraph(document(header).getGraph());
            }
            Model unionModel = org.apache.jena.rdf.model.ModelFactory.createModelForGraph(union);

            int before = findings.size();
            boolean groupPartial = false;
            // True when part of what the group's shapes ask for was not checked.
            boolean groupIncomplete = false;
            int contextFindings = 0;
            // focus|path|value|component|shape|message -> the finding, so the same fact is reported once.
            Map<String, Finding> unique = new LinkedHashMap<>();
            Model fullReport = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
            Resource reportNode = fullReport.createResource().addProperty(RDF.type, SH_VALIDATION_REPORT);

            // Pass 1: the group together, everything but the per-document constraints.
            LoadedShapes unionShapes = shapes(group.unionShapes(), group.name());
            groupIncomplete |= unionShapes.lessThanAsked();
            SHACLValidationReport report = run(unionModel, unionShapes.model());
            noteWarnings(group.name(), report);
            groupPartial |= report.isPartial();
            // No shape of the group has a target, or the documents hold nothing.
            groupIncomplete |= report.checkedNothing();
            copyResults(report.getReportModel(), false, fullReport, reportNode);
            // Read from the report's RDF, not from its result list: a result there names its focus
            // node in a shortened form that cannot be looked up in the documents.
            for (Resource node : report.getReportModel().listSubjectsWithProperty(RDF.type, SH_VALIDATION_RESULT).toList()) {
                if (isPerDataset(componentOf(node)) || report.getReportModel().contains(null, SH_DETAIL, node)) {
                    // Per-document constraints come from pass 2; a detail belongs to its parent result.
                    continue;
                }
                SHACLValidationResult result = read(node, unionShapes.model(), group);
                Statement focus = node.getProperty(SH_FOCUS_NODE);
                List<InstanceHeader> holders = focus == null ? null : holders(focus.getObject().asNode(), group);
                if (holders == null) {
                    // In none of the group's documents: about the shapes, or a node only the report knows.
                    shapeFindings.putIfAbsent(result, group.name());
                } else if (!holders.isEmpty()) {
                    unique.putIfAbsent(key(result), finding(group, holders, result, node));
                } else {
                    // On a context document only: reported in that document's own group, counted here.
                    contextFindings++;
                }
            }
            unionShapes.broken().forEach(b -> shapeFindings.putIfAbsent(b, group.name()));

            // Pass 2: each reported document alone, the per-document constraints only. Every shape
            // file that applies to the document and holds such a constraint takes part, whatever
            // its name: left out, its cardinality and closed-shape checks would run nowhere.
            for (InstanceHeader header : group.reported()) {
                List<Path> own = new ArrayList<>();
                for (Path file : group.datasetShapes().getOrDefault(header.id(), List.of())) {
                    if (holdsPerDatasetConstraints(file)) {
                        own.add(file);
                    }
                }
                if (own.isEmpty()) {
                    continue;
                }
                LoadedShapes ownShapes = shapes(own, group.name());
                groupIncomplete |= ownShapes.lessThanAsked();
                SHACLValidationReport single = run(document(header), ownShapes.model());
                noteWarnings(group.name(), single);
                groupPartial |= single.isPartial();
                groupIncomplete |= single.checkedNothing();
                copyResults(single.getReportModel(), true, fullReport, reportNode);
                Model singleReport = single.getReportModel();
                for (Resource node : singleReport.listSubjectsWithProperty(RDF.type, SH_VALIDATION_RESULT).toList()) {
                    if (isPerDataset(componentOf(node)) && !singleReport.contains(null, SH_DETAIL, node)) {
                        SHACLValidationResult result = read(node, ownShapes.model(), group);
                        unique.putIfAbsent(header.id() + "|" + key(result), finding(group, List.of(header), result, node));
                    }
                }
                ownShapes.broken().forEach(b -> shapeFindings.putIfAbsent(b, group.name()));
            }

            findings.addAll(unique.values());
            partial |= groupPartial;
            complete &= !groupIncomplete;
            List<Finding> own = findings.subList(before, findings.size());
            Map<String, Long> severities = new LinkedHashMap<>();
            own.forEach(f -> severities.merge(ProfileValidationSummary.severityName(f.result().getSeverity()), 1L, Long::sum));

            if (options.isExportTurtleReports() && options.getOutputDir() != null) {
                // A report that left something unchecked must not say the data conforms.
                reportNode.addLiteral(SH_CONFORMS, !fullReport.contains(reportNode, SH_RESULT)
                        && !groupIncomplete && !groupPartial);
                fullReport.setNsPrefix("sh", SH);
                fullReport.withDefaultMappings(report.getReportModel());
                turtleReports.add(writeTurtle(fullReport, group.name()));
            }

            Set<String> shapeNames = new TreeSet<>();
            group.unionShapes().forEach(p -> shapeNames.add(p.getFileName().toString()));
            group.datasetShapes().values().forEach(l -> l.forEach(p -> shapeNames.add(p.getFileName().toString())));
            return new GroupResult(group.name(),
                    group.reported().stream().map(InstanceHeader::source).toList(),
                    group.context().stream().map(InstanceHeader::source).toList(),
                    new ArrayList<>(shapeNames), own.size(), contextFindings, severities, groupPartial,
                    groupIncomplete, (System.nanoTime() - started) / 1_000_000, group.reported());
        }

        /** True when the file, with what it imports, holds a constraint that is evaluated per document. */
        private boolean holdsPerDatasetConstraints(Path file) throws IOException {
            Boolean known = perDatasetFiles.get(file);
            if (known == null) {
                Model model = SHACLValidator.loadShapeFiles(List.of(file), null, new ArrayList<>());
                known = model.contains(null, SH_MIN_COUNT) || model.contains(null, SH_MAX_COUNT)
                        || model.contains(null, SH_CLOSED);
                perDatasetFiles.put(file, known);
            }
            return known;
        }

        private SHACLValidationReport run(Model data, Model shapes) throws IOException {
            try {
                // The documents were typed with the datatype map when they were read, so none is given here.
                return new SHACLValidator(SHACLValidationOptions.builder()
                        .dataModel(data)
                        .shapesModel(shapes)
                        .engine(options.getEngine())
                        .maxResultsPerConstraint(options.getMaxResultsPerConstraint())
                        .workers(options.getWorkers())
                        .build()).validate();
            } catch (JenaException ex) {
                throw new IOException("SHACL validation failed: " + LogSanitizer.forLog(String.valueOf(ex.getMessage())), ex);
            }
        }

        private void noteWarnings(String group, SHACLValidationReport report) {
            for (String warning : report.getWarnings()) {
                if (!warning.startsWith("No datatype map was applied")) {
                    warnings.add(LogSanitizer.forLog(group) + ": " + warning);
                }
            }
        }

        // ---- documents ---------------------------------------------------------------

        private Model document(InstanceHeader header) throws IOException {
            Model model = documents.get(header.id());
            if (model == null) {
                load(header.file());
                model = documents.get(header.id());
                if (model == null) {
                    throw new IOException("Document not found when loading: " + LogSanitizer.forLog(header.source()));
                }
            }
            return model;
        }

        /** Parses the document of {@code given}, or every {@code .xml} document of the archive it is. */
        private void load(Path given) throws IOException {
            Path file = PathPolicy.checkReadIfActive(given);
            String name = file.getFileName().toString();
            long triples = 0;
            if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                Map<String, Model> read = new LinkedHashMap<>();
                ModelFactory.forEachZipEntry(file, (entry, in) -> {
                    // Only the documents a group uses: an entry the plan skipped is not parsed.
                    if (entry.toLowerCase(Locale.ROOT).endsWith(".xml") && lastUse.containsKey(given + "!" + entry)) {
                        read.put(given + "!" + entry, parse(in, name + "/" + entry));
                    }
                });
                for (Model model : read.values()) {
                    triples += model.size();
                }
                documents.putAll(read);
            } else {
                try (InputStream in = Files.newInputStream(file)) {
                    Model model = parse(in, name);
                    triples = model.size();
                    documents.put(given.toString(), model);
                }
            }
            RunStats stats = options.getRunStats();
            if (stats != null) {
                stats.addTriples(triples);
                try {
                    stats.addInputBytes(Files.size(file));
                } catch (IOException ex) {
                    // A file that can't be sized is not counted; statistics never fail a run.
                }
            }
        }

        /** As {@code SHACLValidator} reads a data file: typed with the datatype map, named graphs merged. */
        @SuppressWarnings("unchecked")
        private Model parse(InputStream in, String source) throws IOException {
            Model target = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
            DataTypeStreamRDF typing = dataTypeMap.isEmpty() ? null : new DataTypeStreamRDF(target.getGraph(), dataTypeMap);
            StreamRDF sink = typing != null ? typing : StreamRDFLib.graph(target.getGraph());
            StreamRDF triplesOnly = new StreamRDFWrapper(sink) {
                @Override
                public void quad(Quad quad) {
                    triple(quad.asTriple());
                }
            };
            try {
                RDFParser.source(in).lang(Lang.RDFXML).base(options.getXmlBase()).parse(triplesOnly);
            } catch (JenaException ex) {
                throw new IOException("Could not read the data file " + LogSanitizer.forLog(source) + ": "
                        + LogSanitizer.forLog(String.valueOf(ex.getMessage())), ex);
            }
            if (typing != null) {
                target.setNsPrefixes((Map<String, String>) typing.getPrefixMapping());
            }
            return target;
        }

        // ---- attribution -------------------------------------------------------------

        /**
         * The reported documents of {@code group} that hold {@code focus}: as a subject, or failing
         * that as an object. Empty when only context documents hold it; null when none does.
         */
        private List<InstanceHeader> holders(Node node, ProfileGroup group) {
            for (boolean asSubject : new boolean[]{true, false}) {
                List<InstanceHeader> reported = new ArrayList<>();
                boolean anywhere = false;
                for (InstanceHeader header : group.reported()) {
                    if (holds(documents.get(header.id()), node, asSubject)) {
                        reported.add(header);
                        anywhere = true;
                    }
                }
                for (InstanceHeader header : group.context()) {
                    anywhere |= holds(documents.get(header.id()), node, asSubject);
                }
                if (anywhere) {
                    return reported;
                }
            }
            return null;
        }

        /** One result of a report, read with the shapes it came from; never dropped when it cannot be. */
        private SHACLValidationResult read(Resource node, Model shapes, ProfileGroup group) throws IOException {
            Model single = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
            copyClosure(node, single, new HashSet<>(), SH_DETAIL);
            List<SHACLValidationResult> extracted = ShaclTools.extractSHACLValidationResults(single, shapes);
            if (extracted.size() != 1) {
                throw new IOException("A validation result of group " + LogSanitizer.forLog(group.name())
                        + " could not be read from the report");
            }
            return extracted.getFirst();
        }

        /** The finding, with the full IRIs of its focus node and path, for locating it in the files. */
        private Finding finding(ProfileGroup group, List<InstanceHeader> holders, SHACLValidationResult result,
                                Resource node) {
            Statement focus = node.getProperty(SH_FOCUS_NODE);
            Statement path = node.getProperty(SH_RESULT_PATH);
            return new Finding(group.name(), holders.stream().map(InstanceHeader::source).toList(), result, holders,
                    focus != null && focus.getObject().isURIResource() ? focus.getObject().asResource().getURI() : null,
                    path != null && path.getObject().isURIResource() ? path.getObject().asResource().getURI() : null);
        }

        private static boolean holds(Model document, Node node, boolean asSubject) {
            Graph graph = document.getGraph();
            return asSubject ? graph.contains(node, Node.ANY, Node.ANY) : graph.contains(Node.ANY, Node.ANY, node);
        }

        // ---- shapes ------------------------------------------------------------------

        private LoadedShapes shapes(List<Path> files, String group) throws IOException {
            LoadedShapes cached = shapesCache.get(files);
            if (cached == null) {
                List<String> loadWarnings = new ArrayList<>();
                // A copy: taking a broken constraint out must never reach a model another run may share.
                Model loaded = SHACLValidator.loadShapeFiles(files, null, loadWarnings);
                Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel().add(loaded);
                model.setNsPrefixes(loaded);
                cached = new LoadedShapes(model, loadWarnings, quarantineBrokenSparql(model));
                shapesCache.put(files, cached);
            }
            cached.warnings().forEach(w -> warnings.add(LogSanitizer.forLog(group) + ": " + w));
            return cached;
        }

        // ---- reports -----------------------------------------------------------------

        private Path writeTurtle(Model report, String group) throws IOException {
            Path file = PathPolicy.checkWriteIfActive(
                    options.getOutputDir().resolve(fileName(group) + "__shacl_report.ttl"));
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                RDFDataMgr.write(out, report, RDFFormat.TURTLE_PRETTY);
            }
            return file;
        }

        /** One row per group and reported document, and one for findings about the shapes. */
        private Path writeWorkbook(List<GroupResult> groups) throws IOException {
            // The file that is written is the file that is checked, before anything is created.
            Path file = PathPolicy.checkWriteIfActive(options.getOutputDir().resolve(WORKBOOK_PREFIX
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx"));
            Files.createDirectories(file.getParent());
            try (ValidationExcelWriter writer = new ValidationExcelWriter()) {
                for (GroupResult group : groups) {
                    String shapes = String.join(";", group.shapes());
                    for (String document : group.reported()) {
                        List<SHACLValidationResult> results = findings.stream()
                                .filter(f -> f.group().equals(group.name()) && f.documents().contains(document))
                                .map(Finding::result).toList();
                        // A row conforms only when its group checked everything it should have.
                        writer.appendValidation(ValidationExcelWriter.CaseFolder.UNKNOWN, group.name(), document, "",
                                shapes, results, results.isEmpty() && !group.partial() && !group.incomplete(),
                                group.name() + " / " + document, group.partial(), options.getMaxResultsPerConstraint());
                    }
                }
                // A document that was not validated has a row that does not conform, with the reason.
                for (ProfileGroupPlanner.Skipped skipped : plan.skipped()) {
                    writer.appendValidation(ValidationExcelWriter.CaseFolder.UNKNOWN, "(not validated)", skipped.source(),
                            "", skipped.reason(), List.of(), false, "(not validated) / " + skipped.source(), false,
                            options.getMaxResultsPerConstraint());
                }
                List<SHACLValidationResult> aboutShapes = findings.stream().filter(f -> f.documents().isEmpty())
                        .map(Finding::result).toList();
                if (!aboutShapes.isEmpty()) {
                    writer.appendValidation(ValidationExcelWriter.CaseFolder.UNKNOWN, "(shapes)", "", "", "",
                            aboutShapes, false, "(shapes)", false, options.getMaxResultsPerConstraint());
                }
                return writer.saveAs(file);
            }
        }

    }

    // ---- helpers -------------------------------------------------------------------------

    /** By the component's exact local name: sh:QualifiedMinCountConstraintComponent is not one of them. */
    private static boolean isPerDataset(String component) {
        if (component == null) {
            return false;
        }
        String local = component.substring(Math.max(component.lastIndexOf('#'), component.lastIndexOf(':')) + 1);
        return PER_DATASET.contains(local);
    }

    private static String key(SHACLValidationResult r) {
        return r.getFocusNode() + "|" + r.getPath() + "|" + r.getValue() + "|" + r.getConstraintComponent() + "|"
                + r.getSourceShape() + "|" + r.getMessage();
    }

    private static String fileName(String group) {
        return group.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Copies the results of {@code from} that are (or are not) per-document ones into {@code to}. */
    private static String componentOf(Resource result) {
        Statement component = result.getProperty(SH_COMPONENT);
        return component != null && component.getObject().isURIResource()
                ? component.getObject().asResource().getURI() : "";
    }

    private static void copyResults(Model from, boolean perDataset, Model to, Resource reportNode) {
        for (Resource result : from.listSubjectsWithProperty(RDF.type, SH_VALIDATION_RESULT).toList()) {
            if (isPerDataset(componentOf(result)) == perDataset) {
                copyClosure(result, to, new HashSet<>());
                reportNode.addProperty(SH_RESULT, result.inModel(to));
            }
        }
    }

    /** {@code node}'s statements, and those of the blank nodes they lead to. */
    private static void copyClosure(Resource node, Model to, Set<Resource> seen) {
        copyClosure(node, to, seen, null);
    }

    /** As above, without following {@code stopAt}: its statement is copied, what it points to is not. */
    private static void copyClosure(Resource node, Model to, Set<Resource> seen, Property stopAt) {
        if (!seen.add(node)) {
            return;
        }
        for (Statement statement : node.listProperties().toList()) {
            to.add(statement);
            RDFNode object = statement.getObject();
            if (object.isAnon() && !statement.getPredicate().equals(stopAt)) {
                copyClosure(object.asResource(), to, seen, stopAt);
            }
        }
    }

    /**
     * Takes out of {@code shapes} every SPARQL constraint and SPARQL target whose query cannot be
     * parsed, and returns one finding for each. One such query makes the whole shapes graph
     * unusable, and profile libraries are published with some; the rest is still worth running, as
     * long as what was left out is reported.
     *
     * @throws IOException if the shapes cannot be parsed for another reason
     */
    static List<SHACLValidationResult> quarantineBrokenSparql(Model shapes) throws IOException {
        JenaException first = tryParse(shapes);
        if (first == null) {
            return List.of();
        }
        Model declarations = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        shapes.listStatements().forEach(s -> {
            if (PREFIX_PREDICATES.contains(s.getPredicate().getURI())) {
                declarations.add(s);
            }
        });

        List<SHACLValidationResult> broken = new ArrayList<>();
        for (Property link : List.of(SH_SPARQL, SH_TARGET)) {
            for (Statement statement : shapes.listStatements(null, link, (RDFNode) null).toList()) {
                if (!statement.getObject().isResource()) {
                    continue;
                }
                Resource query = statement.getObject().asResource();
                if (!query.hasProperty(SH_SELECT) && !query.hasProperty(SH_ASK)) {
                    continue;
                }
                JenaException failure = tryParse(probe(declarations, statement));
                if (failure != null) {
                    shapes.remove(statement);
                    String owner = statement.getSubject().isURIResource() ? statement.getSubject().getURI()
                            : query.isURIResource() ? query.getURI() : "(anonymous shape)";
                    broken.add(new SHACLValidationResult(owner, "", "sh:Violation",
                            "The SPARQL of this " + (link.equals(SH_TARGET) ? "target" : "constraint")
                                    + " could not be parsed, so it was not checked: "
                                    + LogSanitizer.forLog(String.valueOf(failure.getMessage())),
                            "", "", INVALID_SPARQL_COMPONENT, "", "", "", "", ""));
                }
            }
        }
        JenaException still = tryParse(shapes);
        if (still != null) {
            throw new IOException("The SHACL shapes could not be parsed: "
                    + LogSanitizer.forLog(String.valueOf(still.getMessage())), still);
        }
        return broken;
    }

    /** A shapes graph with nothing but the prefix declarations and one shape that carries the query. */
    private static Model probe(Model declarations, Statement link) {
        Model probe = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        probe.add(declarations);
        copyClosure(link.getObject().asResource(), probe, new HashSet<>());
        Resource shape = probe.createResource("urn:cimpal:probe-shape");
        Statement path = link.getSubject().getProperty(SH_PATH);
        if (path != null) {
            // A constraint of a property shape may use $PATH.
            probe.add(shape, SH_PATH, path.getObject());
            if (path.getObject().isAnon()) {
                copyClosure(path.getObject().asResource(), probe, new HashSet<>());
            }
            probe.add(shape, RDF.type, ResourceFactory.createResource(SH + "PropertyShape"));
        } else {
            probe.add(shape, RDF.type, SH_NODE_SHAPE);
        }
        if (link.getPredicate().equals(SH_SPARQL)) {
            probe.add(shape, SH_TARGET_CLASS, probe.createResource("urn:cimpal:probe-class"));
        }
        probe.add(shape, link.getPredicate(), link.getObject());
        return probe;
    }

    private static JenaException tryParse(Model shapes) {
        try {
            Shapes.parse(shapes.getGraph());
            return null;
        } catch (JenaException ex) {
            return ex;
        }
    }
}
