/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2022, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package eu.griddigit.cimpal.main.core;

import org.apache.jena.riot.adapters.RDFWriterRIOT;

// Model.write adapter - must be public.
public class RDFWriterCIM extends RDFWriterRIOT { public RDFWriterCIM() { super("RDFXMLCIM") ; } }