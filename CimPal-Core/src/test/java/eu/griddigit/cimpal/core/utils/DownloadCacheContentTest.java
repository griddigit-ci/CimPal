/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When a cached download can't be replaced (Windows: another run has it open, DEP-8), the cached
 * copy may stand in only for identical content; an older version must fail the download.
 */
class DownloadCacheContentTest {

    @TempDir
    Path tempDir;

    @Test
    void identicalContentMayStandIn() throws Exception {
        Path cached = Files.writeString(tempDir.resolve("x.xml"), "<rdf:RDF/>");
        assertThat(ValidationTools.sameContent(cached, "<rdf:RDF/>".getBytes(StandardCharsets.UTF_8))).isTrue();
    }

    @Test
    void anOlderVersionMayNot() throws Exception {
        Path cached = Files.writeString(tempDir.resolve("x.xml"), "<rdf:RDF version='1'/>");
        assertThat(ValidationTools.sameContent(cached, "<rdf:RDF version='2'/>".getBytes(StandardCharsets.UTF_8)))
                .isFalse();
        assertThat(ValidationTools.sameContent(cached, "<rdf:RDF version='1'/> ".getBytes(StandardCharsets.UTF_8)))
                .isFalse();
    }

    @Test
    void aMissingCopyMayNot() {
        assertThat(ValidationTools.sameContent(tempDir.resolve("missing.xml"), new byte[0])).isFalse();
    }
}
