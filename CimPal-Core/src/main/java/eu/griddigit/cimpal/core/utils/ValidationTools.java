package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.compose.MultiUnion;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.shacl.engine.ValidationContext;
import org.apache.jena.shacl.parser.Shape;
import org.apache.jena.shacl.validation.VLib;
import org.apache.jena.shacl.validation.ValidationProc;
import org.apache.jena.shacl.validation.event.ConstraintEvaluatedEvent;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
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
import java.util.stream.Stream;

import java.time.LocalDate;
import java.time.ZoneOffset;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

public class ValidationTools {


    /**
     * Detailed row-level logging opens and appends to a shared file for every event. Keeping it
     * off in ordinary runs avoids serialising validation workers on that file I/O lock. Enable
     * it for a diagnostic run with {@code -Dcimpal.validation.debug=true}.
     */
    private static final boolean DEBUG = Boolean.getBoolean("cimpal.validation.debug");
    private static final boolean DEBUG_TO_CONSOLE = false;

    // Diagnostic log lives under the per-user application data directory. A fixed path in a
    // shared temp location (the previous "C:\Temp\...") is predictable and, on hosts where
    // that directory is writable by every account, lets a local actor tamper with the audit
    // trail or pre-create the file.
    private static final Path DEBUG_LOG_PATH =
            userDataDir().resolve("cimpal_validation_debug.log");

    private static final Object DEBUG_LOCK = new Object();

    private static final RemoteFetchConfig REMOTE_FETCH_CONFIG = RemoteFetchConfig.defaults();
    static final Map<String, Model> REMOTE_IMPORTS_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Object> REMOTE_URL_LOCKS = new ConcurrentHashMap<>();

    // Cache for XML files downloaded from remote URLs (GitHub / HTTP)
    private static final Path REMOTE_XML_CACHE_DIR = initRemoteXmlCacheDir();
    private static final Map<String, Path> REMOTE_XML_FILE_CACHE = new ConcurrentHashMap<>();

    /**
     * Hosts the application may fetch remote shapes and instance data from. An import URI
     * naming any other host is refused rather than fetched: without this allowlist an
     * {@code owl:imports} statement in a third-party shapes file, or a URL cell in a
     * third-party mapping workbook, can drive arbitrary requests from the operator's
     * workstation (SSRF).
     */
    private static final Set<String> ALLOWED_REMOTE_HOSTS = Set.of(
            "raw.githubusercontent.com",
            "api.github.com",
            "github.com"
    );

    /**
     * Hosts that may receive the GitHub credential. Kept separate from
     * {@link #ALLOWED_REMOTE_HOSTS} so that widening the fetch allowlist never silently
     * widens the set of hosts the token is disclosed to.
     */
    private static final Set<String> GITHUB_AUTH_HOSTS = Set.of(
            "raw.githubusercontent.com",
            "api.github.com",
            "github.com"
    );

    /** Hard cap on a single remote response body, to bound memory use on a hostile server. */
    private static final long MAX_REMOTE_BODY_BYTES = 64L * 1024 * 1024;

    /** Maximum length of a single untrusted value written to the diagnostic log. */
    private static final int LOG_FIELD_MAX = 512;

    private static final long FUTURE_TIMEOUT_MINUTES = 60;
    private static final int DEBUG_MAX_RESULTS_PER_ROW = 5000;

    private static final boolean WRITE_SUMMARY_CHECKPOINT_EACH_TIMESTAMP = false;

    // Month label like "July 2026" for chart titles / comparison labels, derived from md:FullModel.
    private static final DateTimeFormatter ANALYSIS_MONTH_YEAR =
            DateTimeFormatter.ofPattern("LLLL yyyy", Locale.ENGLISH);

    private ValidationTools() { }

    /**
     * Forgets what has already been fetched from a remote host, so the next fetch of each URL
     * is checked against its origin again.
     * <p>
     * Both maps are keyed by URL and live as long as the process. Without clearing them, a
     * constraint file edited upstream between two runs stays invisible until the application is
     * restarted: the shapes model is served from {@link #REMOTE_IMPORTS_CACHE} and the model
     * input from the file recorded in {@link #REMOTE_XML_FILE_CACHE}, neither of which consults
     * the origin a second time. This is called at the start of every validation run, which is
     * the boundary at which a user expects their upstream edits to be picked up.
     * <p>
     * The disk caches are deliberately kept: both revalidate against the origin with
     * {@code ETag} / {@code Last-Modified} and answer an unchanged file with a 304, so keeping
     * them costs one conditional request per URL rather than a full download. Use
     * {@link #clearRemoteDiskCaches()} to discard those as well.
     */
    public static void clearRemoteCaches() {
        int shapes = REMOTE_IMPORTS_CACHE.size();
        int xml = REMOTE_XML_FILE_CACHE.size();
        REMOTE_IMPORTS_CACHE.clear();
        REMOTE_XML_FILE_CACHE.clear();
        dbg("CACHE cleared in-memory remote caches shapesEntries=" + shapes + " xmlEntries=" + xml);
    }

    /**
     * Deletes the on-disk remote caches, in addition to clearing the in-memory ones.
     * <p>
     * Only the two directories this class owns are touched, and only the files directly inside
     * them; a subdirectory, if one ever appears there, is left alone rather than walked. Not
     * needed for ordinary staleness - the disk caches revalidate - so this exists for the case
     * where a cached file is corrupt, or an origin serves a changed file under an unchanged
     * validator.
     *
     * @return what was cleared, for reporting back to whoever asked
     */
    public static RemoteCacheClearResult clearRemoteDiskCaches() {
        int memoryEntries = REMOTE_IMPORTS_CACHE.size() + REMOTE_XML_FILE_CACHE.size();
        clearRemoteCaches();

        int deleted = 0;
        List<String> failures = new ArrayList<>();
        for (Path dir : List.of(REMOTE_FETCH_CONFIG.diskCacheDir(), REMOTE_XML_CACHE_DIR)) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> entries = Files.list(dir)) {
                for (Path entry : entries.toList()) {
                    if (!Files.isRegularFile(entry)) {
                        continue;
                    }
                    try {
                        Files.delete(entry);
                        deleted++;
                    } catch (IOException e) {
                        failures.add(entry.getFileName() + ": " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                failures.add(dir + ": " + e.getMessage());
            }
        }

        dbg("CACHE cleared remote disk caches files=" + deleted + " failures=" + failures.size());
        return new RemoteCacheClearResult(memoryEntries, deleted, List.copyOf(failures));
    }

    /** The directories {@link #clearRemoteDiskCaches()} empties, for a confirmation prompt. */
    public static List<Path> remoteDiskCacheDirectories() {
        return List.of(REMOTE_FETCH_CONFIG.diskCacheDir(), REMOTE_XML_CACHE_DIR);
    }

    /** How many files {@link #clearRemoteDiskCaches()} would delete right now. */
    public static int remoteDiskCacheFileCount() {
        int count = 0;
        for (Path dir : remoteDiskCacheDirectories()) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> entries = Files.list(dir)) {
                count += (int) entries.filter(Files::isRegularFile).count();
            } catch (IOException e) {
                dbg("CACHE could not count disk cache dir=" + dir + " error=" + forLog(e.getMessage()));
            }
        }
        return count;
    }

    /** Outcome of {@link #clearRemoteDiskCaches()}: {@code failures} is empty on full success. */
    public record RemoteCacheClearResult(int memoryEntries, int filesDeleted, List<String> failures) { }

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
     *
     * The analysis name (used in chart titles and as the comparison "current" label) is derived
     * mid-process from md:FullModel (scenarioTime / startDate) per timestamp group; it is not a
     * parameter.
     */
    public static ValidationTimestampedRunSummary validateByTimestampedMapping(Path mappingCsvPath,
                                                          Path inputPath,
                                                          Path constraintsRoot,
                                                          Path outputBaseDir,
                                                          int threadCount,
                                                          Map<String, RDFDatatype> dataTypeMap,
                                                          String xmlBase,
                                                          Path previousComparisonCsv)    // NEW  nullable
            throws IOException {
        return validateByTimestampedMapping(mappingCsvPath, inputPath, constraintsRoot,
                outputBaseDir, threadCount, dataTypeMap, xmlBase, previousComparisonCsv, 0);
    }

    /**
     * Runs timestamped validation, optionally stopping a target-shape evaluation after the first
     * results beyond the configured per-source-shape limit. A value of zero means no limit.
     * <p>
     * The sampled path uses Jena's validation listener to interrupt an affected target shape.
     * Constraints that produce no findings, or only a few findings, still run completely.
     */
    public static ValidationTimestampedRunSummary validateByTimestampedMapping(Path mappingCsvPath,
                                                          Path inputPath,
                                                          Path constraintsRoot,
                                                          Path outputBaseDir,
                                                          int threadCount,
                                                          Map<String, RDFDatatype> dataTypeMap,
                                                          String xmlBase,
                                                          Path previousComparisonCsv,
                                                          int maxResultsPerConstraint)
            throws IOException {

        if (maxResultsPerConstraint < 0) {
            throw new IllegalArgumentException("maxResultsPerConstraint must be zero or greater");
        }

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

        //a run re-reads every input: nothing carries over from the previous one
        clearRemoteCaches();

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
        consoleInput("max results per constraint="
                + (maxResultsPerConstraint == 0 ? "unlimited" : maxResultsPerConstraint));

        Map<String, CachedShapes> shapesCache = new ConcurrentHashMap<>();
        List<Path> createdReports = new ArrayList<>();
        int totalConforming = 0;
        int totalViolations = 0;
        int totalErrors    = 0;

        List<InputGroup> inputGroups = prepareTimestampedInputGroups(inputPath, outputBaseDir);

        consoleInput("input groups=" + inputGroups.size());

        try (ValidationExcelWriter allCountriesSummaryWriter = ValidationExcelWriter.createTimestampedSummaryWriter();
             ValidationExcelWriter.ComparisonExcelWriter comparisonWriter =
                     new ValidationExcelWriter.ComparisonExcelWriter(previousComparisonCsv, "Previous", "Current")) {
            for (InputGroup inputGroup : inputGroups) {
                long inputGroupStart = System.currentTimeMillis();

                Map<Path, Model> staticXmlModelCache = new ConcurrentHashMap<>();

                logInfo("START input group=" + inputGroup.name
                        + " roots=" + inputGroup.roots.stream()
                        .map(Path::toString)
                        .collect(Collectors.joining("; ")));

                printMemory("before input group " + inputGroup.name);

                logInfo("START input metadata discovery group=" + inputGroup.name
                        + " localRoots=" + inputGroup.roots.size()
                        + " zipXmlEntries=" + inputGroup.zipEntriesByVirtualPath.size());
                long metadataDiscoveryStart = System.currentTimeMillis();
                List<XmlFileMetadata> metadata = discoverXmlMetadataForInputGroup(inputGroup);
                logInfo("DONE input metadata discovery group=" + inputGroup.name
                        + " xmlFiles=" + metadata.size()
                        + " elapsedMs=" + (System.currentTimeMillis() - metadataDiscoveryStart));

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
                                    inputGroup.zipEntriesByVirtualPath,
                                    threads,
                                    dataTypeMap,
                                    xmlBase,
                                    maxResultsPerConstraint
                            );
                        } finally {
                            timestampXmlModelCache.clear();

                            printMemory("after clearing timestampXmlModelCache "
                                    + inputGroup.name + " " + timestampGroup.timestamp);
                        }

                        for (ValidationTaskResult r : results) {
                            if (r.error != null) totalErrors++;
                            else if (r.conforms)  totalConforming++;
                            else                  totalViolations++;
                        }

                        // NEW: analysis name derived from md:FullModel (scenarioTime / startDate)
                        String monthLabel = deriveMonthLabel(timestampGroup, resolvedRows);
                        String analysisName = monthLabel + " Analysis";
                        comparisonWriter.setCurrentLabel(monthLabel);

                        Path timestampReport;

                        try (ValidationExcelWriter timestampWriter = new ValidationExcelWriter()) {
                            timestampWriter.setReportContext(analysisName, inputGroup.name, timestampGroup.timestamp); // NEW
                            long reportRowsStart = System.currentTimeMillis();
                            appendTaskResultsToWriter(timestampWriter, results, maxResultsPerConstraint);
                            dbg("DONE append timestamp report rows inputGroup=" + inputGroup.name
                                    + " timestamp=" + timestampGroup.timestamp, reportRowsStart);

                            long reportSaveStart = System.currentTimeMillis();
                            timestampReport = saveTimestampReport(
                                    timestampWriter, groupOutputDir, inputGroup.name, timestampGroup.timestamp);
                            dbg("DONE save timestamp report inputGroup=" + inputGroup.name
                                    + " timestamp=" + timestampGroup.timestamp, reportSaveStart);
                            createdReports.add(timestampReport);
                            consoleReport("Timestamp report created: " + timestampReport.toAbsolutePath());
                        }

                        // NEW: feed the comparison workbook (per dataset totals for this timestamp)
                        feedComparison(comparisonWriter, inputGroup.name, timestampGroup.timestamp, results);

                        appendTimestampSummary(
                                summaryWriter,
                                inputGroup.name,
                                timestampGroup.timestamp,
                                timestampReport,
                                results,
                                maxResultsPerConstraint
                        );

                        appendTimestampSummary(
                                allCountriesSummaryWriter,
                                inputGroup.name,
                                timestampGroup.timestamp,
                                timestampReport,
                                results,
                                maxResultsPerConstraint
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

                    long groupSummarySaveStart = System.currentTimeMillis();
                    Path summaryReport = summaryWriter.saveTo(groupOutputDir);
                    dbg("DONE save input-group summary inputGroup=" + inputGroup.name, groupSummarySaveStart);
                    createdReports.add(summaryReport);

                    consoleReport("Timestamped summary report created: " + summaryReport.toAbsolutePath());
                }

                staticXmlModelCache.clear();

                printMemory("after clearing staticXmlModelCache inputGroup=" + inputGroup.name);

                logInfo("DONE input group=" + inputGroup.name);

                dbg("DONE input group=" + inputGroup.name, inputGroupStart);
            }

            long allCountriesSummarySaveStart = System.currentTimeMillis();
            Path allCountriesSummaryReport = allCountriesSummaryWriter.saveTo(outputBaseDir);
            dbg("DONE save all-countries summary", allCountriesSummarySaveStart);
            createdReports.add(allCountriesSummaryReport);
            consoleReport("All-countries timestamped summary report created: "
                    + allCountriesSummaryReport.toAbsolutePath());

            // NEW: comparison workbook (region sheets + delta charts)
            long comparisonSaveStart = System.currentTimeMillis();
            Path comparisonReport = comparisonWriter.saveTo(outputBaseDir);
            dbg("DONE save comparison report", comparisonSaveStart);
            createdReports.add(comparisonReport);
            consoleReport("Comparison report created: " + comparisonReport.toAbsolutePath());
        }

        dbg("DONE validateByTimestampedMapping", allStart);
        printMemory("end validateByTimestampedMapping");

        return new ValidationTimestampedRunSummary(createdReports, totalConforming, totalViolations, totalErrors);
    }

    // NEW: analysis month helpers -------------------------------------------------

    /**
     * Returns a month label like "July 2026", derived from md:FullModel.
     * The timestamp group's time is itself taken from the FullModel header (scenarioTime /
     * startDate) during discovery, so it is used first; if for some reason it is blank or
     * unparseable, a FullModel header is read directly from a resolved input file.
     */
    private static String deriveMonthLabel(TimestampGroup timestampGroup,
                                           List<ResolvedMappingRow> resolvedRows) {
        String label = monthYearFromInstant(timestampGroup == null ? "" : timestampGroup.timestamp);
        if (label != null) {
            return label;
        }

        if (resolvedRows != null) {
            for (ResolvedMappingRow row : resolvedRows) {
                if (row == null || row.xmlFiles == null) {
                    continue;
                }
                for (Path xml : row.xmlFiles) {
                    // readTimestampFromXmlHeader prefers scenarioTime, then startDate, never endDate.
                    String fromHeader = monthYearFromInstant(readTimestampFromXmlHeader(xml));
                    if (fromHeader != null) {
                        return fromHeader;
                    }
                }
            }
        }

        return LocalDate.now().format(ANALYSIS_MONTH_YEAR);
    }

    private static String monthYearFromInstant(String instant) {
        String s = safe(instant).trim();
        if (s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s)
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .format(ANALYSIS_MONTH_YEAR);
        } catch (Exception ex) {
            return null;
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
    public static ValidationRunSummary validateByMapping(Path mappingCsvPath,
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

        //a run re-reads every input: nothing carries over from the previous one
        clearRemoteCaches();

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

        Map<String, Model> shapesCache = new ConcurrentHashMap<>();

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
                    logError("Unhandled exception", t);
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
                        logError("Unhandled exception (cause)", ex.getCause());
                    } else {
                        logError("Unhandled exception", ex);
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
                    logError("Unhandled exception", ex);

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

                    writer.appendError(
                            sheet, r.datasetName, r.xmlFiles, r.missingXmlFiles, r.constraintFile,
                            r.error, r.displayName);
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
                    writer.appendValidation(
                            sheet, r.datasetName, r.xmlFiles, r.missingXmlFiles, r.constraintFile,
                            r.results, r.conforms, r.displayName);
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

        return new ValidationRunSummary(reportPath, ok, fail, err);
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
        final String missingXmlFiles;

        String displayName = "";   // mapping column C, used for chart x-axis
        boolean partialValidation;

        ValidationTaskResult withDisplayName(String dn) {
            this.displayName = (dn == null) ? "" : dn;
            return this;
        }

        ValidationTaskResult withPartialValidation(boolean partialValidation) {
            this.partialValidation = partialValidation;
            return this;
        }

        ValidationTaskResult(int rowIdx,
                             ValidationExcelWriter.CaseFolder caseFolder,
                             String datasetName,
                             String ttlName,
                             String xmlFiles,
                             String missingXmlFiles,
                             String constraintFile,
                             List<SHACLValidationResult> results,
                             boolean conforms,
                             Exception error) {
            this.rowIdx = rowIdx;
            this.caseFolder = caseFolder;
            this.datasetName = datasetName;
            this.ttlName = ttlName;
            this.xmlFiles = xmlFiles;
            this.missingXmlFiles = missingXmlFiles;
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
                                                       Map<String, Model> shapesCache,
                                                       Map<String, RDFDatatype> dataTypeMap,
                                                       String xmlBase) {

        long rowStart = System.currentTimeMillis();

        String ttlName = row.ttl.trim();
        ValidationExcelWriter.CaseFolder caseFolder = categorizeForReport(row);

        String xmlFilesText = row.xmlInputsRaw;
        String missingXmlFilesText = "";
        String constraintFileText = trimReportPath(ttlName);
        String datasetName = "UNKNOWN";

        dbgRow(rowIdx, "START row ttl=" + ttlName
                + " caseFolder=" + caseFolder
                + " xmlRaw=" + shortValue(row.xmlInputsRaw, 300));

        try {
            dbgRow(rowIdx, "START resolveFilesForRow");
            long resolveStart = System.currentTimeMillis();

            ResolvedXmlFiles resolvedXmlFiles =
                    resolveFilesForRow(row, modelsBaseDir);

            LinkedHashSet<Path> xmlFiles = resolvedXmlFiles.existingFiles;
            missingXmlFilesText = resolvedXmlFiles.missingInputsText();

            dbgRow(rowIdx,
                    "DONE resolveFilesForRow xmlFiles=" + xmlFiles.size()
                            + " missingInputs=" + resolvedXmlFiles.missingInputs.size(),
                    resolveStart);

            datasetName = makeDatasetName(xmlFiles);
            xmlFilesText = formatPaths(xmlFiles);

            if (!resolvedXmlFiles.missingInputs.isEmpty()) {
                dbgRow(rowIdx,
                        "WARN missing XML inputs, validation continues: "
                                + missingXmlFilesText);
            }

            if (xmlFiles.isEmpty()) {
                dbgRow(rowIdx, "ERROR no XML matched mapping tokens");
                return new ValidationTaskResult(
                        rowIdx,
                        caseFolder,
                        datasetName,
                        ttlName,
                        xmlFilesText,
                        missingXmlFilesText,
                        constraintFileText,
                        null,
                        false,
                        new IOException("No XML matched mapping tokens")
                );
            }

            dbgRow(rowIdx, "START resolveTtlPath ttlName=" + ttlName);
            Path ttlPath = resolveTtlPath(constraintsRoot, ttlName);
            constraintFileText = trimReportPath(ttlPath.toString());
            dbgRow(rowIdx, "DONE resolveTtlPath ttlPath=" + ttlPath.toAbsolutePath());

            if (!Files.exists(ttlPath)) {
                dbgRow(rowIdx, "ERROR TTL not found: " + ttlPath);
                return new ValidationTaskResult(rowIdx, caseFolder, datasetName, ttlName, xmlFilesText, missingXmlFilesText, constraintFileText, null, false,
                        new FileNotFoundException("TTL not found: " + ttlPath));
            }

            dbgRow(rowIdx, "START loadShapesWithImports ttlPath=" + ttlPath.toAbsolutePath());
            long shapesStart = System.currentTimeMillis();

            LoadShapesResult shapesResult = loadShapesWithImports(
                    new LocalShapeSource(ttlPath), constraintsRoot, shapesCache);
            Model shapesModel = shapesResult.model();

            dbgRow(rowIdx, "DONE loadShapesWithImports shapesTriples=" + shapesModel.size(),
                    shapesStart);

            Resource shNodeShape = ResourceFactory.createResource("http://www.w3.org/ns/shacl#NodeShape");
            Resource shPropertyShape = ResourceFactory.createResource("http://www.w3.org/ns/shacl#PropertyShape");
            long shapeCount = shapesModel.listSubjectsWithProperty(RDF.type, shNodeShape).toList().size()
                    + shapesModel.listSubjectsWithProperty(RDF.type, shPropertyShape).toList().size();

            if (shapeCount == 0) {
                String warnMsg = "[WARN][row " + rowIdx + "] Shapes model contains 0 sh:NodeShape/sh:PropertyShape"
                        + " after loading " + shapesResult.loadedFiles() + " file(s) with "
                        + shapesModel.size() + " triples."
                        + " The shapes file may be empty or contain only owl:imports declarations."
                        + " file=" + ttlPath;
                System.err.println(warnMsg);
                dbgRow(rowIdx, warnMsg);
            }

            if (shapesResult.unresolvableImports() > 0) {
                String warnMsg = "[WARN][row " + rowIdx + "] "
                        + shapesResult.unresolvableImports()
                        + " owl:imports declaration(s) could not be resolved to any local or remote file."
                        + " Some shapes may be missing. file=" + ttlPath;
                System.err.println(warnMsg);
                dbgRow(rowIdx, warnMsg);
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

            return new ValidationTaskResult(
                    rowIdx, caseFolder, datasetName, ttlName,
                    xmlFilesText, missingXmlFilesText, constraintFileText,
                    results, report.conforms(), null
            ).withDisplayName(row.notes);
        } catch (Exception ex) {
            dbgRow(rowIdx, "ERROR row dataset=" + datasetName, rowStart);
            logError("Unhandled exception", ex);

            return new ValidationTaskResult(rowIdx, caseFolder, datasetName, ttlName, xmlFilesText,missingXmlFilesText, constraintFileText, null, false, ex);
        }
    }

    // ---------------- mapping row -> file list ----------------

    private static class ResolvedXmlFiles {
        final LinkedHashSet<Path> existingFiles;
        final List<String> missingInputs;

        ResolvedXmlFiles(LinkedHashSet<Path> existingFiles,
                         List<String> missingInputs) {
            this.existingFiles = existingFiles;
            this.missingInputs = missingInputs;
        }

        String missingInputsText() {
            return String.join("; ", missingInputs);
        }
    }

    private static ResolvedXmlFiles resolveFilesForRow(MappingRow row,
                                                       Path modelsBaseDir) throws IOException {

        List<String> tokens = parseXmlInputs(row.xmlInputsRaw);

        LinkedHashSet<Path> existingFiles = new LinkedHashSet<>();
        List<String> missingInputs = new ArrayList<>();

        dbg("resolveFilesForRow tokens=" + tokens.size()
                + " raw=" + forLog(shortValue(row.xmlInputsRaw, 300)));

        for (String token : tokens) {
            long start = System.currentTimeMillis();
            List<Path> expanded;

            if (isUrlToken(token)) {
                dbg("START expandUrlToken token=" + urlForLog(token));
                expanded = expandUrlToken(token);
                dbg("DONE expandUrlToken token=" + urlForLog(token)
                        + " matches=" + expanded.size(), start);
            } else {
                dbg("START expandToken token=" + forLog(token));
                expanded = expandToken(modelsBaseDir, token);
                dbg("DONE expandToken token=" + forLog(token)
                        + " matches=" + expanded.size(), start);
            }

            if (expanded.isEmpty()) {
                missingInputs.add(token);

                dbg("MISSING XML input token=" + forLog(token)
                        + (isUrlToken(token) ? " (URL, could not download)"
                                             : " resolvedAgainst=" + modelsBaseDir.toAbsolutePath()));
            } else {
                existingFiles.addAll(expanded);
            }
        }

        return new ResolvedXmlFiles(existingFiles, missingInputs);
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
                                                         int rowIdx,
                                                         Map<Path, ZipXmlEntry> zipEntriesByVirtualPath) {
        Path p = xmlFile.toAbsolutePath().normalize();
        ZipXmlEntry zipEntry = zipEntriesByVirtualPath.get(p);

        if (zipEntry == null && !Files.isRegularFile(p)) {
            throw new IllegalArgumentException("XML file is not a regular file: " + p);
        }

        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xml")) {
            throw new IllegalArgumentException("File is not XML: " + p);
        }

        dbgRow(rowIdx, "START loadSingleRdfXmlWithDatatypeMap path=" + p);

        long start = System.currentTimeMillis();

        try (InputStream in = zipEntry == null ? Files.newInputStream(p) : zipEntry.openStream()) {
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

    static Graph loadRdfXmlGraphFromFilesWithCache(Collection<Path> xmlFiles,
                                                      Map<Path, Model> staticXmlModelCache,
                                                      Map<Path, Model> timestampXmlModelCache,
                                                      Map<Path, ZipXmlEntry> zipEntriesByVirtualPath,
                                                      Map<String, RDFDatatype> dataTypeMap,
                                                      String xmlBase,
                                                      int rowIdx) {
        long startAll = System.currentTimeMillis();

        MultiUnion union = new MultiUnion();

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
                    p -> loadSingleRdfXmlWithDatatypeMap(
                            p, dataTypeMap, xmlBase, rowIdx, zipEntriesByVirtualPath)
            );

            dbgRow(rowIdx, "DONE get XML model from cache"
                    + " fileIndex=" + fileIndex
                    + " profile=" + profile
                    + " cache=" + (useStaticCache ? "STATIC" : "TIMESTAMP")
                    + " singleTriples=" + single.size()
                    + " path=" + key.getFileName(), cacheStart);

            // Cached models are fully loaded before publication and remain read-only.
            // The union borrows their graphs: do not mutate or close it, since closing
            // a MultiUnion also closes its shared component graphs.
            union.addGraph(single.getGraph());
        }

        dbgRow(rowIdx, "DONE loadRdfXmlGraphFromFilesWithCache"
                + " xmlFiles=" + xmlFiles.size(), startAll);

        return union;
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

            ResolvedXmlFiles resolvedXmlFiles =
                    resolveFilesForRow(row, modelsBaseDir);

            LinkedHashSet<Path> xmlFiles = resolvedXmlFiles.existingFiles;
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
                .map(p -> trimReportPath(p.toString()))
                .sorted()
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
    }

    private static String trimReportPath(String path) {
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

    /**
     * Resolves the semicolon-separated SHACL roots from one mapping cell. A one-file cell keeps
     * its former behaviour; multiple roots define one shape combination for that mapping row.
     */
    private static List<Path> resolveTtlPaths(Path constraintsRoot, String ttlNames) {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        for (String token : safe(ttlNames).split(";")) {
            String ttl = token.trim();
            if (!ttl.isBlank()) {
                paths.add(resolveTtlPath(constraintsRoot, ttl).toAbsolutePath().normalize());
            }
        }
        return new ArrayList<>(paths);
    }

    private static String formatTtlPaths(Collection<Path> ttlPaths) {
        if (ttlPaths == null || ttlPaths.isEmpty()) {
            return "";
        }
        return ttlPaths.stream()
                .map(path -> trimReportPath(path.toString()))
                .collect(Collectors.joining("; "));
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

            dbg("expandToken noGlob token=" + forLog(token)
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

        dbg("expandToken glob token=" + forLog(token)
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

    // ---------------- remote XML download (GitHub / HTTP) ----------------

    /**
     * The per-user directory for application state: caches and the diagnostic log. Preferred
     * over the system temp directory, which is predictable and, on POSIX hosts, writable by
     * every local account - allowing another user to plant cache entries this application
     * would then parse as trusted input.
     */
    private static Path userDataDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        return localAppData != null && !localAppData.isBlank()
                ? Paths.get(localAppData, "CimPal")
                : Paths.get(System.getProperty("user.home"), ".cimpal");
    }

    /**
     * Creates {@code dir} and, on POSIX hosts, restricts it to the owner. On Windows the
     * per-user profile ACL inherited from {@code LOCALAPPDATA} already provides this.
     */
    private static void createPrivateDirectory(Path dir) throws IOException {
        Files.createDirectories(dir);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            try {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException ignored) {
                // Filesystem does not support POSIX permissions; the directory is still private
                // by virtue of living under the user's home directory.
            }
        }
    }

    private static Path initRemoteXmlCacheDir() {
        Path dir = userDataDir().resolve("remote-xml-cache");
        try {
            createPrivateDirectory(dir);
        } catch (IOException e) {
            System.err.println("[WARN] Could not create remote XML cache dir: " + dir + " \u2013 " + e.getMessage());
        }
        return dir;
    }

    /**
     * Egress policy gate, first tier: scheme, host allowlist and absence of embedded
     * credentials. Performs no name resolution, so it is cheap, deterministic, and safe to
     * apply when classifying an import as remote and when the application is running
     * offline against its disk cache.
     * <p>
     * Without this an import URI supplied in a third-party shapes file, or a URL cell in a
     * third-party mapping workbook, can be used to reach internal services, cloud
     * instance-metadata endpoints, or to enumerate the internal network from the operator's
     * workstation.
     *
     * @return the parsed URI, so callers never re-parse the raw string
     * @throws IOException if the URL is malformed or refused by policy
     * @see #requirePublicHost(URI) the second tier, applied immediately before a request
     */
    private static URI requireAllowedRemoteUri(String url) throws IOException {
        final URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IOException("Malformed remote URL: " + forLog(url), e);
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("Refusing non-HTTPS remote fetch: " + urlForLog(url));
        }
        if (uri.getUserInfo() != null) {
            throw new IOException("Refusing remote fetch with embedded credentials");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("Remote URL has no host: " + urlForLog(url));
        }
        if (!ALLOWED_REMOTE_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            throw new IOException("Host is not on the remote-fetch allowlist: " + forLog(host));
        }

        return uri;
    }

    /**
     * Egress policy gate, second tier: confirms the host does not resolve to a loopback,
     * link-local, private or multicast address. Applied immediately before a request is
     * issued rather than at classification time, both because it costs a DNS lookup and
     * because a name's resolution can change between the two moments.
     */
    private static void requirePublicHost(URI uri) throws IOException {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("Remote URL has no host");
        }
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                        || addr.isSiteLocalAddress() || addr.isAnyLocalAddress()
                        || addr.isMulticastAddress()) {
                    throw new IOException(
                            "Refusing remote fetch: host resolves to a non-public address: " + forLog(host));
                }
            }
        } catch (UnknownHostException e) {
            throw new IOException("Cannot resolve remote-fetch host: " + forLog(host), e);
        }
    }

    /** Applies both egress policy tiers. Use at the point a request is about to be issued. */
    private static URI requireFetchableRemoteUri(String url) throws IOException {
        URI uri = requireAllowedRemoteUri(url);
        requirePublicHost(uri);
        return uri;
    }

    /**
     * Builds an HTTP client for one request. Requests that carry a credential must never
     * follow redirects: the JDK client forwards caller-set headers across hops, so a
     * redirect would hand the token to the redirect target.
     */
    private static HttpClient newHttpClient(boolean credentialed) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(REMOTE_FETCH_CONFIG.connectTimeoutMs()))
                .followRedirects(credentialed
                        ? HttpClient.Redirect.NEVER
                        : HttpClient.Redirect.NORMAL)
                .build();
    }

    /** True when the GitHub credential is present and this host is permitted to receive it. */
    private static boolean willSendGitHubCredential(URI uri) {
        String host = uri.getHost();
        if (host == null || !GITHUB_AUTH_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            return false;
        }
        String token = System.getenv("GITHUB_TOKEN");
        return token != null && !token.isBlank();
    }

    /**
     * Rejects a response body larger than {@link #MAX_REMOTE_BODY_BYTES}.
     */
    private static byte[] requireBoundedBody(HttpResponse<byte[]> response, String url) throws IOException {
        byte[] body = response.body();
        if (body != null && body.length > MAX_REMOTE_BODY_BYTES) {
            throw new IOException("Remote response exceeds the size limit ("
                    + MAX_REMOTE_BODY_BYTES + " bytes): " + urlForLog(url));
        }
        return body;
    }

    /**
     * Prepares an untrusted value for the diagnostic log. Neutralises line terminators so a
     * hostile value cannot forge additional log records, and bounds the field length.
     */
    private static String forLog(String value) {
        if (value == null) {
            return "null";
        }
        // U+241E SYMBOL FOR RECORD SEPARATOR: visible, and cannot start a new log line.
        String s = value.replaceAll("[\\r\\n\\u0085\\u2028\\u2029]", "\u241e");
        return s.length() > LOG_FIELD_MAX ? s.substring(0, LOG_FIELD_MAX) + "..." : s;
    }

    /**
     * Prepares a URL for the diagnostic log, redacting the query string, which may carry
     * tokens or signed-URL credentials.
     */
    private static String urlForLog(String url) {
        if (url == null) {
            return "null";
        }
        int q = url.indexOf('?');
        return forLog(q >= 0 ? url.substring(0, q) + "?<redacted>" : url);
    }

    private static boolean isUrlToken(String token) {
        return token.startsWith("https://") || token.startsWith("http://");
    }

    /**
     * Converts GitHub UI URLs to raw content URLs so the file content is directly downloadable.
     *   https://github.com/owner/repo/blob/branch/path \u2192 https://raw.githubusercontent.com/owner/repo/branch/path
     *   https://github.com/owner/repo/raw/branch/path  \u2192 https://raw.githubusercontent.com/owner/repo/branch/path
     * Other URLs are returned unchanged.
     */
    private static String toRawUrl(String url) {
        if (url.startsWith("https://github.com/") || url.startsWith("http://github.com/")) {
            int hostEnd = url.indexOf('/', url.indexOf("//") + 2);
            String rest = url.substring(hostEnd + 1); // owner/repo/TYPE/branch/path
            String[] segs = rest.split("/", 4);        // [owner, repo, type, tail]
            if (segs.length == 4 && (segs[2].equals("blob") || segs[2].equals("raw"))) {
                return "https://raw.githubusercontent.com/" + segs[0] + "/" + segs[1] + "/" + segs[3];
            }
        }
        return url;
    }

    /**
     * Expands a URL token (possibly containing a glob in the last segment) to a list of local Paths.
     *   No glob: downloads the single file to the local cache.
     *   Glob:    uses the GitHub Contents API to list the directory, filters by glob pattern,
     *            then downloads each matching file.
     */
    private static List<Path> expandUrlToken(String token) throws IOException {
        String norm = cleanToken(token).replace("\\", "/");
        boolean hasGlob = norm.contains("*") || norm.contains("?") || norm.contains("[");

        if (!hasGlob) {
            if (!norm.toLowerCase(Locale.ROOT).endsWith(".xml")) {
                dbg("expandUrlToken skip non-xml url=" + urlForLog(norm));
                return List.of();
            }
            try {
                Path p = downloadXmlToCache(norm);
                dbg("expandUrlToken downloaded url=" + urlForLog(norm) + " local=" + p);
                return List.of(p);
            } catch (IOException e) {
                dbg("expandUrlToken download failed url=" + urlForLog(norm) + " error=" + forLog(e.getMessage()));
                return List.of();
            }
        }

        // Glob: directory URL is everything before the last '/'
        int lastSlash = norm.lastIndexOf('/');
        String dirUrl  = norm.substring(0, lastSlash);
        String pattern = norm.substring(lastSlash + 1);

        String apiUrl = buildGitHubContentsApiUrl(dirUrl);
        if (apiUrl == null) {
            dbg("expandUrlToken cannot map dirUrl to GitHub Contents API: " + urlForLog(dirUrl));
            return List.of();
        }

        dbg("expandUrlToken glob pattern=" + forLog(pattern) + " apiUrl=" + urlForLog(apiUrl));

        List<GitHubApiEntry> entries;
        try {
            entries = fetchGitHubDirectoryContents(apiUrl);
        } catch (IOException e) {
            dbg("expandUrlToken GitHub API fetch failed apiUrl=" + urlForLog(apiUrl) + " error=" + forLog(e.getMessage()));
            return List.of();
        }

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

        List<Path> matches = new ArrayList<>();
        for (GitHubApiEntry entry : entries) {
            if (entry.downloadUrl() == null || entry.downloadUrl().isBlank()) continue;
            if (!entry.name().toLowerCase(Locale.ROOT).endsWith(".xml")) continue;
            if (!matcher.matches(Paths.get(entry.name()))) continue;
            try {
                matches.add(downloadXmlToCache(entry.downloadUrl()));
                dbg("expandUrlToken glob match name=" + forLog(entry.name()));
            } catch (IOException e) {
                dbg("expandUrlToken glob download failed name=" + forLog(entry.name()) + " error=" + forLog(e.getMessage()));
            }
        }

        matches.sort(Comparator.comparing(p -> p.getFileName().toString()));
        dbg("expandUrlToken glob matches=" + matches.size() + " token=" + urlForLog(token));
        return matches;
    }

    /**
     * Downloads an XML file from {@code url} to the local cache directory and returns its Path.
     * <p>
     * A file already in the cache is revalidated against the origin with {@code ETag} /
     * {@code Last-Modified} rather than trusted outright: previously the presence of the file
     * was taken as proof it was current, so an input republished upstream was never picked up
     * again on that workstation. An unchanged file costs one 304 and no download. A cached file
     * with no recorded validator - written before this revalidation existed - is re-downloaded
     * once, which then records one.
     * <p>
     * Within a single run the first resolution of a URL is remembered in
     * {@link #REMOTE_XML_FILE_CACHE}, so a URL used by several mapping rows is revalidated once;
     * {@link #clearRemoteCaches()} drops that at each run boundary.
     */
    private static Path downloadXmlToCache(String url) throws IOException {
        Path existing = REMOTE_XML_FILE_CACHE.get(url);
        if (existing != null) {
            dbg("downloadXmlToCache memory-hit url=" + urlForLog(url));
            return existing;
        }

        String rawUrl = toRawUrl(url);
        String fileName = safeCacheFileName(rawUrl);

        // Defence in depth: even with a sanitised name, confirm the resolved path is inside
        // the cache directory before writing to it.
        Path target = resolveInCacheDir(REMOTE_XML_CACHE_DIR, fileName);
        Path metaFile = resolveInCacheDir(REMOTE_XML_CACHE_DIR, fileName + ".meta");
        boolean onDisk = Files.exists(target);

        if (REMOTE_FETCH_CONFIG.offline()) {
            if (!onDisk) {
                throw new IOException("Offline mode: URL not in disk cache: " + urlForLog(rawUrl));
            }
            dbg("downloadXmlToCache offline disk-hit rawUrl=" + urlForLog(rawUrl) + " local=" + target);
            REMOTE_XML_FILE_CACHE.put(url, target);
            return target;
        }

        Properties meta = onDisk ? readCacheMeta(metaFile) : new Properties();
        ConditionalFetch fetch = fetchHttpBytes(
                rawUrl, meta.getProperty("etag"), meta.getProperty("last-modified"));

        if (fetch.notModified() && onDisk) {
            dbg("downloadXmlToCache 304 not modified, using disk cache rawUrl=" + urlForLog(rawUrl)
                    + " local=" + target);
        } else {
            createPrivateDirectory(REMOTE_XML_CACHE_DIR);
            Path tmp = resolveInCacheDir(REMOTE_XML_CACHE_DIR, fileName + ".tmp");
            Files.write(tmp, fetch.body());
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            writeCacheMeta(metaFile, fetch.etag(), fetch.lastModified());
            dbg("downloadXmlToCache saved bytes=" + fetch.body().length
                    + " revalidated=" + onDisk + " local=" + target);
        }

        REMOTE_XML_FILE_CACHE.put(url, target);
        return target;
    }

    /**
     * Resolves {@code fileName} inside {@code cacheDir}, refusing a result that escapes it.
     * Shared by every write into a cache directory so that no call site can forget the check.
     */
    private static Path resolveInCacheDir(Path cacheDir, String fileName) throws IOException {
        Path resolved = cacheDir.resolve(fileName).normalize();
        if (!resolved.startsWith(cacheDir)) {
            throw new IOException("Refusing to write outside the cache directory: " + resolved);
        }
        return resolved;
    }

    /** The cache validators recorded for a cached file, empty when there are none or it is corrupt. */
    private static Properties readCacheMeta(Path metaFile) {
        Properties meta = new Properties();
        if (!Files.exists(metaFile)) {
            return meta;
        }
        try (InputStream in = Files.newInputStream(metaFile)) {
            meta.load(in);
        } catch (IOException e) {
            // Corrupt or unreadable meta: treat as absent, which fetches fresh and rewrites it.
            dbg("CACHE unreadable meta file=" + metaFile + " error=" + forLog(e.getMessage()));
            meta.clear();
        }
        return meta;
    }

    /**
     * Records the validators for a freshly written cache file, so the next run can revalidate
     * instead of re-downloading. A failure here is not fatal: it costs a full download next
     * time, which is the behaviour that applied before any meta was written.
     */
    private static void writeCacheMeta(Path metaFile, String etag, String lastModified) {
        if (etag == null && lastModified == null) {
            return;
        }
        Properties meta = new Properties();
        if (etag != null) {
            meta.setProperty("etag", etag);
        }
        if (lastModified != null) {
            meta.setProperty("last-modified", lastModified);
        }
        try (OutputStream out = Files.newOutputStream(metaFile)) {
            meta.store(out, null);
        } catch (IOException e) {
            dbg("CACHE meta write failed file=" + metaFile + " error=" + forLog(e.getMessage()));
        }
    }

    /**
     * A conditional GET result: either {@code 304 Not Modified}, in which case {@code body} is
     * null and the caller keeps what it has, or a body plus the validators to store with it.
     */
    private record ConditionalFetch(byte[] body, String etag, String lastModified) {
        boolean notModified() {
            return body == null;
        }
    }

    /**
     * Fetches {@code url}, sending the given validators so an unchanged resource answers 304.
     * Either may be null, which makes this an ordinary unconditional GET.
     */
    private static ConditionalFetch fetchHttpBytes(String url,
                                                   String conditionalEtag,
                                                   String conditionalLastMod) throws IOException {
        URI uri = requireFetchableRemoteUri(url);
        boolean credentialed = willSendGitHubCredential(uri);

        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMillis(REMOTE_FETCH_CONFIG.readTimeoutMs()));

        addGitHubAuthHeader(req, uri);

        if (conditionalEtag != null) {
            req.header("If-None-Match", conditionalEtag);
        } else if (conditionalLastMod != null) {
            req.header("If-Modified-Since", conditionalLastMod);
        }

        HttpResponse<byte[]> response;
        try {
            response = newHttpClient(credentialed)
                    .send(req.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted: " + urlForLog(url), e);
        }

        int status = response.statusCode();
        if (status == 304) {
            dbg("HTTP 304 Not Modified url=" + urlForLog(url));
            return new ConditionalFetch(null, conditionalEtag, conditionalLastMod);
        }
        if (status == 404) throw new IOException("HTTP 404 Not Found: " + urlForLog(url));
        if (status < 200 || status >= 300) throw new IOException("HTTP " + status + ": " + urlForLog(url));

        return new ConditionalFetch(
                requireBoundedBody(response, url),
                response.headers().firstValue("ETag").orElse(null),
                response.headers().firstValue("Last-Modified").orElse(null));
    }

    /**
     * Derives a cache file name that cannot escape the cache directory. The SHA-256 of the
     * URL carries identity; any human-readable suffix is reduced to a conservative character
     * set, so that neither {@code '/'} nor {@code '\'} - a path separator on Windows - can
     * survive into the resolved path. Splitting on {@code '/'} alone, as the previous
     * implementation did, let a URL ending {@code a\..\..\evil.xml} escape the directory.
     */
    private static String safeCacheFileName(String rawUrl) {
        String hash = sha256Hex(rawUrl);

        int lastSep = Math.max(rawUrl.lastIndexOf('/'), rawUrl.lastIndexOf('\\'));
        String tail = rawUrl.substring(lastSep + 1);
        int q = tail.indexOf('?');
        if (q >= 0) {
            tail = tail.substring(0, q);
        }

        if (!tail.toLowerCase(Locale.ROOT).endsWith(".xml")) {
            return hash + ".xml";
        }

        String label = tail.replaceAll("[^A-Za-z0-9._-]", "_");
        return label.isEmpty() || label.startsWith(".") ? hash + ".xml" : hash + "_" + label;
    }

    /**
     * Converts a GitHub directory URL to the GitHub Contents API URL.
     *   https://github.com/owner/repo/tree/branch/path \u2192 https://api.github.com/repos/owner/repo/contents/path?ref=branch
     *   https://raw.githubusercontent.com/owner/repo/branch/path \u2192 same
     */
    private static String buildGitHubContentsApiUrl(String dirUrl) {
        if (dirUrl.startsWith("https://github.com/") || dirUrl.startsWith("http://github.com/")) {
            int hostEnd = dirUrl.indexOf('/', dirUrl.indexOf("//") + 2);
            String rest = dirUrl.substring(hostEnd + 1); // owner/repo/tree/branch/path
            String[] segs = rest.split("/", 4);           // [owner, repo, type, tail]
            if (segs.length < 4) return null;
            String owner = segs[0], repo = segs[1], tail = segs[3]; // tail = branch/path
            int slash = tail.indexOf('/');
            if (slash < 0) return null;
            String branch = tail.substring(0, slash);
            String path   = tail.substring(slash + 1);
            return "https://api.github.com/repos/" + owner + "/" + repo + "/contents/" + path + "?ref=" + branch;
        }

        if (dirUrl.startsWith("https://raw.githubusercontent.com/") || dirUrl.startsWith("http://raw.githubusercontent.com/")) {
            // raw.githubusercontent.com/owner/repo/branch/path
            String noScheme = dirUrl.substring(dirUrl.indexOf("//") + 2);
            String[] segs = noScheme.split("/", 5); // [host, owner, repo, branch, path]
            if (segs.length < 5) return null;
            String owner = segs[1], repo = segs[2], branch = segs[3], path = segs[4];
            return "https://api.github.com/repos/" + owner + "/" + repo + "/contents/" + path + "?ref=" + branch;
        }

        return null;
    }

    private static List<GitHubApiEntry> fetchGitHubDirectoryContents(String apiUrl) throws IOException {
        dbg("fetchGitHubDirectoryContents url=" + urlForLog(apiUrl));

        URI uri = requireFetchableRemoteUri(apiUrl);
        boolean credentialed = willSendGitHubCredential(uri);

        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMillis(REMOTE_FETCH_CONFIG.readTimeoutMs()))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28");

        addGitHubAuthHeader(req, uri);

        HttpResponse<String> response;
        try {
            response = newHttpClient(credentialed)
                    .send(req.GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted: " + urlForLog(apiUrl), e);
        }

        int status = response.statusCode();
        if (status == 404) throw new IOException("HTTP 404 Not Found: " + urlForLog(apiUrl));
        if (status < 200 || status >= 300) throw new IOException("HTTP " + status + ": " + urlForLog(apiUrl));

        return parseGitHubContentsJson(response.body());
    }

    /**
     * Attaches the GitHub credential only when the request authority is exactly a host on
     * {@link #GITHUB_AUTH_HOSTS}.
     * <p>
     * The parameter is a parsed {@link URI}, not a string, by design. The previous
     * implementation tested {@code url.contains("github.com")} against the whole URL, which
     * is satisfied by any attacker-controlled address that merely mentions the domain -
     * {@code https://evil.example/github.com/x.ttl} - and so disclosed the token to that
     * host. Only the URI authority is a safe basis for this decision.
     */
    private static void addGitHubAuthHeader(HttpRequest.Builder req, URI uri) {
        String host = uri.getHost();
        if (host == null || !GITHUB_AUTH_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            return;
        }
        String token = System.getenv("GITHUB_TOKEN");
        if (token != null && !token.isBlank()) {
            req.header("Authorization", "Bearer " + token);
        }
    }

    private record GitHubApiEntry(String name, String downloadUrl) {}

    /**
     * Minimal JSON parser for the GitHub Contents API array response.
     * Extracts "name", "type", and "download_url" from each object in the array.
     * No external dependencies \u2014 uses only basic string operations.
     */
    private static List<GitHubApiEntry> parseGitHubContentsJson(String json) {
        List<GitHubApiEntry> entries = new ArrayList<>();
        for (String obj : splitJsonTopLevelObjects(json)) {
            String name = extractJsonString(obj, "name");
            String type = extractJsonString(obj, "type");
            String dlUrl = extractJsonString(obj, "download_url");
            if (name != null && "file".equals(type)) {
                entries.add(new GitHubApiEntry(name, dlUrl));
            }
        }
        return entries;
    }

    /** Splits a JSON array string into its top-level object substrings. */
    private static List<String> splitJsonTopLevelObjects(String json) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inStr = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inStr) {
                if (c == '\\') i++;           // skip escaped character
                else if (c == '"') inStr = false;
            } else {
                switch (c) {
                    case '"' -> inStr = true;
                    case '{' -> { if (depth++ == 0) start = i; }
                    case '}' -> { if (--depth == 0 && start >= 0) { objects.add(json.substring(start, i + 1)); start = -1; } }
                    default -> { /* ignore */ }
                }
            }
        }
        return objects;
    }

    /** Extracts the string value for {@code key} from a single JSON object string. */
    private static String extractJsonString(String obj, String key) {
        String search = "\"" + key + "\"";
        int ki = obj.indexOf(search);
        if (ki < 0) return null;

        int ci = obj.indexOf(':', ki + search.length());
        if (ci < 0) return null;

        int vi = ci + 1;
        while (vi < obj.length() && Character.isWhitespace(obj.charAt(vi))) vi++;
        if (vi >= obj.length() || obj.startsWith("null", vi)) return null;
        if (obj.charAt(vi) != '"') return null;

        StringBuilder sb = new StringBuilder();
        int i = vi + 1;
        while (i < obj.length()) {
            char c = obj.charAt(i);
            if (c == '"') break;
            if (c == '\\') { i++; if (i < obj.length()) sb.append(obj.charAt(i)); }
            else sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /** Run-scoped cache entry retaining the RDF model for result extraction. */
    record CachedShapes(Model model, Shapes shapes) {}

    static CachedShapes loadParsedShapesWithImports(ShapeSource root,
                                                    Path constraintsRoot,
                                                    Map<String, CachedShapes> cache) throws IOException {
        try {
            // Publish only after both import loading and parsing succeed. Concurrent
            // rows sharing a root wait for one load; failures remain retryable.
            return cache.computeIfAbsent(root.key(), key -> {
                try {
                    Model model = loadShapesWithImports(root, constraintsRoot, new HashMap<>()).model();
                    return new CachedShapes(model, Shapes.parse(model.getGraph()));
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
    }

    static LoadShapesResult loadShapesWithImports(ShapeSource root,
                                                  Path constraintsRoot,
                                                  Map<String, Model> cache) throws IOException {
        String rootKey = root.key();

        Model cached = cache.get(rootKey);
        if (cached != null) {
            dbg("SHAPES cache hit key=" + rootKey + " triples=" + cached.size());
            return new LoadShapesResult(cached, 0, 0, 0);
        }

        dbg("SHAPES cache miss key=" + rootKey);

        Model shapes = ModelFactory.createDefaultModel();
        Set<String> visited = new HashSet<>();
        Deque<ShapeSource> stack = new ArrayDeque<>();
        stack.push(root);

        int loadedFiles = 0;
        int localCount = 0;
        int remoteCount = 0;
        int remoteCacheHits = 0;
        long totalFetchedMs = 0;
        int totalImportsFound = 0;
        int unresolvableImports = 0;

        while (!stack.isEmpty()) {
            ShapeSource src = stack.pop();
            String key = src.key();

            if (!visited.add(key)) {
                dbg("SHAPES skip already visited: " + key);
                continue;
            }

            loadedFiles++;
            boolean isRemote = src instanceof RemoteShapeSource;
            if (isRemote) remoteCount++;
            else localCount++;

            dbg("SHAPES START read index=" + loadedFiles
                    + " type=" + (isRemote ? "remote" : "local")
                    + " src=" + src.displayName());
            long readStart = System.currentTimeMillis();

            Model tmp;
            if (isRemote) {
                boolean inRunCacheHit = REMOTE_IMPORTS_CACHE.containsKey(key);
                long fetchStart = System.currentTimeMillis();
                tmp = loadRemoteCached(key);
                if (inRunCacheHit) {
                    remoteCacheHits++;
                } else {
                    totalFetchedMs += System.currentTimeMillis() - fetchStart;
                }
            } else {
                tmp = readLocalShapeSource((LocalShapeSource) src);
            }

            dbg("SHAPES DONE read index=" + loadedFiles
                    + " tmpTriples=" + tmp.size()
                    + " src=" + src.displayName(), readStart);

            shapes.add(tmp);
            shapes.setNsPrefixes(tmp.getNsPrefixMap());

            StmtIterator it = tmp.listStatements(null, OWL.imports, (RDFNode) null);
            int importsInFile = 0;

            while (it.hasNext()) {
                Statement st = it.nextStatement();
                RDFNode obj = st.getObject();

                if (!obj.isURIResource()) continue;

                String uri = obj.asResource().getURI();
                importsInFile++;
                totalImportsFound++;

                ShapeSource resolved = resolveImport(uri, src, constraintsRoot);

                if (resolved != null) {
                    dbg("SHAPES import uri=" + urlForLog(uri)
                            + " resolved=" + resolved.key()
                            + " from=" + src.displayName());
                    stack.push(resolved);
                } else {
                    // Intentional: urn: prefixes and HTTP/HTTPS namespace URIs that have no
                    // recognised RDF file extension (vocabulary declarations, not downloadable files).
                    boolean isHttpOrHttps = uri.startsWith("http://") || uri.startsWith("https://");
                    boolean intentionalSkip = uri.startsWith("urn:")
                            || (isHttpOrHttps && detectLang(uri) == null);
                    if (intentionalSkip) {
                        dbg("SHAPES skipping import uri=" + urlForLog(uri)
                                + " (urn: or namespace URI without RDF extension)"
                                + " from=" + src.displayName());
                    } else {
                        unresolvableImports++;
                        dbg("SHAPES unresolvable import uri=" + urlForLog(uri)
                                + " could not be resolved to any local or remote file"
                                + " from=" + src.displayName());
                    }
                }
            }

            dbg("SHAPES imports found=" + importsInFile + " src=" + src.displayName());
        }

        cache.put(rootKey, shapes);

        dbg("SHAPES DONE load with imports"
                + " loadedFiles=" + loadedFiles
                + " (local=" + localCount + " remote=" + remoteCount + ")"
                + " totalTriples=" + shapes.size()
                + " fetchedMs=" + totalFetchedMs
                + " cacheHits=" + remoteCacheHits
                + " key=" + rootKey);

        return new LoadShapesResult(shapes, totalImportsFound, loadedFiles, unresolvableImports);
    }

    static ShapeSource resolveImport(String importUri, ShapeSource current, Path constraintsRoot) {
        String u = importUri == null ? "" : importUri.trim();
        if (u.isEmpty()) return null;
        if (u.startsWith("urn:")) return null;

        if (u.startsWith("http://") || u.startsWith("https://")) {
            // Only fetch URLs that look like actual RDF files (have a recognised extension).
            // Namespace URIs such as http://www.w3.org/ns/shacl# carry no file extension
            // and are vocabulary declarations, not downloadable shape files.  Trying to
            // fetch them breaks existing configurations and hammers third-party servers.
            if (detectLang(u) == null) {
                dbg("resolveImport skipping non-file remote URI (no RDF extension): " + urlForLog(importUri));
                return null;
            }
            try {
                // Apply the egress policy at classification time as well as at fetch time, so
                // a disallowed import is reported as unresolvable rather than attempted. An
                // import URI comes from a third-party shapes file and is fully attacker-
                // controlled; without this it can drive requests to arbitrary hosts.
                return new RemoteShapeSource(requireAllowedRemoteUri(u));
            } catch (IOException e) {
                dbg("resolveImport refused remote URI=" + urlForLog(importUri)
                        + " reason=" + forLog(e.getMessage()));
                return null;
            }
        }

        if (u.startsWith("file:")) {
            try {
                Path p = Paths.get(URI.create(u));
                dbg("resolveImport fileUri=" + forLog(importUri) + " resolved=" + p);
                return new LocalShapeSource(p);
            } catch (Exception ex) {
                dbg("resolveImport invalid fileUri=" + forLog(importUri) + " error=" + forLog(ex.getMessage()));
                return null;
            }
        }

        // Some CGMES constraint packages use C://path/to/file.ttl instead of a file: URI.
        // Treat that explicitly as an absolute Windows path rather than resolving it relative
        // to the current TTL directory.
        if (u.matches("(?i)^[a-z]:/+.*")) {
            try {
                String windowsPath = u.replaceFirst("(?i)^([a-z]):/+", "$1:/");
                Path p = Paths.get(windowsPath).toAbsolutePath().normalize();
                if (Files.isRegularFile(p)) {
                    dbg("resolveImport windowsPath=" + forLog(importUri) + " resolved=" + p);
                    return new LocalShapeSource(p);
                }
                dbg("resolveImport windowsPath missing=" + forLog(importUri) + " resolved=" + p);
                return null;
            } catch (Exception ex) {
                dbg("resolveImport invalid windowsPath=" + forLog(importUri)
                        + " error=" + forLog(ex.getMessage()));
                return null;
            }
        }

        // Relative reference: if current is remote, resolve against its URI. The result is
        // re-validated because a protocol-relative reference such as "//evil.example/x.ttl"
        // resolves to a different host and would otherwise bypass the egress policy.
        if (current instanceof RemoteShapeSource rss) {
            try {
                URI resolved = rss.uri().resolve(u);
                dbg("resolveImport relative-from-remote uri=" + forLog(u)
                        + " resolved=" + urlForLog(resolved.toString()));
                return new RemoteShapeSource(requireAllowedRemoteUri(resolved.toString()));
            } catch (IOException e) {
                dbg("resolveImport refused relative-from-remote uri=" + forLog(u)
                        + " reason=" + forLog(e.getMessage()));
                return null;
            } catch (Exception e) {
                dbg("resolveImport relative-from-remote failed uri=" + forLog(u)
                        + " error=" + forLog(e.getMessage()));
            }
        }

        Path currentDir = current instanceof LocalShapeSource lss
                ? lss.path().toAbsolutePath().normalize().getParent()
                : constraintsRoot;

        Path candidate1 = currentDir.resolve(u).normalize();
        if (Files.exists(candidate1)) {
            dbg("resolveImport candidate1=" + candidate1);
            return new LocalShapeSource(candidate1);
        }

        Path candidate2 = constraintsRoot.resolve(u).normalize();
        if (Files.exists(candidate2)) {
            dbg("resolveImport candidate2=" + candidate2);
            return new LocalShapeSource(candidate2);
        }

        String fileName = u;
        int slash = fileName.lastIndexOf('/');
        if (slash >= 0) fileName = fileName.substring(slash + 1);

        Path candidate3 = constraintsRoot.resolve(fileName).normalize();
        if (Files.exists(candidate3)) {
            dbg("resolveImport candidate3=" + candidate3);
            return new LocalShapeSource(candidate3);
        }

        dbg("resolveImport could not resolve uri=" + forLog(importUri) + " from=" + forLog(current.displayName()));
        return null;
    }

    private static Model readLocalShapeSource(LocalShapeSource src) throws IOException {
        Path p = src.path().toAbsolutePath().normalize();
        if (!Files.exists(p)) {
            throw new FileNotFoundException("Imported TTL not found: " + p);
        }
        Model m = ModelFactory.createDefaultModel();
        RDFDataMgr.read(m, p.toUri().toString());
        return m;
    }

    private static Model loadRemoteCached(String url) throws IOException {
        Model existing = REMOTE_IMPORTS_CACHE.get(url);
        if (existing != null) {
            return copyWithPrefixes(existing);
        }
        Object lock = REMOTE_URL_LOCKS.computeIfAbsent(url, k -> new Object());
        synchronized (lock) {
            Model existing2 = REMOTE_IMPORTS_CACHE.get(url);
            if (existing2 != null) {
                return copyWithPrefixes(existing2);
            }
            dbg("SHAPES remote fetch start url=" + urlForLog(url));
            long fetchStart = System.currentTimeMillis();
            Model fetched = fetchRemoteModel(url);
            dbg("SHAPES remote fetch done url=" + urlForLog(url)
                    + " triples=" + fetched.size(), fetchStart);
            REMOTE_IMPORTS_CACHE.put(url, fetched);
            return copyWithPrefixes(fetched);
        }
    }

    private static Model copyWithPrefixes(Model src) {
        return ModelFactory.createDefaultModel().add(src).setNsPrefixes(src.getNsPrefixMap());
    }

    private static Model fetchRemoteModel(String url) throws IOException {
        if (!REMOTE_FETCH_CONFIG.enabled()) {
            throw new IOException("Remote imports are disabled by configuration: " + url);
        }
        if (REMOTE_FETCH_CONFIG.offline()) {
            return loadFromDiskCacheOrFail(url);
        }

        String hash = sha256Hex(url);
        Path cacheDir = REMOTE_FETCH_CONFIG.diskCacheDir();
        Path cachedFile = resolveInCacheDir(cacheDir, hash + ".ttl");
        Path metaFile = resolveInCacheDir(cacheDir, hash + ".meta");

        String etag = null;
        String lastModified = null;
        if (Files.exists(cachedFile)) {
            Properties meta = readCacheMeta(metaFile);
            etag = meta.getProperty("etag");
            lastModified = meta.getProperty("last-modified");
        }

        IOException lastEx = null;
        for (int attempt = 0; attempt <= REMOTE_FETCH_CONFIG.retries(); attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(500L * (1L << (attempt - 1)));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting to retry: " + url, ie);
                }
            }
            try {
                return fetchHttpWithDiskCache(url, etag, lastModified, cachedFile, metaFile);
            } catch (IOException e) {
                String msg = e.getMessage();
                if (msg != null && msg.startsWith("HTTP 404")) throw e;
                lastEx = e;
                dbg("SHAPES remote fetch attempt=" + attempt + " failed url=" + urlForLog(url)
                        + " error=" + e.getMessage());
            }
        }
        throw lastEx != null ? lastEx
                : new IOException("Failed to fetch remote import: " + url);
    }

    private static Model fetchHttpWithDiskCache(String url,
                                                String conditionalEtag,
                                                String conditionalLastMod,
                                                Path cachedFile,
                                                Path metaFile) throws IOException {
        URI uri = requireFetchableRemoteUri(url);
        boolean credentialed = willSendGitHubCredential(uri);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMillis(REMOTE_FETCH_CONFIG.readTimeoutMs()));

        addGitHubAuthHeader(reqBuilder, uri);

        if (conditionalEtag != null) {
            reqBuilder.header("If-None-Match", conditionalEtag);
        } else if (conditionalLastMod != null) {
            reqBuilder.header("If-Modified-Since", conditionalLastMod);
        }

        HttpResponse<byte[]> response;
        try {
            response = newHttpClient(credentialed).send(reqBuilder.GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted for: " + urlForLog(url), e);
        }

        int status = response.statusCode();
        dbg("SHAPES remote HTTP status=" + status + " url=" + urlForLog(url)
                + " bytes=" + (response.body() != null ? response.body().length : 0));

        if (status == 304 && Files.exists(cachedFile)) {
            dbg("SHAPES remote 304 Not Modified, using disk cache url=" + urlForLog(url));
            return parseTtlBytes(Files.readAllBytes(cachedFile), url);
        }

        if (status == 404) {
            throw new IOException("HTTP 404 Not Found: " + urlForLog(url));
        }
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " fetching: " + urlForLog(url));
        }

        byte[] body = requireBoundedBody(response, url);
        String newEtag = response.headers().firstValue("ETag").orElse(null);
        String newLastMod = response.headers().firstValue("Last-Modified").orElse(null);

        Model m = parseTtlBytes(body, url);

        try {
            Path dir = REMOTE_FETCH_CONFIG.diskCacheDir();
            createPrivateDirectory(dir);
            Path tmpFile = resolveInCacheDir(dir, cachedFile.getFileName() + ".tmp");
            Files.write(tmpFile, body);
            Files.move(tmpFile, cachedFile, StandardCopyOption.REPLACE_EXISTING);

            writeCacheMeta(metaFile, newEtag, newLastMod);
        } catch (IOException cacheEx) {
            dbg("SHAPES remote disk cache write failed url=" + urlForLog(url)
                    + " error=" + cacheEx.getMessage());
        }

        return m;
    }

    private static Model loadFromDiskCacheOrFail(String url) throws IOException {
        String hash = sha256Hex(url);
        Path cachedFile = REMOTE_FETCH_CONFIG.diskCacheDir().resolve(hash + ".ttl");
        if (!Files.exists(cachedFile)) {
            throw new IOException("Offline mode: URL not in disk cache: " + url);
        }
        dbg("SHAPES offline disk cache hit url=" + urlForLog(url));
        return parseTtlBytes(Files.readAllBytes(cachedFile), url);
    }

    private static Model parseTtlBytes(byte[] bytes, String baseUrl) throws IOException {
        bytes = fixDoubleAngleBracket(bytes);
        bytes = injectSparqlPrefixes(bytes);
        Lang lang = detectLang(baseUrl);
        Model m = ModelFactory.createDefaultModel();
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
            if (lang != null) {
                RDFParser.source(bais).lang(lang).base(baseUrl).parse(m);
            } else {
                RDFParser.source(bais).base(baseUrl).parse(m);
            }
        } catch (Exception e) {
            throw new IOException("Failed to parse RDF from " + baseUrl + ": " + e.getMessage(), e);
        }
        return m;
    }

    private static byte[] fixDoubleAngleBracket(byte[] bytes) {
        // Some NCP 2.5 SHACL files contain a typo in SPARQL IN() lists:
        //   IN (<<https://...>  instead of  IN (<https://...>
        // "<<https://" and "<<http://" are never valid in any RDF or SPARQL syntax
        // (old RDF-star uses "<< <iri>" with a space; new syntax uses "<<("),
        // so this replacement is safe and unambiguous.
        String s = new String(bytes, StandardCharsets.UTF_8);
        String f = s.replace("<<https://", "<https://").replace("<<http://", "<http://");
        return f.length() == s.length() && f.equals(s) ? bytes : f.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Jena SHACL does not reliably resolve prefixes in sh:select/sh:ask SPARQL queries from
     * sh:PrefixDeclaration resources in the shapes graph when models are merged across imports.
     * This fix collects the Turtle/SPARQL PREFIX declarations at the top of the file and injects
     * them verbatim at the start of every sh:select and sh:ask triple-quoted string, ensuring
     * the SPARQL parser always has the full prefix context for each query.
     */
    private static byte[] injectSparqlPrefixes(byte[] bytes) {
        String s = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = s.split("\n", -1);

        // Collect PREFIX declarations: both SPARQL-style (PREFIX foo: <...>) and
        // Turtle-style (@prefix foo: <...> .) — converting the latter to SPARQL form.
        StringBuilder prefixBlock = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("PREFIX ") || t.startsWith("prefix ")) {
                prefixBlock.append(t).append("\n");
            } else if (t.startsWith("@prefix ") || t.startsWith("@PREFIX ")) {
                String sparql = t
                    .replaceFirst("(?i)^@prefix\\s+", "PREFIX ")
                    .replaceFirst("\\s*\\.$", "");
                prefixBlock.append(sparql).append("\n");
            }
        }
        if (prefixBlock.length() == 0) return bytes;

        String injection = "\n" + prefixBlock;
        String trigger1 = "sh:select \"\"\"";
        String trigger2 = "sh:ask \"\"\"";
        boolean changed = s.contains(trigger1) || s.contains(trigger2);
        if (!changed) return bytes;

        String fixed = s.replace(trigger1, trigger1 + injection)
                        .replace(trigger2, trigger2 + injection);
        return fixed.getBytes(StandardCharsets.UTF_8);
    }

    private static Lang detectLang(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        int q = lower.indexOf('?');
        String path = q >= 0 ? lower.substring(0, q) : lower;
        if (path.endsWith(".ttl")) return Lang.TURTLE;
        if (path.endsWith(".rdf") || path.endsWith(".owl")) return Lang.RDFXML;
        if (path.endsWith(".nt")) return Lang.NTRIPLES;
        if (path.endsWith(".jsonld")) return Lang.JSONLD;
        if (path.endsWith(".n3")) return Lang.N3;
        return null;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
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
        final Map<Path, ZipXmlEntry> zipEntriesByVirtualPath;

        InputGroup(String name, List<Path> roots, List<ZipXmlEntry> zipEntries) {
            this.name = name;
            this.roots = roots;
            this.zipEntriesByVirtualPath = zipEntries.stream()
                    .collect(Collectors.toMap(
                            entry -> entry.virtualPath,
                            entry -> entry,
                            (left, right) -> left,
                            LinkedHashMap::new
                    ));
        }
    }

    /**
     * Produces one parsed Jena Shapes object for the exact set of roots selected by a mapping
     * row. Each root still contributes its full owl:imports closure; RDF set semantics remove
     * overlapping imported triples. The canonical cache key makes the combination reusable for
     * every timestamp without conflating it with another shape set.
     */
    static CachedShapes loadParsedShapesWithImports(Collection<Path> roots,
                                                    Path constraintsRoot,
                                                    Map<String, CachedShapes> cache) throws IOException {
        List<Path> canonicalRoots = roots.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .sorted(Comparator.comparing(Path::toString))
                .toList();

        if (canonicalRoots.isEmpty()) {
            throw new IOException("No SHACL constraint files were resolved");
        }

        String cacheKey = "SHAPES_COMBINATION:"
                + canonicalRoots.stream().map(Path::toString).collect(Collectors.joining("|"));

        try {
            return cache.computeIfAbsent(cacheKey, ignored -> {
                try {
                    Model combination = ModelFactory.createDefaultModel();
                    Map<String, Model> rootsInCombination = new HashMap<>();

                    for (Path root : canonicalRoots) {
                        Model rootModel = loadShapesWithImports(
                                new LocalShapeSource(root), constraintsRoot, rootsInCombination).model();
                        combination.add(rootModel);
                        combination.setNsPrefixes(rootModel.getNsPrefixMap());
                    }

                    Shapes parsed = Shapes.parse(combination.getGraph());
                    long deactivated = parsed.getShapeMap().values().stream()
                            .filter(Shape::deactivated)
                            .count();
                    dbg("SHAPES combination loaded"
                            + " roots=" + canonicalRoots.size()
                            + " triples=" + combination.size()
                            + " parsedShapes=" + parsed.numShapes()
                            + " deactivatedShapes=" + deactivated
                            + " key=" + cacheKey);
                    return new CachedShapes(combination, parsed);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
    }

    /**
     * An XML entry in an input archive. Its virtual path is only an identity used by the
     * existing timestamp/mapping resolver; no corresponding file is ever written to disk.
     */
    static class ZipXmlEntry {
        final Path archivePath;
        final String entryName;
        final Path virtualPath;

        ZipXmlEntry(Path archivePath, String entryName, Path virtualPath) {
            this.archivePath = archivePath;
            this.entryName = entryName;
            this.virtualPath = virtualPath;
        }

        InputStream openStream() throws IOException {
            java.util.zip.ZipFile archive = new java.util.zip.ZipFile(archivePath.toFile());
            java.util.zip.ZipEntry entry = archive.getEntry(entryName);
            if (entry == null) {
                archive.close();
                throw new FileNotFoundException("ZIP entry not found: " + archivePath + "!" + entryName);
            }

            InputStream entryStream = archive.getInputStream(entry);
            return new java.io.FilterInputStream(entryStream) {
                private long bytesRead;

                @Override
                public int read() throws IOException {
                    int value = super.read();
                    if (value >= 0) checkLimit(1);
                    return value;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    int count = super.read(bytes, offset, length);
                    if (count > 0) checkLimit(count);
                    return count;
                }

                private void checkLimit(long count) throws IOException {
                    bytesRead += count;
                    if (bytesRead > MAX_ZIP_XML_BYTES) {
                        throw new IOException("ZIP entry exceeds the XML size limit: "
                                + archivePath.getFileName() + "!" + entryName);
                    }
                }

                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        archive.close();
                    }
                }
            };
        }
    }

    private static List<InputGroup> prepareTimestampedInputGroups(Path inputPath, Path outputBaseDir) throws IOException {
        if (Files.isRegularFile(inputPath)
                && inputPath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {

            String zipBaseName = removeExtension(inputPath.getFileName().toString());
            return List.of(new InputGroup(zipBaseName, List.of(), scanZipXmlEntries(inputPath)));
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
        List<ZipXmlEntry> zipEntries = new ArrayList<>();

        Path normalizedOutput = outputBaseDir.toAbsolutePath().normalize();

        try (java.util.stream.Stream<Path> stream = Files.walk(groupRoot)) {
            List<Path> zipFiles = stream
                    .filter(p -> !isIgnoredTimestampedInputPath(p, normalizedOutput))
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();

            for (Path zip : zipFiles) {
                List<ZipXmlEntry> entries = scanZipXmlEntries(zip);
                zipEntries.addAll(entries);

                logInfo("Prepared zip input group=" + groupName
                        + " zip=" + zip.toAbsolutePath()
                        + " xmlEntries=" + entries.size()
                        + " storage=in-memory-on-demand");
            }
        }

        return new InputGroup(groupName, roots, zipEntries);
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

        for (ZipXmlEntry zipEntry : inputGroup.zipEntriesByVirtualPath.values()) {
            Path xml = zipEntry.virtualPath;
            String fileName = xml.getFileName().toString();
            String profile = detectProfile(fileName, xml);
            String headerTimestamp = readTimestampFromXmlHeader(xml, inputGroup.zipEntriesByVirtualPath);
            String filenameTimestamp = readTimestampFromFileName(fileName);

            drafts.add(new XmlDiscoveryDraft(
                    xml,
                    fileName,
                    profile,
                    headerTimestamp,
                    filenameTimestamp
            ));
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

            String constraintFileText = formatTtlPaths(resolveTtlPaths(constraintsRoot, ttlName));

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
                } else {
                    status = "Missing input - skipped";
                    message = "One or more required inputs from the mapping row were not resolved, so validation was skipped to avoid validating an incomplete data graph.";
                }
            } else if (resolution.xmlFiles.size() > resolution.expectedFileCount) {
                status = "Too many files - skipped";
                message = "More XML files were resolved than requested mapping tokens. Validation was skipped to avoid loading an unintended data graph.";
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
                logWarn("PRECHECK_SKIP validation row"
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
        return resolution != null
                && resolution.xmlFiles != null
                && !resolution.xmlFiles.isEmpty()
                && resolution.missingInputs != null
                && resolution.missingInputs.isEmpty()
                && resolution.xmlFiles.size() == resolution.expectedFileCount;
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

        if (profiles.isEmpty()) {
            return xmlFiles == null || xmlFiles.isEmpty()
                    ? "UNKNOWN_PROFILES"
                    : xmlFiles.stream()
                    .filter(Objects::nonNull)
                    .map(p -> p.getFileName() == null ? "" : p.getFileName().toString())
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.joining(" + "));
        }

        return String.join(" + ", profiles);
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
                    createPrivateDirectory(parent);
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
        return readTimestampFromXmlHeader(xmlPath, Map.of());
    }

    private static String readTimestampFromXmlHeader(Path xmlPath,
                                                     Map<Path, ZipXmlEntry> zipEntriesByVirtualPath) {
        ZipXmlEntry zipEntry = zipEntriesByVirtualPath.get(xmlPath.toAbsolutePath().normalize());

        try (InputStream input = zipEntry == null
                ? Files.newInputStream(xmlPath)
                : zipEntry.openStream()) {
            return readTimestampFromRdfXmlHeader(input);

        } catch (Exception ex) {
            dbg("Could not read timestamp from XML header: " + xmlPath
                    + " error=" + ex.getMessage());
            return "";
        }
    }

    /**
     * Reads the RDF/XML header structurally, without loading an XML document or relying on line
     * breaks.  A CGMES export may be a single very long line; StAX still emits an event for every
     * element and permits us to stop as soon as its header closes.
     * <p>
     * Both common forms are supported: {@code <md:FullModel>} / {@code <dcat:Dataset>} elements
     * and {@code <rdf:Description>} whose child {@code rdf:type} identifies either class.
     * Within FullModel, scenarioTime has priority over startDate. Dataset startDate is used for
     * Dataset headers.
     */
    static String readTimestampFromRdfXmlHeader(InputStream input) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);

        Deque<RdfXmlHeaderElement> elements = new ArrayDeque<>();
        XMLStreamReader reader = factory.createXMLStreamReader(input, StandardCharsets.UTF_8.name());
        try {
            while (reader.hasNext()) {
                int event = reader.next();

                if (event == XMLStreamConstants.START_ELEMENT) {
                    RdfXmlHeaderElement parent = elements.peek();
                    RdfXmlHeaderElement element = new RdfXmlHeaderElement(
                            parent,
                            classifyDirectHeaderElement(reader.getNamespaceURI(), reader.getLocalName())
                    );
                    elements.push(element);

                    if (isRdfTypeElement(reader)) {
                        HeaderKind type = classifyHeaderTypeUri(
                                reader.getAttributeValue(RDF.getURI(), "resource"));
                        if (type != HeaderKind.NONE && parent != null) {
                            parent.kind = type;
                        }
                    }

                    if (isScenarioTimeName(reader.getLocalName())
                            || isStartDateName(reader.getLocalName())) {
                        RdfXmlHeaderElement header = findHeaderElement(parent);
                        if (header != null) {
                            element.captureHeader = header;
                            element.captureScenarioTime = isScenarioTimeName(reader.getLocalName());
                        }
                    }
                } else if (event == XMLStreamConstants.CHARACTERS
                        || event == XMLStreamConstants.CDATA) {
                    RdfXmlHeaderElement current = elements.peek();
                    if (current != null && current.captureHeader != null) {
                        current.text.append(reader.getText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    RdfXmlHeaderElement current = elements.pop();
                    if (current.captureHeader != null) {
                        String timestamp = normalizeTimestamp(current.text.toString());
                        if (!timestamp.isBlank()) {
                            if (current.captureScenarioTime) {
                                current.captureHeader.scenarioTime = timestamp;
                            } else {
                                current.captureHeader.startDate = timestamp;
                            }
                        }
                    }

                    if (current.kind == HeaderKind.FULL_MODEL) {
                        if (!current.scenarioTime.isBlank()) {
                            return current.scenarioTime;
                        }
                        if (!current.startDate.isBlank()) {
                            return current.startDate;
                        }
                    }
                    if (current.kind == HeaderKind.DATASET && !current.startDate.isBlank()) {
                        return current.startDate;
                    }
                }
            }
        } finally {
            reader.close();
        }
        return "";
    }

    private enum HeaderKind { NONE, FULL_MODEL, DATASET }

    private static final class RdfXmlHeaderElement {
        final RdfXmlHeaderElement parent;
        final StringBuilder text = new StringBuilder();
        HeaderKind kind;
        RdfXmlHeaderElement captureHeader;
        boolean captureScenarioTime;
        String scenarioTime = "";
        String startDate = "";

        RdfXmlHeaderElement(RdfXmlHeaderElement parent, HeaderKind kind) {
            this.parent = parent;
            this.kind = kind;
        }
    }

    private static RdfXmlHeaderElement findHeaderElement(RdfXmlHeaderElement start) {
        for (RdfXmlHeaderElement current = start; current != null; current = current.parent) {
            if (current.kind != HeaderKind.NONE) {
                return current;
            }
        }
        return null;
    }

    private static HeaderKind classifyDirectHeaderElement(String namespace, String localName) {
        if ("FullModel".equals(localName)) {
            return HeaderKind.FULL_MODEL;
        }
        if ("Dataset".equals(localName) && "http://www.w3.org/ns/dcat#".equals(namespace)) {
            return HeaderKind.DATASET;
        }
        return HeaderKind.NONE;
    }

    private static HeaderKind classifyHeaderTypeUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return HeaderKind.NONE;
        }
        if (uri.endsWith("#FullModel") || uri.endsWith("/FullModel")) {
            return HeaderKind.FULL_MODEL;
        }
        if ("http://www.w3.org/ns/dcat#Dataset".equals(uri)) {
            return HeaderKind.DATASET;
        }
        return HeaderKind.NONE;
    }

    private static boolean isRdfTypeElement(XMLStreamReader reader) {
        return "type".equals(reader.getLocalName()) && RDF.getURI().equals(reader.getNamespaceURI());
    }

    private static boolean isScenarioTimeName(String localName) {
        return safe(localName).toLowerCase(Locale.ROOT).endsWith("scenariotime");
    }

    private static boolean isStartDateName(String localName) {
        return safe(localName).toLowerCase(Locale.ROOT).endsWith("startdate");
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

    private static String removeExtension(String fileName) {
        String s = safe(fileName).trim();
        int dot = s.lastIndexOf('.');
        if (dot > 0) {
            return s.substring(0, dot);
        }
        return s;
    }

    /** Maximum uncompressed XML bytes accepted from one input archive. */
    private static final long MAX_ZIP_XML_BYTES = 2L * 1024 * 1024 * 1024; // 2 GiB
    /** Maximum XML entries accepted from one input archive. */
    private static final int MAX_ZIP_XML_ENTRIES = 10_000;

    /**
     * Lists XML entries without extracting them. The entry stream is opened only when metadata
     * or RDF parsing needs it, and is closed immediately after that operation.
     */
    static List<ZipXmlEntry> scanZipXmlEntries(Path zipPath) throws IOException {
        List<ZipXmlEntry> entries = new ArrayList<>();
        long declaredBytes = 0;
        Path normalizedZip = zipPath.toAbsolutePath().normalize();
        String archiveIdentity = Integer.toUnsignedString(normalizedZip.toString().hashCode(), 36);
        Path virtualRoot = normalizedZip.getParent()
                .resolve(".cimpal-zip-inputs")
                .resolve(archiveIdentity);

        try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(normalizedZip.toFile())) {
            Enumeration<? extends java.util.zip.ZipEntry> zipEntries = archive.entries();
            while (zipEntries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = zipEntries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }

                String entryName = entry.getName().replace("\\", "/");
                if (!entryName.toLowerCase(Locale.ROOT).endsWith(".xml")) {
                    continue;
                }

                Path virtualPath = virtualRoot.resolve(entryName).normalize();
                if (!virtualPath.startsWith(virtualRoot)) {
                    throw new IOException("Unsafe ZIP entry path: " + entryName);
                }

                if (entries.size() + 1 > MAX_ZIP_XML_ENTRIES) {
                    throw new IOException("Archive exceeds the entry limit ("
                            + MAX_ZIP_XML_ENTRIES + "): " + normalizedZip.getFileName());
                }

                if (entry.getSize() >= 0) {
                    declaredBytes += entry.getSize();
                    if (declaredBytes > MAX_ZIP_XML_BYTES) {
                        throw new IOException("Archive exceeds the XML size limit ("
                                + MAX_ZIP_XML_BYTES + " bytes): " + normalizedZip.getFileName());
                    }
                }

                entries.add(new ZipXmlEntry(normalizedZip, entryName, virtualPath));
            }
        }

        return entries;
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
                                                                  Map<String, CachedShapes> shapesCache,
                                                                  Map<Path, Model> staticXmlModelCache,
                                                                  Map<Path, Model> timestampXmlModelCache,
                                                                  Map<Path, ZipXmlEntry> zipEntriesByVirtualPath,
                                                                  int threads,
                                                                  Map<String, RDFDatatype> dataTypeMap,
                                                                  String xmlBase,
                                                                  int maxResultsPerConstraint) throws IOException {

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
                            zipEntriesByVirtualPath,
                            dataTypeMap,
                            xmlBase,
                            maxResultsPerConstraint
                    );
                } catch (Throwable t) {
                    System.err.println("[WORKER_ERROR][" + Thread.currentThread().getName()
                            + "][timestamp row " + row.rowIdx + "]");
                    logError("Unhandled exception", t);
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
                        "",
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
                        "",
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

    /**
     * Records a failure and its stack trace in the diagnostic log.
     * <p>
     * Preferred over {@code Throwable.printStackTrace()}, which writes to {@code stderr} -
     * discarded entirely under the windowed launcher - so the detail was lost exactly when
     * it was needed. The message is sanitised, as it may embed untrusted values.
     */
    private static void logError(String message, Throwable t) {
        StringWriter sw = new StringWriter();
        if (t != null) {
            t.printStackTrace(new PrintWriter(sw));
        }
        dbg("ERROR " + forLog(message) + (t != null ? System.lineSeparator() + sw : ""));
    }

    private static ValidationTaskResult validateOneResolvedRow(ResolvedMappingRow row,
                                                               Path constraintsRoot,
                                                               Map<String, CachedShapes> shapesCache,
                                                               Map<Path, Model> staticXmlModelCache,
                                                               Map<Path, Model> timestampXmlModelCache,
                                                               Map<Path, ZipXmlEntry> zipEntriesByVirtualPath,
                                                               Map<String, RDFDatatype> dataTypeMap,
                                                               String xmlBase,
                                                               int maxResultsPerConstraint) {

        long rowStart = System.currentTimeMillis();

        dbgRow(row.rowIdx, "START timestamped resolved row"
                + " tso=" + row.tso
                + " timestamp=" + row.timestamp
                + " ttl=" + row.ttlName
                + " xmlFiles=" + (row.xmlFiles == null ? 0 : row.xmlFiles.size()));

        try {
            if (row.xmlFiles == null || row.xmlFiles.isEmpty()) {
                return new ValidationTaskResult(
                        row.rowIdx,
                        row.caseFolder,
                        row.datasetName,
                        row.ttlName,
                        row.xmlFilesText,
                        "",
                        row.constraintFileText,
                        null,
                        false,
                        new IOException("No XML files resolved for timestamped validation row")
                );
            }

            List<Path> ttlPaths = resolveTtlPaths(constraintsRoot, row.ttlName);
            Optional<Path> missingTtl = ttlPaths.stream().filter(path -> !Files.exists(path)).findFirst();

            if (ttlPaths.isEmpty() || missingTtl.isPresent()) {
                return new ValidationTaskResult(
                        row.rowIdx,
                        row.caseFolder,
                        row.datasetName,
                        row.ttlName,
                        row.xmlFilesText,
                        "",
                        row.constraintFileText,
                        null,
                        false,
                        new FileNotFoundException("TTL not found: "
                                + missingTtl.map(Path::toString).orElse(row.ttlName))
                );
            }

            long shapesStart = System.currentTimeMillis();
            CachedShapes cachedShapes = loadParsedShapesWithImports(ttlPaths, constraintsRoot, shapesCache);
            Model shapesModel = cachedShapes.model();
            dbgRow(row.rowIdx, "DONE load timestamped shapes"
                    + " triples=" + shapesModel.size(), shapesStart);

            long dataStart = System.currentTimeMillis();
            Graph dataGraph = loadRdfXmlGraphFromFilesWithCache(
                    row.xmlFiles,
                    staticXmlModelCache,
                    timestampXmlModelCache,
                    zipEntriesByVirtualPath,
                    dataTypeMap,
                    xmlBase,
                    row.rowIdx
            );
            dbgRow(row.rowIdx, "DONE load timestamped data graph", dataStart);

            long validationStart = System.currentTimeMillis();
            LimitedValidationOutcome limitedOutcome = maxResultsPerConstraint == 0
                    ? validateCompletely(cachedShapes.shapes(), dataGraph, shapesModel)
                    : validateWithResultLimit(cachedShapes.shapes(), dataGraph, shapesModel,
                            maxResultsPerConstraint, row.rowIdx);
            dbgRow(row.rowIdx, "DONE timestamped SHACL validation"
                    + " conforms=" + limitedOutcome.conforms()
                    + (limitedOutcome.partial() ? " partial=true" : ""), validationStart);

            long extractionStart = System.currentTimeMillis();
            List<SHACLValidationResult> results = limitedOutcome.results();
            dbgRow(row.rowIdx, "DONE timestamped result extraction"
                    + " resultCount=" + (results == null ? "null" : results.size())
                    + (limitedOutcome.partial()
                    ? " limitPerConstraint=" + maxResultsPerConstraint + " partial=true" : ""), extractionStart);
            dbgTopResultShapes(row.rowIdx, results);

            dbgRow(row.rowIdx, "DONE timestamped resolved row"
                            + " tso=" + row.tso
                            + " timestamp=" + row.timestamp
                            + " conforms=" + limitedOutcome.conforms()
                            + " resultCount=" + (results == null ? "null" : results.size()),
                    rowStart);

            return new ValidationTaskResult(
                    row.rowIdx, row.caseFolder, row.datasetName, row.ttlName,
                    row.xmlFilesText, "", row.constraintFileText,
                    results, limitedOutcome.conforms(), null
            ).withDisplayName(row.sourceRow.notes)
                    .withPartialValidation(limitedOutcome.partial());

        } catch (Exception ex) {
            dbgRow(row.rowIdx, "ERROR timestamped resolved row"
                            + " tso=" + row.tso
                            + " timestamp=" + row.timestamp,
                    rowStart);

            logError("Unhandled exception", ex);

            return new ValidationTaskResult(
                    row.rowIdx,
                    row.caseFolder,
                    row.datasetName,
                    row.ttlName,
                    row.xmlFilesText,
                    "",
                    row.constraintFileText,
                    null,
                    false,
                    ex
            );
        }
    }

    private static void appendTaskResultsToWriter(ValidationExcelWriter writer,
                                                  List<ValidationTaskResult> taskResults,
                                                  int maxResultsPerConstraint) {
        for (ValidationTaskResult r : taskResults) {
            if (r.error != null) {
                writer.appendError(
                        r.caseFolder, r.datasetName, r.xmlFiles, "", r.constraintFile,
                        r.error, r.displayName);
            } else {
                writer.appendValidation(
                        r.caseFolder, r.datasetName, r.xmlFiles, "", r.constraintFile,
                        r.results, r.conforms, r.displayName,
                        r.partialValidation, maxResultsPerConstraint);
            }
        }
    }

    private record LimitedValidationOutcome(List<SHACLValidationResult> results,
                                            boolean conforms,
                                            boolean partial) {}

    private static LimitedValidationOutcome validateCompletely(Shapes shapes,
                                                                Graph dataGraph,
                                                                Model shapesModel) {
        ValidationReport report = ShaclValidator.get().validate(shapes, dataGraph);
        return new LimitedValidationOutcome(
                ShaclTools.extractSHACLValidationResults(report, shapesModel),
                report.conforms(),
                false
        );
    }

    /**
     * Performs a sampled validation directly through Jena's public validation primitives.
     * Each target shape has its own context and listener. Once the listener observes more than
     * {@code maxResultsPerConstraint} failed constraint evaluations for one source shape it
     * interrupts that target-shape evaluation, retaining the results already emitted by Jena.
     * All severities are counted.
     * <p>
     * An interrupted target shape is intentionally reported as partial: a non-conformance finding
     * is valid, but no conclusion can be drawn about checks that have not run yet.
     */
    private static LimitedValidationOutcome validateWithResultLimit(Shapes shapes,
                                                                      Graph dataGraph,
                                                                      Model shapesModel,
                                                                      int maxResultsPerConstraint,
                                                                      int rowIdx) {
        List<SHACLValidationResult> combinedResults = new ArrayList<>();
        boolean partial = false;

        for (Shape targetShape : shapes.getTargetShapes()) {
            long targetShapeStart = System.currentTimeMillis();
            int focusNodeCount = 0;
            boolean targetShapePartial = false;
            ResultLimitListener listener = new ResultLimitListener(maxResultsPerConstraint);
            ValidationContext context = ValidationContext.create(shapes, dataGraph, listener);

            try {
                for (org.apache.jena.graph.Node focusNode : VLib.focusNodes(dataGraph, targetShape)) {
                    focusNodeCount++;
                    ValidationProc.execValidateShape(context, dataGraph, targetShape, focusNode);
                }
            } catch (ResultLimitReached ex) {
                partial = true;
                targetShapePartial = true;
            }

            ValidationReport targetReport = context.generateReport();
            List<SHACLValidationResult> targetResults =
                    ShaclTools.extractSHACLValidationResults(targetReport, shapesModel);
            combinedResults.addAll(targetResults);
            dbgRow(rowIdx, "DONE sampled target shape=" + targetShape.getShapeNode()
                    + " focusNodes=" + focusNodeCount
                    + " resultCount=" + targetResults.size()
                    + (targetShapePartial ? " partial=true" : ""), targetShapeStart);
        }

        List<SHACLValidationResult> distinctResults = new ArrayList<>(new LinkedHashSet<>(combinedResults));
        List<SHACLValidationResult> limitedResults = limitResultsPerConstraint(
                distinctResults, maxResultsPerConstraint);
        return new LimitedValidationOutcome(limitedResults, !partial && limitedResults.isEmpty(), partial);
    }

    /** Keeps the first {@code maxResultsPerConstraint} report rows for each SHACL source shape. */
    private static List<SHACLValidationResult> limitResultsPerConstraint(
            List<SHACLValidationResult> results, int maxResultsPerConstraint) {
        Map<String, Integer> retainedBySourceShape = new HashMap<>();
        List<SHACLValidationResult> limited = new ArrayList<>(results.size());
        for (SHACLValidationResult result : results) {
            String sourceShape = safe(result.getSourceShape());
            int retained = retainedBySourceShape.getOrDefault(sourceShape, 0);
            if (retained < maxResultsPerConstraint) {
                limited.add(result);
                retainedBySourceShape.put(sourceShape, retained + 1);
            }
        }
        return limited;
    }

    private static final class ResultLimitListener implements org.apache.jena.shacl.validation.ValidationListener {
        private final int limit;
        private final Map<org.apache.jena.graph.Node, Integer> failuresByShape = new HashMap<>();

        private ResultLimitListener(int limit) {
            this.limit = limit;
        }

        @Override
        public void onValidationEvent(org.apache.jena.shacl.validation.event.ValidationEvent event) {
            if (!(event instanceof ConstraintEvaluatedEvent evaluated) || evaluated.isValid()) {
                return;
            }

            org.apache.jena.graph.Node shape = evaluated.getShape().getShapeNode();
            int failures = failuresByShape.merge(shape, 1, Integer::sum);
            if (failures > limit) {
                throw new ResultLimitReached();
            }
        }
    }

    private static final class ResultLimitReached extends RuntimeException {
        private ResultLimitReached() {
            super(null, null, false, false);
        }
    }

    /**
     * Makes high-volume SHACL output actionable during a diagnostic run.  Result count is often
     * the first signal of a broad SPARQL constraint or an unintended cartesian join; logging the
     * leading source shapes lets us profile only those constraints next.
     */
    private static void dbgTopResultShapes(int rowIdx, List<SHACLValidationResult> results) {
        if (!DEBUG || results == null || results.isEmpty()) {
            return;
        }

        results.stream()
                .collect(Collectors.groupingBy(
                        result -> safe(result.getSourceShape()),
                        Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(20)
                .forEach(entry -> dbgRow(rowIdx, "RESULT_SOURCE"
                        + " count=" + entry.getValue()
                        + " shape=" + forLog(entry.getKey())));
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
                                               List<ValidationTaskResult> results,
                                               int maxResultsPerConstraint) {
        int validationCount = 0;
        int conformCount = 0;
        int nonConformCount = 0;
        int errorCount = 0;
        int totalResults = 0;
        int violationCount = 0;
        int warningCount = 0;
        int infoCount = 0;
        int partialValidationCount = 0;

        for (ValidationTaskResult r : results) {
            validationCount++;

            if (r.error != null) {
                errorCount++;
                continue;
            }

            if (r.partialValidation) {
                partialValidationCount++;
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
                infoCount,
                partialValidationCount,
                maxResultsPerConstraint
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

    private static void feedComparison(ValidationExcelWriter.ComparisonExcelWriter comparisonWriter,
                                       String region,
                                       String timestamp,
                                       List<ValidationTaskResult> results) {
        if (comparisonWriter == null || results == null) {
            return;
        }
        for (ValidationTaskResult r : results) {
            if (r.error != null || r.results == null) {
                continue;
            }
            int w = 0, i = 0, v = 0;
            for (SHACLValidationResult res : r.results) {
                String sev = safe(res.getSeverity()).toLowerCase(Locale.ROOT);
                if (sev.contains("violation")) v++;
                else if (sev.contains("warning")) w++;
                else i++;
            }
            String label = safe(r.displayName).isBlank() ? r.datasetName : r.displayName;
            comparisonWriter.addTimestampDataset(region, timestamp, label, w, i, v);
        }
    }

    /**
     * Re-creates the comparison XLSX from the per-timestamp reports already written to
     * {@code outputBaseDir} by a previous full run, without re-running validation.
     *
     * <p>Reads the most recent {@code timestamped_validation_summary__*.xlsx} found in
     * {@code outputBaseDir} to discover which per-timestamp reports exist and which country
     * each one belongs to, then feeds their statistics into a fresh
     * {@link ValidationExcelWriter.ComparisonExcelWriter} together with whatever historical
     * data {@code previousComparisonXlsx} carries, and saves the result to {@code outputBaseDir}.
     *
     * @param outputBaseDir       folder that holds the per-region subdirectories and the summary
     * @param previousComparisonXlsx  previous {@code validation_comparison__*.xlsx}, or null
     * @return path of the newly written {@code validation_comparison__*.xlsx}
     */
    public static Path regenerateComparisonXlsx(Path outputBaseDir,
                                                Path previousComparisonXlsx) throws IOException {
        // 1. Find the most recent all-countries summary file.
        Path summaryXlsx;
        try (Stream<Path> ls = Files.list(outputBaseDir)) {
            summaryXlsx = ls
                    .filter(p -> p.getFileName().toString()
                            .startsWith("timestamped_validation_summary__")
                            && p.getFileName().toString().endsWith(".xlsx"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElseThrow(() -> new IOException(
                            "No timestamped_validation_summary__*.xlsx found in " + outputBaseDir));
        }

        // 2. Read TimestampOverview.
        List<ValidationExcelWriter.TimestampOverviewRow> overview =
                ValidationExcelWriter.readTimestampOverview(summaryXlsx);

        if (overview.isEmpty()) {
            throw new IOException("TimestampOverview sheet is empty in " + summaryXlsx);
        }

        // 3. Derive a human-readable month label from the first timestamp encountered.
        String monthLabel = overview.stream()
                .map(r -> monthYearFromInstant(r.timestamp()))
                .filter(l -> l != null)
                .findFirst()
                .orElse("Current");

        // 4. Feed statistics into the comparison writer.
        try (ValidationExcelWriter.ComparisonExcelWriter compWriter =
                     new ValidationExcelWriter.ComparisonExcelWriter(
                             previousComparisonXlsx, "Previous", "Current")) {
            compWriter.setCurrentLabel(monthLabel);

            for (ValidationExcelWriter.TimestampOverviewRow row : overview) {
                String country    = row.country();
                String timestamp  = row.timestamp();
                String reportFile = row.reportFileName();

                if (reportFile.isBlank()) {
                    continue;
                }

                Path reportPath = outputBaseDir
                        .resolve(sanitizePathPart(country))
                        .resolve(reportFile);

                if (!Files.isRegularFile(reportPath)) {
                    logWarn("regenerateComparison: report not found: " + reportPath);
                    continue;
                }

                List<ValidationExcelWriter.StatisticsRow> stats =
                        ValidationExcelWriter.readStatisticsSheet(reportPath);

                for (ValidationExcelWriter.StatisticsRow stat : stats) {
                    compWriter.addTimestampDataset(
                            country, timestamp, stat.label(),
                            stat.warnings(), stat.infos(), stat.violations());
                }
            }

            return compWriter.saveTo(outputBaseDir);
        }
    }

    // ---- ShapeSource: abstraction over local path or remote URL ----

    sealed interface ShapeSource permits LocalShapeSource, RemoteShapeSource {
        String key();
        String displayName();
    }

    record LocalShapeSource(Path path) implements ShapeSource {
        @Override
        public String key() {
            return path.toAbsolutePath().normalize().toString();
        }

        @Override
        public String displayName() {
            return path.getFileName().toString();
        }
    }

    record RemoteShapeSource(URI uri) implements ShapeSource {
        @Override
        public String key() {
            try {
                return new URI(
                        uri.getScheme().toLowerCase(Locale.ROOT),
                        uri.getUserInfo(),
                        uri.getHost() != null ? uri.getHost().toLowerCase(Locale.ROOT) : null,
                        uri.getPort(),
                        uri.getPath(),
                        uri.getQuery(),
                        uri.getFragment()
                ).toString();
            } catch (URISyntaxException e) {
                return uri.toString();
            }
        }

        @Override
        public String displayName() {
            String p = uri.getPath();
            int i = p.lastIndexOf('/');
            return (i >= 0 && i < p.length() - 1) ? p.substring(i + 1) : p;
        }
    }

    record RemoteFetchConfig(
            boolean enabled,
            int connectTimeoutMs,
            int readTimeoutMs,
            int maxRedirects,
            int retries,
            boolean offline,
            Path diskCacheDir
    ) {
        static RemoteFetchConfig defaults() {
            String localAppData = System.getenv("LOCALAPPDATA");
            Path cacheDir = localAppData != null
                    ? Paths.get(localAppData, "CimPal", "shapes-cache")
                    : Paths.get(System.getProperty("user.home"), ".cimpal", "shapes-cache");
            return new RemoteFetchConfig(true, 10_000, 30_000, 5, 2, false, cacheDir);
        }
    }

    record LoadShapesResult(Model model, int importsFound, int loadedFiles, int unresolvableImports) {}

    public record ValidationRunSummary(Path reportPath, int conforming, int violations, int errors) {
        public boolean hasViolations() { return violations > 0 || errors > 0; }
        public int totalRows() { return conforming + violations + errors; }
    }

    public record ValidationTimestampedRunSummary(List<Path> reports, int conforming, int violations, int errors) {
        public boolean hasViolations() { return violations > 0 || errors > 0; }
        public int totalRows() { return conforming + violations + errors; }
    }
}
