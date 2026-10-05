/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.SHACLValidationOptions;
import eu.griddigit.cimpal.core.models.SHACLValidationReport;
import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.riot.system.StreamRDFLib;
import org.apache.jena.riot.system.StreamRDFWrapper;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.parser.Shape;
import org.apache.jena.shacl.vocabulary.SHACL;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.vocabulary.RDF;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates one dataset against a set of SHACL shapes:
 * <pre>{@code
 * SHACLValidationReport report = new SHACLValidator(
 *         SHACLValidationOptionsPresets.cgmes30()
 *                 .dataFiles(eq, ssh, tp, sv, boundary)
 *                 .shapeFiles(constraints)
 *                 .build())
 *         .validate();
 * }</pre>
 * Inputs are loaded the way mapping validation loads them: {@code owl:imports} of the shape files
 * are followed, locally and from the allow-listed GitHub hosts, and the datatype map is applied
 * while the data files are parsed, so results match the SHACL Validation tab's. Remote imports are
 * cached for the life of the process; call {@link ValidationTools#clearRemoteCaches()} to pick up
 * upstream edits.
 * <p>
 * All shape files, and the {@code .ttl} and {@code .rdf} entries of ZIP archives among them, form
 * one shapes graph; all data files, and the {@code .xml} entries of ZIP archives among them, form
 * one data graph. The report breaks the results down by the constraint file that declares their
 * source shape.
 */
public class SHACLValidator {

    private final SHACLValidationOptions options;

    public SHACLValidator(SHACLValidationOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    public SHACLValidationOptions getOptions() {
        return options;
    }

    /**
     * Loads the shapes and the data and validates them.
     *
     * @throws IOException if an input cannot be read, a remote import cannot be fetched, or the
     *                     validation engine fails
     */
    public SHACLValidationReport validate() throws IOException {
        long started = System.currentTimeMillis();
        ValidationTools.logValidationDebug("START SHACLValidator engine=" + options.getEngine()
                + " dataFiles=" + options.getDataFiles().size()
                + " shapeFiles=" + options.getShapeFiles().size());
        List<String> warnings = new ArrayList<>();

        Map<String, RDFDatatype> dataTypeMap = CompleteDatatypeMapLoader.resolve(
                options.getDatatypeMapPreset(), options.getDatatypeMapFile(), options.getDatatypeMap());

        LoadedShapes loadedShapes = loadShapes(warnings);
        Model shapesModel = loadedShapes.model();
        Shapes shapes = Shapes.parse(shapesModel.getGraph());
        if (shapes.getTargetShapes().isEmpty()) {
            warnings.add("The shapes declare no targets, so no data was validated ("
                    + shapesModel.size() + " shape triples loaded).");
        }

        LoadedData loadedData = loadData(dataTypeMap);
        Model dataModel = loadedData.model();
        if (dataTypeMap.isEmpty() && !options.getDataFiles().isEmpty()) {
            warnings.add("No datatype map was applied: untyped literals were validated as xsd:string, "
                    + "so datatype and value-range constraints on them may report incorrectly.");
        }

        ValidationTools.LimitedValidationOutcome outcome;
        try {
            outcome = ValidationTools.validateWithSelectedEngine(options.getEngine(), shapes,
                    dataModel.getGraph(), shapesModel, options.getMaxResultsPerConstraint(), -1,
                    workers(), true);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("SHACL validation was interrupted");
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        }

        // Prefixes make the report's Turtle readable; the engine's own bindings take precedence.
        Model reportModel = outcome.reportModel();
        reportModel.withDefaultMappings(shapesModel);
        reportModel.withDefaultMappings(dataModel);

        ValidationTools.logValidationDebug("DONE SHACLValidator conforms=" + outcome.conforms()
                + " results=" + outcome.results().size() + " partial=" + outcome.partial()
                + " elapsedMs=" + (System.currentTimeMillis() - started));
        return new SHACLValidationReport(outcome.conforms(), outcome.partial(), outcome.results(),
                reportModel, warnings, datasetName(), loadedData.sources(), shapeSources(),
                options.getMaxResultsPerConstraint(),
                loadedShapes.byConstraintFile(shapes, shapesModel, outcome.results()));
    }

    private int workers() {
        return options.getWorkers() > 0
                ? options.getWorkers()
                : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }

    /**
     * Loads the shape files, and the {@code .ttl} and {@code .rdf} entries of the ZIP archives among
     * them, as one owl:imports closure, and unions it with the supplied shapes model.
     */
    private LoadedShapes loadShapes(List<String> warnings) throws IOException {
        List<Path> files = options.getShapeFiles();
        if (files.isEmpty()) {
            return new LoadedShapes(options.getShapesModel(), List.of());
        }

        Path constraintsRoot = options.getConstraintsRoot();
        if (constraintsRoot == null) {
            constraintsRoot = files.getFirst().toAbsolutePath().normalize().getParent();
        }

        List<ValidationTools.ShapeSource> roots = new ArrayList<>();
        for (Path file : files) {
            if (!Files.isRegularFile(file)) {
                throw new FileNotFoundException("SHACL shape file not found: " + file.toAbsolutePath());
            }
            if (isZip(file)) {
                ShapeArchive archive = ShapeArchive.read(file);
                roots.addAll(archive.entries());
                warnAboutUnreadRdfEntries(file, archive.unreadRdfEntries(), warnings);
            } else {
                roots.add(new ValidationTools.LocalShapeSource(file));
            }
        }

        // One closure for all roots: loaded root by root, an import they share would be read once
        // per root, and the constraints of its anonymous shapes reported once per root.
        List<ShapeDocument> documents = new ArrayList<>();
        ValidationTools.LoadShapesResult loaded = ValidationTools.loadShapesWithImports(roots, constraintsRoot,
                new HashMap<>(), (source, document) -> documents.add(new ShapeDocument(source, document)));
        Model combined = loaded.model();
        if (options.getShapesModel() != null) {
            combined.add(options.getShapesModel());
            combined.withDefaultMappings(options.getShapesModel());
        }

        if (loaded.unresolvableImports() > 0) {
            warnings.add(loaded.unresolvableImports() + " owl:imports declaration(s) could not be resolved to a"
                    + " local or remote file; the shapes they define were not validated.");
        }
        return new LoadedShapes(combined, documents);
    }

    /**
     * Unions the data files, and the {@code .xml} entries of the ZIP archives among them (nested
     * archives included), parsed with the datatype map, with the supplied data model.
     */
    private LoadedData loadData(Map<String, RDFDatatype> dataTypeMap) throws IOException {
        List<Path> files = options.getDataFiles();
        Model supplied = options.getDataModel();
        if (files.isEmpty() && dataTypeMap.isEmpty()) {
            return new LoadedData(supplied, describeSources(List.of(), true, "data model"));
        }

        Model data = ModelFactory.createDefaultModel();
        List<String> read = new ArrayList<>();
        for (Path file : files) {
            if (!Files.isRegularFile(file)) {
                throw new FileNotFoundException("Data file not found: " + file.toAbsolutePath());
            }
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (isZip(file)) {
                List<String> entries = new ArrayList<>();
                eu.griddigit.cimpal.core.utils.ModelFactory.forEachZipEntry(file, (entry, in) -> {
                    if (entry.toLowerCase(Locale.ROOT).endsWith(".xml")) {
                        parseInto(data, in, Lang.RDFXML, dataTypeMap);
                        entries.add(file.getFileName() + "/" + entry);
                    }
                });
                if (entries.isEmpty()) {
                    // An archive of the wrong kind would otherwise add no data, and pass validation.
                    throw new IOException("No .xml files found in archive: " + file.getFileName());
                }
                read.addAll(entries);
                continue;
            }
            try (InputStream in = Files.newInputStream(file)) {
                parseInto(data, in, RDFLanguages.filenameToLang(name, Lang.RDFXML), dataTypeMap);
            }
            read.add(file.getFileName().toString());
        }

        if (supplied != null) {
            if (dataTypeMap.isEmpty()) {
                data.add(supplied);
            } else {
                addTyped(data.getGraph(), supplied.getGraph(), dataTypeMap);
            }
            data.withDefaultMappings(supplied);
        }
        return new LoadedData(data, describeSources(read, supplied != null, "data model"));
    }

    private static boolean isZip(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    /** Points out RDF files of a shapes archive that were not read, rather than drop them silently. */
    private static void warnAboutUnreadRdfEntries(Path archive, List<String> unread, List<String> warnings) {
        if (unread.isEmpty()) {
            return;
        }
        String names = String.join(", ", unread.subList(0, Math.min(5, unread.size())))
                + (unread.size() > 5 ? ", ..." : "");
        warnings.add(archive.getFileName() + ": " + unread.size()
                + (unread.size() == 1 ? " RDF file was" : " RDF files were")
                + " not read as constraints; only .ttl and .rdf files are: " + names);
    }

    @SuppressWarnings("unchecked")
    private void parseInto(Model target, InputStream in, Lang lang, Map<String, RDFDatatype> dataTypeMap) {
        // With a map, the same typing mapping validation applies: mapped properties get their
        // datatype and every other literal without a language tag becomes xsd:string.
        DataTypeStreamRDF typing = dataTypeMap.isEmpty() ? null : new DataTypeStreamRDF(target.getGraph(), dataTypeMap);
        StreamRDF sink = typing != null ? typing : StreamRDFLib.graph(target.getGraph());
        // Named graphs (TriG, N-Quads, JSON-LD) are merged into the one data graph.
        StreamRDF triplesOnly = new StreamRDFWrapper(sink) {
            @Override
            public void quad(Quad quad) {
                triple(quad.asTriple());
            }
        };
        RDFParser.source(in).lang(lang).base(options.getXmlBase()).parse(triplesOnly);
        if (typing != null) {
            target.setNsPrefixes((Map<String, String>) typing.getPrefixMapping());
        }
    }

    /**
     * Copies {@code source} into {@code target}, typing the plain string literals of mapped
     * properties. Unlike parsing, literals that already carry a datatype are left alone: a
     * supplied model may have been built with typed literals on purpose.
     */
    private static void addTyped(Graph target, Graph source, Map<String, RDFDatatype> dataTypeMap) {
        source.find().forEachRemaining(triple -> {
            Node object = triple.getObject();
            RDFDatatype datatype = object.isLiteral()
                    && XSDDatatype.XSDstring.getURI().equals(object.getLiteralDatatypeURI())
                    ? dataTypeMap.get(triple.getPredicate().getURI())
                    : null;
            target.add(datatype == null ? triple : Triple.create(triple.getSubject(), triple.getPredicate(),
                    NodeFactory.createLiteralDT(object.getLiteralLexicalForm(), datatype)));
        });
    }

    private String datasetName() {
        return options.getDataFiles().isEmpty()
                ? "Data model"
                : ValidationTools.makeDatasetName(options.getDataFiles());
    }

    private String shapeSources() {
        return describeSources(options.getShapeFiles().stream().map(file -> file.getFileName().toString()).toList(),
                options.getShapesModel() != null, "shapes model");
    }

    private static String describeSources(List<String> names, boolean hasModel, String modelLabel) {
        String joined = names.stream().sorted().collect(Collectors.joining("; "));
        if (!hasModel) {
            return joined;
        }
        return joined.isEmpty() ? modelLabel : joined + "; " + modelLabel;
    }

    /** The data graph and the files it was read from, for the report. */
    private record LoadedData(Model model, String sources) { }

    /** The shapes graph and the documents it was read from, in reading order. */
    private record LoadedShapes(Model model, List<ShapeDocument> documents) {

        /**
         * Breaks {@code results} down by the constraint file that declares each result's source
         * shape: the first document read that types it {@code sh:NodeShape} or
         * {@code sh:PropertyShape}, or failing that the first that describes it. Each file that
         * declares an active shape gets an entry, so a file whose shapes all passed is listed too.
         * A file that only adds to another file's shape, a constraint or a message, has what that
         * shape finds counted under the file that declares the shape.
         * <p>
         * Returns no breakdown when a result cannot be placed (Python engines report anonymous
         * shapes under blank nodes of their own) rather than show the files it might belong to
         * as conforming.
         */
        List<SHACLValidationReport.ConstraintFileResults> byConstraintFile(Shapes shapes, Model shapesModel,
                                                                           List<SHACLValidationResult> results) {
            if (documents.isEmpty()) {
                return List.of();
            }
            Map<ShapeDocument, List<SHACLValidationResult>> resultsByDocument = new HashMap<>();
            Map<String, ShapeDocument> documentByLabel = new HashMap<>();
            Set<String> ambiguousLabels = new HashSet<>();
            for (Shape shape : shapes.getShapeMap().values()) {
                ShapeDocument document = declaringDocument(shape.getShapeNode());
                if (document == null) {
                    continue;
                }
                if (!shape.deactivated()) {
                    resultsByDocument.putIfAbsent(document, new ArrayList<>());
                }
                // Results name their source shape by this label, and two files' shapes may share it.
                String label = ShaclTools.sourceShapeLabel(shapesModel.wrapAsResource(shape.getShapeNode()), shapesModel);
                ShapeDocument first = documentByLabel.putIfAbsent(label, document);
                if (first != null && first != document) {
                    ambiguousLabels.add(label);
                }
            }
            for (SHACLValidationResult result : results) {
                String label = result.getSourceShape();
                ShapeDocument document = ambiguousLabels.contains(label) ? null : documentByLabel.get(label);
                if (document == null) {
                    return List.of();
                }
                resultsByDocument.computeIfAbsent(document, ignored -> new ArrayList<>()).add(result);
            }
            // By file name; a stable sort keeps files of the same name in reading order.
            return documents.stream()
                    .filter(resultsByDocument::containsKey)
                    .map(document -> new SHACLValidationReport.ConstraintFileResults(
                            document.constraintFile(), resultsByDocument.get(document)))
                    .sorted(Comparator.comparing(SHACLValidationReport.ConstraintFileResults::constraintFile,
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }

        private ShapeDocument declaringDocument(Node shape) {
            for (ShapeDocument document : documents) {
                if (document.types(shape)) {
                    return document;
                }
            }
            for (ShapeDocument document : documents) {
                if (document.describes(shape)) {
                    return document;
                }
            }
            return null;
        }
    }

    /** One document read while loading the shapes: its file name and the nodes it describes. */
    private static final class ShapeDocument {
        private final String constraintFile;
        private final Set<Node> typedShapes = new HashSet<>();
        private final Set<Node> subjects = new HashSet<>();

        ShapeDocument(ValidationTools.ShapeSource source, Model document) {
            String name = source.displayName();
            this.constraintFile = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
            document.getGraph().find().forEachRemaining(triple -> {
                subjects.add(triple.getSubject());
                if (RDF.Nodes.type.equals(triple.getPredicate())
                        && (SHACL.NodeShape.equals(triple.getObject()) || SHACL.PropertyShape.equals(triple.getObject()))) {
                    typedShapes.add(triple.getSubject());
                }
            });
        }

        String constraintFile() {
            return constraintFile;
        }

        boolean types(Node shape) {
            return typedShapes.contains(shape);
        }

        boolean describes(Node shape) {
            return subjects.contains(shape);
        }
    }
}
