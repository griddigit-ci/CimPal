/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.interfaces.ShaclRuleTesterCallback;
import eu.griddigit.cimpal.core.models.SHACLValidationOptions;
import eu.griddigit.cimpal.core.models.SHACLValidationReport;
import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import eu.griddigit.cimpal.core.models.ShaclRuleTestOptions;
import eu.griddigit.cimpal.core.models.ShaclRuleTestReport;
import eu.griddigit.cimpal.core.models.ShaclRuleTestReport.ModelResult;
import eu.griddigit.cimpal.core.models.ShaclRuleTestReport.Note;
import eu.griddigit.cimpal.core.models.ShaclRuleTestReport.Outcome;
import eu.griddigit.cimpal.core.models.ShaclRuleTestReport.RuleResult;
import eu.griddigit.cimpal.core.models.ShaclRuleTestReport.Verdict;
import org.apache.commons.io.FilenameUtils;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.vocabulary.RDF;
import org.topbraid.shacl.vocabulary.SH;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Tests SHACL rules against models whose outcome is known: for each rule, models that must not
 * trigger it and models that must.
 * <pre>{@code
 * ShaclRuleTestReport report = new ShaclRuleTester(ShaclRuleTestOptions.builder()
 *         .shapeFiles(constraintFiles)
 *         .suiteFolder(Path.of("C:/RuleTests"))
 *         .datatypeMap(DatatypeMapPreset.CGMES24_NC22)
 *         .xmlBase("http://iec.ch/TC57/2013/CIM-schema-cim16")
 *         .build()).run();
 * }</pre>
 * The suite folder holds a folder per rule, named after the rule's {@code sh:name}, and in it a
 * {@code Conform} and a {@code NonConform} folder of model archives ({@code .zip}):
 * <pre>
 * suite/
 *   ACLineSegmentR/
 *     Conform/      models that must not trigger the rule
 *     NonConform/   models that must trigger it
 * </pre>
 * Every model is validated against all the shapes. A rule is triggered by any finding whose source
 * shape has the rule's {@code sh:name}; findings of other rules do not affect its verdict. Models
 * are grouped by content, so a model copied into several rule folders is validated once, and two
 * models that share a file name but not their content are validated separately.
 * <p>
 * The models are the fixtures here and the rules are under test, which is why this is a tool of
 * its own rather than a dataset validation workflow. Each model is validated with
 * {@link SHACLValidator}, so it is typed with the datatype map and the shapes' {@code owl:imports}
 * resolve as they do for dataset validation.
 * <p>
 * A rule passes only when it has models on both sides, none of its Conform models triggers it,
 * every NonConform model does, and nothing in its folder was skipped. Nothing in the suite is
 * passed over silently: a model archive outside a Conform or NonConform folder is listed in the
 * report's notes and keeps its rule from passing, a folder of archives without Conform or
 * NonConform folders is a rule that could not be tested, and so is a rule folder whose name no
 * shape carries. Only the {@code .xml} files of a model archive are read.
 * <p>
 * Each model's validation report is written beside it, and a results workbook,
 * {@value #WORKBOOK_PREFIX}{@code <yyyyMMdd_HHmmss>.xlsx}, into the suite folder.
 */
public class ShaclRuleTester {

    /** Name of the results workbook up to its timestamp. */
    public static final String WORKBOOK_PREFIX = "rule_test_results_";

    /** Folder of the models that must not trigger the rule; matched ignoring case. */
    public static final String CONFORM = "Conform";

    /** Folder of the models that must trigger the rule; matched ignoring case. */
    public static final String NON_CONFORM = "NonConform";

    /** Heap that one model under validation may need, for sizing the automatic worker count. */
    private static final long HEAP_PER_MODEL = 6L * 1024 * 1024 * 1024;
    private static final int MAX_CONCURRENT_MODELS = 12;

    private final ShaclRuleTestOptions options;
    private final ShaclRuleTesterCallback callback;

    public ShaclRuleTester(ShaclRuleTestOptions options) {
        this(options, null);
    }

    public ShaclRuleTester(ShaclRuleTestOptions options, ShaclRuleTesterCallback callback) {
        this.options = options;
        this.callback = callback;
    }

    /**
     * Reads the suite, validates its models and returns each rule's verdict.
     *
     * @throws IOException if the suite folder, a constraint file or the datatype map cannot be
     *                     read, an {@code owl:imports} of the constraints cannot be resolved, or the
     *                     constraints are not a valid shapes graph. A model that cannot be read is
     *                     an error of its rules, not of the run.
     */
    public ShaclRuleTestReport run() throws IOException {
        LocalDateTime started = LocalDateTime.now();
        Path suiteFolder = options.getSuiteFolder().toAbsolutePath().normalize();
        if (!Files.isDirectory(suiteFolder)) {
            throw new FileNotFoundException("Test suite folder not found: " + suiteFolder);
        }
        updateProgress(0);

        List<Note> notes = Collections.synchronizedList(new ArrayList<>());
        Map<String, RuleFolder> ruleFolders = readSuite(suiteFolder, notes);

        Map<String, RDFDatatype> dataTypeMap = CompleteDatatypeMapLoader.resolve(
                options.getDatatypeMapPreset(), options.getDatatypeMapFile(), options.getDatatypeMap());
        // As at the start of every validation run: remote imports are read again, not reused.
        ValidationTools.clearRemoteCaches();
        Set<String> warnings = Collections.synchronizedSet(new LinkedHashSet<>());
        List<String> shapeWarnings = new ArrayList<>();
        Model shapes = SHACLValidator.loadShapeFiles(options.getShapeFiles(), null, shapeWarnings);
        try {
            // Parsed here as well as for every model: a shapes graph Jena rejects fails the whole run
            // once, with its reason, rather than every model with the same error.
            Shapes.parse(shapes.getGraph());
        } catch (RuntimeException ex) {
            throw new IOException("The constraint files are not a valid SHACL shapes graph: " + describe(ex), ex);
        }
        warnings.addAll(shapeWarnings);
        if (dataTypeMap.isEmpty()) {
            warnings.add("No datatype map was applied: literals were validated as xsd:string, so rules on "
                    + "numeric ranges, booleans or dates may fire on models that conform to them, or not fire at all.");
        }
        RuleNames ruleNames = RuleNames.of(shapes);

        List<Path> models = ruleFolders.values().stream()
                .flatMap(folder -> Stream.concat(folder.conform().stream(), folder.nonConform().stream()))
                .toList();
        Map<Path, String> contentByModel = new HashMap<>();
        Map<String, Validation> validations = new ConcurrentHashMap<>();
        Map<String, List<Path>> copiesByContent = groupByContent(models, contentByModel, validations);

        output("Testing " + ruleFolders.size() + " rule(s) with " + models.size() + " model(s), "
                + copiesByContent.size() + " of them distinct, in " + suiteFolder + "\n");
        for (Note note : notes) {
            output("NOTE  " + relative(suiteFolder, note.path()) + ": " + note.message() + "\n");
        }

        validateAll(suiteFolder, copiesByContent, shapes, ruleNames, dataTypeMap, validations, notes, warnings);
        updateProgress(0.95);

        List<RuleResult> rules = new ArrayList<>();
        for (RuleFolder folder : ruleFolders.values()) {
            RuleResult result = evaluate(folder, ruleNames, contentByModel, validations);
            rules.add(result);
            output(String.format(Locale.ROOT, "%-5s %s%s\n", result.verdict().name(), result.rule(),
                    result.message().isEmpty() ? "" : ": " + result.message()));
        }
        for (String warning : warnings) {
            output("WARNING " + warning + "\n");
        }

        ShaclRuleTestReport report = new ShaclRuleTestReport(suiteFolder, options.getShapeFiles(),
                describeDatatypeMap(), options.getXmlBase(), started, rules, List.copyOf(notes),
                List.copyOf(warnings), models.size(), copiesByContent.size(), null);
        Path workbook = suiteFolder.resolve(WORKBOOK_PREFIX
                + started.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx");
        try {
            ShaclRuleTestWorkbook.write(report, PathPolicy.checkWriteIfActive(workbook));
            report = report.withWorkbook(workbook);
        } catch (IOException | RuntimeException ex) {
            // The verdicts are in the Output pane and the report; losing them over the workbook would be worse.
            output("WARNING The results workbook could not be written: " + describe(ex) + "\n");
        }

        output(report.count(Verdict.PASS) + " passed, " + report.count(Verdict.FAIL) + " failed, "
                + report.count(Verdict.ERROR) + " could not be tested."
                + (report.getWorkbook() == null ? "" : " Results: " + report.getWorkbook()) + "\n");
        updateProgress(1);
        return report;
    }

    // ---- reading the suite ---------------------------------------------------

    /**
     * A rule folder: the rule it names, the model archives of its Conform and NonConform folders,
     * the loose {@code .xml} files beside them (not models: a model is an archive), and what in the
     * folder was not tested.
     */
    private record RuleFolder(String rule, List<Path> conform, List<Path> nonConform,
                              int looseConformXml, int looseNonConformXml, List<Note> untested) {
    }

    /**
     * The rule folders of the suite, by rule name. Model archives outside a Conform or NonConform
     * folder are added to {@code notes}, so a misplaced model is reported rather than skipped.
     */
    private static Map<String, RuleFolder> readSuite(Path suiteFolder, List<Note> notes) throws IOException {
        Map<String, RuleFolder> rules = new TreeMap<>(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
        for (Path entry : children(suiteFolder)) {
            if (Files.isDirectory(entry)) {
                readRuleFolder(entry, rules, notes);
            } else if (isModel(entry)) {
                notes.add(new Note(entry, "Not tested: a model belongs in <rule>/" + CONFORM
                        + " or <rule>/" + NON_CONFORM + "."));
            }
        }
        return rules;
    }

    /**
     * Reads a top-level folder of the suite. It is a rule folder when it has a Conform or a
     * NonConform folder, or holds model archives anywhere: a folder of archives without them is a
     * rule whose models were not tested, and is reported as one rather than left out.
     */
    private static void readRuleFolder(Path folder, Map<String, RuleFolder> rules, List<Note> notes) throws IOException {
        List<Path> conform = new ArrayList<>();
        List<Path> nonConform = new ArrayList<>();
        List<Note> untested = new ArrayList<>();
        int looseConformXml = 0;
        int looseNonConformXml = 0;
        boolean hasConformOrNonConform = false;
        for (Path entry : children(folder)) {
            String name = entry.getFileName().toString();
            if (Files.isDirectory(entry)) {
                if (CONFORM.equalsIgnoreCase(name)) {
                    hasConformOrNonConform = true;
                    looseConformXml += readModels(entry, conform, untested);
                } else if (NON_CONFORM.equalsIgnoreCase(name)) {
                    hasConformOrNonConform = true;
                    looseNonConformXml += readModels(entry, nonConform, untested);
                } else if (containsModels(entry)) {
                    untested.add(new Note(entry, "Not tested: only the " + CONFORM + " and " + NON_CONFORM
                            + " folders of a rule are read."));
                }
            } else if (isModel(entry)) {
                untested.add(new Note(entry, "Not tested: move it into the " + CONFORM + " or " + NON_CONFORM + " folder."));
            }
        }
        notes.addAll(untested);
        if (hasConformOrNonConform || !untested.isEmpty()) {
            String rule = folder.getFileName().toString();
            rules.put(rule, new RuleFolder(rule, conform, nonConform, looseConformXml, looseNonConformXml,
                    List.copyOf(untested)));
        }
    }

    /**
     * Adds the model archives of a Conform or NonConform folder to {@code models}, and to
     * {@code untested} the folders inside it that hold archives.
     *
     * @return how many loose {@code .xml} files the folder holds
     */
    private static int readModels(Path folder, List<Path> models, List<Note> untested) throws IOException {
        int looseXml = 0;
        for (Path entry : children(folder)) {
            if (Files.isDirectory(entry)) {
                if (containsModels(entry)) {
                    untested.add(new Note(entry, "Not tested: models must be directly in the " + CONFORM
                            + " or " + NON_CONFORM + " folder, not in a folder inside it."));
                }
            } else if (isModel(entry)) {
                models.add(entry);
            } else if (Files.isRegularFile(entry)
                    && entry.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml")) {
                looseXml++;
            }
        }
        return looseXml;
    }

    /** A model archive. Other files - reports, notes, unzipped copies - are not models. */
    private static boolean isModel(Path file) {
        return Files.isRegularFile(file)
                && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    /**
     * Whether a folder holds model archives at any depth. Symbolic links are followed, as they
     * are when the suite is read, so a linked folder of archives is noted rather than skipped; a
     * link loop fails the run.
     */
    private static boolean containsModels(Path folder) throws IOException {
        try (Stream<Path> walk = Files.walk(folder, FileVisitOption.FOLLOW_LINKS)) {
            return walk.anyMatch(ShaclRuleTester::isModel);
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
    }

    private static List<Path> children(Path folder) throws IOException {
        try (Stream<Path> list = Files.list(folder)) {
            return list.sorted(Comparator.comparing((Path p) -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Comparator.naturalOrder())).toList();
        }
    }

    // ---- grouping models by content -----------------------------------------

    /**
     * The models grouped by a digest of their bytes, in suite order. {@code contentByModel}
     * receives every model's group key. A model that cannot be read gets a key of its own, outside
     * the groups, and its error in {@code validations}: it fails its rules' tests, not the run.
     */
    private static Map<String, List<Path>> groupByContent(List<Path> models, Map<Path, String> contentByModel,
                                                          Map<String, Validation> validations) {
        Map<String, List<Path>> copies = new LinkedHashMap<>();
        for (Path model : models) {
            String content;
            try {
                content = sha256(model);
                copies.computeIfAbsent(content, key -> new ArrayList<>()).add(model);
            } catch (IOException ex) {
                content = "unreadable:" + model;
                validations.put(content, Validation.failed("The file could not be read: " + describe(ex)));
            }
            contentByModel.put(model, content);
        }
        return copies;
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file)) {
            for (int read; (read = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    // ---- validation ----------------------------------------------------------

    /**
     * What validating one distinct model found: the rules it triggered, its findings in total and
     * by rule as its report lists them, or why it could not be validated.
     */
    private record Validation(Set<String> triggered, Map<String, Integer> findingsByRule, int findings,
                              String error) {

        static Validation failed(String error) {
            return new Validation(Set.of(), Map.of(), 0, error);
        }

        /**
         * The rule's findings: none unless one of its shapes fired, which is decided by shape node,
         * and then as many as the report lists, at least one.
         */
        int findingsOf(String rule) {
            return triggered.contains(rule) ? Math.max(1, findingsByRule.getOrDefault(rule, 0)) : 0;
        }
    }

    /**
     * Validates each distinct model once, several at a time, and writes its reports beside every
     * copy. Fills {@code validations} by content key.
     */
    private void validateAll(Path suiteFolder, Map<String, List<Path>> copiesByContent, Model shapes,
                             RuleNames ruleNames, Map<String, RDFDatatype> dataTypeMap,
                             Map<String, Validation> validations, List<Note> notes, Set<String> warnings)
            throws IOException {
        int distinct = copiesByContent.size();
        if (distinct == 0) {
            return;
        }
        int concurrent = concurrentModels(distinct);
        int workersPerModel = workersPerModel(concurrent);
        ValidationTools.logValidationDebug("rule test: models=" + distinct + " concurrent=" + concurrent
                + " workersPerModel=" + workersPerModel);

        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(concurrent);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (Map.Entry<String, List<Path>> group : copiesByContent.entrySet()) {
                futures.add(pool.submit(() -> {
                    List<Path> copies = group.getValue();
                    Validation validation = validate(suiteFolder, copies, shapes, ruleNames, dataTypeMap,
                            workersPerModel, notes, warnings);
                    validations.put(group.getKey(), validation);
                    String model = relative(suiteFolder, copies.getFirst())
                            + (copies.size() > 1 ? " (and " + (copies.size() - 1) + " identical copies)" : "");
                    output(validation.error() == null
                            ? "Validated " + model + ": " + validation.findings() + " finding(s)\n"
                            : "ERROR " + model + ": " + validation.error() + "\n");
                    updateProgress(0.95 * done.incrementAndGet() / distinct);
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("The rule test was interrupted");
        } catch (ExecutionException ex) {
            // validate() turns every failure of a model into its error, so this is a JVM error.
            if (ex.getCause() instanceof Error error) {
                throw error;
            }
            throw new IOException("Rule test failed: " + ex.getCause().getMessage(), ex.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Validates the first of identical copies and writes its reports beside each of them. */
    private Validation validate(Path suiteFolder, List<Path> copies, Model shapes, RuleNames ruleNames,
                                Map<String, RDFDatatype> dataTypeMap, int workers, List<Note> notes,
                                Set<String> warnings) {
        SHACLValidationReport report;
        try {
            report = new SHACLValidator(SHACLValidationOptions.builder()
                    .shapesModel(shapes)
                    .dataFiles(copies.getFirst())
                    .datatypeMap(dataTypeMap)
                    .xmlBase(options.getXmlBase())
                    .workers(workers)
                    .build()).validate();
        } catch (IOException | RuntimeException ex) {
            return Validation.failed(describe(ex));
        } catch (StackOverflowError ex) {
            // Jena's parsers recurse, so an input nested deeply enough exhausts the stack.
            return Validation.failed("The model is nested too deeply to be read.");
        }
        // The map warning is the run's own, worded for rules; the validator's would repeat it per model.
        report.getWarnings().stream()
                .filter(warning -> !warning.startsWith("No datatype map was applied"))
                .forEach(warnings::add);
        writeReports(suiteFolder, copies, report, notes);
        return countFindings(report, ruleNames);
    }

    /**
     * Writes the model's report beside the first copy and copies the file beside the others,
     * which have the same content and so the same report.
     */
    private void writeReports(Path suiteFolder, List<Path> copies, SHACLValidationReport report, List<Note> notes) {
        if (options.isExcelReports()) {
            writeReport(suiteFolder, copies, ".xlsx", notes, target -> ExcelTools.exportSHACLValidationToExcel(
                    report.getResults(), target.getParent().toFile(), target.getFileName().toString()));
        }
        if (options.isTurtleReports()) {
            writeReport(suiteFolder, copies, ".ttl", notes, report::writeTurtle);
        }
    }

    private interface ReportWriter {
        void write(Path target) throws IOException;
    }

    private void writeReport(Path suiteFolder, List<Path> copies, String extension, List<Note> notes,
                             ReportWriter writer) {
        Path first = null;
        for (Path copy : copies) {
            Path target = copy.resolveSibling(FilenameUtils.removeExtension(copy.getFileName().toString())
                    + "_report" + extension);
            try {
                target = PathPolicy.checkWriteIfActive(target);
                if (first == null) {
                    writer.write(target);
                    first = target;
                } else {
                    Files.copy(first, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException ex) {
                String message = "The model's report could not be written: " + describe(ex);
                notes.add(new Note(target, message));
                output("NOTE  " + relative(suiteFolder, target) + ": " + message + "\n");
            }
        }
    }

    /**
     * Which rules the model triggered, and its findings in total and by rule.
     * <p>
     * Whether a rule fired is decided by the source shape node of each finding in the report
     * graph, which is exact. The counts come from the results instead, which are the rows of the
     * model's report: they drop the duplicate findings Jena reports for an aggregate
     * {@code sh:sparql} constraint, and carry their shape's {@code sh:name} values joined by
     * {@code " | "}. Split on that, a name which itself contains the separator could be credited
     * to another rule, so the counts never decide a verdict.
     */
    private static Validation countFindings(SHACLValidationReport report, RuleNames ruleNames) {
        Set<String> triggered = new HashSet<>();
        ResIterator findings = report.getReportModel().listResourcesWithProperty(RDF.type, SH.ValidationResult);
        while (findings.hasNext()) {
            Resource shape = findings.next().getPropertyResourceValue(SH.sourceShape);
            if (shape != null) {
                triggered.addAll(ruleNames.of(shape.asNode()));
            }
        }
        Map<String, Integer> byRule = new HashMap<>();
        for (SHACLValidationResult result : report.getResults()) {
            for (String rule : ruleNamesOf(result)) {
                byRule.merge(rule, 1, Integer::sum);
            }
        }
        return new Validation(Set.copyOf(triggered), Map.copyOf(byRule), report.getResults().size(), null);
    }

    /**
     * The rule names a result is counted under: the {@code sh:name} values of its source shape,
     * which it carries joined by {@code " | "}, and the whole string too, for a name that itself
     * contains the separator.
     */
    private static Set<String> ruleNamesOf(SHACLValidationResult result) {
        String name = result.getName();
        if (name == null || name.isEmpty()) {
            return Set.of();
        }
        Set<String> names = new HashSet<>(Arrays.asList(name.split(" \\| ")));
        names.add(name);
        return names;
    }

    // ---- verdicts ------------------------------------------------------------

    /**
     * The {@code sh:name} values of the shapes, by shape node; all the names declared; and those
     * carried by a shape that is not deactivated.
     */
    private record RuleNames(Map<Node, Set<String>> byShape, Set<String> declared, Set<String> active) {

        static RuleNames of(Model shapes) {
            Map<Node, Set<String>> byShape = new HashMap<>();
            Set<String> declared = new HashSet<>();
            Set<String> active = new HashSet<>();
            for (StmtIterator it = shapes.listStatements(null, SH.name, (RDFNode) null); it.hasNext(); ) {
                Statement statement = it.next();
                if (!statement.getObject().isLiteral()) {
                    continue;
                }
                String name = statement.getLiteral().getLexicalForm();
                byShape.computeIfAbsent(statement.getSubject().asNode(), node -> new HashSet<>()).add(name);
                declared.add(name);
                if (!statement.getSubject().hasLiteral(SH.deactivated, true)) {
                    active.add(name);
                }
            }
            return new RuleNames(byShape, declared, active);
        }

        /** The names of the shape a finding came from; the same nodes, as the report is built from these shapes. */
        Set<String> of(Node shape) {
            return byShape.getOrDefault(shape, Set.of());
        }
    }

    private static RuleResult evaluate(RuleFolder folder, RuleNames ruleNames, Map<Path, String> contentByModel,
                                       Map<String, Validation> validations) {
        String rule = folder.rule();
        boolean declared = ruleNames.declared().contains(rule);
        List<ModelResult> models = new ArrayList<>();
        for (Path model : folder.conform()) {
            models.add(check(model, true, rule, declared, validations.get(contentByModel.get(model))));
        }
        for (Path model : folder.nonConform()) {
            models.add(check(model, false, rule, declared, validations.get(contentByModel.get(model))));
        }

        if (!declared) {
            String similar = ruleNames.declared().stream()
                    .filter(name -> name.equalsIgnoreCase(rule))
                    .findFirst().map(name -> "; the closest is \"" + name + "\".").orElse(".");
            return new RuleResult(rule, Verdict.ERROR,
                    "No shape in the constraint files has sh:name \"" + rule + "\"" + similar, models);
        }
        if (!ruleNames.active().contains(rule)) {
            return new RuleResult(rule, Verdict.ERROR,
                    "Every shape with this sh:name is deactivated (sh:deactivated true).", models);
        }
        if (models.isEmpty()) {
            return new RuleResult(rule, Verdict.ERROR, folder.untested().isEmpty()
                    ? "Its " + CONFORM + " and " + NON_CONFORM + " folders hold no model archives (.zip)"
                            + loose(folder.looseConformXml() + folder.looseNonConformXml()) + "."
                    : "None of its models is in a " + CONFORM + " or " + NON_CONFORM
                            + " folder, so none was tested (see Notes).", models);
        }

        // A pass means the rule was seen both to stay silent and to fire, with nothing in its folder
        // skipped: a test with a side missing, or with archives it did not read, cannot show that.
        List<String> gaps = new ArrayList<>();
        if (folder.conform().isEmpty()) {
            gaps.add("no " + CONFORM + " models" + loose(folder.looseConformXml()));
        }
        if (folder.nonConform().isEmpty()) {
            gaps.add("no " + NON_CONFORM + " models" + loose(folder.looseNonConformXml()));
        }
        if (!folder.untested().isEmpty()) {
            gaps.add(folder.untested().size() + " item(s) in its folder were not tested (see Notes)");
        }
        String incomplete = gaps.isEmpty() ? "" : "Not completely tested: " + String.join(", ", gaps) + ".";

        long errors = models.stream().filter(m -> m.outcome() == Outcome.ERROR).count();
        if (errors > 0) {
            return new RuleResult(rule, Verdict.ERROR,
                    join(errors + " model(s) could not be validated.", incomplete), models);
        }
        long unexpected = models.stream().filter(m -> m.outcome() == Outcome.UNEXPECTED_TRIGGER).count();
        long missed = models.stream().filter(m -> m.outcome() == Outcome.EXPECTED_TRIGGER_NOT_FOUND).count();
        if (unexpected + missed > 0) {
            List<String> reasons = new ArrayList<>();
            if (unexpected > 0) {
                reasons.add(unexpected + " " + CONFORM + " model(s) triggered the rule");
            }
            if (missed > 0) {
                reasons.add(missed + " " + NON_CONFORM + " model(s) did not trigger it");
            }
            // A failure stays a failure: it is a finding about the rule, whatever else was not tested.
            return new RuleResult(rule, Verdict.FAIL, join(String.join("; ", reasons) + ".", incomplete), models);
        }
        if (!gaps.isEmpty()) {
            return new RuleResult(rule, Verdict.ERROR, incomplete, models);
        }
        return new RuleResult(rule, Verdict.PASS, "", models);
    }

    /** Points out loose {@code .xml} files where models were expected: a model is read only from an archive. */
    private static String loose(int xmlFiles) {
        return xmlFiles == 0 ? ""
                : " (" + xmlFiles + " loose .xml file(s) there, which are read only inside a .zip archive)";
    }

    private static String join(String first, String second) {
        return second.isEmpty() ? first : first + " " + second;
    }

    private static ModelResult check(Path model, boolean conform, String rule, boolean declared, Validation validation) {
        if (validation == null) {
            return new ModelResult(model, conform, 0, 0, Outcome.ERROR, "The model was not validated.");
        }
        if (validation.error() != null) {
            return new ModelResult(model, conform, 0, 0, Outcome.ERROR, validation.error());
        }
        int ruleFindings = validation.findingsOf(rule);
        int otherFindings = Math.max(0, validation.findings() - ruleFindings);
        if (!declared) {
            return new ModelResult(model, conform, 0, validation.findings(), Outcome.NOT_TESTED,
                    "No shape has this sh:name.");
        }
        if (conform) {
            return ruleFindings == 0
                    ? new ModelResult(model, true, 0, otherFindings, Outcome.PASS, "")
                    : new ModelResult(model, true, ruleFindings, otherFindings, Outcome.UNEXPECTED_TRIGGER,
                    "The rule fired on a model that must conform to it.");
        }
        return ruleFindings > 0
                ? new ModelResult(model, false, ruleFindings, otherFindings, Outcome.PASS, "")
                : new ModelResult(model, false, 0, otherFindings, Outcome.EXPECTED_TRIGGER_NOT_FOUND,
                "The rule did not fire on a model that must break it.");
    }

    // ---- workers -------------------------------------------------------------

    /** Models validated at once: as many as asked for, or as the heap allows. */
    private int concurrentModels(int distinct) {
        int limit = options.getWorkers() > 0
                ? options.getWorkers()
                : (int) Math.clamp(Runtime.getRuntime().maxMemory() / HEAP_PER_MODEL, 1, MAX_CONCURRENT_MODELS);
        return Math.clamp(Math.min(limit, distinct), 1, MAX_CONCURRENT_MODELS);
    }

    /** Target-shape threads for each model: what is left of the budget, shared out. */
    private int workersPerModel(int concurrent) {
        int budget = options.getWorkers() > 0
                ? options.getWorkers()
                : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        return Math.max(1, budget / concurrent);
    }

    // ---- helpers -------------------------------------------------------------

    private String describeDatatypeMap() {
        if (options.getDatatypeMap() != null) {
            return "Custom map (" + options.getDatatypeMap().size() + " properties)";
        }
        if (options.getDatatypeMapFile() != null) {
            return options.getDatatypeMapFile().toString();
        }
        DatatypeMapPreset preset = options.getDatatypeMapPreset();
        return preset == null ? DatatypeMapPreset.NONE.displayName() : preset.displayName();
    }

    private static String describe(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    static String relative(Path suiteFolder, Path path) {
        return path.startsWith(suiteFolder) ? suiteFolder.relativize(path).toString() : path.toString();
    }

    private void updateProgress(double progress) {
        if (callback != null) {
            callback.updateProgress(progress);
        }
    }

    private void output(String message) {
        if (callback != null) {
            callback.appendOutput(message);
        }
    }
}
