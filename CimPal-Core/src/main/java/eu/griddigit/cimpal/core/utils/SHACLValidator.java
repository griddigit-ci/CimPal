package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.SHACLValidationOptions;
import eu.griddigit.cimpal.core.models.SHACLValidationReport;
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
import org.apache.jena.sparql.core.Quad;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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

        Model shapesModel = loadShapes(warnings);
        Shapes shapes = Shapes.parse(shapesModel.getGraph());
        if (shapes.getTargetShapes().isEmpty()) {
            warnings.add("The shapes declare no targets, so no data was validated ("
                    + shapesModel.size() + " shape triples loaded).");
        }

        Model dataModel = loadData(dataTypeMap);
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
                reportModel, warnings, datasetName(), dataSources(), shapeSources(),
                options.getMaxResultsPerConstraint());
    }

    private int workers() {
        return options.getWorkers() > 0
                ? options.getWorkers()
                : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }

    /** Unions every shape file's owl:imports closure with the supplied shapes model. */
    private Model loadShapes(List<String> warnings) throws IOException {
        List<Path> roots = options.getShapeFiles();
        if (roots.isEmpty()) {
            return options.getShapesModel();
        }

        Path constraintsRoot = options.getConstraintsRoot();
        if (constraintsRoot == null) {
            constraintsRoot = roots.getFirst().toAbsolutePath().normalize().getParent();
        }

        Model combined = ModelFactory.createDefaultModel();
        Map<String, Model> rootsLoaded = new HashMap<>();
        int unresolvableImports = 0;
        for (Path root : roots) {
            if (!Files.isRegularFile(root)) {
                throw new FileNotFoundException("SHACL shape file not found: " + root.toAbsolutePath());
            }
            ValidationTools.LoadShapesResult loaded = ValidationTools.loadShapesWithImports(
                    new ValidationTools.LocalShapeSource(root), constraintsRoot, rootsLoaded);
            combined.add(loaded.model());
            combined.setNsPrefixes(loaded.model().getNsPrefixMap());
            unresolvableImports += loaded.unresolvableImports();
        }
        if (options.getShapesModel() != null) {
            combined.add(options.getShapesModel());
            combined.withDefaultMappings(options.getShapesModel());
        }

        if (unresolvableImports > 0) {
            warnings.add(unresolvableImports + " owl:imports declaration(s) could not be resolved to a"
                    + " local or remote file; the shapes they define were not validated.");
        }
        return combined;
    }

    /** Unions the data files, parsed with the datatype map, with the supplied data model. */
    private Model loadData(Map<String, RDFDatatype> dataTypeMap) throws IOException {
        List<Path> files = options.getDataFiles();
        Model supplied = options.getDataModel();
        if (files.isEmpty() && dataTypeMap.isEmpty()) {
            return supplied;
        }

        Model data = ModelFactory.createDefaultModel();
        for (Path file : files) {
            if (!Files.isRegularFile(file)) {
                throw new FileNotFoundException("Data file not found: " + file.toAbsolutePath());
            }
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".zip")) {
                for (ValidationTools.ZipXmlEntry entry : ValidationTools.scanZipXmlEntries(file)) {
                    try (InputStream in = entry.openStream()) {
                        parseInto(data, in, Lang.RDFXML, dataTypeMap);
                    }
                }
                continue;
            }
            try (InputStream in = Files.newInputStream(file)) {
                parseInto(data, in, RDFLanguages.filenameToLang(name, Lang.RDFXML), dataTypeMap);
            }
        }

        if (supplied != null) {
            if (dataTypeMap.isEmpty()) {
                data.add(supplied);
            } else {
                addTyped(data.getGraph(), supplied.getGraph(), dataTypeMap);
            }
            data.withDefaultMappings(supplied);
        }
        return data;
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

    private String dataSources() {
        return describeSources(options.getDataFiles(), options.getDataModel() != null, "data model");
    }

    private String shapeSources() {
        return describeSources(options.getShapeFiles(), options.getShapesModel() != null, "shapes model");
    }

    private static String describeSources(List<Path> files, boolean hasModel, String modelLabel) {
        String names = ValidationTools.formatPaths(files);
        if (!hasModel) {
            return names;
        }
        return names.isEmpty() ? modelLabel : names + "; " + modelLabel;
    }
}
