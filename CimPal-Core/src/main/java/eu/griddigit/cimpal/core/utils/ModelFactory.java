/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.apache.commons.io.FilenameUtils;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.graph.Graph;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.vocabulary.DCAT;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class ModelFactory {

    public static LinkedList<String> zipfilesnames;


    public static class InheritanceResult {
        public final Model processedModel;
        public final Model inheritanceModel;

        public InheritanceResult(Model processedModel, Model inheritanceModel) {
            this.processedModel = processedModel;
            this.inheritanceModel = inheritanceModel;
        }
    }

    //Loads one or many models
    // Backwards-compatible overload: default considerCimDiff = false
    public static Map<String, Model> modelLoad(
            List<File> files, String xmlBase, Lang rdfSourceFormat, boolean isSHACL, boolean treeID) throws IOException {
        return modelLoad(files, xmlBase, rdfSourceFormat, isSHACL, treeID, false);
    }

    /**
     * Loads one or many models (threaded). Optionally applies CIM diff prefix normalization.
     */
    public static Map<String, Model> modelLoad(
            List<File> files, String xmlBase, Lang rdfSourceFormat, boolean isSHACL, boolean treeID, boolean considerCimDiff) throws IOException {

        // Thread-safe map for individual models
        ConcurrentMap<String, Model> modelMap = new ConcurrentHashMap<>();

        // Thread-safe union models
        Model unionModel = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        Model unionNoHeader = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();

        // Lock objects to safely modify shared union models
        Object unionLock = new Object();
        Object unionNoHeaderLock = new Object();

        files.parallelStream().forEach(file -> {
            String ext = FilenameUtils.getExtension(file.getName()).toLowerCase();
            boolean isZip = ext.equals("zip");
            Lang format;

            List<InputStream> streams;
            try {
                if (isZip) {
                    streams = unzip(file);
                    format = Lang.RDFXML;
                } else {
                    streams = List.of(new ByteArrayInputStream(Files.readAllBytes(file.toPath())));
                    format = getLangFromExtension(ext, rdfSourceFormat);
                }

                streams.parallelStream().forEach(in -> {
                    Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
                    RDFDataMgr.read(model, in, xmlBase, format);

                    String keyword = getProfileKeyword(model);
                    if ("FileHeader.rdf".equalsIgnoreCase(file.getName())) keyword = "FH";
                    String key = buildModelKey(file, keyword, isZip, streams.indexOf(in), treeID);

                    modelMap.put(key, model);

                    synchronized (unionLock) {
                        unionModel.add(model);
                        unionModel.setNsPrefixes(model);
                    }
                    if (!"FH".equals(keyword)) {
                        synchronized (unionNoHeaderLock) {
                            unionNoHeader.add(model);
                            unionNoHeader.setNsPrefixes(model);
                        }
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        // Post-process final models
        Map<String, Model> result = new HashMap<>(modelMap);

        // Optionally apply CIM prefix normalization used by other loader overload
        if (considerCimDiff) {
            Map<String, String> prefixMap = unionModel.getNsPrefixMap();
            String cim2URI = prefixMap.get("cim");
            if (cim2URI != null && !cim2URI.isEmpty()) {
                // remove generic prefix and replace with versioned prefix
                unionModel.removeNsPrefix("cim");
                String cim2Pref = switch (cim2URI) {
                    case "http://iec.ch/TC57/2013/CIM-schema-cim16#",
                         "https://iec.ch/TC57/2013/CIM-schema-cim16#" -> "cim16";
                    case "http://iec.ch/TC57/CIM100#", "https://iec.ch/TC57/CIM100#" -> "cim17";
                    case "http://cim.ucaiug.io/ns#", "https://cim.ucaiug.io/ns#" -> "cim18";
                    default -> null;
                };
                if (cim2Pref != null) {
                    unionModel.setNsPrefix(cim2Pref, cim2URI);
                }
            }

            // Also apply to union without header
            Map<String, String> prefixMapNoHeader = unionNoHeader.getNsPrefixMap();
            String cim2URINoHeader = prefixMapNoHeader.get("cim");
            if (cim2URINoHeader != null && !cim2URINoHeader.isEmpty()) {
                unionNoHeader.removeNsPrefix("cim");
                String cim2Pref2 = switch (cim2URINoHeader) {
                    case "http://iec.ch/TC57/2013/CIM-schema-cim16#",
                         "https://iec.ch/TC57/2013/CIM-schema-cim16#" -> "cim16";
                    case "http://iec.ch/TC57/CIM100#", "https://iec.ch/TC57/CIM100#" -> "cim17";
                    case "http://cim.ucaiug.io/ns#", "https://cim.ucaiug.io/ns#" -> "cim18";
                    default -> null;
                };
                if (cim2Pref2 != null) {
                    unionNoHeader.setNsPrefix(cim2Pref2, cim2URINoHeader);
                }
            }
        }

        if (isSHACL) {
            Model shaclModel = ShapeFactory.createShapeModelWithOwlImport(unionModel);
            result.put("shacl", shaclModel);
        } else {
            result.put("unionModel", unionModel);
            result.put("modelUnionWithoutHeader", unionNoHeader);
        }

        return result;
    }

    /**
     * Loads one or more RDF/XML inputs - plain files or ZIP archives of them - into a single union
     * model, applying {@code dataTypeMap} while parsing.
     * <p>
     * This is {@link #modelLoad(List, String, Lang, boolean, boolean)} minus the per-file keys and
     * the header split, plus the datatype mapping: the mapping has to be applied by the parser
     * (see {@link DataTypeStreamRDF}), so a model already read by {@code RDFDataMgr} cannot be
     * retro-typed. Callers that need typed literals - validation, comparison - must load this way.
     */
    public static Model modelLoadUnionWithDatatypeMap(
            List<File> files, Map<String, RDFDatatype> dataTypeMap, String xmlBase) throws IOException {

        Model union = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();

        for (File file : files) {
            String ext = FilenameUtils.getExtension(file.getName()).toLowerCase();
            List<InputStream> streams = "zip".equals(ext)
                    ? unzip(file)
                    : List.of(new ByteArrayInputStream(Files.readAllBytes(file.toPath())));

            for (InputStream in : streams) {
                Model model = modelLoadXMLmapping(in, dataTypeMap, xmlBase);
                union.add(model);
                union.setNsPrefixes(model);
            }
        }

        return union;
    }

    //Loads model data with datatype mapping
    public static Model modelLoadXMLmapping(InputStream inputStream, Map<String, RDFDatatype> dataTypeMap, String xmlBase) {
        // Create a Graph to hold the parsed data
        Graph graph = GraphFactory.createDefaultGraph();

        // Create a StreamRDF for handling parsed triples and datatypes
        DataTypeStreamRDF sink = new DataTypeStreamRDF(graph, dataTypeMap);

        // Use RDFParser to parse the input stream
        RDFParser.create().source(inputStream).lang(Lang.RDFXML).base(xmlBase).parse(sink);

        // Obtain the parsed graph and create a Model from it
        graph = sink.getGraph();
        Model model = org.apache.jena.rdf.model.ModelFactory.createModelForGraph(graph);

        // Set namespace prefixes based on the sink's prefix mapping
        Map<String, String> prefixMapping = sink.getPrefixMapping();
        model.setNsPrefixes(prefixMapping);

        return model;
    }

    public static Map<String, Model> modelLoadPerFiles(List<File> files, String xmlBase, Lang defaultLang) throws IOException {
        return modelLoadPerFiles(files, xmlBase, defaultLang, ZipBudget::new);
    }

    /** {@link #modelLoadPerFiles(List, String, Lang)} with one budget per archive from {@code budgets}. */
    static Map<String, Model> modelLoadPerFiles(List<File> files, String xmlBase, Lang defaultLang,
                                                java.util.function.Supplier<ZipBudget> budgets) throws IOException {
        ConcurrentMap<String, Model> result = new ConcurrentHashMap<>();

        files.parallelStream().forEach(file -> {
            try {
                String ext = FilenameUtils.getExtension(file.getName()).toLowerCase(Locale.ROOT);
                boolean isZip = "zip".equals(ext);

                if (isZip) {
                    try (ZipFile zipFile = new ZipFile(file)) {
                        // The same limits as unzip(): sparql, compare-instances and manifest
                        // load archives here, and before SEC-5 nothing bounded them.
                        ZipBudget budget = budgets.get();
                        Path parentDir = file.getParentFile() != null
                                ? file.getParentFile().toPath().toAbsolutePath()
                                : Paths.get("").toAbsolutePath();

                        // Collect all valid entries first
                        List<ZipEntry> validEntries = new ArrayList<>();
                        Enumeration<? extends ZipEntry> entries = zipFile.entries();
                        while (entries.hasMoreElements()) {
                            ZipEntry entry = entries.nextElement();
                            if (!entry.isDirectory()) {
                                budget.enterEntry();
                                validEntries.add(entry);
                            }
                        }

                        // Process entries in parallel
                        validEntries.parallelStream().forEach(entry -> {
                            try {
                                String entryName = entry.getName();
                                Path destPath = parentDir.resolve(entryName).normalize();
                                if (!destPath.startsWith(parentDir)) {
                                    throw new IOException("Invalid zip entry path (possible zip-slip): " + entryName);
                                }

                                String entryExt = FilenameUtils.getExtension(entryName).toLowerCase(Locale.ROOT);
                                Lang lang = getLangFromExtension(entryExt, defaultLang);

                                try (InputStream in = zipFile.getInputStream(entry)) {
                                    Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
                                    readIntoModel(model, budget.limit(in), xmlBase, lang);
                                    result.put(entryName, model);
                                }
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
                    }
                } else {
                    Lang lang = getLangFromExtension(ext, defaultLang);
                    try (InputStream in = new FileInputStream(file)) {
                        Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
                        readIntoModel(model, in, xmlBase, lang);
                        result.put(file.getName(), model);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        return new HashMap<>(result);
    }

    /**
     * Loads a list of RDF files into a single combined model for SPARQL execution.
     * Supports both plain XML/RDF files and ZIP archives via {@link #modelLoadPerFiles(List, String, Lang)}.
     */
    public static Model loadCombinedModelForSparql(List<File> files, String xmlBase) throws IOException {
        Map<String, Model> loadedModels = modelLoadPerFiles(files, xmlBase, Lang.RDFXML);
        Model combinedModel = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        Map<String, String> prefixMap = combinedModel.getNsPrefixMap();

        for (Model model : loadedModels.values()) {
            prefixMap.putAll(model.getNsPrefixMap());
            combinedModel.add(model);
        }

        combinedModel.setNsPrefixes(prefixMap);
        return combinedModel;
    }

    private static Lang getLangFromExtension(String ext, Lang fallback) {
        Lang detected = RDFLanguages.filenameToLang("input." + ext);
        return detected == null ? fallback : detected;
    }

    /** Loads both graph and dataset syntaxes into CimPal's combined graph model. */
    private static void readIntoModel(Model target, InputStream in, String xmlBase, Lang lang) {
        if (RDFLanguages.isQuads(lang)) {
            Dataset dataset = DatasetFactory.createTxnMem();
            RDFDataMgr.read(dataset, in, xmlBase, lang);
            target.add(dataset.getDefaultModel());
            dataset.listNames().forEachRemaining(name -> target.add(dataset.getNamedModel(name)));
        } else {
            RDFDataMgr.read(target, in, xmlBase, lang);
        }
    }

    private static String buildModelKey(File file, String keyword, boolean isZip, int index, boolean treeID) {
        if (keyword == null || keyword.isEmpty()) {
            return FilenameUtils.getName(file.getName());
        }

        if (treeID) {
            if (isZip && !zipfilesnames.isEmpty()) {
                String zipName = zipfilesnames.get(Math.min(index,
                        zipfilesnames.size() - 1));
                return zipName + "|" + keyword;
            } else {
                return file.getName() + "|" + keyword;
            }
        }
        return keyword;
    }

    /** Maximum cumulative uncompressed size accepted from a single archive traversal. */
    private static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 2L * 1024 * 1024 * 1024; // 2 GiB
    /** Maximum uncompressed size of one archive entry. */
    private static final long MAX_ZIP_ENTRY_BYTES = 1024L * 1024 * 1024; // 1 GiB
    /** Maximum number of entries accepted from a single archive traversal. */
    private static final int MAX_ZIP_ENTRIES = 10_000;
    /** Maximum depth of nested archives followed during expansion. */
    private static final int MAX_ZIP_NESTING_DEPTH = 3;

    /**
     * Expansion budget shared across one archive traversal, nested archives included.
     * <p>
     * Archive entries arrive from third parties (CGMES datasets), and an archive that declares
     * a small compressed size can expand enormously. Bytes are therefore counted while each
     * entry is read, so an oversized entry stops at the limit instead of being read into memory
     * first (SEC-5; before, the total was checked only after {@code readAllBytes()}). Thread-safe:
     * {@link #modelLoadPerFiles} reads entries in parallel.
     */
    static final class ZipBudget {
        private final int maxEntries;
        private final long maxTotalBytes;
        private final long maxEntryBytes;
        private final java.util.concurrent.atomic.AtomicInteger entries = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicLong bytes = new java.util.concurrent.atomic.AtomicLong();

        ZipBudget() {
            this(MAX_ZIP_ENTRIES, MAX_TOTAL_UNCOMPRESSED_BYTES, MAX_ZIP_ENTRY_BYTES);
        }

        ZipBudget(int maxEntries, long maxTotalBytes, long maxEntryBytes) {
            this.maxEntries = maxEntries;
            this.maxTotalBytes = maxTotalBytes;
            this.maxEntryBytes = maxEntryBytes;
        }

        /** Counts one more entry. */
        void enterEntry() throws IOException {
            if (entries.incrementAndGet() > maxEntries) {
                throw new IOException("Archive exceeds the entry limit (" + maxEntries + ")");
            }
        }

        /**
         * Wraps one entry's stream so reading past the per-entry or the total limit fails. The
         * wrapper doesn't close {@code in}: for a {@link ZipInputStream} that would end the
         * whole archive.
         */
        InputStream limit(InputStream in) {
            return new FilterInputStream(in) {
                private long entryBytes;

                @Override
                public int read() throws IOException {
                    int b = super.read();
                    if (b >= 0) count(1);
                    return b;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int n = super.read(buffer, offset, length);
                    if (n > 0) count(n);
                    return n;
                }

                @Override
                public void close() {
                    // see above: the caller owns the underlying stream
                }

                private void count(long n) throws IOException {
                    entryBytes += n;
                    if (entryBytes > maxEntryBytes) {
                        throw new IOException("Archive entry exceeds the size limit (" + maxEntryBytes + " bytes)");
                    }
                    if (bytes.addAndGet(n) > maxTotalBytes) {
                        throw new IOException("Archive exceeds the total uncompressed size limit ("
                                + maxTotalBytes + " bytes)");
                    }
                }
            };
        }

        /** Counts an entry and reads it within the limits. */
        byte[] readEntry(InputStream in) throws IOException {
            enterEntry();
            return limit(in).readAllBytes();
        }
    }

    public static List<InputStream> unzip(File selectedFile) {
        List<InputStream> inputstreamlist = new LinkedList<>();
        zipfilesnames = new LinkedList<>();
        ZipBudget budget = new ZipBudget();

        try (ZipFile zipFile = new ZipFile(selectedFile)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }

                try (InputStream inputStream = zipFile.getInputStream(entry)) {
                    byte[] content = budget.readEntry(inputStream);

                    String entryName = entry.getName();
                    String ext = FilenameUtils.getExtension(entryName).toLowerCase(Locale.ROOT);

                    // Check if the entry is itself a ZIP file
                    if (ext.equals("zip")) {
                        // Recursively extract nested ZIP, under the shared budget
                        inputstreamlist.addAll(
                                unzip(new ByteArrayInputStream(content), budget, 1));
                    } else {
                        // Add non-ZIP file to the list
                        inputstreamlist.add(new ByteArrayInputStream(content));
                    }
                    zipfilesnames.add(entryName);
                }
            }
        } catch (IOException e) {
            // Previously a per-entry IOException was swallowed with printStackTrace(), which
            // yielded a silently incomplete model. Expansion failure must be visible.
            throw new RuntimeException("Error unzipping file " + selectedFile, e);
        }

        return inputstreamlist;
    }

    public static List<InputStream> unzip(InputStream zipStream) {
        return unzip(zipStream, new ZipBudget(), 0);
    }

    /** {@link #unzip(InputStream)} with a given budget (tests use small limits). */
    static List<InputStream> unzip(InputStream zipStream, ZipBudget budget) {
        return unzip(zipStream, budget, 0);
    }

    private static List<InputStream> unzip(InputStream zipStream, ZipBudget budget, int depth) {
        if (depth > MAX_ZIP_NESTING_DEPTH) {
            throw new RuntimeException(
                    "Archive nesting exceeds the depth limit (" + MAX_ZIP_NESTING_DEPTH + ")");
        }

        List<InputStream> inputstreamlist = new LinkedList<>();

        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }

                byte[] content = budget.readEntry(zis);

                String entryName = entry.getName();
                String ext = FilenameUtils.getExtension(entryName).toLowerCase(Locale.ROOT);

                if (ext.equals("zip")) {
                    inputstreamlist.addAll(
                            unzip(new ByteArrayInputStream(content), budget, depth + 1));
                } else {
                    inputstreamlist.add(new ByteArrayInputStream(content));
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            throw new RuntimeException("Error unzipping input stream", e);
        }

        return inputstreamlist;
    }


    /**
     * True when {@code destPathStr} resolves strictly inside {@code targetDir}. Retained for
     * use by any future code path that extracts archive entries to disk; the expansion
     * methods above hold entries in memory and never write, so they do not call it.
     * <p>
     * Compares normalised absolute {@link Path} objects. A string prefix test against an
     * unnormalised, possibly relative directory is not a containment check and can be
     * satisfied by a path that escapes the directory.
     */
    static boolean isValidDestPath(String targetDir, String destPathStr) {
        Path base = Paths.get(targetDir).toAbsolutePath().normalize();
        Path dest = Paths.get(destPathStr).toAbsolutePath().normalize();
        return dest.startsWith(base) && !dest.equals(base);
    }

    //get the keyword for the profile
    public static String getProfileKeyword(Model model) {

        String keyword = "";


        if (model.listObjectsOfProperty(DCAT.keyword).hasNext()) {
            keyword = model.listObjectsOfProperty(DCAT.keyword).next().toString();
        }
        if (model.listObjectsOfProperty(ResourceFactory.createProperty(DCAT.NS, "Model.keyword")).hasNext()) {
            keyword = model.listObjectsOfProperty(ResourceFactory.createProperty(DCAT.NS, "Model.keyword")).next().toString();
        }

        if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#EquipmentVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#EquipmentVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
        } else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#EquipmentBoundaryVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#EquipmentBoundaryVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
            keyword = "EQBD";
        } else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#TopologyBoundaryVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#TopologyBoundaryVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
            //TODO maybe fix RDFS. Here a quick override
            keyword = "TPBD";
        } else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#TopologyVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#TopologyVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
        } else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#SteadyStateHypothesisVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#SteadyStateHypothesisVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
        } else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#StateVariablesVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#StateVariablesVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
        } else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#EDynamicsVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#EDynamicsVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
        } else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#GeographicalLocationVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#GeographicalLocationVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
        } else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#DiagramLayoutVersion.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#DiagramLayoutVersion.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
        } else if (model.contains(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#Ontology.shortName"),
                ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed"))) {
            keyword = model.getRequiredProperty(ResourceFactory.createResource("http://entsoe.eu/CIM/SchemaExtension/3/1#Ontology.shortName"),
                    ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().toString();
        } else if (model.listObjectsOfProperty(ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.profile")).hasNext()) {
            List<RDFNode> profileString = model.listObjectsOfProperty(ResourceFactory.createProperty("http://iec.ch/TC57/61970-552/ModelDescription/1#Model.profile")).toList();
            for (RDFNode node : profileString) {
                String nodeString = node.toString();
                if (nodeString.equals("http://entsoe.eu/CIM/EquipmentCore/3/1") || nodeString.equals("http://entsoe.eu/CIM/EquipmentOperation/3/1") || nodeString.equals("http://entsoe.eu/CIM/EquipmentShortCircuit/3/1")) {
                    keyword = "EQ";
                } else if (nodeString.equals("http://entsoe.eu/CIM/SteadyStateHypothesis/1/1")) {
                    keyword = "SSH";
                } else if (nodeString.equals("http://entsoe.eu/CIM/Topology/4/1")) {
                    keyword = "TP";
                } else if (nodeString.equals("http://entsoe.eu/CIM/StateVariables/4/1")) {
                    keyword = "SV";
                } else if (nodeString.equals("http://entsoe.eu/CIM/EquipmentBoundary/3/1") || nodeString.equals("http://entsoe.eu/CIM/EquipmentBoundaryOperation/3/1")) {
                    keyword = "EQBD";
                } else if (nodeString.equals("http://entsoe.eu/CIM/TopologyBoundary/3/1")) {
                    keyword = "TPBD";
                } else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/CoreEquipment-EU/3.0")) {
                    keyword = "EQ";
                } else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/Operation-EU/3.0")) {
                    keyword = "OP";
                } else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/ShortCircuit-EU/3.0")) {
                    keyword = "SC";
                } else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/SteadyStateHypothesis-EU/3.0")) {
                    keyword = "SSH";
                } else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/Topology-EU/3.0")) {
                    keyword = "TP";
                } else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/StateVariables-EU/3.0")) {
                    keyword = "SV";
                } else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/EquipmentBoundary-EU/3.0")) {
                    keyword = "EQBD";
                } else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/DiagramLayout-EU/3.0")) {
                    keyword = "DL";
                } else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/GeographicalLocation-EU/3.0")) {
                    keyword = "GL";
                } else if (nodeString.equals("http://iec.ch/TC57/ns/CIM/Dynamics-EU/1.0")) {
                    keyword = "DY";
                }
            }
        }

        if (keyword.isEmpty()) {
            try {
                List<Resource> listRes = model.listSubjectsWithProperty(RDF.type).toList();
                for (Resource res : listRes) {
                    if (res.getLocalName().contains("Ontology.keyword")) {
                        keyword = model.getRequiredProperty(res, ResourceFactory.createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#isFixed")).getObject().asLiteral().getString();
                    }
                }
            } catch (NullPointerException e) {
                keyword = "";
            }
        }

        return keyword;
    }


    public static InheritanceResult generateInheritanceModels(
            Model model, boolean inheritanceList, boolean inheritanceListConcrete) {

        Model processed = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        processed.setNsPrefixes(model.getNsPrefixMap());

        Model inheritance = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        inheritance.setNsPrefixes(model.getNsPrefixMap());
        inheritance.setNsPrefix("owl", OWL2.NS);

        if (!inheritanceList) {
            model.listStatements().forEachRemaining(stmt -> {
                if (isInheritanceStmt(stmt)) processed.add(stmt);
            });
        } else {
            model.listStatements().forEachRemaining(stmt -> {
                if (isInheritanceStmt(stmt)) {
                    processed.add(stmt);
                    if (stmt.getPredicate().equals(RDF.type)) {
                        inheritanceStructure(stmt.getSubject(),
                                stmt.getSubject(),
                                inheritance,
                                model,
                                inheritanceListConcrete);
                    }
                }
            });
        }

        return new InheritanceResult(processed, inheritance);
    }

    private static boolean isInheritanceStmt(Statement stmt) {
        Property p = stmt.getPredicate();
        return p.equals(RDF.type)
                || p.equals(RDFS.subClassOf)
                || p.equals(RDFS.subPropertyOf)
                || p.equals(RDFS.domain)
                || p.equals(RDFS.range);
    }

    private static Model inheritanceStructure(
            Resource root, Resource current,
            Model inheritance, Model fullModel,
            boolean onlyConcrete) {

        ResIterator subs = fullModel.listSubjectsWithProperty(RDFS.subClassOf, current);
        while (subs.hasNext()) {
            Resource sub = subs.next();
            if (!onlyConcrete || isConcrete(sub, fullModel)) {
                inheritance.add(root, OWL2.members, sub);
                inheritance.add(root, RDF.type, OWL2.Class);
            }
            inheritanceStructure(root, sub, inheritance, fullModel, onlyConcrete);
        }
        return inheritance;
    }

    private static boolean isConcrete(Resource cls, Model model) {
        Property stereo = ResourceFactory
                .createProperty("http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#", "stereotype");
        NodeIterator it = model.listObjectsOfProperty(cls, stereo);
        while (it.hasNext()) {
            RDFNode n = it.next();
            if (n.isResource()
                    && n.asResource().getURI()
                    .equals("http://iec.ch/TC57/NonStandard/UML#concrete")) {
                return true;
            }
        }
        return false;
    }


    public static Model LoadSHACLSHACL() {
        Model shaclModel = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        InputStream inputStream = ModelFactory.class.getResourceAsStream("/shacl-shacl/shacl-shaclFixed.ttl");

        if (inputStream != null) {
            RDFDataMgr.read(shaclModel, inputStream, "", Lang.TURTLE);
        } else {
            try {
                throw new FileNotFoundException("File not found for shacl validation.");
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
        return shaclModel;
    }

}
