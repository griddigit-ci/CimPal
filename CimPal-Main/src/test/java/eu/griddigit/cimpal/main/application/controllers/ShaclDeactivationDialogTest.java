/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.application.controllers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The import rows of the deactivation dialog: what the Status column says about each import IRI. */
class ShaclDeactivationDialogTest {

    @Test
    void importDefaultsToTheFileUri() {
        File file = new File("C:/constraints/EQ.ttl");
        ShaclDeactivationDialog.ImportRow row = new ShaclDeactivationDialog.ImportRow(file, "Base");

        assertThat(row.iri.get()).isEqualTo(file.toURI().toString());
        assertThat(row.status()).isEqualTo("Local file");
    }

    @Test
    void statusFlagsUnusableAndSkippedImports() {
        ShaclDeactivationDialog.ImportRow row = new ShaclDeactivationDialog.ImportRow(new File("EQ.ttl"), "Base");

        row.iri.set("https://example.com/constraints/EQ.ttl");
        assertThat(row.status()).isEqualTo("OK");

        row.iri.set("https://example.com/constraints/EQ");
        assertThat(row.status()).startsWith("Warning");

        row.iri.set("C:\\constraints\\EQ.ttl");
        assertThat(row.status()).startsWith("Not usable");
    }

    @Test
    void mergedFileGoesNextToTheConfiguration() {
        Path folder = Path.of("C:/configs");

        assertThat(ShaclDeactivationDialog.mergedFileFor(folder.resolve("EQConfiguration.ttl")))
                .isEqualTo(folder.resolve("EQConfiguration-merged.ttl"));
        assertThat(ShaclDeactivationDialog.mergedFileFor(folder.resolve("config")))
                .isEqualTo(folder.resolve("config-merged.ttl"));
    }

    /** A base file that happens to be named like the merged file must not be replaced by it. */
    @Test
    void mergedFileThatIsAnImportedFileIsRefused(@TempDir Path tempDir) throws Exception {
        Path imported = Files.writeString(tempDir.resolve("Configuration-merged.ttl"), "# a base file");
        Path other = Files.writeString(tempDir.resolve("other.ttl"), "# another");
        Path target = ShaclDeactivationDialog.mergedFileFor(tempDir.resolve("Configuration.ttl"));

        assertThat(ShaclDeactivationDialog.isInputFile(target, List.of(other, imported))).isTrue();
        assertThat(ShaclDeactivationDialog.isInputFile(target, List.of(other))).isFalse();
        assertThat(ShaclDeactivationDialog.isInputFile(tempDir.resolve("new-merged.ttl"), List.of(imported))).isFalse();
    }
}
