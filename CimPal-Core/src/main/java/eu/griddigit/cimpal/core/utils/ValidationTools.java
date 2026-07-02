package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.ValidationReport;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import java.time.LocalDate;
import java.time.ZoneOffset;

public class ValidationTools {


    private static final boolean DEBUG = true;
    private static final boolean DEBUG_TO_CONSOLE = false;

    private static final Path DEBUG_LOG_PATH = Paths.get(
            "C:\\Temp\\cimpal_validation_debug.log"
    );

    private static final Object DEBUG_LOCK = new Object();
    private static final String XML_REPORT_PREFIX =
            "C:\\GitHub\\relicapgrid\\Instance\\";

    private static final String CONSTRAINT_REPORT_PREFIX =
            "C:\\SHACL-Constraints\\ApplicationLibraryValidationConfigurations\\";

    private static final long FUTURE_TIMEOUT_MINUTES = 60;
    private static final int DEBUG_MAX_RESULTS_PER_ROW = 5000;

    private static final boolean WRITE_SUMMARY_CHECKPOINT_EACH_TIMESTAMP = false;

    private ValidationTools() { }

    public static class MappingRow {
        public final String xmlInputsRaw;
        public final String ttl;
        public final String notes;

        public MappingRow(String xmlInputsRaw, String ttl, String notes) {
            this.xmlInputsRaw = xmlInputsRaw;
            this.ttl = ttl;
            this.notes = notes;
        }
    }

    /**
     * New workflow for timestamped files:
     *  - input can be a folder or a zip file
     *  - discover XML files once
     *  - read timestamp/profile/TSO metadata once
     *  - group files by TSO and timestamp
     *  - run the existing mapping logic once per timestamp
     *  - create one XLSX per timestamp and one aggregated XLSX per TSO
     *
     * This does not replace validateByMapping(...). It is a separate trigger point.
     */
    public static List<Path> validateByTimestampedMapping(Path mappingCsvPath,
                                                          Path inputPath,
                                                          Path constraintsRoot,
                                                          Path outputBaseDir,
                                                          int threadCount,
                                                          Map<String, RDFDatatype> dataTypeMap,
                                                          String xmlBase) throws IOException {

        long allStart = System.currentTimeMillis();

        if (DEBUG) {
            try {
                Files.deleteIfExists(DEBUG_LOG_PATH);
            } catch (IOException e) {
                System.err.println("[DBG_LOG_ERROR] Could not delete old debug log: " + e.getMessage());
            }
        }

        dbg("START validateByTimestampedMapping");
        printMemory("start validateByTimestampedMapping");

        Files.createDirectories(outputBaseDir);

        List<MappingRow> mappingRows = readMappingCsv(mappingCsvPath);

        int threads = (threadCount > 0)
                ? threadCount
                : Math.min(Math.max(1, Runtime.getRuntime().availableProcessors() - 1), 6);

        consoleInput("validateByTimestampedMapping");
        consoleInput("mappingCsvPath=" + mappingCsvPath.toAbsolutePath());
        consoleInput("inputPath=" + inputPath.toAbsolutePath());
        consoleInput("constraintsRoot=" + constraintsRoot.toAbsolutePath());
        consoleInput("outputBaseDir=" + outputBaseDir.toAbsolutePath());
        consoleInput("mapping rows=" + mappingRows.size());
        consoleInput("threads=" + threads);

        Map<Path, Model> shapesCache = new ConcurrentHashMap<>();
        List<Path> createdReports = new ArrayList<>();

        List<InputGroup> inputGroups = prepareTimestampedInputGroups(inputPath, outputBaseDir);

        consoleInput("input groups=" + inputGroups.size());

        try (ValidationExcelWriter allCountriesSummaryWriter = ValidationExcelWriter.createTimestampedSummaryWriter()) {

            for (InputGroup inputGroup : inputGroups) {
                long inputGroupStart = System.currentTimeMillis();

                Map<Path, Model> staticXmlModelCache = new ConcurrentHashMap<>();

                logInfo("START input group=" + inputGroup.name
                        + " roots=" + inputGroup.roots.stream()
                        .map(Path::toString)
                        .collect(Collectors.joining("; ")));

                printMemory("before input group " + inputGroup.name);

                List<XmlFileMetadata> metadata = discoverXmlMetadataForInputGroup(inputGroup);

                if (metadata.isEmpty()) {
                    logWarn("No XML metadata discovered for input group=" + inputGroup.name);
                    continue;
                }

                TsoFileIndex tsoIndex = buildSingleInputGroupIndex(inputGroup.name, metadata);

                if (tsoIndex.byTimestamp.isEmpty()) {
                    logWarn("No timestamp groups discovered for input group=" + inputGroup.name);
                    continue;
                }

                Path groupOutputDir = outputBaseDir.resolve(sanitizePathPart(inputGroup.name));
                Files.createDirectories(groupOutputDir);

                try (ValidationExcelWriter summaryWriter = ValidationExcelWriter.createTimestampedSummaryWriter()) {

                    for (TimestampGroup timestampGroup : tsoIndex.byTimestamp.values()) {
                        long timestampStart = System.currentTimeMillis();

                        logInfo("START timestamp run inputGroup=" + inputGroup.name
                                + " timestamp=" + timestampGroup.timestamp);

                        printMemory("before timestamp run "
                                + inputGroup.name + " " + timestampGroup.timestamp);

                        ResolvedRowsAndInputChecks resolved =
                                buildResolvedRowsForTimestampKeepingPairings(
                                        mappingRows,
                                        tsoIndex,
                                        timestampGroup,
                                        constraintsRoot
                                );

                        List<ResolvedMappingRow> resolvedRows = resolved.resolvedRows;

                        appendMappingRowInputChecks(
                                summaryWriter,
                                resolved.inputChecks
                        );

                        appendMappingRowInputChecks(
                                allCountriesSummaryWriter,
                                resolved.inputChecks
                        );

                        if (resolvedRows.isEmpty()) {
                            logWarn("No resolved validation rows for inputGroup="
                                    + inputGroup.name + " timestamp=" + timestampGroup.timestamp);

                            continue;
                        }

                        Map<Path, Model> timestampXmlModelCache = new ConcurrentHashMap<>();

                        List<ValidationTaskResult> results;

                        try {
                            results = executeResolvedRows(
                                    resolvedRows,
                                    constraintsRoot,
                                    shapesCache,
                                    staticXmlModelCache,
                                    timestampXmlModelCache,
                                    threads,
                                    dataTypeMap,
                                    xmlBase
                            );
                        } finally {
                            timestampXmlModelCache.clear();

                            printMemory("after clearing timestampXmlModelCache "
                                    + inputGroup.name + " " + timestampGroup.timestamp);
                        }

                        Path timestampReport;

                        try (ValidationExcelWriter timestampWriter = new ValidationExcelWriter()) {
                            appendTaskResultsToWriter(timestampWriter, results);

                            timestampReport = saveTimestampReport(
                                    timestampWriter,
                                    groupOutputDir,
                                    inputGroup.name,
                                    timestampGroup.timestamp
                            );

                            createdReports.add(timestampReport);

                            consoleReport("Timestamp report created: " + timestampReport.toAbsolutePath());
                        }

                        appendTimestampSummary(
                                summaryWriter,
                                inputGroup.name,
                                timestampGroup.timestamp,
                                timestampReport,
                                results
                        );

                        appendTimestampSummary(
                                allCountriesSummaryWriter,
                                inputGroup.name,
                                timestampGroup.timestamp,
                                timestampReport,
                                results
                        );

                        if (WRITE_SUMMARY_CHECKPOINT_EACH_TIMESTAMP) {
                            Path checkpointSummary = summaryWriter.saveTo(groupOutputDir);
                            createdReports.add(checkpointSummary);

                            consoleReport("Timestamped summary checkpoint created: "
                                    + checkpointSummary.toAbsolutePath());
                        }

                        logInfo("DONE timestamp run inputGroup=" + inputGroup.name
                                + " timestamp=" + timestampGroup.timestamp
                                + " resolvedRows=" + resolvedRows.size()
                                + " results=" + results.size());

                        dbg("DONE timestamp run inputGroup=" + inputGroup.name
                                        + " timestamp=" + timestampGroup.timestamp,
                                timestampStart);

                        printMemory("after timestamp run "
                                + inputGroup.name + " " + timestampGroup.timestamp);
                    }

                    Path summaryReport = summaryWriter.saveTo(groupOutputDir);
                    createdReports.add(summaryReport);

                    consoleReport("Timestamped summary report created: " + summaryReport.toAbsolutePath());
                }

                staticXmlModelCache.clear();

                printMemory("after clearing staticXmlModelCache inputGroup=" + inputGroup.name);

                logInfo("DONE input group=" + inputGroup.name);

                dbg("DONE input group=" + inputGroup.name, inputGroupStart);
            }

            Path allCountriesSummaryReport = allCountriesSummaryWriter.saveTo(outputBaseDir);
            createdReports.add(allCountriesSummaryReport);

            consoleReport("All-countries timestamped summary report created: "
                    + allCountriesSummaryReport.toAbsolutePath());
        }

        dbg("DONE validateByTimestampedMapping", allStart);
        printMemory("end validateByTimestampedMapping");

        return createdReports;
    }
    /**
     * Validate directly from the mapping (no zips required):
     *  - resolve XML file(s) from xml_inputs (supports wildcards in filename)
     *  - resolve TTL under ApplicationLibraryValidationConfigurations root
     *  - cache TTL shapes models
     *  - validate data model vs shapes model
     *  - write one Excel report: one sheet per CaseFolder
     */
    public static Path validateByMapping(Path mappingCsvPath,
                                         Path modelsBaseDir,
                                         Path constraintsRoot,
                                         Path outputBaseDir,
                                         int threadCount,
                                         Map<String, RDFDatatype> dataTypeMap,
                                         String xmlBase
    ) throws IOException {

        long allStart = System.currentTimeMillis();

        if (DEBUG) {
            try {
                Files.deleteIfExists(DEBUG_LOG_PATH);
            } catch (IOException e) {
                System.err.println("[DBG_LOG_ERROR] Could not delete old debug log: " + e.getMessage());
            }
        }

        dbg("START validateByMapping");
        printMemory("start validateByMapping");

        dbg("START read mapping csv: " + mappingCsvPath.toAbsolutePath());
        List<MappingRow> rows = readMappingCsv(mappingCsvPath);
        dbg("DONE read mapping csv rows=" + rows.size());

        dbg("START create output directory: " + outputBaseDir.toAbsolutePath());
        Files.createDirectories(outputBaseDir);
        dbg("DONE create output directory");

        int threads = (threadCount > 0)
                ? threadCount
                : Math.min(Math.max(1, Runtime.getRuntime().availableProcessors() - 1), 6);

        System.out.println("[INFO] validateByMapping rows=" + rows.size() + " threads=" + threads);
        System.out.println("[INFO] mappingCsvPath=" + mappingCsvPath.toAbsolutePath());
        System.out.println("[INFO] modelsBaseDir=" + modelsBaseDir.toAbsolutePath());
        System.out.println("[INFO] constraintsRoot=" + constraintsRoot.toAbsolutePath());
        System.out.println("[INFO] outputBaseDir=" + outputBaseDir.toAbsolutePath());
        System.out.println("[INFO] dataTypeMap size=" + (dataTypeMap == null ? "null" : dataTypeMap.size()));
        System.out.println("[INFO] xmlBase=" + xmlBase);

        Map<Path, Model> shapesCache = new ConcurrentHashMap<>();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<ValidationTaskResult>> tasks = new ArrayList<>();

        dbg("START create validation tasks");

        int rowIdx = 0;
        for (MappingRow row : rows) {
            rowIdx++;
            final int idx = rowIdx;

            if (row.xmlInputsRaw == null || row.xmlInputsRaw.trim().isEmpty()
                    || row.ttl == null || row.ttl.trim().isEmpty()) {
                dbgRow(idx, "SKIP row because xmlInputsRaw or ttl is empty");
                continue;
            }

            dbgRow(idx, "ADD task ttl=" + row.ttl + " xmlRaw=" + shortValue(row.xmlInputsRaw, 300));

            tasks.add(() -> {
                try {
                    return validateOneRow(idx, row, modelsBaseDir, constraintsRoot, shapesCache, dataTypeMap, xmlBase);
                } catch (Throwable t) {
                    System.err.println("[WORKER_ERROR][" + Thread.currentThread().getName() + "][row " + idx + "]");
                    t.printStackTrace();
                    throw t;
                }
            });
        }

        dbg("DONE create validation tasks count=" + tasks.size());

        List<Future<ValidationTaskResult>> futures = new ArrayList<>();

        try {
            dbg("START submit tasks");

            for (Callable<ValidationTaskResult> task : tasks) {
                futures.add(pool.submit(task));
            }

            dbg("DONE submit tasks futures=" + futures.size());

        } finally {
            dbg("START pool shutdown");
            pool.shutdown();
            dbg("DONE pool shutdown requested");
        }

        int ok = 0;
        int fail = 0;
        int err = 0;

        List<String> nonConforms = new ArrayList<>();
        List<String> errorModels = new ArrayList<>();

        Path reportPath;

        dbg("START Excel writer / future collection");
        printMemory("before future collection and excel writer");

        try (ValidationExcelWriter writer = new ValidationExcelWriter()) {
            dbg("DONE create ValidationExcelWriter");

            for (int i = 0; i < futures.size(); i++) {
                Future<ValidationTaskResult> f = futures.get(i);

                dbg("WAIT future index=" + i);

                ValidationTaskResult r;

                try {
                    r = f.get(FUTURE_TIMEOUT_MINUTES, TimeUnit.MINUTES);

                    dbg("DONE future index=" + i
                            + " row=" + r.rowIdx
                            + " dataset=" + r.datasetName
                            + " error=" + (r.error != null)
                            + " conforms=" + r.conforms
                            + " resultCount=" + (r.results == null ? "null" : r.results.size()));

                } catch (TimeoutException ex) {
                    err++;
                    f.cancel(true);

                    System.err.println("[TIMEOUT] future index=" + i
                            + " afterMinutes=" + FUTURE_TIMEOUT_MINUTES);

                    writer.appendError(
                            ValidationExcelWriter.CaseFolder.UNKNOWN,
                            "TIMEOUT_ROW",
                            "UNKNOWN_XML",
                            "UNKNOWN_CONSTRAINT",
                            new Exception("Validation future timed out after "
                                    + FUTURE_TIMEOUT_MINUTES + " minutes", ex)
                    );

                    continue;

                } catch (ExecutionException ex) {
                    err++;

                    System.err.println("[EXECUTION_ERROR] future index=" + i);
                    if (ex.getCause() != null) {
                        ex.getCause().printStackTrace();
                    } else {
                        ex.printStackTrace();
                    }

                    writer.appendError(
                            ValidationExcelWriter.CaseFolder.UNKNOWN,
                            "UNKNOWN_ROW",
                            "UNKNOWN_XML",
                            "UNKNOWN_CONSTRAINT",
                            new Exception(ex)
                    );

                    continue;

                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Validation interrupted while waiting for future index=" + i, ex);

                } catch (Exception ex) {
                    err++;

                    System.err.println("[FUTURE_ERROR] future index=" + i);
                    ex.printStackTrace();

                    writer.appendError(
                            ValidationExcelWriter.CaseFolder.UNKNOWN,
                            "UNKNOWN_ROW",
                            "UNKNOWN_XML",
                            "UNKNOWN_CONSTRAINT",
                            new Exception(ex)
                    );

                    continue;
                }

                ValidationExcelWriter.CaseFolder sheet = r.caseFolder;

                if (r.error != null) {
                    err++;

                    dbgRow(r.rowIdx, "START writer.appendError dataset=" + r.datasetName);
                    long appendStart = System.currentTimeMillis();

                    writer.appendError(sheet, r.datasetName, r.xmlFiles, r.constraintFile, r.error);

                    dbgRow(r.rowIdx, "DONE writer.appendError dataset=" + r.datasetName,
                            appendStart);

                    errorModels.add(r.datasetName);

                } else {
                    if (r.conforms) {
                        ok++;
                    } else {
                        fail++;
                        nonConforms.add(r.datasetName);
                    }

                    dbgRow(r.rowIdx, "START writer.appendValidation dataset=" + r.datasetName
                            + " resultCount=" + (r.results == null ? "null" : r.results.size())
                            + " conforms=" + r.conforms);
                    long appendStart = System.currentTimeMillis();


/*
                    //cap mmaximum row number to write inside the excel
                    List<SHACLValidationResult> resultsToWrite = r.results;
                    if (resultsToWrite != null && resultsToWrite.size() > DEBUG_MAX_RESULTS_PER_ROW) {
                        dbgRow(r.rowIdx, "WARN resultCount=" + resultsToWrite.size()
                                + " cappedTo=" + DEBUG_MAX_RESULTS_PER_ROW
                                + " for Excel debug output");

                        resultsToWrite = resultsToWrite.subList(0, DEBUG_MAX_RESULTS_PER_ROW);
                    }

                    writer.appendValidation(sheet, r.datasetName, r.xmlFiles, r.constraintFile, resultsToWrite, r.conforms);
*/
                    writer.appendValidation(sheet, r.datasetName, r.xmlFiles, r.constraintFile, r.results, r.conforms);

                    dbgRow(r.rowIdx, "DONE writer.appendValidation dataset=" + r.datasetName,
                            appendStart);
                }

                if (i == 0 || i % 10 == 0 || i == futures.size() - 1) {
                    printMemory("after future index=" + i);
                }
            }

            printMemory("before save excel");
            dbg("START writer.saveTo outputBaseDir=" + outputBaseDir.toAbsolutePath());
            long saveStart = System.currentTimeMillis();

            reportPath = writer.saveTo(outputBaseDir);

            dbg("DONE writer.saveTo reportPath=" + reportPath.toAbsolutePath(), saveStart);
            printMemory("after save excel");
        }

        System.out.println("[INFO] Done. ok=" + ok + " fail=" + fail + " error=" + err);
        System.out.println("List of failed models: ");
        nonConforms.forEach(System.out::println);
        System.out.println("List of error models: ");
        errorModels.forEach(System.out::println);

        System.out.println("[INFO] Excel report: " + reportPath.toAbsolutePath());

        dbg("DONE validateByMapping", allStart);
        printMemory("end validateByMapping");

        return reportPath;
    }

    // ---------------- core validation per row ----------------

    private static class ValidationTaskResult {
        final int rowIdx;
        final ValidationExcelWriter.CaseFolder caseFolder;
        final String datasetName;
        final String ttlName;
        final String xmlFiles;
        final String constraintFile;
        final List<eu.griddigit.cimpal.core.models.SHACLValidationResult> results;
        final boolean conforms;
        final Exception error;

        ValidationTaskResult(int rowIdx,
                             ValidationExcelWriter.CaseFolder caseFolder,
                             String datasetName,
                             String ttlName,
                             String xmlFiles,
                             String constraintFile,
                             List<eu.griddigit.cimpal.core.models.SHACLValidationResult> results,
                             boolean conforms,
                             Exception error) {
            this.rowIdx = rowIdx;
            this.caseFolder = caseFolder;
            this.datasetName = datasetName;
            this.ttlName = ttlName;
            this.xmlFiles = xmlFiles;
            this.constraintFile = constraintFile;
            this.results = results;
            this.conforms = conforms;
            this.error = error;
        }
    }

    private static ValidationTaskResult validateOneRow(int rowIdx,
                                                       MappingRow row,
                                                       Path modelsBaseDir,
                                                       Path constraintsRoot,
                                                       Map<Path, Model> shapesCache,
                                                       Map<String, RDFDatatype> dataTypeMap,
                                                       String xmlBase) {

        long rowStart = System.currentTimeMillis();

        String ttlName = row.ttl.trim();
        ValidationExcelWriter.CaseFolder caseFolder = categorizeForReport(row);

        String xmlFilesText = row.xmlInputsRaw;
        String constraintFileText = trimReportPath(ttlName, CONSTRAINT_REPORT_PREFIX);
        String datasetName = "UNKNOWN";

        dbgRow(rowIdx, "START row ttl=" + ttlName
                + " caseFolder=" + caseFolder
                + " xmlRaw=" + shortValue(row.xmlInputsRaw, 300));

        try {
            dbgRow(rowIdx, "START resolveFilesForRow");
            long resolveStart = System.currentTimeMillis();

            LinkedHashSet<Path> xmlFiles = resolveFilesForRow(row, modelsBaseDir);

            dbgRow(rowIdx, "DONE resolveFilesForRow xmlFiles=" + xmlFiles.size(), resolveStart);

            datasetName = makeDatasetName(xmlFiles);
            xmlFilesText = formatPaths(xmlFiles);

            if (xmlFiles.isEmpty()) {
                dbgRow(rowIdx, "ERROR no XML matched mapping tokens");
                return new ValidationTaskResult(rowIdx, caseFolder, datasetName, ttlName, xmlFilesText, constraintFileText, null, false,
                        new IOException("No XML matched mapping tokens"));
            }

            dbgRow(rowIdx, "START resolveTtlPath ttlName=" + ttlName);
            Path ttlPath = resolveTtlPath(constraintsRoot, ttlName);
            constraintFileText = trimReportPath(ttlPath.toString(), CONSTRAINT_REPORT_PREFIX);
            dbgRow(rowIdx, "DONE resolveTtlPath ttlPath=" + ttlPath.toAbsolutePath());

            if (!Files.exists(ttlPath)) {
                dbgRow(rowIdx, "ERROR TTL not found: " + ttlPath);
                return new ValidationTaskResult(rowIdx, caseFolder, datasetName, ttlName, xmlFilesText, constraintFileText, null, false,
                        new FileNotFoundException("TTL not found: " + ttlPath));
            }

            dbgRow(rowIdx, "START loadShapesWithLocalImports ttlPath=" + ttlPath.toAbsolutePath());
            long shapesStart = System.currentTimeMillis();

            Model shapesModel = loadShapesWithLocalImports(ttlPath, constraintsRoot, shapesCache);

            dbgRow(rowIdx, "DONE loadShapesWithLocalImports shapesTriples=" + shapesModel.size(),
                    shapesStart);

            if (shapesModel.size() < 200) {
                System.err.println("[WARN][row " + rowIdx + "] Shapes model seems too small ("
                        + shapesModel.size()
                        + " triples). Imports may not be loaded for: "
                        + ttlPath);
            }

            logShapeStats(rowIdx, shapesModel);

            dbgRow(rowIdx, "START loadRdfXmlFromFilesWithDatatypeMap xmlFiles=" + xmlFiles.size());
            long dataStart = System.currentTimeMillis();

            Model dataModel = loadRdfXmlFromFilesWithDatatypeMap(xmlFiles, dataTypeMap, xmlBase, rowIdx);

            dbgRow(rowIdx, "DONE loadRdfXmlFromFilesWithDatatypeMap dataTriples=" + dataModel.size(),
                    dataStart);

            dbgRow(rowIdx, "xmlFiles=" + xmlFiles.size());
            dbgRow(rowIdx, "data triples=" + dataModel.size());
            dbgRow(rowIdx, "shapes triples=" + shapesModel.size());

            xmlFiles.stream()
                    .limit(10)
                    .forEach(p -> dbgRow(rowIdx, "XML " + p));

            printMemory("[row " + rowIdx + "] before SHACL validation");

            dbgRow(rowIdx, "START SHACL validation");
            long validationStart = System.currentTimeMillis();

            ValidationReport report = ShaclValidator.get().validate(shapesModel.getGraph(), dataModel.getGraph());

            dbgRow(rowIdx, "DONE SHACL validation conforms=" + report.conforms(),
                    validationStart);

            printMemory("[row " + rowIdx + "] after SHACL validation");

            dbgRow(rowIdx, "START extractSHACLValidationResults");
            long extractStart = System.currentTimeMillis();

            List<eu.griddigit.cimpal.core.models.SHACLValidationResult> results =
                    ShaclTools.extractSHACLValidationResults(report, shapesModel);

            dbgRow(rowIdx, "DONE extractSHACLValidationResults resultCount="
                            + (results == null ? "null" : results.size()),
                    extractStart);

            dbgRow(rowIdx, "DONE row dataset=" + datasetName
                            + " conforms=" + report.conforms()
                            + " resultCount=" + (results == null ? "null" : results.size()),
                    rowStart);

            return new ValidationTaskResult(rowIdx, caseFolder, datasetName, ttlName, xmlFilesText, constraintFileText, results, report.conforms(), null);

        } catch (Exception ex) {
            dbgRow(rowIdx, "ERROR row dataset=" + datasetName, rowStart);
            ex.printStackTrace();

            return new ValidationTaskResult(rowIdx, caseFolder, datasetName, ttlName, xmlFilesText, constraintFileText, null, false, ex);
        }
    }

    // ---------------- mapping row -> file list ----------------

    private static LinkedHashSet<Path> resolveFilesForRow(MappingRow row, Path modelsBaseDir) throws IOException {
        List<String> tokens = parseXmlInputs(row.xmlInputsRaw);
        LinkedHashSet<Path> files = new LinkedHashSet<>();

        dbg("resolveFilesForRow tokens=" + tokens.size()
                + " raw=" + shortValue(row.xmlInputsRaw, 300));

        for (String token : tokens) {
            dbg("START expandToken token=" + token);

            long start = System.currentTimeMillis();
            List<Path> expanded = expandToken(modelsBaseDir, token);

            dbg("DONE expandToken token=" + token + " matches=" + expanded.size(), start);

            files.addAll(expanded);
        }

        return files;
    }



    private static Model loadRdfXmlFromFilesWithDatatypeMap(Collection<Path> xmlFiles,
                                                            Map<String, RDFDatatype> dataTypeMap,
                                                            String xmlBase,
                                                            int rowIdx) throws Exception {

        long startAll = System.currentTimeMillis();

        Model merged = ModelFactory.createDefaultModel();

        int fileIndex = 0;

        for (Path p : xmlFiles) {
            fileIndex++;

            if (!Files.isRegularFile(p)) {
                dbgRow(rowIdx, "SKIP non-regular file: " + p);
                continue;
            }

            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".xml")) {
                dbgRow(rowIdx, "SKIP non-xml file: " + p);
                continue;
            }

            dbgRow(rowIdx, "START load XML fileIndex=" + fileIndex
                    + "/" + xmlFiles.size()
                    + " path=" + p.toAbsolutePath());

            long fileStart = System.currentTimeMillis();

            try (InputStream in = Files.newInputStream(p)) {
                Model single = eu.griddigit.cimpal.core.utils.ModelFactory.modelLoadXMLmapping(in, dataTypeMap, xmlBase);

                dbgRow(rowIdx, "DONE modelLoadXMLmapping fileIndex=" + fileIndex
                        + " singleTriples=" + single.size()
                        + " path=" + p.getFileName(), fileStart);

                long mergeStart = System.currentTimeMillis();

                merged.add(single);
                merged.setNsPrefixes(single.getNsPrefixMap());

                dbgRow(rowIdx, "DONE merge XML fileIndex=" + fileIndex
                                + " mergedTriples=" + merged.size()
                                + " path=" + p.getFileName(),
                        mergeStart);
            }
        }

        dbgRow(rowIdx, "DONE load all XML files mergedTriples=" + merged.size(), startAll);

        return merged;
    }

    private static Model loadSingleRdfXmlWithDatatypeMap(Path xmlFile,
                                                         Map<String, RDFDatatype> dataTypeMap,
                                                         String xmlBase,
                                                         int rowIdx) {
        Path p = xmlFile.toAbsolutePath().normalize();

        if (!Files.isRegularFile(p)) {
            throw new IllegalArgumentException("XML file is not a regular file: " + p);
        }

        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xml")) {
            throw new IllegalArgumentException("File is not XML: " + p);
        }

        dbgRow(rowIdx, "START loadSingleRdfXmlWithDatatypeMap path=" + p);

        long start = System.currentTimeMillis();

        try (InputStream in = Files.newInputStream(p)) {
            Model single = eu.griddigit.cimpal.core.utils.ModelFactory.modelLoadXMLmapping(
                    in,
                    dataTypeMap,
                    xmlBase
            );

            dbgRow(rowIdx, "DONE loadSingleRdfXmlWithDatatypeMap"
                    + " triples=" + single.size()
                    + " path=" + p.getFileName(), start);

            return single;

        } catch (Exception ex) {
            throw new RuntimeException("Could not load XML file: " + p, ex);
        }
    }

    private static Model loadRdfXmlFromFilesWithCache(Collection<Path> xmlFiles,
                                                      Map<Path, Model> staticXmlModelCache,
                                                      Map<Path, Model> timestampXmlModelCache,
                                                      Map<String, RDFDatatype> dataTypeMap,
                                                      String xmlBase,
                                                      int rowIdx) {
        long startAll = System.currentTimeMillis();

        Model merged = ModelFactory.createDefaultModel();

        int fileIndex = 0;

        for (Path xml : xmlFiles) {
            fileIndex++;

            Path key = xml.toAbsolutePath().normalize();

            String profile = detectProfile(
                    key.getFileName() == null ? "" : key.getFileName().toString(),
                    key
            );

            boolean useStaticCache =
                    STATIC_PROFILES.contains(profile)
                            || BOUNDARY_PROFILES.contains(profile);

            Map<Path, Model> selectedCache = useStaticCache
                    ? staticXmlModelCache
                    : timestampXmlModelCache;

            dbgRow(rowIdx, "START get XML model from cache"
                    + " fileIndex=" + fileIndex + "/" + xmlFiles.size()
                    + " profile=" + profile
                    + " cache=" + (useStaticCache ? "STATIC" : "TIMESTAMP")
                    + " path=" + key);

            long cacheStart = System.currentTimeMillis();

            Model single = selectedCache.computeIfAbsent(
                    key,
                    p -> loadSingleRdfXmlWithDatatypeMap(p, dataTypeMap, xmlBase, rowIdx)
            );

            dbgRow(rowIdx, "DONE get XML model from cache"
                    + " fileIndex=" + fileIndex
                    + " profile=" + profile
                    + " cache=" + (useStaticCache ? "STATIC" : "TIMESTAMP")
                    + " singleTriples=" + single.size()
                    + " path=" + key.getFileName(), cacheStart);

            long mergeStart = System.currentTimeMillis();

            synchronized (single) {
                merged.add(single);
                merged.setNsPrefixes(single.getNsPrefixMap());
            }

            dbgRow(rowIdx, "DONE merge cached XML"
                    + " fileIndex=" + fileIndex
                    + " mergedTriples=" + merged.size()
                    + " path=" + key.getFileName(), mergeStart);
        }

        dbgRow(rowIdx, "DONE loadRdfXmlFromFilesWithCache"
                + " xmlFiles=" + xmlFiles.size()
                + " mergedTriples=" + merged.size(), startAll);

        return merged;
    }

    /**
     * Load all RDF/XML files into one model. Base URI is set so rdf:about="#..." is resolvable.
     */
    private static Model loadRdfXmlFromFiles(Collection<Path> xmlFiles) throws IOException {
        Model m = ModelFactory.createDefaultModel();

        int fileIndex = 0;

        for (Path p : xmlFiles) {
            fileIndex++;

            if (!Files.isRegularFile(p)) continue;

            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".xml")) continue;

            String base = p.toUri().toString() + "#";

            dbg("START loadRdfXmlFromFiles fileIndex=" + fileIndex + " path=" + p.toAbsolutePath());
            long start = System.currentTimeMillis();

            try (InputStream in = Files.newInputStream(p)) {
                RDFParser.source(in)
                        .lang(Lang.RDFXML)
                        .base(base)
                        .parse(m);
            }

            dbg("DONE loadRdfXmlFromFiles fileIndex=" + fileIndex
                    + " mergedTriples=" + m.size()
                    + " path=" + p.getFileName(), start);
        }

        return m;
    }

    // ---------------- zipping ----------------

    public static List<Path> zipByMapping(Path mappingCsvPath,
                                          Path modelsBaseDir,
                                          Path outputBaseDir) throws IOException {

        dbg("START zipByMapping");

        List<MappingRow> rows = readMappingCsv(mappingCsvPath);
        Files.createDirectories(outputBaseDir);

        List<Path> createdZips = new ArrayList<>();

        int rowIdx = 0;
        for (MappingRow row : rows) {
            rowIdx++;

            if (row.xmlInputsRaw == null || row.xmlInputsRaw.trim().isEmpty()) continue;
            if (row.ttl == null || row.ttl.trim().isEmpty()) continue;

            dbgRow(rowIdx, "START zip row");

            LinkedHashSet<Path> xmlFiles = resolveFilesForRow(row, modelsBaseDir);
            if (xmlFiles.isEmpty()) {
                dbgRow(rowIdx, "SKIP zip row because no xml files matched");
                continue;
            }

            ValidationExcelWriter.CaseFolder category = categorizeForReport(row);
            Path categoryDir = outputBaseDir.resolve(category.name());
            Files.createDirectories(categoryDir);

            String datasetName = makeDatasetName(xmlFiles);
            String zipFileName = makeRowZipFileName(rowIdx, datasetName);

            Path zipPath = categoryDir.resolve(zipFileName);

            dbgRow(rowIdx, "START createSingleRowZip zipPath=" + zipPath.toAbsolutePath());
            long start = System.currentTimeMillis();

            createSingleRowZip(zipPath, xmlFiles);

            dbgRow(rowIdx, "DONE createSingleRowZip zipPath=" + zipPath.toAbsolutePath(), start);

            createdZips.add(zipPath);
        }

        System.out.println("[INFO] Created " + createdZips.size() + " zip file(s) under " + outputBaseDir.toAbsolutePath());

        dbg("DONE zipByMapping createdZips=" + createdZips.size());

        return createdZips;
    }

    private static void createSingleRowZip(Path zipPath,
                                           Collection<Path> files) throws IOException {

        Path parent = zipPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (java.util.zip.ZipOutputStream zos =
                     new java.util.zip.ZipOutputStream(Files.newOutputStream(zipPath))) {

            List<Path> sortedFiles = files.stream()
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();

            Set<String> usedNamesInZip = new HashSet<>();

            int idx = 0;

            for (Path file : sortedFiles) {
                idx++;

                String fileName = file.getFileName().toString();
                String entryName = fileName;

                if (!usedNamesInZip.add(entryName)) {
                    entryName = makeUniqueFileName(entryName, usedNamesInZip);
                }

                dbg("ZIP add file index=" + idx
                        + "/" + sortedFiles.size()
                        + " entryName=" + entryName
                        + " path=" + file.toAbsolutePath());

                java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(entryName);
                zos.putNextEntry(entry);
                Files.copy(file, zos);
                zos.closeEntry();
            }
        }
    }

    private static String makeRowZipFileName(int rowIdx, String datasetName) {
        String base = (datasetName == null || datasetName.trim().isEmpty()) ? "UNKNOWN" : datasetName.trim();
        return sanitizeZipFileName(String.format("row_%03d_%s.zip", rowIdx, base));
    }

    private static String makeUniqueFileName(String originalName, Set<String> existingNames) {
        int dot = originalName.lastIndexOf('.');
        String base = (dot >= 0) ? originalName.substring(0, dot) : originalName;
        String ext = (dot >= 0) ? originalName.substring(dot) : "";

        int counter = 2;
        String candidate;
        do {
            candidate = base + "_" + counter + ext;
            counter++;
        } while (!existingNames.add(candidate));

        return candidate;
    }

    private static String sanitizeZipFileName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "UNKNOWN";
        }

        String cleaned = name.trim()
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "_");

        return cleaned.isEmpty() ? "UNKNOWN" : cleaned;
    }

    // ---------------- categorization + dataset naming ----------------

    private static ValidationExcelWriter.CaseFolder categorizeForReport(MappingRow row) {
        String ttl = row.ttl == null ? "" : row.ttl.trim();
        String xml = row.xmlInputsRaw == null ? "" : row.xmlInputsRaw.replace("\\", "/");

        if (ttl.startsWith("NCP_v2-4-0/NC-v2-4-0_AP-Con-Complex-Common-SHACL.ttl")) {
            return ValidationExcelWriter.CaseFolder.DANGLINGREFERENCE;
        }
        if (ttl.startsWith("NC_")) {
            return ValidationExcelWriter.CaseFolder.NC_SINGLE;
        }

        boolean isCgmes = ttl.startsWith("CGMES_");
        boolean hasBoundary = xml.contains("Grid_CommonData_CGM-CD.xml");
        boolean hasEQ = xml.contains("EQ");
        boolean hasSSH = xml.contains("SSH");
        boolean hasTP = xml.contains("TP");
        boolean hasSV = xml.contains("SV");
        boolean hasSvedala = xml.contains("Svedala");
        boolean hasBritheim = xml.contains("Britheim");
        boolean hasMultipleCountry = (hasBritheim && hasSvedala);

        if (isCgmes && hasBoundary && hasEQ && hasMultipleCountry) {
            return ValidationExcelWriter.CaseFolder.CGMES_CGM;
        }

        if (isCgmes && hasBoundary && hasEQ && hasSSH && hasTP && hasSV) {
            return ValidationExcelWriter.CaseFolder.CGMES_IGM_COMPLETE;
        }

        if (isCgmes) {
            return ValidationExcelWriter.CaseFolder.CGMES_SINGLE_PROFILE;
        }

        return ValidationExcelWriter.CaseFolder.UNKNOWN;
    }

    private static String formatPaths(Collection<Path> paths) {
        if (paths == null || paths.isEmpty()) return "";
        return paths.stream()
                .filter(Objects::nonNull)
                .map(p -> trimReportPath(p.toString(), XML_REPORT_PREFIX))
                .sorted()
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
    }

    private static String trimReportPath(String path, String prefixToRemove) {
        if (path == null) return "";

        String normalizedPath = path.replace("\\", "/").trim();

        while (normalizedPath.endsWith("/") && normalizedPath.length() > 1) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
        }

        int slash = normalizedPath.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < normalizedPath.length()) {
            return normalizedPath.substring(slash + 1);
        }

        return normalizedPath;
    }

    private static String makeDatasetName(Collection<Path> xmlFiles) {
        if (xmlFiles == null || xmlFiles.isEmpty()) {
            return "UNKNOWN";
        }

        List<String> paths = xmlFiles.stream()
                .map(Path::toString)
                .map(p -> p.replace("\\", "/"))
                .sorted()
                .toList();

        if (paths.stream().anyMatch(p -> p.contains("Instance/Grid/CGM"))) {
            return "CGM";
        }

        if (paths.size() > 1) {
            for (String p : paths) {
                int idx = p.indexOf("Instance/Grid/");
                if (idx >= 0) {
                    String sub = p.substring(idx + "Instance/Grid/".length());
                    String[] segs = sub.split("/");
                    if (segs.length > 0 && segs[0].startsWith("IGM_")) {
                        return segs[0];
                    }
                }
            }
        }

        Path first = xmlFiles.iterator().next();
        Path fileName = first.getFileName();
        return fileName != null ? fileName.toString() : first.toString();
    }

    // ---------------- TTL resolution ----------------

    private static Path resolveTtlPath(Path constraintsRoot, String ttlName) {
        String ttl = (ttlName == null) ? "" : ttlName.trim();

        if (ttl.contains("/") || ttl.contains("\\")) {
            return constraintsRoot.resolve(ttl).normalize();
        }

        if (ttl.startsWith("CGMES_")) {
            return constraintsRoot.resolve("CGMES_v3-0-0").resolve(ttl).normalize();
        }
        if (ttl.startsWith("NC_")) {
            return constraintsRoot.resolve("NCP_v2-4-0").resolve(ttl).normalize();
        }

        return constraintsRoot.resolve(ttl).normalize();
    }

    // ---------------- CSV parsing ----------------

    private static List<MappingRow> readMappingCsv(Path csvPath) throws IOException {
        List<MappingRow> out = new ArrayList<>();
        boolean headerSkipped = false;

        try (BufferedReader br = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;

            while ((line = br.readLine()) != null) {
                lineNo++;

                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#")) continue;

                if (!headerSkipped) {
                    String h = line.replace("\uFEFF", "").toLowerCase(Locale.ROOT);
                    if (h.startsWith("xml_inputs")) {
                        headerSkipped = true;
                        dbg("CSV header skipped at line=" + lineNo);
                        continue;
                    }
                    headerSkipped = true;
                }

                List<String> cols = parseCsvLine(line);
                if (cols.size() < 2) {
                    dbg("CSV skip line=" + lineNo + " because cols=" + cols.size());
                    continue;
                }

                String xml = cols.get(0).replace("\uFEFF", "").trim();
                String ttl = cols.get(1).replace("\uFEFF", "").trim();
                String notes = cols.size() >= 3 ? cols.get(2).replace("\uFEFF", "").trim() : "";

                if (xml.startsWith("#")) {
                    dbg("CSV skip commented line=" + lineNo);
                    continue;
                }

                out.add(new MappingRow(xml, ttl, notes));
            }
        }

        return out;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> cols = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                cols.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }

        cols.add(cur.toString());
        return cols;
    }

    // ---------------- tokenization + file expansion ----------------

    private static List<String> parseXmlInputs(String xmlInputsRaw) {
        String s = xmlInputsRaw == null ? "" : xmlInputsRaw.trim();
        if (s.toUpperCase(Locale.ROOT).startsWith("ZIP:")) {
            s = s.substring(4).trim();
        }

        String[] parts = s.split(";");
        List<String> tokens = new ArrayList<>();

        for (String p : parts) {
            String t = cleanToken(p);
            if (!t.isEmpty()) tokens.add(t);
        }

        return tokens;
    }

    /**
     * Supports filename globs in the last segment only (within one directory), e.g.:
     *   Instance/Grid/IGM_Belgovia/*EQ*.xml
     * Does NOT support directory wildcards like Instance/Grid//*EQ*.xml
     */
    private static List<Path> expandToken(Path modelsBaseDir, String token) throws IOException {
        String norm = cleanToken(token).replace("\\", "/");
        boolean hasGlob = norm.contains("*") || norm.contains("?") || norm.contains("[");

        if (!hasGlob) {
            Path p = modelsBaseDir.resolve(norm).normalize();

            boolean ok = Files.isRegularFile(p)
                    && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml");

            dbg("expandToken noGlob token=" + token
                    + " resolved=" + p.toAbsolutePath()
                    + " existsRegularXml=" + ok);

            if (ok) {
                return List.of(p);
            }

            return List.of();
        }

        int lastSlash = norm.lastIndexOf('/');
        String parent = (lastSlash >= 0) ? norm.substring(0, lastSlash) : "";
        String pattern = (lastSlash >= 0) ? norm.substring(lastSlash + 1) : norm;

        Path parentDir = modelsBaseDir.resolve(parent).normalize();

        dbg("expandToken glob token=" + token
                + " parentDir=" + parentDir.toAbsolutePath()
                + " pattern=" + pattern);

        if (!Files.isDirectory(parentDir)) {
            dbg("expandToken glob parentDir not found: " + parentDir.toAbsolutePath());
            return List.of();
        }

        final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

        List<Path> matches = new ArrayList<>();

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(parentDir)) {
            for (Path child : ds) {
                if (Files.isRegularFile(child)
                        && child.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml")
                        && matcher.matches(child.getFileName())) {
                    matches.add(child);
                }
            }
        }

        matches.sort(Comparator.comparing(Path::toString));

        dbg("expandToken glob matches=" + matches.size()
                + " token=" + token);

        return matches;
    }

    private static String cleanToken(String t) {
        if (t == null) return "";
        return t.replace("\uFEFF", "")
                .replace("\u00A0", " ")
                .replace("\"", "")
                .trim();
    }

    private static Model loadShapesWithLocalImports(Path ttlPath, Path constraintsRoot, Map<Path, Model> cache) throws IOException {
        Path key = ttlPath.toAbsolutePath().normalize();

        Model cached = cache.get(key);
        if (cached != null) {
            dbg("SHAPES cache hit key=" + key + " triples=" + cached.size());
            return cached;
        }

        dbg("SHAPES cache miss key=" + key);

        Model shapes = ModelFactory.createDefaultModel();
        Set<Path> visited = new HashSet<>();
        Deque<Path> stack = new ArrayDeque<>();
        stack.push(key);

        int loadedFiles = 0;

        while (!stack.isEmpty()) {
            Path p = stack.pop().toAbsolutePath().normalize();

            if (!visited.add(p)) {
                dbg("SHAPES skip already visited: " + p);
                continue;
            }

            if (!Files.exists(p)) {
                throw new FileNotFoundException("Imported TTL not found: " + p);
            }

            loadedFiles++;

            dbg("SHAPES START read ttl index=" + loadedFiles + " path=" + p);
            long readStart = System.currentTimeMillis();

            Model tmp = ModelFactory.createDefaultModel();
            RDFDataMgr.read(tmp, p.toUri().toString());

            dbg("SHAPES DONE read ttl index=" + loadedFiles
                    + " tmpTriples=" + tmp.size()
                    + " path=" + p.getFileName(), readStart);

            long addStart = System.currentTimeMillis();

            shapes.add(tmp);

            dbg("SHAPES DONE add ttl index=" + loadedFiles
                    + " totalShapesTriples=" + shapes.size()
                    + " path=" + p.getFileName(), addStart);

            StmtIterator it = tmp.listStatements(null, OWL.imports, (RDFNode) null);
            int imports = 0;

            while (it.hasNext()) {
                Statement st = it.nextStatement();
                RDFNode obj = st.getObject();

                if (!obj.isURIResource()) continue;

                String uri = obj.asResource().getURI();
                imports++;

                Path importedPath = resolveImportToLocalPath(uri, p, constraintsRoot);

                if (importedPath != null) {
                    dbg("SHAPES import local uri=" + uri
                            + " resolved=" + importedPath.toAbsolutePath());
                    stack.push(importedPath);
                } else {
                    System.out.println("[DBG] Skipping non-local import: " + uri);
                }
            }

            dbg("SHAPES imports found=" + imports + " ttl=" + p.getFileName());
        }

        cache.put(key, shapes);

        dbg("SHAPES DONE load with imports loadedFiles=" + loadedFiles
                + " totalTriples=" + shapes.size()
                + " key=" + key);

        return shapes;
    }

    private static Path resolveImportToLocalPath(String importUri, Path currentTtl, Path constraintsRoot) {
        String u = importUri == null ? "" : importUri.trim();

        if (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("urn:")) {
            return null;
        }

        try {
            if (u.startsWith("file:")) {
                Path p = Paths.get(java.net.URI.create(u));
                dbg("resolveImportToLocalPath fileUri=" + importUri + " resolved=" + p);
                return p;
            }
        } catch (Exception ex) {
            dbg("resolveImportToLocalPath invalid fileUri=" + importUri + " error=" + ex.getMessage());
        }

        Path currentDir = currentTtl.getParent();

        Path candidate1 = currentDir.resolve(u).normalize();
        if (Files.exists(candidate1)) {
            return candidate1;
        }

        Path candidate2 = constraintsRoot.resolve(u).normalize();
        if (Files.exists(candidate2)) {
            return candidate2;
        }

        String fileName = u;
        int slash = fileName.lastIndexOf('/');
        if (slash >= 0) fileName = fileName.substring(slash + 1);

        Path candidate3 = constraintsRoot.resolve(fileName).normalize();
        return Files.exists(candidate3) ? candidate3 : null;
    }

    // ---------------- debug helpers ----------------

    private static void logShapeStats(int rowIdx, Model shapesModel) {
        try {
            Property shTargetNode = shapesModel.createProperty("http://www.w3.org/ns/shacl#targetNode");
            Property shTargetSubjectsOf = shapesModel.createProperty("http://www.w3.org/ns/shacl#targetSubjectsOf");
            Property shSparql = shapesModel.createProperty("http://www.w3.org/ns/shacl#sparql");
            Resource sparqlConstraint = shapesModel.createResource("http://www.w3.org/ns/shacl#SPARQLConstraint");

            int targetNodeCount = countStatements(shapesModel, null, shTargetNode, null);
            int targetSubjectsOfCount = countStatements(shapesModel, null, shTargetSubjectsOf, null);
            int sparqlPropertyCount = countStatements(shapesModel, null, shSparql, null);
            int sparqlConstraintCount = countStatements(shapesModel, null, RDF.type, sparqlConstraint);

            dbgRow(rowIdx, "SHAPES stats targetNode=" + targetNodeCount
                    + " targetSubjectsOf=" + targetSubjectsOfCount
                    + " sh:sparql=" + sparqlPropertyCount
                    + " SPARQLConstraint=" + sparqlConstraintCount);

            StmtIterator targetNodes = shapesModel.listStatements(null, shTargetNode, (RDFNode) null);
            int printed = 0;

            while (targetNodes.hasNext() && printed < 20) {
                Statement st = targetNodes.nextStatement();
                printed++;
                dbgRow(rowIdx, "SHAPES targetNode shape=" + st.getSubject()
                        + " target=" + st.getObject());
            }

        } catch (Exception ex) {
            dbgRow(rowIdx, "ERROR logShapeStats " + ex.getMessage());
        }
    }

    private static int countStatements(Model model, Resource s, Property p, RDFNode o) {
        int count = 0;
        StmtIterator it = model.listStatements(s, p, o);
        while (it.hasNext()) {
            it.nextStatement();
            count++;
        }
        return count;
    }

    private static void dbg(String stage) {
        if (!DEBUG) return;

        writeDebugLine(
                "[DBG][" + Thread.currentThread().getName() + "] "
                        + java.time.LocalTime.now()
                        + " " + stage
        );
    }

    private static void dbg(String stage, long startMs) {
        if (!DEBUG) return;

        long elapsed = System.currentTimeMillis() - startMs;

        writeDebugLine(
                "[DBG][" + Thread.currentThread().getName() + "] "
                        + java.time.LocalTime.now()
                        + " " + stage
                        + " elapsedMs=" + elapsed
        );
    }

    private static void dbgRow(int rowIdx, String stage) {
        if (!DEBUG) return;

        writeDebugLine(
                "[DBG][" + Thread.currentThread().getName() + "] "
                        + java.time.LocalTime.now()
                        + " [row " + rowIdx + "] "
                        + stage
        );
    }

    private static void dbgRow(int rowIdx, String stage, long startMs) {
        if (!DEBUG) return;

        long elapsed = System.currentTimeMillis() - startMs;

        writeDebugLine(
                "[DBG][" + Thread.currentThread().getName() + "] "
                        + java.time.LocalTime.now()
                        + " [row " + rowIdx + "] "
                        + stage
                        + " elapsedMs=" + elapsed
        );
    }

    private static void printMemory(String stage) {
        if (!DEBUG) return;

        Runtime rt = Runtime.getRuntime();

        long usedMb = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        long totalMb = rt.totalMemory() / 1024 / 1024;
        long maxMb = rt.maxMemory() / 1024 / 1024;

        writeDebugLine(
                "[MEM][" + Thread.currentThread().getName() + "] "
                        + java.time.LocalTime.now()
                        + " " + stage
                        + " usedMb=" + usedMb
                        + " totalMb=" + totalMb
                        + " maxMb=" + maxMb
        );
    }

    private static class InputGroup {
        final String name;
        final List<Path> roots;

        InputGroup(String name, List<Path> roots) {
            this.name = name;
            this.roots = roots;
        }
    }

    private static List<InputGroup> prepareTimestampedInputGroups(Path inputPath, Path outputBaseDir) throws IOException {
        if (Files.isRegularFile(inputPath)
                && inputPath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {

            String zipBaseName = removeExtension(inputPath.getFileName().toString());
            Path targetDir = outputBaseDir
                    .resolve("_unzipped")
                    .resolve(sanitizePathPart(zipBaseName));

            unzipXmlFiles(inputPath, targetDir);

            return List.of(new InputGroup(zipBaseName, List.of(targetDir)));
        }

        if (!Files.isDirectory(inputPath)) {
            throw new IOException("Input must be a directory or zip file: " + inputPath);
        }

        List<InputGroup> groups = new ArrayList<>();

        Path normalizedOutput = outputBaseDir.toAbsolutePath().normalize();

        List<Path> directChildFolders;
        try (java.util.stream.Stream<Path> stream = Files.list(inputPath)) {
            directChildFolders = stream
                    .filter(Files::isDirectory)
                    .filter(p -> !isIgnoredTimestampedInputPath(p, normalizedOutput))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }

        boolean rootHasDirectXmlOrZip;
        try (java.util.stream.Stream<Path> stream = Files.list(inputPath)) {
            rootHasDirectXmlOrZip = stream
                    .filter(Files::isRegularFile)
                    .anyMatch(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".xml") || n.endsWith(".zip");
                    });
        }

        if (directChildFolders.isEmpty() || rootHasDirectXmlOrZip) {
            groups.add(prepareSingleInputGroup(inputPath, inputPath.getFileName().toString(), outputBaseDir));
        }

        for (Path childFolder : directChildFolders) {
            groups.add(prepareSingleInputGroup(childFolder, childFolder.getFileName().toString(), outputBaseDir));
        }

        return groups;
    }

    private static InputGroup prepareSingleInputGroup(Path groupRoot,
                                                      String groupName,
                                                      Path outputBaseDir) throws IOException {
        List<Path> roots = new ArrayList<>();
        roots.add(groupRoot);

        Path unzipBase = outputBaseDir
                .resolve("_unzipped")
                .resolve(sanitizePathPart(groupName));

        Path normalizedOutput = outputBaseDir.toAbsolutePath().normalize();

        try (java.util.stream.Stream<Path> stream = Files.walk(groupRoot)) {
            List<Path> zipFiles = stream
                    .filter(p -> !isIgnoredTimestampedInputPath(p, normalizedOutput))
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();

            for (Path zip : zipFiles) {
                String zipBaseName = removeExtension(zip.getFileName().toString());
                Path targetDir = unzipBase.resolve(sanitizePathPart(zipBaseName));

                unzipXmlFiles(zip, targetDir);
                roots.add(targetDir);

                logInfo("Prepared zip input group=" + groupName
                        + " zip=" + zip.toAbsolutePath()
                        + " extractedTo=" + targetDir.toAbsolutePath());
            }
        }

        return new InputGroup(groupName, roots);
    }

    private static List<XmlFileMetadata> discoverXmlMetadataForInputGroup(InputGroup inputGroup) throws IOException {
        List<XmlDiscoveryDraft> drafts = new ArrayList<>();
        Set<Path> seen = new HashSet<>();

        for (Path root : inputGroup.roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }

            try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
                List<Path> xmlFiles = stream
                        .filter(p -> !isIgnoredDiscoveryPathForRoot(root, p))                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml"))
                        .sorted(Comparator.comparing(Path::toString))
                        .toList();


                for (Path xml : xmlFiles) {
                    Path key = xml.toAbsolutePath().normalize();

                    if (!seen.add(key)) {
                        continue;
                    }

                    String fileName = xml.getFileName().toString();
                    String profile = detectProfile(fileName, xml);

                    String headerTimestamp = readTimestampFromXmlHeader(xml);
                    String filenameTimestamp = readTimestampFromFileName(fileName);

                    drafts.add(new XmlDiscoveryDraft(
                            xml,
                            fileName,
                            profile,
                            headerTimestamp,
                            filenameTimestamp
                    ));
                }
            }
        }

        Map<String, String> filenameToMetadataTimestamp = buildFilenameToMetadataTimestampMap(inputGroup.name, drafts);


        Set<String> metadataTimestamps = drafts.stream()
                .map(d -> safe(d.headerTimestamp))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<XmlFileMetadata> out = new ArrayList<>();

        for (XmlDiscoveryDraft draft : drafts) {
            TimestampDetection timestampDetection = resolveTimestampFromMetadataMap(
                    inputGroup.name,
                    draft,
                    filenameToMetadataTimestamp,
                    metadataTimestamps
            );

            XmlFileMetadata meta = new XmlFileMetadata(
                    draft.path,
                    draft.fileName,
                    inputGroup.name,
                    draft.profile,
                    timestampDetection.timestamp,
                    timestampDetection.source
            );

            out.add(meta);

            logInfo("DISCOVER inputGroup=" + inputGroup.name
                    + " xml=" + draft.path.toAbsolutePath()
                    + " profile=" + draft.profile
                    + " metadataTimestamp=" + draft.headerTimestamp
                    + " filenameTimestamp=" + draft.filenameTimestamp
                    + " finalTimestamp=" + timestampDetection.timestamp
                    + " source=" + timestampDetection.source);
        }

        printDiscoverySummary(out);

        return out;
    }

    private static class XmlDiscoveryDraft {
        final Path path;
        final String fileName;
        final String profile;
        final String headerTimestamp;
        final String filenameTimestamp;

        XmlDiscoveryDraft(Path path,
                          String fileName,
                          String profile,
                          String headerTimestamp,
                          String filenameTimestamp) {
            this.path = path;
            this.fileName = fileName;
            this.profile = profile;
            this.headerTimestamp = headerTimestamp;
            this.filenameTimestamp = filenameTimestamp;
        }
    }

    private static Map<String, String> buildFilenameToMetadataTimestampMap(String inputGroupName,
                                                                           List<XmlDiscoveryDraft> drafts) {
        Map<String, String> out = new LinkedHashMap<>();

        for (XmlDiscoveryDraft draft : drafts) {
            String filenameTimestamp = safe(draft.filenameTimestamp);
            String metadataTimestamp = safe(draft.headerTimestamp);

            if (filenameTimestamp.isBlank() || metadataTimestamp.isBlank()) {
                continue;
            }

            String existing = out.get(filenameTimestamp);

            if (existing == null) {
                out.put(filenameTimestamp, metadataTimestamp);
                continue;
            }

            if (!existing.equals(metadataTimestamp)) {
                logWarn("Different metadata timestamps found for same filename timestamp"
                        + " inputGroup=" + inputGroupName
                        + " filenameTimestamp=" + filenameTimestamp
                        + " existingMetadataTimestamp=" + existing
                        + " newMetadataTimestamp=" + metadataTimestamp
                        + " file=" + draft.fileName
                        + " -> keeping existing");
            }
        }

        logInfo("Timestamp filename-to-metadata map inputGroup="
                + inputGroupName
                + " count=" + out.size());

        return out;
    }



    private static TimestampDetection resolveTimestampFromMetadataMap(String inputGroupName,
                                                                      XmlDiscoveryDraft draft,
                                                                      Map<String, String> filenameToMetadataTimestamp,
                                                                      Set<String> metadataTimestamps) {
        String metadataTimestamp = safe(draft.headerTimestamp);
        String filenameTimestamp = safe(draft.filenameTimestamp);

        if (!metadataTimestamp.isBlank()) {
            if (!filenameTimestamp.isBlank() && !metadataTimestamp.equals(filenameTimestamp)) {
                logInfo("Timestamp mismatch inputGroup=" + inputGroupName
                        + " file=" + draft.fileName
                        + " metadata=" + metadataTimestamp
                        + " filename=" + filenameTimestamp
                        + " -> using metadata");
            }

            return new TimestampDetection(normalizeTimestampToReportTime(metadataTimestamp), "METADATA_HOUR");        }

        if (!filenameTimestamp.isBlank()) {
            String sameHourMetadataTimestamp = mapFilenameTimestampToSameHourMetadataTimestamp(
                    filenameTimestamp,
                    metadataTimestamps
            );

            if (!sameHourMetadataTimestamp.isBlank()) {
                logInfo("Timestamp mapped from filename to same-hour metadata"
                        + " inputGroup=" + inputGroupName
                        + " file=" + draft.fileName
                        + " filenameTimestamp=" + filenameTimestamp
                        + " metadataTimestamp=" + sameHourMetadataTimestamp);

                return new TimestampDetection(normalizeTimestampToReportTime(sameHourMetadataTimestamp), "FILENAME_MAPPED_TO_SAME_HOUR_METADATA");            }

            String mappedMetadataTimestamp = filenameToMetadataTimestamp.get(filenameTimestamp);

            if (mappedMetadataTimestamp != null && !mappedMetadataTimestamp.isBlank()) {
                logInfo("Timestamp mapped from filename to metadata"
                        + " inputGroup=" + inputGroupName
                        + " file=" + draft.fileName
                        + " filenameTimestamp=" + filenameTimestamp
                        + " metadataTimestamp=" + mappedMetadataTimestamp);

                return new TimestampDetection(normalizeTimestampToReportTime(mappedMetadataTimestamp), "FILENAME_MAPPED_TO_METADATA_HOUR");            }

            logWarn("No metadata timestamp found and filename timestamp could not be mapped"
                    + " inputGroup=" + inputGroupName
                    + " file=" + draft.fileName
                    + " filenameTimestamp=" + filenameTimestamp
                    + " -> using filename timestamp");

            return new TimestampDetection(normalizeTimestampToReportTime(filenameTimestamp), "FILENAME_HOUR");
        }

        return new TimestampDetection("", "NONE");
    }

    private static boolean isIgnoredDiscoveryPathForRoot(Path root, Path path) {
        if (root == null || path == null) {
            return true;
        }

        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();

        boolean rootIsExplicitUnzippedRoot = hasPathPart(normalizedRoot, "_unzipped")
                || hasPathPart(normalizedRoot, "_timestamped_input_unzipped");

        if (rootIsExplicitUnzippedRoot) {
            return false;
        }

        return isIgnoredTimestampedInputPath(normalizedPath, null);
    }

    private static boolean hasPathPart(Path path, String expectedPart) {
        if (path == null || expectedPart == null) {
            return false;
        }

        String expected = expectedPart.toLowerCase(Locale.ROOT);

        for (Path part : path) {
            String name = part.toString().toLowerCase(Locale.ROOT);

            if (name.equals(expected) || name.startsWith(expected)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isIgnoredTimestampedInputPath(Path path, Path outputBaseDir) {
        if (path == null) {
            return true;
        }

        Path normalized = path.toAbsolutePath().normalize();

        if (outputBaseDir != null) {
            Path normalizedOutput = outputBaseDir.toAbsolutePath().normalize();
            if (normalized.startsWith(normalizedOutput)) {
                return true;
            }
        }

        for (Path part : normalized) {
            String name = part.toString().toLowerCase(Locale.ROOT);

            if (name.equals("output")
                    || name.equals("_unzipped")
                    || name.equals("_timestamped_input_unzipped")
                    || name.equals("aggregated")
                    || name.equals("validation statistics")
                    || name.equals("validation_report")) {
                return true;
            }

            if (name.startsWith("_unzipped")
                    || name.startsWith("_timestamped_input_unzipped")) {
                return true;
            }
        }

        return false;
    }

    private static TsoFileIndex buildSingleInputGroupIndex(String inputGroupName,
                                                           List<XmlFileMetadata> metadata) {
        TsoFileIndex index = new TsoFileIndex(inputGroupName);

        for (XmlFileMetadata meta : metadata) {
            String profile = safe(meta.profile).toUpperCase(Locale.ROOT);

            if (BOUNDARY_PROFILES.contains(profile) || STATIC_PROFILES.contains(profile)) {
                index.staticFiles(profile).add(meta.path);

                logInfo("INDEX_STATIC"
                        + " inputGroup=" + inputGroupName
                        + " profile=" + profile
                        + " file=" + meta.fileName
                        + " metadataTimestamp=" + meta.timestamp
                        + " path=" + meta.path.toAbsolutePath());

                continue;
            }

            if (TIMESTAMPED_PROFILES.contains(profile)) {
                if (!meta.isTimestamped()) {
                    logWarn("Timestamped profile without timestamp ignored"
                            + " inputGroup=" + inputGroupName
                            + " file=" + meta.fileName
                            + " profile=" + profile);
                    continue;
                }

                TimestampGroup group = index.byTimestamp.computeIfAbsent(
                        meta.timestamp,
                        ts -> new TimestampGroup(inputGroupName, ts)
                );

                group.timestampFiles(profile).add(meta.path);

                logInfo("INDEX_TIMESTAMPED"
                        + " inputGroup=" + inputGroupName
                        + " timestamp=" + meta.timestamp
                        + " profile=" + profile
                        + " file=" + meta.fileName
                        + " path=" + meta.path.toAbsolutePath());

                continue;
            }

            logWarn("Unknown profile ignored"
                    + " inputGroup=" + inputGroupName
                    + " file=" + meta.fileName
                    + " profile=" + profile
                    + " metadataTimestamp=" + meta.timestamp
                    + " path=" + meta.path.toAbsolutePath());
        }

        logInfo("InputGroup=" + index.tso
                + " timestamps=" + index.byTimestamp.size()
                + " staticProfiles=" + formatProfileCounts(index.staticFilesByProfile));

        for (TimestampGroup group : index.byTimestamp.values()) {
            for (String profile : TIMESTAMPED_PROFILES) {
                warnIfNotOne(index.tso, group.timestamp, profile, group.timestampFiles(profile));
            }
        }

        return index;
    }

    private static ResolvedRowsAndInputChecks buildResolvedRowsForTimestampKeepingPairings(List<MappingRow> mappingRows,
                                                                                           TsoFileIndex tsoIndex,
                                                                                           TimestampGroup timestampGroup,
                                                                                           Path constraintsRoot) {
        List<ResolvedMappingRow> resolvedRows = new ArrayList<>();
        List<MappingRowInputCheck> inputChecks = new ArrayList<>();

        int rowIdx = 0;

        for (MappingRow row : mappingRows) {
            rowIdx++;

            if (row.xmlInputsRaw == null || row.xmlInputsRaw.trim().isEmpty()
                    || row.ttl == null || row.ttl.trim().isEmpty()) {
                continue;
            }

            String ttlName = row.ttl.trim();

            String constraintFileText = trimReportPath(
                    resolveTtlPath(constraintsRoot, ttlName).toString(),
                    CONSTRAINT_REPORT_PREFIX
            );

            String requestedInput = cleanRequestedInputForReport(row.xmlInputsRaw);

            InputResolution resolution = resolveInputForMappingRow(
                    row,
                    tsoIndex,
                    timestampGroup
            );

            String resolvedFilesText = formatPaths(resolution.xmlFiles);
            String missingInputText = String.join("; ", resolution.missingInputs);

            boolean willRunValidation = shouldRunValidationWithResolvedInputs(resolution);

            String status;
            String message;

            if (!resolution.missingInputs.isEmpty()) {
                if (resolution.onlyCountrySpecificMissing) {
                    status = "Missing - country-specific input";
                    message = "The mapping row requests input for another country or region. This skip can be expected if the row is intentionally country-specific.";
                } else if (willRunValidation) {
                    status = "Partial input - validation executed";
                    message = "Some requested inputs were missing, but at least one XML file was resolved and the validation was executed with the available files.";
                } else {
                    status = "Missing input - skipped";
                    message = "One or more required inputs from the mapping row were not resolved, and no partial validation was executed.";
                }
            } else if (resolution.xmlFiles.size() > resolution.expectedFileCount) {
                status = "Too many files";
                message = "More XML files were resolved than requested mapping tokens. Check duplicate files or broad token matching.";
            } else {
                status = "OK";
                message = "All requested inputs were resolved.";
            }

            inputChecks.add(new MappingRowInputCheck(
                    tsoIndex.tso,
                    timestampGroup.timestamp,
                    rowIdx,
                    constraintFileText,
                    requestedInput,
                    resolution.expectedFileCount,
                    resolution.xmlFiles.size(),
                    status,
                    resolvedFilesText,
                    missingInputText,
                    message
            ));

            if (!willRunValidation) {
                logWarn("PRECHECK_SKIP validation row"
                        + " row=" + rowIdx
                        + " inputGroup=" + tsoIndex.tso
                        + " timestamp=" + timestampGroup.timestamp
                        + " missingInput=" + missingInputText
                        + " status=" + status
                        + " resolvedXmlCount=" + resolution.xmlFiles.size()
                        + " expectedFileCount=" + resolution.expectedFileCount
                        + " xmlInputsRaw=" + row.xmlInputsRaw
                        + " ttl=" + row.ttl);

                continue;
            }

            if (!resolution.missingInputs.isEmpty()) {
                logWarn("PRECHECK_PARTIAL_RUN validation row"
                        + " row=" + rowIdx
                        + " inputGroup=" + tsoIndex.tso
                        + " timestamp=" + timestampGroup.timestamp
                        + " missingInput=" + missingInputText
                        + " resolvedXmlCount=" + resolution.xmlFiles.size()
                        + " expectedFileCount=" + resolution.expectedFileCount
                        + " xmlInputsRaw=" + row.xmlInputsRaw
                        + " ttl=" + row.ttl);
            }

            String datasetName = makeTimestampedDatasetName(
                    tsoIndex.tso,
                    timestampGroup.timestamp,
                    row.xmlInputsRaw,
                    resolution.xmlFiles
            );

            ValidationExcelWriter.CaseFolder caseFolder = categorizeForReport(row);

            resolvedRows.add(new ResolvedMappingRow(
                    rowIdx,
                    row,
                    caseFolder,
                    tsoIndex.tso,
                    timestampGroup.timestamp,
                    datasetName,
                    ttlName,
                    resolution.xmlFiles,
                    resolvedFilesText,
                    constraintFileText
            ));
        }

        return new ResolvedRowsAndInputChecks(resolvedRows, inputChecks);
    }

    private static InputResolution resolveInputForMappingRow(MappingRow row,
                                                             TsoFileIndex tsoIndex,
                                                             TimestampGroup timestampGroup) {
        LinkedHashSet<Path> resolvedFiles = new LinkedHashSet<>();
        List<String> missingInputs = new ArrayList<>();

        List<String> tokens = parseXmlInputs(row.xmlInputsRaw);

        boolean sawRealMissing = false;
        boolean sawOtherCountrySpecificMissing = false;

        int expectedFileCount = 0;

        for (String token : tokens) {
            String requestedToken = cleanInputTokenForReport(token);

            if (requestedToken.isBlank()) {
                continue;
            }

            boolean countrySpecific = isCountrySpecificToken(token);
            boolean applicable = isCountrySpecificTokenApplicable(token, tsoIndex.tso);

            List<String> requestedProfiles = detectProfilesFromMappingToken(token);

            if (requestedProfiles.isEmpty()) {
                expectedFileCount++;
            } else {
                expectedFileCount += requestedProfiles.size();
            }

            if (countrySpecific && !applicable) {
                missingInputs.add(requestedToken);
                sawOtherCountrySpecificMissing = true;
                continue;
            }

            List<Path> tokenFiles = resolveXmlFilesForSingleMappingToken(
                    token,
                    row,
                    tsoIndex,
                    timestampGroup
            );

            if (tokenFiles.isEmpty()) {
                missingInputs.add(requestedToken);
                sawRealMissing = true;
            } else {
                resolvedFiles.addAll(tokenFiles);
            }
        }

        boolean onlyCountrySpecificMissing =
                sawOtherCountrySpecificMissing
                        && !sawRealMissing
                        && resolvedFiles.isEmpty();

        return new InputResolution(
                new ArrayList<>(resolvedFiles),
                missingInputs,
                onlyCountrySpecificMissing,
                expectedFileCount
        );
    }

    private static boolean shouldRunValidationWithResolvedInputs(InputResolution resolution) {
        if (resolution == null) {
            return false;
        }

        if (resolution.xmlFiles == null || resolution.xmlFiles.isEmpty()) {
            return false;
        }

        if (resolution.missingInputs == null || resolution.missingInputs.isEmpty()) {
            return true;
        }

        if (resolution.onlyCountrySpecificMissing) {
            return false;
        }

        return resolution.expectedFileCount > 1;
    }


    private static List<Path> resolveXmlFilesForSingleMappingToken(String token,
                                                                   MappingRow row,
                                                                   TsoFileIndex tsoIndex,
                                                                   TimestampGroup timestampGroup) {
        LinkedHashSet<Path> out = new LinkedHashSet<>();

        List<String> requestedProfiles = detectProfilesFromMappingToken(token);

        if (requestedProfiles.isEmpty()) {
            logWarn("No profile detected from mapping token"
                    + " inputGroup=" + tsoIndex.tso
                    + " timestamp=" + timestampGroup.timestamp
                    + " token=" + token
                    + " ttl=" + row.ttl);
            return List.of();
        }

        for (String profile : requestedProfiles) {
            if (BOUNDARY_PROFILES.contains(profile) || STATIC_PROFILES.contains(profile)) {
                out.addAll(filterFilesByToken(tsoIndex.staticFiles(profile), token));
                continue;
            }

            if (TIMESTAMPED_PROFILES.contains(profile)) {
                out.addAll(filterFilesByToken(timestampGroup.timestampFiles(profile), token));
                continue;
            }

            logWarn("Profile from mapping token is not configured"
                    + " profile=" + profile
                    + " token=" + token);
        }

        return new ArrayList<>(out);
    }

    private static String cleanRequestedInputForReport(String xmlInputsRaw) {
        List<String> tokens = parseXmlInputs(xmlInputsRaw);

        return tokens.stream()
                .map(ValidationTools::cleanInputTokenForReport)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("; "));
    }

    private static String cleanInputTokenForReport(String token) {
        return cleanToken(token)
                .replace("{", "")
                .replace("}", "")
                .trim();
    }

    private static List<String> detectProfilesFromMappingToken(String token) {
        String s = safe(token).replace("\\", "/").toUpperCase(Locale.ROOT);

        List<String> profiles = new ArrayList<>();

        for (String profile : ALL_INPUT_PROFILES_ORDERED) {
            if (containsProfileToken(s, profile)
                    || containsWildcardProfileToken(s, profile)
                    || isLogicalProfileToken(s, profile)) {
                profiles.add(profile);
            }
        }

        return profiles;
    }

    private static boolean isCountrySpecificToken(String token) {
        return !countrySpecificOwner(token).isBlank();
    }

    private static boolean isCountrySpecificTokenApplicable(String token, String inputGroupName) {
        String owner = countrySpecificOwner(token);

        if (owner.isBlank()) {
            return true;
        }

        String group = safe(inputGroupName)
                .toUpperCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "");

        if (owner.equals("RTE")) {
            return group.equals("RTE") || group.equals("RTEFRANCE");
        }

        return group.equals(owner);
    }

    private static String countrySpecificOwner(String token) {
        String s = cleanInputTokenForReport(token)
                .toUpperCase(Locale.ROOT)
                .replace("-", "_");

        if (s.startsWith("REE_")) {
            return "REE";
        }

        if (s.startsWith("REN_")) {
            return "REN";
        }

        if (s.startsWith("RTEFRANCE_") || s.startsWith("RTE_")) {
            return "RTE";
        }

        return "";
    }

    private static String makeTimestampedDatasetName(String tso,
                                                     String timestamp,
                                                     String xmlInputsRaw,
                                                     Collection<Path> xmlFiles) {
        LinkedHashSet<String> profiles = detectProfilesFromMappingInput(xmlInputsRaw);

        String profilePart;

        if (profiles.isEmpty()) {
            profilePart = xmlFiles == null || xmlFiles.isEmpty()
                    ? "UNKNOWN_PROFILES"
                    : xmlFiles.stream()
                    .filter(Objects::nonNull)
                    .map(p -> p.getFileName() == null ? "" : p.getFileName().toString())
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.joining(" + "));
        } else {
            profilePart = String.join(" + ", profiles);
        }

        return safe(tso) + "__" + safe(timestamp) + "__" + profilePart;
    }

    private static boolean containsWildcardProfileToken(String value, String profile) {
        String s = safe(value).toUpperCase(Locale.ROOT);
        String p = safe(profile).toUpperCase(Locale.ROOT);

        if (s.isBlank() || p.isBlank()) {
            return false;
        }

        return s.contains("*" + p + "*")
                || s.contains("*" + p)
                || s.contains(p + "*");
    }

    private static boolean isLogicalProfileToken(String token, String profile) {
        String s = cleanToken(token)
                .replace("{", "")
                .replace("}", "")
                .trim()
                .toUpperCase(Locale.ROOT);

        String p = safe(profile).toUpperCase(Locale.ROOT);

        return s.equals(p);
    }

    private static List<Path> filterFilesByToken(List<Path> candidates, String token) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        if (isAnyLogicalProfileToken(token) || isCountrySpecificLogicalProfileToken(token)) {
            return candidates;
        }

        String filePattern = filePatternFromMappingToken(token);

        if (filePattern.isBlank()) {
            return candidates;
        }

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + filePattern);

        List<Path> out = candidates.stream()
                .filter(Objects::nonNull)
                .filter(Files::isRegularFile)
                .filter(p -> matcher.matches(p.getFileName()))
                .sorted(Comparator.comparing(Path::toString))
                .toList();

        if (out.isEmpty()) {
            logWarn("Token filter matched no candidates"
                    + " token=" + token
                    + " filePattern=" + filePattern
                    + " candidateCount=" + candidates.size()
                    + " -> returning no files");

            return List.of();
        }

        return out;
    }

    private static boolean isAnyLogicalProfileToken(String token) {
        String s = cleanToken(token)
                .replace("{", "")
                .replace("}", "")
                .trim()
                .toUpperCase(Locale.ROOT);

        for (String profile : ALL_INPUT_PROFILES_ORDERED) {
            if (s.equals(profile)) {
                return true;
            }
        }

        return false;
    }

    private static String filePatternFromMappingToken(String token) {
        String s = cleanToken(token).replace("\\", "/");

        int slash = s.lastIndexOf('/');
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }

        if (s.isBlank()) {
            return "";
        }

        if (isAnyLogicalProfileToken(s)) {
            return "";
        }

        if (!s.toLowerCase(Locale.ROOT).endsWith(".xml") && !s.contains(".")) {
            s = s + ".xml";
        }

        return s;
    }

    private static void writeDebugLine(String line) {
        synchronized (DEBUG_LOCK) {
            try {
                Path parent = DEBUG_LOG_PATH.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                Files.writeString(
                        DEBUG_LOG_PATH,
                        line + System.lineSeparator(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );

                if (DEBUG_TO_CONSOLE) {
                    System.out.println(line);
                }

            } catch (IOException e) {
                // Last-resort fallback only.
                System.err.println("[DBG_LOG_ERROR] " + e.getMessage());
            }
        }
    }
    private static String shortValue(String value, int maxLength) {
        if (value == null) return "null";
        if (value.length() <= maxLength) return value;
        return value.substring(0, maxLength) + "...";
    }

    private static class TimestampedRunSummary {
        final String country;
        final String timestamp;
        final Path reportPath;
        final int validationCount;
        final int conformCount;
        final int nonConformCount;
        final int errorCount;
        final int resultCount;
        final int violationCount;
        final int warningCount;
        final int infoCount;

        TimestampedRunSummary(String country,
                              String timestamp,
                              Path reportPath,
                              int validationCount,
                              int conformCount,
                              int nonConformCount,
                              int errorCount,
                              int resultCount,
                              int violationCount,
                              int warningCount,
                              int infoCount) {
            this.country = country;
            this.timestamp = timestamp;
            this.reportPath = reportPath;
            this.validationCount = validationCount;
            this.conformCount = conformCount;
            this.nonConformCount = nonConformCount;
            this.errorCount = errorCount;
            this.resultCount = resultCount;
            this.violationCount = violationCount;
            this.warningCount = warningCount;
            this.infoCount = infoCount;
        }
    }

    private static Path saveTimestampReport(ValidationExcelWriter writer,
                                            Path countryOutputDir,
                                            String country,
                                            String timestamp) throws IOException {
        Files.createDirectories(countryOutputDir);

        Path created = writer.saveTo(countryOutputDir);

        String baseFileName = "validation_report_"
                + sanitizePathPart(country)
                + "_"
                + sanitizePathPart(timestamp);

        Path target = countryOutputDir.resolve(baseFileName + ".xlsx");

        int i = 1;
        while (Files.exists(target)) {
            target = countryOutputDir.resolve(baseFileName + "_" + i + ".xlsx");
            i++;
        }

        Files.move(created, target);

        return target;
    }


    private static class TimestampDetection {
        final String timestamp;
        final String source;

        TimestampDetection(String timestamp, String source) {
            this.timestamp = timestamp;
            this.source = source;
        }
    }


    private static String readTimestampFromXmlHeader(Path xmlPath) {
        try (BufferedReader br = Files.newBufferedReader(xmlPath, StandardCharsets.UTF_8)) {
            StringBuilder head = new StringBuilder();
            String line;
            int lineCount = 0;

            while ((line = br.readLine()) != null && lineCount < 500) {
                head.append(line).append('\n');
                lineCount++;
            }

            return readTimestampFromHeaderText(head.toString());

        } catch (Exception ex) {
            dbg("Could not read timestamp from XML header: " + xmlPath
                    + " error=" + ex.getMessage());
            return "";
        }
    }

    private static String readTimestampFromHeaderText(String text) {
        String s = safe(text);

        // 1) Prefer explicit scenarioTime.
        String scenarioTime = findTimestampNearField(s, "scenarioTime");
        if (!scenarioTime.isBlank()) {
            return scenarioTime;
        }

        // 2) Then prefer explicit startDate.
        String startDate = findTimestampNearField(s, "startDate");
        if (!startDate.isBlank()) {
            return startDate;
        }

        // 3) Then any date-like field, but never endDate.
        String genericDate = findGenericDateTimestamp(s);
        if (!genericDate.isBlank()) {
            return genericDate;
        }

        return "";
    }

    private static String findTimestampNearField(String text, String fieldName) {
        String s = safe(text);
        String f = Pattern.quote(fieldName);

        List<Pattern> patterns = List.of(
                // Attribute form: CGMES:scenarioTime="2026-01-01T00:00:00Z"
                Pattern.compile("(?i)\\b[\\w:.-]*" + f + "\\b\\s*=\\s*\"([^\"]+)\""),

                // Element form: <CGMES:scenarioTime>2026-01-01T00:00:00Z</CGMES:scenarioTime>
                Pattern.compile("(?i)<[\\w:.-]*" + f + "[^>]*>\\s*([^<]+)\\s*</[\\w:.-]*" + f + "\\s*>"),

                // RDF-ish attribute/value nearby fallback.
                Pattern.compile("(?i)\\b[\\w:.-]*" + f + "\\b.{0,300}?(" + timestampRegexBody() + ")")
        );

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(s);
            while (matcher.find()) {
                String candidate = matcher.group(1);

                String normalized = normalizeTimestamp(candidate);
                if (!normalized.isBlank()) {
                    return normalized;
                }

                normalized = extractTimestampFromText(candidate);
                if (!normalized.isBlank()) {
                    return normalized;
                }
            }
        }

        return "";
    }

    private static String findGenericDateTimestamp(String text) {
        String s = safe(text);

        Pattern fieldPattern = Pattern.compile(
                "(?i)([\\w:.-]*date[\\w:.-]*)\\s*=\\s*\"([^\"]+)\"|<([\\w:.-]*date[\\w:.-]*)[^>]*>\\s*([^<]+)\\s*</[\\w:.-]*date[\\w:.-]*\\s*>"
        );

        Matcher matcher = fieldPattern.matcher(s);

        while (matcher.find()) {
            String fieldName;
            String value;

            if (matcher.group(1) != null) {
                fieldName = matcher.group(1);
                value = matcher.group(2);
            } else {
                fieldName = matcher.group(3);
                value = matcher.group(4);
            }

            String fieldLower = safe(fieldName).toLowerCase(Locale.ROOT);

            if (fieldLower.contains("enddate") || fieldLower.contains("end_date") || fieldLower.endsWith(":enddate")) {
                continue;
            }

            String normalized = normalizeTimestamp(value);
            if (!normalized.isBlank()) {
                return normalized;
            }

            normalized = extractTimestampFromText(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }

        return "";
    }

    private static String extractTimestampFromText(String text) {
        String s = safe(text);

        List<Pattern> patterns = List.of(
                Pattern.compile("(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z)"),
                Pattern.compile("(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}Z)"),
                Pattern.compile("(\\d{8}T\\d{6}Z)"),
                Pattern.compile("(\\d{8}_\\d{6})"),
                Pattern.compile("(\\d{8}T\\d{4}Z)"),
                Pattern.compile("(\\d{8}_\\d{4})")
        );

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(s);
            if (matcher.find()) {
                String normalized = normalizeTimestamp(matcher.group(1));
                if (!normalized.isBlank()) {
                    return normalized;
                }
            }
        }

        return "";
    }

    private static String timestampRegexBody() {
        return "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"
                + "|\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}Z"
                + "|\\d{8}T\\d{6}Z"
                + "|\\d{8}_\\d{6}"
                + "|\\d{8}T\\d{4}Z"
                + "|\\d{8}_\\d{4}";
    }

    private static boolean looksLikeTimestampField(String value) {
        String s = safe(value).toLowerCase(Locale.ROOT);

        return s.contains("timestamp")
                || s.contains("created")
                || s.contains("scenario")
                || s.contains("timehorizon")
                || s.contains("processtime")
                || s.contains("modelingauthorityset")
                || s.contains("modeldescription");
    }

    private static String readTimestampFromFileName(String fileName) {
        String renSsiTimestamp = readRenSsiHourTimestampFromFileName(fileName);

        if (!renSsiTimestamp.isBlank()) {
            return renSsiTimestamp;
        }

        return extractTimestampFromText(fileName);
    }

    private static String readRenSsiHourTimestampFromFileName(String fileName) {
        String s = safe(fileName).trim();

        Pattern pattern = Pattern.compile(
                "(?i)(\\d{8})_REN_CGM_I\\d+_H(\\d{1,2})_SSI\\d+.*"
        );

        Matcher matcher = pattern.matcher(s);

        if (!matcher.find()) {
            return "";
        }

        String datePart = matcher.group(1);
        int marketHour;

        try {
            marketHour = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException ex) {
            return "";
        }

        if (marketHour < 1 || marketHour > 24) {
            return "";
        }

        try {
            LocalDate date = LocalDate.parse(datePart, DateTimeFormatter.BASIC_ISO_DATE);

            return date
                    .atStartOfDay(ZoneOffset.UTC)
                    .plusHours(marketHour - 3L)
                    .plusMinutes(marketHour - 3L)
                    .toInstant()
                    .toString();

        } catch (Exception ex) {
            return "";
        }
    }

    private static String normalizeTimestamp(String raw) {
        String s = safe(raw).trim();

        if (s.isBlank()) {
            return "";
        }

        s = s.replace("\"", "");

        try {
            return Instant.parse(s).toString();
        } catch (DateTimeParseException ignore) {
        }

        try {
            if (s.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}Z")) {
                return Instant.parse(s.replace("Z", ":00Z")).toString();
            }
        } catch (DateTimeParseException ignore) {
        }

        if (s.matches("\\d{8}T\\d{6}Z")) {
            String converted = s.substring(0, 4) + "-"
                    + s.substring(4, 6) + "-"
                    + s.substring(6, 8) + "T"
                    + s.substring(9, 11) + ":"
                    + s.substring(11, 13) + ":"
                    + s.substring(13, 15) + "Z";

            try {
                return Instant.parse(converted).toString();
            } catch (DateTimeParseException ignore) {
            }
        }

        if (s.matches("\\d{8}_\\d{6}")) {
            String converted = s.substring(0, 4) + "-"
                    + s.substring(4, 6) + "-"
                    + s.substring(6, 8) + "T"
                    + s.substring(9, 11) + ":"
                    + s.substring(11, 13) + ":"
                    + s.substring(13, 15) + "Z";

            try {
                return Instant.parse(converted).toString();
            } catch (DateTimeParseException ignore) {
            }
        }

        if (s.matches("\\d{8}T\\d{4}Z")) {
            String converted = s.substring(0, 4) + "-"
                    + s.substring(4, 6) + "-"
                    + s.substring(6, 8) + "T"
                    + s.substring(9, 11) + ":"
                    + s.substring(11, 13) + ":00Z";

            try {
                return Instant.parse(converted).toString();
            } catch (DateTimeParseException ignore) {
            }
        }

        if (s.matches("\\d{8}_\\d{4}")) {
            String converted = s.substring(0, 4) + "-"
                    + s.substring(4, 6) + "-"
                    + s.substring(6, 8) + "T"
                    + s.substring(9, 11) + ":"
                    + s.substring(11, 13) + ":00Z";

            try {
                return Instant.parse(converted).toString();
            } catch (DateTimeParseException ignore) {
            }
        }

        return "";
    }

    private static Path prepareTimestampedInput(Path inputPath, Path outputBaseDir) throws IOException {
        if (Files.isDirectory(inputPath)) {
            return inputPath;
        }

        if (Files.isRegularFile(inputPath)
                && inputPath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {

            String zipBaseName = removeExtension(inputPath.getFileName().toString());

            Path tempDir = outputBaseDir.resolve("_timestamped_input_unzipped_"
                    + sanitizePathPart(zipBaseName)
                    + "_"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));

            unzipXmlFiles(inputPath, tempDir);
            return tempDir;
        }

        throw new IOException("Input must be a directory or zip file: " + inputPath);
    }

    private static String removeExtension(String fileName) {
        String s = safe(fileName).trim();
        int dot = s.lastIndexOf('.');
        if (dot > 0) {
            return s.substring(0, dot);
        }
        return s;
    }

    private static void unzipXmlFiles(Path zipPath, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);

        int extracted = 0;

        try (java.util.zip.ZipInputStream zis =
                     new java.util.zip.ZipInputStream(Files.newInputStream(zipPath))) {

            java.util.zip.ZipEntry entry;

            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                String entryName = entry.getName().replace("\\", "/");

                if (!entryName.toLowerCase(Locale.ROOT).endsWith(".xml")) {
                    continue;
                }

                Path out = targetDir.resolve(entryName).normalize();

                if (!out.startsWith(targetDir.normalize())) {
                    throw new IOException("Unsafe zip entry path: " + entryName);
                }

                Path parent = out.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                extracted++;
            }
        }

        logInfo("Extracted XML files from zip=" + zipPath.toAbsolutePath()
                + " targetDir=" + targetDir.toAbsolutePath()
                + " count=" + extracted);
    }

    private static class XmlFileMetadata {
        final Path path;
        final String fileName;
        final String tso;
        final String profile;
        final String timestamp;
        final String timestampSource;

        XmlFileMetadata(Path path,
                        String fileName,
                        String tso,
                        String profile,
                        String timestamp,
                        String timestampSource) {
            this.path = path;
            this.fileName = fileName;
            this.tso = tso;
            this.profile = profile;
            this.timestamp = timestamp;
            this.timestampSource = timestampSource;
        }

        boolean isTimestamped() {
            return timestamp != null && !timestamp.isBlank();
        }
    }


    private static class TimestampGroup {
        final String tso;
        final String timestamp;
        final Map<String, List<Path>> timestampFilesByProfile = new LinkedHashMap<>();

        TimestampGroup(String tso, String timestamp) {
            this.tso = tso;
            this.timestamp = timestamp;

            for (String profile : TIMESTAMPED_PROFILES) {
                timestampFilesByProfile.put(profile, new ArrayList<>());
            }
        }

        List<Path> timestampFiles(String profile) {
            return timestampFilesByProfile.computeIfAbsent(profile, k -> new ArrayList<>());
        }
    }

    private static class ResolvedMappingRow {
        final int rowIdx;
        final MappingRow sourceRow;
        final ValidationExcelWriter.CaseFolder caseFolder;
        final String tso;
        final String timestamp;
        final String datasetName;
        final String ttlName;
        final List<Path> xmlFiles;
        final String xmlFilesText;
        final String constraintFileText;

        ResolvedMappingRow(int rowIdx,
                           MappingRow sourceRow,
                           ValidationExcelWriter.CaseFolder caseFolder,
                           String tso,
                           String timestamp,
                           String datasetName,
                           String ttlName,
                           List<Path> xmlFiles,
                           String xmlFilesText,
                           String constraintFileText) {
            this.rowIdx = rowIdx;
            this.sourceRow = sourceRow;
            this.caseFolder = caseFolder;
            this.tso = tso;
            this.timestamp = timestamp;
            this.datasetName = datasetName;
            this.ttlName = ttlName;
            this.xmlFiles = xmlFiles;
            this.xmlFilesText = xmlFilesText;
            this.constraintFileText = constraintFileText;
        }
    }

    private static String detectProfile(String fileName, Path path) {
        String s = (safe(fileName) + " " + safe(path.toString()))
                .toUpperCase(Locale.ROOT)
                .replace("\\", "/");

        for (String profile : ALL_INPUT_PROFILES_ORDERED) {
            if (containsProfileToken(s, profile)) {
                return profile;
            }
        }

        return "UNKNOWN";
    }

    private static boolean containsProfileToken(String value, String profile) {
        String normalized = safe(value)
                .toUpperCase(Locale.ROOT)
                .replace("\\", "/")
                .replaceAll("[^A-Z0-9]+", "_");

        String p = safe(profile).toUpperCase(Locale.ROOT);

        if (normalized.isBlank() || p.isBlank()) {
            return false;
        }

        return normalized.matches(".*(^|_)" + Pattern.quote(p) + "([0-9]+)?($|_).*");
    }

    private static String detectTso(String fileName, Path path, String profile) {
        String normalizedPath = path.toString().replace("\\", "/");

        String fromFolder = detectTsoFromFolder(normalizedPath);
        if (!fromFolder.isBlank()) {
            return fromFolder;
        }

        String fromFileName = detectTsoFromFileName(fileName, profile);
        if (!fromFileName.isBlank()) {
            return fromFileName;
        }

        return "UNKNOWN_TSO";
    }

    private static String detectTsoFromFolder(String normalizedPath) {
        String[] parts = normalizedPath.split("/");

        for (String part : parts) {
            String p = safe(part).trim();

            if (p.startsWith("IGM_") && p.length() > 4) {
                return p;
            }

            if (p.startsWith("TSO_") && p.length() > 4) {
                return p;
            }
        }

        return "";
    }

    private static String detectTsoFromFileName(String fileName, String profile) {
        String s = safe(fileName);

        if (profile == null || profile.isBlank() || "UNKNOWN".equals(profile)) {
            return "";
        }

        String marker1 = "_" + profile + "_";
        String marker2 = "-" + profile + "-";
        String marker3 = "_" + profile + ".";
        String marker4 = "-" + profile + ".";

        int idx = indexOfAnyIgnoreCase(s, marker1, marker2, marker3, marker4);

        if (idx > 0) {
            String prefix = s.substring(0, idx)
                    .replaceAll("[^A-Za-z0-9_\\-]+", "_")
                    .replaceAll("_+$", "");

            return prefix.isBlank() ? "" : prefix;
        }

        return "";
    }

    private static int indexOfAnyIgnoreCase(String value, String... needles) {
        String upper = safe(value).toUpperCase(Locale.ROOT);

        int best = -1;

        for (String needle : needles) {
            int idx = upper.indexOf(needle.toUpperCase(Locale.ROOT));
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx;
            }
        }

        return best;
    }

    private static class TsoFileIndex {
        final String tso;
        final Map<String, TimestampGroup> byTimestamp = new TreeMap<>();
        final Map<String, List<Path>> staticFilesByProfile = new LinkedHashMap<>();

        TsoFileIndex(String tso) {
            this.tso = tso;

            for (String profile : BOUNDARY_PROFILES) {
                staticFilesByProfile.put(profile, new ArrayList<>());
            }

            for (String profile : STATIC_PROFILES) {
                staticFilesByProfile.put(profile, new ArrayList<>());
            }
        }

        List<Path> staticFiles(String profile) {
            return staticFilesByProfile.computeIfAbsent(profile, k -> new ArrayList<>());
        }
    }

    private static void validateTsoIndexes(Map<String, TsoFileIndex> indexByTso) {
        for (TsoFileIndex index : indexByTso.values()) {
            logInfo("TSO=" + index.tso
                    + " timestamps=" + index.byTimestamp.size()
                    + " staticProfiles=" + formatProfileCounts(index.staticFilesByProfile));

            for (TimestampGroup group : index.byTimestamp.values()) {
                for (String profile : TIMESTAMPED_PROFILES) {
                    warnIfNotOne(index.tso, group.timestamp, profile, group.timestampFiles(profile));
                }
            }
        }
    }

    private static Map<String, TsoFileIndex> buildTsoIndexes(List<XmlFileMetadata> metadata) {
        Map<String, TsoFileIndex> out = new TreeMap<>();

        for (XmlFileMetadata meta : metadata) {
            String tso = safe(meta.tso).isBlank() ? "UNKNOWN_TSO" : meta.tso;
            String profile = safe(meta.profile).toUpperCase(Locale.ROOT);

            TsoFileIndex index = out.computeIfAbsent(tso, TsoFileIndex::new);

            if (BOUNDARY_PROFILES.contains(profile) || STATIC_PROFILES.contains(profile)) {
                index.staticFiles(profile).add(meta.path);
                continue;
            }

            if (TIMESTAMPED_PROFILES.contains(profile)) {
                if (!meta.isTimestamped()) {
                    logWarn("Timestamped profile without timestamp ignored: "
                            + meta.fileName + " profile=" + profile);
                    continue;
                }

                TimestampGroup group = index.byTimestamp.computeIfAbsent(
                        meta.timestamp,
                        ts -> new TimestampGroup(tso, ts)
                );

                group.timestampFiles(profile).add(meta.path);
                continue;
            }

            logWarn("Unknown profile ignored: "
                    + meta.fileName + " profile=" + profile);
        }

        validateTsoIndexes(out);

        return out;
    }

    private static String formatProfileCounts(Map<String, List<Path>> filesByProfile) {
        return filesByProfile.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue().size())
                .collect(Collectors.joining(", "));
    }

    private static void warnIfNotOne(String tso, String timestamp, String profile, List<Path> files) {
        if (files.size() != 1) {
            logWarn("TSO=" + tso
                    + " timestamp=" + timestamp
                    + " profile=" + profile
                    + " expected=1 actual=" + files.size()
                    + " files=" + files.stream()
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.joining(", ")));
        }
    }

    private static void printDiscoverySummary(List<XmlFileMetadata> metadata) {
        Map<String, Long> byProfile = metadata.stream()
                .collect(Collectors.groupingBy(m -> safe(m.profile), TreeMap::new, Collectors.counting()));

        Map<String, Long> byTimestampSource = metadata.stream()
                .collect(Collectors.groupingBy(m -> safe(m.timestampSource), TreeMap::new, Collectors.counting()));

        logInfo("[INFO] Discovery summary by profile:");
        byProfile.forEach((k, v) -> logInfo("  " + k + " = " + v));

        logInfo("[INFO] Discovery summary by timestamp source:");
        byTimestampSource.forEach((k, v) -> logInfo("  " + k + " = " + v));
    }

    private static List<ValidationTaskResult> executeResolvedRows(List<ResolvedMappingRow> resolvedRows,
                                                                  Path constraintsRoot,
                                                                  Map<Path, Model> shapesCache,
                                                                  Map<Path, Model> staticXmlModelCache,
                                                                  Map<Path, Model> timestampXmlModelCache,
                                                                  int threads,
                                                                  Map<String, RDFDatatype> dataTypeMap,
                                                                  String xmlBase) throws IOException {

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<ValidationTaskResult>> tasks = new ArrayList<>();

        for (ResolvedMappingRow row : resolvedRows) {
            tasks.add(() -> {
                try {
                    return validateOneResolvedRow(
                            row,
                            constraintsRoot,
                            shapesCache,
                            staticXmlModelCache,
                            timestampXmlModelCache,
                            dataTypeMap,
                            xmlBase
                    );
                } catch (Throwable t) {
                    System.err.println("[WORKER_ERROR][" + Thread.currentThread().getName()
                            + "][timestamp row " + row.rowIdx + "]");
                    t.printStackTrace();
                    throw t;
                }
            });
        }

        List<Future<ValidationTaskResult>> futures = new ArrayList<>();

        try {
            for (Callable<ValidationTaskResult> task : tasks) {
                futures.add(pool.submit(task));
            }
        } finally {
            pool.shutdown();
        }

        List<ValidationTaskResult> results = new ArrayList<>();

        for (int i = 0; i < futures.size(); i++) {
            Future<ValidationTaskResult> f = futures.get(i);

            try {
                results.add(f.get(FUTURE_TIMEOUT_MINUTES, TimeUnit.MINUTES));

            } catch (TimeoutException ex) {
                f.cancel(true);

                results.add(new ValidationTaskResult(
                        i + 1,
                        ValidationExcelWriter.CaseFolder.UNKNOWN,
                        "TIMEOUT_TIMESTAMP_ROW",
                        "UNKNOWN_TTL",
                        "UNKNOWN_XML",
                        "UNKNOWN_CONSTRAINT",
                        null,
                        false,
                        new Exception("Validation future timed out after "
                                + FUTURE_TIMEOUT_MINUTES + " minutes", ex)
                ));

            } catch (ExecutionException ex) {
                results.add(new ValidationTaskResult(
                        i + 1,
                        ValidationExcelWriter.CaseFolder.UNKNOWN,
                        "EXECUTION_ERROR_TIMESTAMP_ROW",
                        "UNKNOWN_TTL",
                        "UNKNOWN_XML",
                        "UNKNOWN_CONSTRAINT",
                        null,
                        false,
                        new Exception(ex)
                ));

            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Timestamped validation interrupted while waiting for future index=" + i, ex);
            }
        }

        return results;
    }

    private static void consoleInput(String message) {
        System.out.println("[INPUT] " + message);
    }

    private static void consoleReport(String message) {
        System.out.println("[REPORT] " + message);
    }

    private static void logInfo(String message) {
        dbg("INFO " + message);
    }

    private static void logWarn(String message) {
        dbg("WARN " + message);
    }

    private static void logError(String message) {
        dbg("ERROR " + message);
    }

    private static ValidationTaskResult validateOneResolvedRow(ResolvedMappingRow row,
                                                               Path constraintsRoot,
                                                               Map<Path, Model> shapesCache,
                                                               Map<Path, Model> staticXmlModelCache,
                                                               Map<Path, Model> timestampXmlModelCache,
                                                               Map<String, RDFDatatype> dataTypeMap,
                                                               String xmlBase) {

        long rowStart = System.currentTimeMillis();

        dbgRow(row.rowIdx, "START timestamped resolved row"
                + " tso=" + row.tso
                + " timestamp=" + row.timestamp
                + " ttl=" + row.ttlName
                + " xmlFiles=" + row.xmlFiles.size());

        try {
            if (row.xmlFiles == null || row.xmlFiles.isEmpty()) {
                return new ValidationTaskResult(
                        row.rowIdx,
                        row.caseFolder,
                        row.datasetName,
                        row.ttlName,
                        row.xmlFilesText,
                        row.constraintFileText,
                        null,
                        false,
                        new IOException("No XML files resolved for timestamped validation row")
                );
            }

            Path ttlPath = resolveTtlPath(constraintsRoot, row.ttlName);

            if (!Files.exists(ttlPath)) {
                return new ValidationTaskResult(
                        row.rowIdx,
                        row.caseFolder,
                        row.datasetName,
                        row.ttlName,
                        row.xmlFilesText,
                        row.constraintFileText,
                        null,
                        false,
                        new FileNotFoundException("TTL not found: " + ttlPath)
                );
            }

            Model shapesModel = loadShapesWithLocalImports(ttlPath, constraintsRoot, shapesCache);

            Model dataModel = loadRdfXmlFromFilesWithCache(
                    row.xmlFiles,
                    staticXmlModelCache,
                    timestampXmlModelCache,
                    dataTypeMap,
                    xmlBase,
                    row.rowIdx
            );
            ValidationReport report = ShaclValidator.get().validate(shapesModel.getGraph(), dataModel.getGraph());

            List<SHACLValidationResult> results =
                    ShaclTools.extractSHACLValidationResults(report, shapesModel);

            dbgRow(row.rowIdx, "DONE timestamped resolved row"
                            + " tso=" + row.tso
                            + " timestamp=" + row.timestamp
                            + " conforms=" + report.conforms()
                            + " resultCount=" + (results == null ? "null" : results.size()),
                    rowStart);

            return new ValidationTaskResult(
                    row.rowIdx,
                    row.caseFolder,
                    row.datasetName,
                    row.ttlName,
                    row.xmlFilesText,
                    row.constraintFileText,
                    results,
                    report.conforms(),
                    null
            );

        } catch (Exception ex) {
            dbgRow(row.rowIdx, "ERROR timestamped resolved row"
                            + " tso=" + row.tso
                            + " timestamp=" + row.timestamp,
                    rowStart);

            ex.printStackTrace();

            return new ValidationTaskResult(
                    row.rowIdx,
                    row.caseFolder,
                    row.datasetName,
                    row.ttlName,
                    row.xmlFilesText,
                    row.constraintFileText,
                    null,
                    false,
                    ex
            );
        }
    }

    private static void appendTaskResultsToWriter(ValidationExcelWriter writer,
                                                  List<ValidationTaskResult> taskResults) {
        for (ValidationTaskResult r : taskResults) {
            if (r.error != null) {
                writer.appendError(
                        r.caseFolder,
                        r.datasetName,
                        r.xmlFiles,
                        r.constraintFile,
                        r.error
                );
            } else {
                writer.appendValidation(
                        r.caseFolder,
                        r.datasetName,
                        r.xmlFiles,
                        r.constraintFile,
                        r.results,
                        r.conforms
                );
            }
        }
    }

    private static LinkedHashSet<String> detectProfilesFromMappingInput(String xmlInputsRaw) {
        LinkedHashSet<String> profiles = new LinkedHashSet<>();

        List<String> tokens = parseXmlInputs(xmlInputsRaw);

        for (String token : tokens) {
            profiles.addAll(detectProfilesFromMappingToken(token));
        }

        return profiles;
    }

    private static String sanitizePathPart(String value) {
        String s = safe(value).trim();

        if (s.isBlank()) {
            return "UNKNOWN";
        }

        return s.replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "_");
    }

    private static String safe(String s) {
        if (s == null) return "";
        if ("None".equalsIgnoreCase(s)) return "";
        return s;
    }

    private static final List<String> TIMESTAMPED_PROFILES = List.of(
            "EQ", "TP", "SSH", "SV", "SSI"
    );

    private static final List<String> STATIC_PROFILES = List.of(
            "AE", "AP", "PS", "AS", "CO", "ER", "IAM", "RA", "RAS", "OP", "SAR", "SIS"
    );

    private static final List<String> BOUNDARY_PROFILES = List.of(
            "EQBD", "TPBD"
    );

    private static final List<String> ALL_INPUT_PROFILES_ORDERED = List.of(
            "EQBD", "TPBD",
            "SSH", "SSI", "SIS",
            "RAS", "SAR",
            "IAM",
            "AE",
            "EQ", "TP", "SV",
            "AP", "PS", "AS", "CO", "ER", "RA", "OP"
    );

    private static void appendTimestampSummary(ValidationExcelWriter summaryWriter,
                                               String country,
                                               String timestamp,
                                               Path timestampReport,
                                               List<ValidationTaskResult> results) {
        int validationCount = 0;
        int conformCount = 0;
        int nonConformCount = 0;
        int errorCount = 0;
        int totalResults = 0;
        int violationCount = 0;
        int warningCount = 0;
        int infoCount = 0;

        for (ValidationTaskResult r : results) {
            validationCount++;

            if (r.error != null) {
                errorCount++;
                continue;
            }

            if (r.conforms) {
                conformCount++;
            } else {
                nonConformCount++;
            }

            if (r.results == null) {
                continue;
            }

            totalResults += r.results.size();

            for (SHACLValidationResult res : r.results) {
                String sev = safe(res.getSeverity()).toLowerCase(Locale.ROOT);

                if (sev.contains("violation")) {
                    violationCount++;
                } else if (sev.contains("warning")) {
                    warningCount++;
                } else {
                    infoCount++;
                }
            }

            summaryWriter.collectTimestampConstraintStatistics(
                    country,
                    timestamp,
                    timestampReport,
                    r.constraintFile,
                    r.results
            );
        }

        summaryWriter.appendTimestampOverview(
                country,
                timestamp,
                timestampReport,
                validationCount,
                conformCount,
                nonConformCount,
                errorCount,
                totalResults,
                violationCount,
                warningCount,
                infoCount
        );
    }

    private static void appendMappingRowInputChecks(ValidationExcelWriter summaryWriter,
                                                    List<MappingRowInputCheck> checks) {
        if (checks == null || checks.isEmpty()) {
            return;
        }

        for (MappingRowInputCheck check : checks) {
            summaryWriter.appendInputCompleteness(
                    check.country,
                    check.timestamp,
                    check.mappingRow,
                    check.constraintFile,
                    check.requestedInput,
                    check.expectedFileCount,
                    check.resolvedFileCount,
                    check.status,
                    check.resolvedFiles,
                    check.missingInput,
                    check.message
            );
        }
    }

    private static String mapFilenameTimestampToSameHourMetadataTimestamp(String filenameTimestamp,
                                                                          Set<String> metadataTimestamps) {
        String ts = safe(filenameTimestamp);

        if (ts.isBlank() || metadataTimestamps == null || metadataTimestamps.isEmpty()) {
            return "";
        }

        try {
            Instant instant = Instant.parse(ts);
            java.time.ZonedDateTime zdt = instant.atZone(java.time.ZoneOffset.UTC);

            if (zdt.getMinute() != 30 || zdt.getSecond() != 0) {
                return "";
            }

            java.time.ZonedDateTime sameHour = zdt.withMinute(30).withSecond(0).withNano(0);
            String candidate = sameHour.toInstant().toString();

            return metadataTimestamps.contains(candidate) ? candidate : "";

        } catch (DateTimeParseException ex) {
            return "";
        }
    }

    private static String normalizeTimestampToReportTime(String timestamp) {
        String ts = safe(timestamp);

        if (ts.isBlank()) {
            return "";
        }

        try {
            Instant instant = Instant.parse(ts);

            return instant
                    .atZone(java.time.ZoneOffset.UTC)
                    .withMinute(30)
                    .withSecond(0)
                    .withNano(0)
                    .toInstant()
                    .toString();

        } catch (DateTimeParseException ex) {
            return ts;
        }
    }

    private static class MappingRowInputCheck {
        final String country;
        final String timestamp;
        final int mappingRow;
        final String constraintFile;
        final String requestedInput;
        final int expectedFileCount;
        final int resolvedFileCount;
        final String status;
        final String resolvedFiles;
        final String missingInput;
        final String message;

        MappingRowInputCheck(String country,
                             String timestamp,
                             int mappingRow,
                             String constraintFile,
                             String requestedInput,
                             int expectedFileCount,
                             int resolvedFileCount,
                             String status,
                             String resolvedFiles,
                             String missingInput,
                             String message) {
            this.country = country;
            this.timestamp = timestamp;
            this.mappingRow = mappingRow;
            this.constraintFile = constraintFile;
            this.requestedInput = requestedInput;
            this.expectedFileCount = expectedFileCount;
            this.resolvedFileCount = resolvedFileCount;
            this.status = status;
            this.resolvedFiles = resolvedFiles;
            this.missingInput = missingInput;
            this.message = message;
        }
    }

    private static class ResolvedRowsAndInputChecks {
        final List<ResolvedMappingRow> resolvedRows;
        final List<MappingRowInputCheck> inputChecks;

        ResolvedRowsAndInputChecks(List<ResolvedMappingRow> resolvedRows,
                                   List<MappingRowInputCheck> inputChecks) {
            this.resolvedRows = resolvedRows;
            this.inputChecks = inputChecks;
        }
    }

    private static class InputResolution {
        final List<Path> xmlFiles;
        final List<String> missingInputs;
        final boolean onlyCountrySpecificMissing;
        final int expectedFileCount;

        InputResolution(List<Path> xmlFiles,
                        List<String> missingInputs,
                        boolean onlyCountrySpecificMissing,
                        int expectedFileCount) {
            this.xmlFiles = xmlFiles;
            this.missingInputs = missingInputs;
            this.onlyCountrySpecificMissing = onlyCountrySpecificMissing;
            this.expectedFileCount = expectedFileCount;
        }
    }


    private static boolean isCountrySpecificLogicalProfileToken(String token) {
        String s = cleanInputTokenForReport(token)
                .toUpperCase(Locale.ROOT)
                .replace("-", "_");

        for (String profile : ALL_INPUT_PROFILES_ORDERED) {
            if (s.endsWith("_" + profile)) {
                return true;
            }
        }

        return false;
    }
}