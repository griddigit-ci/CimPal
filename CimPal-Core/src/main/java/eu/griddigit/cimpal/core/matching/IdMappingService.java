/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching;

import eu.griddigit.cimpal.core.matching.engine.LogicalLineBuilder;
import eu.griddigit.cimpal.core.matching.engine.ReportAssembler;
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
     * Runs a full matching and writes the workbook.
     *
     * @param pfFiles       PF-side EQ files (EirGrid + SONI), unioned
     * @param igmsFiles     IGMS-side EQ file(s)
     * @param boundaryFiles optional boundary set(s), loaded read-only into both
     *                      sides so BaseVoltage and boundary CNs resolve
     * @param output        target .xlsx path
     * @param listener      progress callback (may be null)
     */
    public RunSummary run(List<File> pfFiles, List<File> igmsFiles, List<File> boundaryFiles,
                          Path output, ProgressListener listener) throws Exception {
        String xmlBase = MatchingConfigPresets.DEFAULT_XML_BASE;
        QueryRunner runner = new QueryRunner(config.getQueryFolder());

        // Load, extract and free one side at a time so only one full Jena model
        // is resident at a time and query results are never fully buffered.
        progress(listener, 0.05, "Loading PF model (union of EQ + boundary)...");
        Model pfModel = ModelFactory.loadCombinedModelForSparql(union(pfFiles, boundaryFiles), xmlBase);
        progress(listener, 0.15, "Extracting PF tables via SPARQL (streaming)...");
        ModelTables pfTables = ModelTablesLoader.load(Side.PF, "PF", runner, pfModel);
        pfModel = null; // release the model before loading the next side

        progress(listener, 0.35, "Loading IGMS model...");
        Model igmsModel = ModelFactory.loadCombinedModelForSparql(union(igmsFiles, boundaryFiles), xmlBase);
        progress(listener, 0.45, "Extracting IGMS tables via SPARQL (streaming)...");
        ModelTables igmsTables = ModelTablesLoader.load(Side.IGMS, "IGMS", runner, igmsModel);
        igmsModel = null;

        progress(listener, 0.60, "Building logical lines (collapsing series chains)...");
        List<LogicalLine> pfLines = LogicalLineBuilder.build(pfTables);
        List<LogicalLine> igmsLines = LogicalLineBuilder.build(igmsTables);

        progress(listener, 0.72, "Matching substations and lines...");
        SubstationLineMatcher.Result result = SubstationLineMatcher.match(
                pfTables, pfLines, igmsTables, igmsLines, config);

        progress(listener, 0.82, "Matching equipment within substations (topology)...");
        TopologyMatcher.Result topo = TopologyMatcher.match(
                pfTables, pfLines, igmsTables, igmsLines, result);

        progress(listener, 0.90, "Writing workbook...");
        MatchingReport report = ReportAssembler.assemble(result, topo, pfTables, igmsTables, pfLines, igmsLines);
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
