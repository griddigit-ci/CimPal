package eu.griddigit.cimpal.core.utils;

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

public class ValidationTools {

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

        List<MappingRow> rows = readMappingCsv(mappingCsvPath);
        Files.createDirectories(outputBaseDir);

        int threads = (threadCount > 0)
                ? threadCount
                : Math.min(Math.max(1, Runtime.getRuntime().availableProcessors() - 1), 6);

        System.out.println("[INFO] validateByMapping rows=" + rows.size() + " threads=" + threads);
        System.out.println("[INFO] mappingCsvPath=" + mappingCsvPath.toAbsolutePath());
        System.out.println("[INFO] modelsBaseDir=" + modelsBaseDir.toAbsolutePath());
        System.out.println("[INFO] constraintsRoot=" + constraintsRoot.toAbsolutePath());
        System.out.println("[INFO] outputBaseDir=" + outputBaseDir.toAbsolutePath());

        // Cache shapes models by ttlPath
        Map<Path, Model> shapesCache = new ConcurrentHashMap<>();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<ValidationTaskResult>> tasks = new ArrayList<>();

        int rowIdx = 0;
        for (MappingRow row : rows) {
            rowIdx++;
            final int idx = rowIdx;

            if (row.xmlInputsRaw == null || row.xmlInputsRaw.trim().isEmpty()
                    || row.ttl == null || row.ttl.trim().isEmpty()) {
                continue;
            }

            tasks.add(() -> validateOneRow(idx, row, modelsBaseDir, constraintsRoot, shapesCache, dataTypeMap, xmlBase));
        }

        List<Future<ValidationTaskResult>> futures;
        try {
            futures = pool.invokeAll(tasks);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Validation interrupted", ie);
        } finally {
            pool.shutdown();
        }

        int ok = 0, fail = 0, err = 0;
        List<String> nonConforms = new ArrayList<>();
        List<String> errorModels = new ArrayList<>();
        Path reportPath;
        try (ValidationExcelWriter writer = new ValidationExcelWriter()) {
            for (Future<ValidationTaskResult> f : futures) {
                ValidationTaskResult r;
                try {
                    r = f.get();
                } catch (Exception ex) {
                    err++;
                    writer.appendError(
                            ValidationExcelWriter.CaseFolder.UNKNOWN,
                            "UNKNOWN_ROW",
                            "UNKNOWN_TTL",
                            new Exception(ex)
                    );
                    continue;
                }

                ValidationExcelWriter.CaseFolder sheet = r.caseFolder;


                if (r.error != null) {
                    err++;
                    writer.appendError(sheet, r.datasetName, r.ttlName, r.error);
                    errorModels.add(r.datasetName);

                } else {
                    if (r.conforms) ok++; else { fail++; nonConforms.add(r.datasetName); }

                    writer.appendExcelBlock(sheet, r.datasetName, r.ttlName, r.results);
                }
            }

            reportPath = writer.saveTo(outputBaseDir);
        }

        System.out.println("[INFO] Done. ok=" + ok + " fail=" + fail + " error=" + err);
        System.out.println(("List of failed models: "));
        nonConforms.forEach(System.out::println);
        System.out.println(("List of error models: "));
        errorModels.forEach(System.out::println);

        System.out.println("[INFO] Excel report: " + reportPath.toAbsolutePath());
        return reportPath;
    }

    // ---------------- core validation per row ----------------

    private static class ValidationTaskResult {
        final int rowIdx;
        final ValidationExcelWriter.CaseFolder caseFolder;
        final String datasetName;
        final String ttlName;
        final List<eu.griddigit.cimpal.core.models.SHACLValidationResult> results;
        final boolean conforms;
        final Exception error;

        ValidationTaskResult(int rowIdx,
                             ValidationExcelWriter.CaseFolder caseFolder,
                             String datasetName,
                             String ttlName,
                             List<eu.griddigit.cimpal.core.models.SHACLValidationResult> results,
                             boolean conforms,
                             Exception error) {
            this.rowIdx = rowIdx;
            this.caseFolder = caseFolder;
            this.datasetName = datasetName;
            this.ttlName = ttlName;
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

        String ttlName = row.ttl.trim();
        ValidationExcelWriter.CaseFolder caseFolder = categorizeForReport(row);
        String datasetName = makeDatasetName(rowIdx, row);

        try {
            LinkedHashSet<Path> xmlFiles = resolveFilesForRow(row, modelsBaseDir);
            if (xmlFiles.isEmpty()) {
                return new ValidationTaskResult(rowIdx, caseFolder, datasetName, ttlName, null, false,
                        new IOException("No XML matched mapping tokens"));
            }

            Path ttlPath = resolveTtlPath(constraintsRoot, ttlName);
            if (!Files.exists(ttlPath)) {
                return new ValidationTaskResult(rowIdx, caseFolder, datasetName, ttlName, null, false,
                        new FileNotFoundException("TTL not found: " + ttlPath));
            }

            // Shapes cached
            Model shapesModel = loadShapesWithLocalImports(ttlPath, constraintsRoot, shapesCache);

            if (shapesModel.size() < 200) {
                System.err.println("[WARN] Shapes model seems too small (" + shapesModel.size() + " triples). Imports may not be loaded for: " + ttlPath);
            }

            // Data model from XML files (base URI fix included)
            Model dataModel = loadRdfXmlFromFilesWithDatatypeMap(xmlFiles, dataTypeMap, xmlBase);

            System.out.println("[DBG][row " + rowIdx + "] xmlFiles=" + xmlFiles.size());
            System.out.println("[DBG][row " + rowIdx + "] data triples=" + dataModel.size());
            System.out.println("[DBG][row " + rowIdx + "] shapes triples=" + shapesModel.size());

            xmlFiles.stream().limit(5).forEach(p -> System.out.println("  [DBG] " + p));

            ValidationReport report = ShaclValidator.get().validate(shapesModel.getGraph(), dataModel.getGraph());

            // Extract enriched results here (you have shapesModel here!)
            List<eu.griddigit.cimpal.core.models.SHACLValidationResult> results =
                    ShaclTools.extractSHACLValidationResults(report, shapesModel);

            return new ValidationTaskResult(rowIdx, caseFolder, datasetName, ttlName, results, report.conforms(), null);

        } catch (Exception ex) {
            return new ValidationTaskResult(rowIdx, caseFolder, datasetName, ttlName, null, false, ex);        }
    }

    // ---------------- mapping row → file list ----------------

    private static LinkedHashSet<Path> resolveFilesForRow(MappingRow row, Path modelsBaseDir) throws IOException {
        List<String> tokens = parseXmlInputs(row.xmlInputsRaw);
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        for (String token : tokens) {
            files.addAll(expandToken(modelsBaseDir, token));
        }
        return files;
    }


    private static Model loadRdfXmlFromFilesWithDatatypeMap(Collection<Path> xmlFiles,
                                                            Map<String, RDFDatatype> dataTypeMap,
                                                            String xmlBase) throws Exception {

        Model merged = ModelFactory.createDefaultModel();

        for (Path p : xmlFiles) {
            if (!Files.isRegularFile(p)) continue;
            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".xml")) continue;

            try (InputStream in = Files.newInputStream(p)) {
                // Reuse your existing function:
                Model single = eu.griddigit.cimpal.core.utils.ModelFactory.modelLoadXMLmapping(in, dataTypeMap, xmlBase);

                // Merge into one dataset model (EQ+SSH+TP+SV+Boundary etc.)
                merged.add(single);

                // Keep prefixes (optional)
                merged.setNsPrefixes(single.getNsPrefixMap());
            }
        }

        return merged;
    }
    /**
     * Load all RDF/XML files into one model. Base URI is set so rdf:about="#..." is resolvable.
     */
    private static Model loadRdfXmlFromFiles(Collection<Path> xmlFiles) throws IOException {
        Model m = ModelFactory.createDefaultModel();

        for (Path p : xmlFiles) {
            if (!Files.isRegularFile(p)) continue;
            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".xml")) continue;

            String base = p.toUri().toString() + "#";
            try (InputStream in = Files.newInputStream(p)) {
                RDFParser.source(in)
                        .lang(Lang.RDFXML)
                        .base(base)
                        .parse(m);
            }
        }
        return m;
    }

    // ---------------- categorization + dataset naming ----------------

    private static ValidationExcelWriter.CaseFolder categorizeForReport(MappingRow row) {
        String ttl = row.ttl == null ? "" : row.ttl;

        if (ttl.startsWith("NC_")) return ValidationExcelWriter.CaseFolder.NC_SINGLE;

        String xml = row.xmlInputsRaw == null ? "" : row.xmlInputsRaw.replace("\\", "/");

        if (xml.contains("Instance/Grid/CGM")) return ValidationExcelWriter.CaseFolder.CGMES_CGM;

        boolean hasBoundary = xml.contains("CommonData_and_Boundary_merged.xml");
        boolean hasEQ = xml.contains("EQ");
        boolean hasSSH = xml.contains("SSH");
        boolean hasTP = xml.contains("TP");
        boolean hasSV = xml.contains("SV");

        if (ttl.startsWith("CGMES_") && hasBoundary && hasEQ && hasSSH && hasTP && hasSV) {
            return ValidationExcelWriter.CaseFolder.CGMES_IGM_COMPLETE;
        }
        if (ttl.startsWith("CGMES_")) return ValidationExcelWriter.CaseFolder.CGMES_SINGLE_PROFILE;

        return ValidationExcelWriter.CaseFolder.UNKNOWN;
    }

    private static String makeDatasetName(int rowIdx, MappingRow row) {
        String s = row.xmlInputsRaw == null ? "" : row.xmlInputsRaw.replace("\\", "/");

        int igm = s.indexOf("IGM_");
        if (igm >= 0) {
            String sub = s.substring(igm);
            String name = sub.split("[/;\\s]")[0];
            return "row" + rowIdx + "__" + name;
        }

        int nc = s.indexOf("Instance/NetworkCode/");
        if (nc >= 0) {
            String sub = s.substring(nc + "Instance/NetworkCode/".length());
            String name = sub.split("[/;\\s]")[0];
            return "row" + rowIdx + "__NC_" + name;
        }

        if (s.contains("Instance/Grid/CGM")) return "row" + rowIdx + "__CGM";
        return "row" + rowIdx;
    }

    // ---------------- TTL resolution (ApplicationLibraryValidationConfigurations as root) ----------------

    private static Path resolveTtlPath(Path constraintsRoot, String ttlName) {
        String ttl = (ttlName == null) ? "" : ttlName.trim();

        // If mapping already contains subfolders, respect it
        if (ttl.contains("/") || ttl.contains("\\")) {
            return constraintsRoot.resolve(ttl).normalize();
        }

        // Auto-route based on prefix
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
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#")) continue;

                if (!headerSkipped) {
                    String h = line.replace("\uFEFF", "").toLowerCase(Locale.ROOT);
                    if (h.startsWith("xml_inputs")) {
                        headerSkipped = true;
                        continue;
                    }
                    headerSkipped = true;
                }

                List<String> cols = parseCsvLine(line);
                if (cols.size() < 2) continue;

                String xml = cols.get(0).replace("\uFEFF", "").trim();
                String ttl = cols.get(1).replace("\uFEFF", "").trim();
                String notes = cols.size() >= 3 ? cols.get(2).replace("\uFEFF", "").trim() : "";

                if (xml.startsWith("#")) continue;
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

        // No-glob: only accept regular XML files
        if (!hasGlob) {
            Path p = modelsBaseDir.resolve(norm).normalize();
            if (Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml")) {
                return List.of(p);
            }
            return List.of();
        }

        int lastSlash = norm.lastIndexOf('/');
        String parent = (lastSlash >= 0) ? norm.substring(0, lastSlash) : "";
        String pattern = (lastSlash >= 0) ? norm.substring(lastSlash + 1) : norm;

        Path parentDir = modelsBaseDir.resolve(parent).normalize();
        if (!Files.isDirectory(parentDir)) return List.of();

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
        if (cached != null) return cached;

        // Build shapes model by following owl:imports
        Model shapes = ModelFactory.createDefaultModel();
        Set<Path> visited = new HashSet<>();
        Deque<Path> stack = new ArrayDeque<>();
        stack.push(key);

        while (!stack.isEmpty()) {
            Path p = stack.pop().toAbsolutePath().normalize();
            if (!visited.add(p)) continue;

            if (!Files.exists(p)) {
                throw new FileNotFoundException("Imported TTL not found: " + p);
            }

            // Read this TTL into a small temp model so we can discover its imports
            Model tmp = ModelFactory.createDefaultModel();
            RDFDataMgr.read(tmp, p.toUri().toString());
            shapes.add(tmp);

            // Find owl:imports
            StmtIterator it = tmp.listStatements(null, OWL.imports, (RDFNode) null);
            while (it.hasNext()) {
                Statement st = it.nextStatement();
                RDFNode obj = st.getObject();
                if (!obj.isURIResource()) continue;

                String uri = obj.asResource().getURI();

                // Try resolve import to local path:
                // - if import is a file: URI, use it
                // - if import is relative or "CGMES_v3-0-0/xxx.ttl" style, resolve under constraintsRoot
                Path importedPath = resolveImportToLocalPath(uri, p, constraintsRoot);

                if (importedPath != null) {
                    stack.push(importedPath);
                }
                else {
                    System.out.println("[DBG] Skipping non-local import: " + uri);
                }
            }
        }

        cache.put(key, shapes);
        return shapes;
    }

    private static Path resolveImportToLocalPath(String importUri, Path currentTtl, Path constraintsRoot) {
        String u = importUri == null ? "" : importUri.trim();

        // Ignore web/urn imports (vocabularies, SHACL ontology, etc.)
        if (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("urn:")) {
            return null;
        }

        // file:///... imports
        try {
            if (u.startsWith("file:")) {
                return Paths.get(java.net.URI.create(u));
            }
        } catch (Exception ignore) {}

        // Resolve relative imports against current TTL folder first
        Path currentDir = currentTtl.getParent();
        Path candidate1 = currentDir.resolve(u).normalize();
        if (Files.exists(candidate1)) return candidate1;

        // Resolve under constraintsRoot
        Path candidate2 = constraintsRoot.resolve(u).normalize();
        if (Files.exists(candidate2)) return candidate2;

        // Last attempt: filename under constraintsRoot
        String fileName = u;
        int slash = fileName.lastIndexOf('/');
        if (slash >= 0) fileName = fileName.substring(slash + 1);
        Path candidate3 = constraintsRoot.resolve(fileName).normalize();
        return Files.exists(candidate3) ? candidate3 : null;
    }

}