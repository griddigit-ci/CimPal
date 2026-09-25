/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.interfaces;

import eu.griddigit.cimpal.core.models.RDFCompareResult;
import org.apache.jena.rdf.model.Model;

public interface IRDFComparator {
    RDFCompareResult compare(Model modelA, Model modelB);
}
