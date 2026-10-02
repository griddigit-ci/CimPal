/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.apache.jena.riot.Lang;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Archive limits are enforced while entries are read, and on every archive path (SEC-5, from
 * TEST-2's F7 finding). Small budgets stand in for the real 1 GiB / 2 GiB limits.
 */
class ZipBudgetTest {

    @TempDir
    Path tempDir;

    private static final String TTL = "<urn:a> <urn:p> \"" + "x".repeat(200) + "\" .\n";

    private static byte[] zip(String... namesAndContents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                out.putNextEntry(new ZipEntry(namesAndContents[i]));
                out.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    void oversizedEntryStopsAtThePerEntryLimitWhileReading() throws Exception {
        ModelFactory.ZipBudget budget = new ModelFactory.ZipBudget(10, 10_000, 100);
        byte[] archive = zip("big.xml", "a".repeat(5_000));

        assertThatThrownBy(() -> ModelFactory.unzip(new ByteArrayInputStream(archive), budget))
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("Archive entry exceeds the size limit (100 bytes)");
    }

    @Test
    void totalLimitTripsAcrossEntries() throws Exception {
        ModelFactory.ZipBudget budget = new ModelFactory.ZipBudget(10, 300, 1_000);
        byte[] archive = zip("a.xml", "a".repeat(200), "b.xml", "b".repeat(200));

        assertThatThrownBy(() -> ModelFactory.unzip(new ByteArrayInputStream(archive), budget))
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("Archive exceeds the total uncompressed size limit (300 bytes)");
    }

    @Test
    void archiveWithinTheLimitsExpands() throws Exception {
        ModelFactory.ZipBudget budget = new ModelFactory.ZipBudget(10, 1_000, 500);
        byte[] archive = zip("a.xml", "<a/>", "b.xml", "<b/>");

        assertThat(ModelFactory.unzip(new ByteArrayInputStream(archive), budget)).hasSize(2);
    }

    @Test
    void modelLoadPerFilesIsBoundedBySize() throws Exception {
        Path archive = Files.write(tempDir.resolve("models.zip"), zip("a.ttl", TTL, "b.ttl", TTL));

        assertThatThrownBy(() -> ModelFactory.modelLoadPerFiles(List.of(archive.toFile()), "urn:base", Lang.TURTLE,
                () -> new ModelFactory.ZipBudget(10, 300, 1_000)))
                // The RDF parser reports the stream's IOException as its own runtime exception.
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("size limit");
    }

    @Test
    void modelLoadPerFilesIsBoundedByEntryCount() throws Exception {
        Path archive = Files.write(tempDir.resolve("models.zip"), zip("a.ttl", TTL, "b.ttl", TTL));

        assertThatThrownBy(() -> ModelFactory.modelLoadPerFiles(List.of(archive.toFile()), "urn:base", Lang.TURTLE,
                () -> new ModelFactory.ZipBudget(1, 1_000_000, 1_000_000)))
                .isInstanceOf(UncheckedIOException.class)
                .hasStackTraceContaining("entry limit");
    }

    @Test
    void modelLoadPerFilesStillLoadsAnArchiveWithinTheLimits() throws Exception {
        Path archive = Files.write(tempDir.resolve("models.zip"), zip("a.ttl", TTL, "b.ttl", TTL));

        assertThat(ModelFactory.modelLoadPerFiles(List.of(archive.toFile()), "urn:base", Lang.TURTLE)).hasSize(2);
    }
}
