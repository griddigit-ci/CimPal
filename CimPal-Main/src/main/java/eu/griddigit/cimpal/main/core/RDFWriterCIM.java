/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.core;

import org.apache.jena.riot.adapters.RDFWriterRIOT;

// Model.write adapter - must be public.
public class RDFWriterCIM extends RDFWriterRIOT { public RDFWriterCIM() { super("RDFXMLCIM") ; } }