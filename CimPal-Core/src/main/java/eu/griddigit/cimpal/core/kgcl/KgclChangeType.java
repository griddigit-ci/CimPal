/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.kgcl;

/**
 * The subset of KGCL (Knowledge Graph Change Language) change types that the RDF comparer emits.
 * <p>
 * These map onto the KGCL taxonomy (see <a href="https://w3id.org/kgcl">w3id.org/kgcl</a>): node
 * creation/deletion, rename (label change), definition change (comment), move (subClassOf change)
 * and a generic node annotation change used as a faithful catch-all for CIM-specific facets
 * (multiplicity, datatype, stereotype, ...) that have no dedicated KGCL type.
 */
public enum KgclChangeType {
    NODE_CREATION,
    NODE_DELETION,
    NODE_RENAME,
    NODE_DEFINITION_CHANGE,
    NODE_MOVE,
    NODE_ANNOTATION_CHANGE
}
