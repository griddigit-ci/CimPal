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
import java.util.ArrayList;
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

    /** An archive holding one entry, {@code name}, with {@code content}. */
    private static byte[] zipOf(String name, byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry(name));
            out.write(content);
            out.closeEntry();
        }
        return bytes.toByteArray();
    }

    /** {@code levels} archives, each inside the next, around {@code eq.xml}. */
    private Path nestedArchive(int levels) throws IOException {
        byte[] archive = zip("eq.xml", "<a/>");
        for (int level = 1; level < levels; level++) {
            archive = zipOf("level" + level + ".zip", archive);
        }
        return Files.write(tempDir.resolve("nested.zip"), archive);
    }

    private static List<String> entryNames(Path archive) throws IOException {
        List<String> names = new ArrayList<>();
        ModelFactory.forEachZipEntry(archive, (name, in) -> names.add(name));
        return names;
    }

    @Test
    void forEachZipEntryNamesNestedEntriesAfterTheirArchive() throws Exception {
        Path archive = Files.write(tempDir.resolve("cgm.zip"),
                zipOf("igm\\be.zip", zip("eq.xml", "<a/>", "./tp/../ssh.xml", "<b/>")));

        List<String> contents = new ArrayList<>();
        ModelFactory.forEachZipEntry(archive, (name, in) ->
                contents.add(name + "=" + new String(in.readAllBytes(), StandardCharsets.UTF_8)));

        assertThat(contents).containsExactly("igm/be.zip/eq.xml=<a/>", "igm/be.zip/ssh.xml=<b/>");
    }

    @Test
    void forEachZipEntryFollowsNestingUpToTheDepthLimitOfUnzip() throws Exception {
        // The file and three archives inside it, as unzip() allows; a fourth level is refused.
        assertThat(entryNames(nestedArchive(4))).containsExactly("level3.zip/level2.zip/level1.zip/eq.xml");

        Path tooDeep = nestedArchive(5);
        assertThatThrownBy(() -> entryNames(tooDeep))
                .isInstanceOf(IOException.class)
                .hasMessage("Archive nesting exceeds the depth limit (3)");
    }

    @Test
    void forEachZipEntryIsBoundedBySizeWhileReading() throws Exception {
        Path archive = Files.write(tempDir.resolve("big.zip"), zip("big.xml", "a".repeat(5_000)));

        assertThatThrownBy(() -> ModelFactory.forEachZipEntry(archive, new ModelFactory.ZipBudget(10, 10_000, 100),
                (name, in) -> in.readAllBytes()))
                .isInstanceOf(IOException.class)
                .hasMessage("Archive entry exceeds the size limit (100 bytes)");
    }

    @Test
    void forEachZipEntryCountsNestedEntriesAgainstOneBudget() throws Exception {
        // The nested archive and its two entries are three entries of one traversal.
        Path archive = Files.write(tempDir.resolve("outer.zip"), zipOf("inner.zip", zip("a.xml", "<a/>", "b.xml", "<b/>")));

        assertThatThrownBy(() -> ModelFactory.forEachZipEntry(archive, new ModelFactory.ZipBudget(2, 10_000, 10_000),
                (name, in) -> in.readAllBytes()))
                .isInstanceOf(IOException.class)
                .hasMessage("Archive exceeds the entry limit (2)");
    }

    @Test
    void forEachZipEntryCountsNestedEntriesItsConsumerSkips() throws Exception {
        // ZipInputStream inflates a skipped entry to reach the next one. Uncounted, a bomb in an
        // entry nobody reads would inflate without limit (security review of the combined workflow).
        Path archive = Files.write(tempDir.resolve("outer.zip"),
                zipOf("inner.zip", zipOf("pad.bin", new byte[5_000])));

        assertThatThrownBy(() -> ModelFactory.forEachZipEntry(archive, new ModelFactory.ZipBudget(10, 100_000, 1_000),
                (name, in) -> { }))
                .isInstanceOf(IOException.class)
                .hasMessage("Archive entry exceeds the size limit (1000 bytes)");
    }

    @Test
    void forEachZipEntryCountsDataInNestedDirectoryEntries() throws Exception {
        Path archive = Files.write(tempDir.resolve("outer.zip"),
                zipOf("inner.zip", zipOf("folder/", new byte[5_000])));

        assertThatThrownBy(() -> ModelFactory.forEachZipEntry(archive, new ModelFactory.ZipBudget(10, 100_000, 1_000),
                (name, in) -> { }))
                .isInstanceOf(IOException.class)
                .hasMessage("Archive entry exceeds the size limit (1000 bytes)");
    }

    @Test
    void forEachZipEntryLimitsEntriesReadInsideANestedArchive() throws Exception {
        Path big = Files.write(tempDir.resolve("big.zip"), zipOf("inner.zip", zipOf("big.xml", new byte[5_000])));
        assertThatThrownBy(() -> ModelFactory.forEachZipEntry(big, new ModelFactory.ZipBudget(10, 100_000, 1_000),
                (name, in) -> in.readAllBytes()))
                .isInstanceOf(IOException.class)
                .hasMessage("Archive entry exceeds the size limit (1000 bytes)");

        Path many = Files.write(tempDir.resolve("many.zip"),
                zipOf("inner.zip", zip("a.xml", "a".repeat(600), "b.xml", "b".repeat(600))));
        assertThatThrownBy(() -> ModelFactory.forEachZipEntry(many, new ModelFactory.ZipBudget(10, 1_000, 10_000),
                (name, in) -> in.readAllBytes()))
                .isInstanceOf(IOException.class)
                .hasMessage("Archive exceeds the total uncompressed size limit (1000 bytes)");
    }

    @Test
    void budgetWithATotalLimitAppliesItToEachEntryAndToAll() throws Exception {
        // Shape archives use such a budget, far below the default, because they are held in memory.
        Path one = Files.write(tempDir.resolve("one.zip"), zip("a.ttl", "a".repeat(1_500)));
        assertThatThrownBy(() -> ModelFactory.forEachZipEntry(one, ModelFactory.ZipBudget.withTotalLimit(1_000),
                (name, in) -> in.readAllBytes()))
                .isInstanceOf(IOException.class)
                .hasMessage("Archive entry exceeds the size limit (1000 bytes)");

        Path two = Files.write(tempDir.resolve("two.zip"), zip("a.ttl", "a".repeat(600), "b.ttl", "b".repeat(600)));
        assertThatThrownBy(() -> ModelFactory.forEachZipEntry(two, ModelFactory.ZipBudget.withTotalLimit(1_000),
                (name, in) -> in.readAllBytes()))
                .isInstanceOf(IOException.class)
                .hasMessage("Archive exceeds the total uncompressed size limit (1000 bytes)");
    }

    @Test
    void unsafeEntryNamesAreRefused() {
        // Control characters would forge lines in the logs that quote entry names.
        for (String unsafe : List.of("../x.xml", "a/../../x.xml", "..\\x.xml", "/abs.xml", "C:/x.xml",
                "x.xml:stream", "./", "a\nb.ttl", "a\rb.ttl", "a\u2028b.xml", "a\u0000b.xml", "a\tb.xml")) {
            assertThatThrownBy(() -> ModelFactory.safeZipEntryName(unsafe))
                    .as(unsafe)
                    .isInstanceOf(IOException.class)
                    .hasMessage("Unsafe ZIP entry path: " + LogSanitizer.forLog(unsafe));
        }
    }

    @Test
    void entryNamesAreNormalised() throws Exception {
        assertThat(ModelFactory.safeZipEntryName("a\\b/./c.xml")).isEqualTo("a/b/c.xml");
        assertThat(ModelFactory.safeZipEntryName("a//b/../c.xml")).isEqualTo("a/c.xml");
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
