/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.model;

/**
 * One ACLineSegment from Map03_ACLineSegments. Electrical parameters are
 * summed across the member segments of a logical line during Phase 1.
 * Any numeric field may be null when the source omits it.
 */
public record AcLineSegmentRow(
        String id,
        String name,
        Double r,
        Double x,
        Double bch,
        Double gch,
        Double length,
        Double nomV,
        String lineContainer,
        String lineName,
        String source
) {
}
