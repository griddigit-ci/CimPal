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
import java.util.*;
import java.util.concurrent.*;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;

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

            System.out.println("[DBG][row " + rowIdx + "] xmlFiles=" + xmlFiles.size());
            System.out.println("[DBG][row " + rowIdx + "] data triples=" + dataModel.size());
            System.out.println("[DBG][row " + rowIdx + "] shapes triples=" + shapesModel.size());

            xmlFiles.stream()
                    .limit(10)
                    .forEach(p -> System.out.println("  [DBG][row " + rowIdx + "] XML " + p));

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
                                                            String xmlBase) throws Exception {
        return loadRdfXmlFromFilesWithDatatypeMap(xmlFiles, dataTypeMap, xmlBase, -1);
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
                .map(Path::toString)
                .map(p -> trimReportPath(p, XML_REPORT_PREFIX))
                .sorted()
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
    }

    private static String trimReportPath(String path, String prefixToRemove) {
        if (path == null) return "";

        String normalizedPath = path.replace("\\", "/").trim();
        String normalizedPrefix = prefixToRemove.replace("\\", "/").trim();

        if (normalizedPath.startsWith(normalizedPrefix)) {
            return normalizedPath.substring(normalizedPrefix.length());
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
}