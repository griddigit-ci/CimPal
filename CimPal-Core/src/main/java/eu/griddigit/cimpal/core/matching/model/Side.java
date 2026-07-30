/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.model;

/**
 * Which of the two models a table or element belongs to.
 * <ul>
 *   <li>{@code SOURCE} - the first model, the source of the canonical mRIDs
 *       (for EirGrid this is the merged IGMG model). Its ids are the reference,
 *       reported in the Source_ID column.</li>
 *   <li>{@code MATCHED} - the model(s) mapped against the source (for EirGrid the
 *       union of the SONI and EirGrid EQ files). Its ids are reported in the
 *       Matched_ID column.</li>
 * </ul>
 */
public enum Side {
    SOURCE,
    MATCHED
}
