/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.query;

import eu.griddigit.cimpal.core.matching.extract.Extractor;
import eu.griddigit.cimpal.core.matching.model.*;
import org.apache.jena.rdf.model.Model;

import java.util.ArrayList;
import java.util.List;

/**
 * Memory-lean loader: runs the queries the deterministic matcher needs
 * (terminals, ACLineSegment parameters, substations, transformer ends) and
 * streams every solution straight into compact typed records - the full
 * per-query result set is never buffered. Tables not used by the current
 * matcher are left empty.
 */
public final class ModelTablesLoader {

    private ModelTablesLoader() {
    }

    public static ModelTables load(Side side, String label, QueryRunner runner, Model model) {
        List<TerminalRow> terminals = new ArrayList<>();
        List<AcLineSegmentRow> acls = new ArrayList<>();
        List<SubstationRow> substations = new ArrayList<>();
        List<TransformerEndRow> transformerEnds = new ArrayList<>();

        runner.runStreaming(QueryRunner.MAP02_TERMINAL_DETAILS, model,
                g -> terminals.add(Extractor.terminalRow(g, label)));
        runner.runStreaming(QueryRunner.MAP03_ACLINESEGMENTS, model,
                g -> acls.add(Extractor.acLineSegmentRow(g, label)));
        runner.runStreaming(QueryRunner.MAP04_SUBSTATIONS, model,
                g -> substations.add(Extractor.substationRow(g, label)));
        runner.runStreaming(QueryRunner.MAP05_TRANSFORMER_ENDS, model,
                g -> transformerEnds.add(Extractor.transformerEndRow(g, label)));

        return new ModelTables(side, terminals, acls, substations, transformerEnds,
                List.of(), List.of(), List.of(), List.of());
    }
}
