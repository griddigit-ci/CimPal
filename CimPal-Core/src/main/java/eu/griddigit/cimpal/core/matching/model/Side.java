/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.model;

/**
 * Which of the two models a table or element belongs to.
 * PF is the PowerFactory-exported side (union of the EirGrid and SONI EQ files);
 * IGMS is the reference side. The mapping direction is PF -&gt; IGMS
 * (PF_ID is the key, the matched IGMS mRID is New_ID).
 */
public enum Side {
    PF,
    IGMS
}
