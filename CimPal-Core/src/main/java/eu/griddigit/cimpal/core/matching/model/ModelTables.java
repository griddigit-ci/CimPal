/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.model;

import java.util.List;

/**
 * The complete set of neutral (schema-free) tables extracted from one model.
 * This is the boundary between SPARQL/Jena and the matching engine: the engine
 * consumes only {@code ModelTables} and never touches an RDF triple. All lists
 * are non-null (possibly empty).
 */
public record ModelTables(
        Side side,
        List<TerminalRow> terminals,
        List<AcLineSegmentRow> acLineSegments,
        List<SubstationRow> substations,
        List<TransformerEndRow> transformerEnds,
        List<SwitchRow> switches,
        List<BusbarRow> busbars,
        List<InjectionRow> injections,
        List<ConnectivityNodeRow> connectivityNodes
) {
}
