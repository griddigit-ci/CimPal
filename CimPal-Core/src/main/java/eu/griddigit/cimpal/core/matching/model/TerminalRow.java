/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.model;

/**
 * One Terminal from Map02_TerminalDetails: the adjacency backbone.
 * {@code eq} is the conducting equipment the terminal belongs to, {@code cn}
 * the connectivity node it attaches to. The connectivity graph is built from
 * (eq, cn) pairs. Container/substation fields are null when containment is
 * absent or unresolved. {@code source} is a provenance label (side or file).
 */
public record TerminalRow(
        String terminal,
        Integer seq,
        String eq,
        String eqType,
        String eqName,
        String cn,
        String cnName,
        String container,
        String containerType,
        String substation,
        String substationName,
        Double nomV,
        String source
) {
}
