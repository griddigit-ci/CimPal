/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.model;

/**
 * One PowerTransformerEnd from Map05_TransformerEnds. Grouping rows by
 * {@code trafo} gives the winding count (2 vs 3) and the multiset of ratedU
 * that labels the transformer for matching.
 */
public record TransformerEndRow(
        String trafo,
        String trafoName,
        String end,
        Integer endNum,
        Double ratedU,
        Double ratedS,
        String terminal,
        String substation,
        Double nomV,
        String source
) {
}
