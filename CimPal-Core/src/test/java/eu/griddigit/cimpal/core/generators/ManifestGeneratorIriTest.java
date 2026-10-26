/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.generators;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * File and folder names become the path of a {@code file:} IRI in the manifest; a name with
 * characters an IRI can't hold must not break out of it in the Turtle output (DOC-2: manifest is
 * reachable through serve, mcp and run, with names from uploads).
 */
class ManifestGeneratorIriTest {

    @Test
    void ordinaryNamesStayReadable() {
        assertThat(ManifestGenerator.iriPath("Instance\\Grid/cimxml")).isEqualTo("Instance/Grid/cimxml");
        assertThat(ManifestGenerator.iriPath("EQ_2026-10-20.xml")).isEqualTo("EQ_2026-10-20.xml");
        assertThat(ManifestGenerator.iriPath("my model.xml")).isEqualTo("my%20model.xml");
    }

    @Test
    void anAbsoluteFolderGivesOnlyItsLastNameOnEveryPlatform() {
        // A POSIX path is a valid relative URI, so it once went into the IRI whole and the
        // server's folder layout ended up in the manifest (seen on Linux only).
        java.io.File model = new java.io.File("upload/other/EQ.xml");
        assertThat(ManifestGenerator.compactAccessUrl("/srv/cimpal/work/models", model)).isEqualTo("file:models/EQ.xml");
        assertThat(ManifestGenerator.compactAccessUrl("file:///srv/cimpal/work/models/EQ.xml", model))
                .isEqualTo("file:models/EQ.xml");
        assertThat(ManifestGenerator.compactAccessUrl("Instance/Grid/cimxml", model))
                .isEqualTo("file:Instance/Grid/cimxml/EQ.xml");
    }

    @Test
    void charactersThatWouldEndTheIriAreEncoded() {
        assertThat(ManifestGenerator.iriPath("a>.xml")).isEqualTo("a%3E.xml");
        assertThat(ManifestGenerator.iriPath("a\"<b\n.xml")).isEqualTo("a%22%3Cb%0A.xml");
        assertThat(ManifestGenerator.iriPath("{x}|`^.xml")).isEqualTo("%7Bx%7D%7C%60%5E.xml");
        assertThat(ManifestGenerator.iriPath("é.xml")).isEqualTo("%C3%A9.xml");
    }
}
