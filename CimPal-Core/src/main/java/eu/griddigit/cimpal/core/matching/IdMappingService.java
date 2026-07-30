/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching;

import eu.griddigit.cimpal.core.matching.engine.AliasDictionary;
import eu.griddigit.cimpal.core.matching.engine.LogicalLineBuilder;
import eu.griddigit.cimpal.core.matching.engine.ReportAssembler;
import eu.griddigit.cimpal.core.matching.engine.SimpleIdMatcher;
import eu.griddigit.cimpal.core.matching.engine.SubstationLineMatcher;
import eu.griddigit.cimpal.core.matching.engine.TopologyMatcher;
import eu.griddigit.cimpal.core.matching.model.*;
import eu.griddigit.cimpal.core.matching.query.ModelTablesLoader;
import eu.griddigit.cimpal.core.matching.query.QueryRunner;
import eu.griddigit.cimpal.core.presets.MatchingConfigPresets;
import eu.griddigit.cimpal.core.utils.ModelFactory;
import org.apache.jena.rdf.model.Model;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Headless facade for the ID Mapping / Model Matching feature. Orchestrates the
 * whole pipeline - model loading, the SPARQL query catalog, extraction to
 * neutral tables, Phase 1 logical-line construction, Phase 2 substation/line
 * matching, report assembly, and workbook output - while keeping every stage in
 * its own reusable component. The GUI controller and any future CLI call this.
 */
public final class IdMappingService {

    /** Progress callback for the UI; fraction in [0,1] and a short message. */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(double fraction, String message);
    }

    public record RunSummary(Path output, int matched, int unmatched) {
    }

    private final MatchingConfig config;

    public IdMappingService(MatchingConfig config) {
        this.config = config == null ? MatchingConfigPresets.defaults() : config;
    }

    /**
     * Runs a full matching and writes the workbook. IGMG is the source of ids.
     *
     * @param igmgFiles     the IGMG model file(s) - the source of the canonical mRIDs
     * @param otherFiles    the SONI + EirGrid EQ files, unioned - mapped against IGMG
     * @param boundaryFiles  optional boundary / common-data set(s), loaded read-only into
     *                       both sides so BaseVoltage and boundary CNs resolve
     * @param dictionaryFile optional substation code&lt;-&gt;name dictionary CSV (may be null)
     * @param output         target .xlsx path
     * @param listener       progress callback (may be null)
     */
    public RunSummary run(List<File> igmgFiles, List<File> otherFiles, List<File> boundaryFiles,
                          File dictionaryFile, Path output, ProgressListener listener) throws Exception {
        String xmlBase = MatchingConfigPresets.DEFAULT_XML_BASE;
        QueryRunner runner = new QueryRunner(config.getQueryFolder());

        // Load, extract and free one side at a time so only one full Jena model
        // is resident at a time and query results are never fully buffered.
        progress(listener, 0.05, "Loading IGMG (source) model...");
        Model sourceModel = ModelFactory.loadCombinedModelForSparql(union(igmgFiles, boundaryFiles), xmlBase);
        progress(listener, 0.15, "Extracting IGMG tables via SPARQL (streaming)...");
        ModelTables sourceTables = ModelTablesLoader.load(Side.SOURCE, "IGMG", runner, sourceModel);
        sourceModel = null; // release the model before loading the next side

        progress(listener, 0.35, "Loading SONI+EirGrid model (union)...");
        Model matchedModel = ModelFactory.loadCombinedModelForSparql(union(otherFiles, boundaryFiles), xmlBase);
        progress(listener, 0.45, "Extracting SONI+EirGrid tables via SPARQL (streaming)...");
        ModelTables matchedTables = ModelTablesLoader.load(Side.MATCHED, "PF", runner, matchedModel);
        matchedModel = null;

        progress(listener, 0.55, "Same-id + same-type matches...");
        SimpleIdMatcher.Result simple = SimpleIdMatcher.match(sourceTables, matchedTables);

        AliasDictionary dict = dictionaryFile == null
                ? AliasDictionary.empty()
                : AliasDictionary.fromCsv(dictionaryFile.toPath());

        progress(listener, 0.62, "Building logical lines (collapsing series chains)...");
        List<LogicalLine> sourceLines = LogicalLineBuilder.build(sourceTables);
        List<LogicalLine> matchedLines = LogicalLineBuilder.build(matchedTables);

        progress(listener, 0.72, "Matching substations and lines"
                + (dict.isEmpty() ? "..." : " (using alias dictionary, " + dict.size() + " aliases)..."));
        SubstationLineMatcher.Result result = SubstationLineMatcher.match(
                sourceTables, sourceLines, matchedTables, matchedLines, config, simple.substationSeeds(), dict);

        progress(listener, 0.82, "Matching equipment within substations (topology)...");
        TopologyMatcher.Result topo = TopologyMatcher.match(
                sourceTables, sourceLines, matchedTables, matchedLines, result);

        progress(listener, 0.90, "Writing workbook...");
        MatchingReport report = ReportAssembler.assemble(simple, result, topo,
                sourceTables, matchedTables, sourceLines, matchedLines);
        eu.griddigit.cimpal.core.matching.report.MatchingExcelWriter.write(report, output);

        progress(listener, 1.0, "Done.");
        return new RunSummary(output, report.matched().size(), report.unmatched().size());
    }

    private static List<File> union(List<File> a, List<File> b) {
        List<File> out = new ArrayList<>();
        if (a != null) out.addAll(a);
        if (b != null) out.addAll(b);
        return out;
    }

    private static void progress(ProgressListener l, double f, String msg) {
        if (l != null) l.onProgress(f, msg);
    }
}
