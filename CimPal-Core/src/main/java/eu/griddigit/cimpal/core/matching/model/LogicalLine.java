/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.model;

import java.util.List;

/**
 * A logical line produced by Phase 1: one or more ACLineSegments (plus any
 * pass-through SeriesCompensators/Junctions) collapsed along degree-2
 * connectivity nodes into a single electrical branch between endpoint
 * substations.
 *
 * <p>A normal line has two {@code endpointSubstations}; a T-connection
 * (degree-3 interior CN) has three and {@code multiEnded} is true. Impedance
 * fields are the summed totals over {@code memberSegments}. Endpoints that
 * cannot be resolved to a substation appear as null entries and drive the
 * BOUNDARY / unmatched classification downstream.</p>
 */
public record LogicalLine(
        String id,
        Side side,
        String name,
        List<String> memberSegments,
        int segmentCount,
        double totalR,
        double totalX,
        double totalBch,
        double totalLength,
        Double nomV,
        List<String> endpointCns,
        List<String> endpointSubstations,
        boolean multiEnded,
        List<String> notes
) {
}
