/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
module CimPal.CLI {
    requires org.apache.jena.core;
    requires CimPal.Core;
    requires CimPal.CustomWriter;
    requires org.apache.jena.arq;
    requires info.picocli;
    requires tools.jackson.databind;
    requires org.apache.poi.poi;
    requires org.apache.poi.ooxml;
    requires jdk.httpserver;
}