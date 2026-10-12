/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Extracting untrusted upload archives to disk (DEP-7): hostile archives are refused whole. */
class ZipExtractorTest {

    @TempDir
    Path tempDir;

    /** A zip of name/content pairs; names are written as given, however hostile. */
    private Path zip(String... namesAndContents) throws IOException {
        Path file = Files.createTempFile(tempDir, "upload", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                out.putNextEntry(new ZipEntry(namesAndContents[i]));
                out.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }

    private Path target() {
        return tempDir.resolve("uploads/u1");
    }

    private void assertRefused(Path zip, ZipExtractor.Limits limits, String messagePart) {
        IOException e = assertThrows(IOException.class, () -> ZipExtractor.extract(zip, target(), limits));
        assertTrue(e.getMessage().contains(messagePart), e.getMessage());
        assertFalse(Files.exists(target()), "the target folder is removed again");
    }

    @Test
    void aNormalArchiveIsExtractedWithItsFolders() throws Exception {
        Path zip = zip("models/eq.xml", "<rdf/>", "constraints/a.ttl", "# shapes", "mapping.csv", "a;b");

        List<ZipExtractor.Entry> entries = ZipExtractor.extract(zip, target(), ZipExtractor.Limits.defaults());

        assertEquals(List.of(new ZipExtractor.Entry("models/eq.xml", 6), new ZipExtractor.Entry("constraints/a.ttl", 8),
                new ZipExtractor.Entry("mapping.csv", 3)), entries);
        assertEquals("<rdf/>", Files.readString(target().resolve("models/eq.xml")));
        assertEquals("a;b", Files.readString(target().resolve("mapping.csv")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../x.xml", "a/../../x.xml", "..\\x.xml", "/abs.xml", "C:/x.xml", "C:x.xml",
            "\\\\server\\share\\x.xml", "x.xml:stream", "./", "a\u0007b.xml"})
    void namesThatLeaveTheFolderOrAreUnsafeAreRefused(String name) throws Exception {
        assertRefused(zip("ok.xml", "<ok/>", name, "evil"), ZipExtractor.Limits.defaults(), "Unsafe ZIP entry path");
    }

    @Test
    void anEncodedDotDotIsJustAName() throws Exception {
        // Nothing decodes entry names, so "%2e%2e" is a harmless folder name, kept inside the target.
        List<ZipExtractor.Entry> entries = ZipExtractor.extract(zip("%2e%2e/x.xml", "x"), target(),
                ZipExtractor.Limits.defaults());
        assertEquals("%2e%2e/x.xml", entries.getFirst().name());
        assertTrue(Files.isRegularFile(target().resolve("%2e%2e/x.xml")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CON", "nul.txt", "models/COM1.xml", "lpt9", "aux.ttl", "trailing.", "dir./x.xml",
            "blank ", " leading.xml"})
    void deviceNamesAndNamesWindowsWouldChangeAreRefused(String name) throws Exception {
        assertRefused(zip(name, "x"), ZipExtractor.Limits.defaults(), "Unsafe ZIP entry path");
    }

    @Test
    void namesDifferingOnlyInCaseAreRefused() throws Exception {
        assertRefused(zip("Model.xml", "a", "model.XML", "b"), ZipExtractor.Limits.defaults(), "same name twice");
    }

    @Test
    void tooManyEntriesAreRefusedBeforeAnythingIsWritten() throws Exception {
        String[] pairs = new String[2 * 11];
        for (int i = 0; i < 11; i++) {
            pairs[2 * i] = "f" + i + ".txt";
            pairs[2 * i + 1] = "x";
        }
        assertRefused(zip(pairs), new ZipExtractor.Limits(10, 1 << 20, 1 << 20, 100, 1 << 20), "entry limit");
    }

    @Test
    void theTotalAndPerEntrySizesAreCountedWhileReading() throws Exception {
        String kb = "x".repeat(1024);
        assertRefused(zip("a.txt", kb, "b.txt", kb, "c.txt", kb), new ZipExtractor.Limits(100, 2500, 2000, 1000, 1 << 20),
                "total uncompressed size");
        assertRefused(zip("big.txt", kb + kb), new ZipExtractor.Limits(100, 10_000, 1500, 1000, 1 << 20),
                "size limit");
    }

    @Test
    void anEntryThatExpandsTooMuchIsRefused() throws Exception {
        // 8 MiB of zeros compress to a few KiB: far beyond 100:1.
        Path zip = Files.createTempFile(tempDir, "bomb", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("zeros.bin"));
            out.write(new byte[8 << 20]);
            out.closeEntry();
        }
        assertRefused(zip, ZipExtractor.Limits.defaults(), "expands more than 100 times");
    }

    @Test
    void aStoredEntryOfTheSameSizeIsFine() throws Exception {
        // STORED: compressed size equals size, ratio 1.
        byte[] content = new byte[2 << 20];
        Path zip = Files.createTempFile(tempDir, "stored", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            ZipEntry entry = new ZipEntry("stored.bin");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(content.length);
            entry.setCompressedSize(content.length);
            CRC32 crc = new CRC32();
            crc.update(content);
            entry.setCrc(crc.getValue());
            out.putNextEntry(entry);
            out.write(content);
            out.closeEntry();
        }
        assertEquals(content.length, ZipExtractor.extract(zip, target(), ZipExtractor.Limits.defaults()).getFirst().size());
    }

    @Test
    void notAZipOrATruncatedZipIsRefused() throws Exception {
        Path text = Files.writeString(tempDir.resolve("not.zip"), "hello");
        assertRefused(text, ZipExtractor.Limits.defaults(), "");

        Path good = zip("a.txt", "x".repeat(5000));
        byte[] all = Files.readAllBytes(good);
        Path truncated = Files.write(tempDir.resolve("truncated.zip"), java.util.Arrays.copyOf(all, all.length / 2));
        assertRefused(truncated, ZipExtractor.Limits.defaults(), "");
    }

    @Test
    void anEmptyArchiveGivesAnEmptyFolder() throws Exception {
        assertEquals(List.of(), ZipExtractor.extract(zip(), target(), ZipExtractor.Limits.defaults()));
        assertTrue(Files.isDirectory(target()));
    }

    @Test
    void aNestedArchiveIsStoredAsAFileNotExpanded() throws Exception {
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(inner)) {
            out.putNextEntry(new ZipEntry("../escape.xml"));
            out.write(1);
            out.closeEntry();
        }
        Path zip = Files.createTempFile(tempDir, "outer", ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("igm.zip"));
            out.write(inner.toByteArray());
            out.closeEntry();
        }

        ZipExtractor.extract(zip, target(), ZipExtractor.Limits.defaults());

        assertArrayEquals(inner.toByteArray(), Files.readAllBytes(target().resolve("igm.zip")));
        assertFalse(Files.exists(tempDir.resolve("uploads/escape.xml")));
    }

    @Test
    void anExistingTargetIsNeverReused() throws Exception {
        Files.createDirectories(target());
        Files.writeString(target().resolve("other-upload.xml"), "keep");
        IOException e = assertThrows(IOException.class,
                () -> ZipExtractor.extract(zip("a.txt", "x"), target(), ZipExtractor.Limits.defaults()));
        assertTrue(e.getMessage().contains("already exists"));
        assertEquals("keep", Files.readString(target().resolve("other-upload.xml")));
    }

    @Test
    void aUnixLinkEntryIsWrittenAsAPlainFile() throws Exception {
        // Mark the entry as a symbolic link (mode 0120777 in the external attributes), as Info-ZIP
        // does for "zip -y"; its content is the link target.
        Path zip = zip("link", "/etc/passwd");
        byte[] bytes = Files.readAllBytes(zip);
        for (int i = 0; i + 42 <= bytes.length; i++) {
            if (bytes[i] == 0x50 && bytes[i + 1] == 0x4b && bytes[i + 2] == 0x01 && bytes[i + 3] == 0x02) {
                bytes[i + 5] = 3; // made by: Unix
                int attrs = 0120777 << 16;
                bytes[i + 38] = (byte) attrs;
                bytes[i + 39] = (byte) (attrs >> 8);
                bytes[i + 40] = (byte) (attrs >> 16);
                bytes[i + 41] = (byte) (attrs >> 24);
            }
        }
        Files.write(zip, bytes);

        ZipExtractor.extract(zip, target(), ZipExtractor.Limits.defaults());

        Path written = target().resolve("link");
        assertFalse(Files.isSymbolicLink(written));
        assertTrue(Files.isRegularFile(written, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertEquals("/etc/passwd", Files.readString(written));
    }

    @Test
    void namesTooDeepOrTooLongAreRefused() throws Exception {
        assertRefused(zip("a/".repeat(ZipExtractor.MAX_SEGMENTS) + "x.txt", "x"), ZipExtractor.Limits.defaults(),
                "too long or too deep");
        assertRefused(zip("x".repeat(ZipExtractor.MAX_NAME_CHARS + 1), "x"), ZipExtractor.Limits.defaults(),
                "too long or too deep");
    }

    @Test
    void emptyFilesAndFoldersAreNotFree() throws Exception {
        // 11 empty files cost 11 x 4 KiB, over a 40 KB budget.
        String[] empties = new String[22];
        for (int i = 0; i < 11; i++) {
            empties[2 * i] = "e" + i;
            empties[2 * i + 1] = "";
        }
        assertRefused(zip(empties), new ZipExtractor.Limits(100, 40_000, 40_000, 100, 1 << 20), "total uncompressed size");
        // Ten folders and one file are eleven entries.
        assertRefused(zip("a/b/c/d/e/f/g/h/i/j/k.txt", "x"), new ZipExtractor.Limits(10, 1 << 20, 1 << 20, 100, 1 << 20),
                "entry limit");
        ZipExtractor.Result ok = ZipExtractor.extractCharged(zip("a/b.txt", "xyz"), target(), ZipExtractor.Limits.defaults());
        assertEquals(3 + 2 * ZipExtractor.ENTRY_OVERHEAD_BYTES, ok.chargedBytes());
    }

    @Test
    void aForgedEndRecordIsRefusedBeforeTheCentralDirectoryIsRead() throws Exception {
        Path zip = zip("a.txt", "x");
        byte[] bytes = Files.readAllBytes(zip);
        int eocd = bytes.length - 22;
        assertEquals(0x06054b50, java.nio.ByteBuffer.wrap(bytes, eocd, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt());

        byte[] manyEntries = bytes.clone();
        manyEntries[eocd + 10] = (byte) 0xff;
        manyEntries[eocd + 11] = (byte) 0xff; // 65535 entries
        assertRefused(Files.write(tempDir.resolve("many.zip"), manyEntries), ZipExtractor.Limits.defaults(), "entry limit");

        byte[] hugeDirectory = bytes.clone();
        hugeDirectory[eocd + 12] = (byte) 0xf0;
        hugeDirectory[eocd + 13] = (byte) 0xff;
        hugeDirectory[eocd + 14] = (byte) 0xff;
        hugeDirectory[eocd + 15] = (byte) 0x7f; // ~2 GiB central directory
        assertRefused(Files.write(tempDir.resolve("cen.zip"), hugeDirectory), ZipExtractor.Limits.defaults(),
                "central directory is too large");
    }

    @Test
    void errorsNameTheEntryNeverTheServerFolder() throws Exception {
        IOException e = assertThrows(IOException.class,
                () -> ZipExtractor.extract(zip("a", "1", "a/b", "2"), target(), ZipExtractor.Limits.defaults()));
        assertTrue(e.getMessage().contains("a/b"), e.getMessage());
        assertFalse(e.getMessage().contains(tempDir.toString()), e.getMessage());
        assertFalse(e.getMessage().contains(tempDir.toRealPath().toString()), e.getMessage());
        assertFalse(Files.exists(target()));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void deleteTreeRemovesAJunctionWithoutEnteringIt() throws Exception {
        Path keep = Files.writeString(Files.createDirectories(tempDir.resolve("keep")).resolve("k.txt"), "k");
        Path dir = Files.createDirectories(tempDir.resolve("gone"));
        Process mklink = new ProcessBuilder(List.of("cmd", "/c", "mklink", "/J",
                dir.resolve("junction").toString(), keep.getParent().toString())).redirectErrorStream(true).start();
        mklink.getInputStream().readAllBytes();
        org.junit.jupiter.api.Assumptions.assumeTrue(mklink.waitFor() == 0, "mklink /J not available");

        ZipExtractor.deleteTree(dir);

        assertFalse(Files.exists(dir));
        assertEquals("k", Files.readString(keep));
    }

    @Test
    void writingNeverReplacesAnExistingFile() throws Exception {
        // Two spellings of one name (ZipOutputStream refuses an exact duplicate, a hostile tool
        // doesn't) are caught before the second write could replace the first file.
        assertRefused(zip("a.txt", "1", "./a.txt", "2"), ZipExtractor.Limits.defaults(), "same name twice");
        assertRefused(zip("d/a.txt", "1", "d//a.txt", "2"), ZipExtractor.Limits.defaults(), "same name twice");
    }

    @Test
    void deleteTreeDoesNotFollowLinks() throws Exception {
        Path keep = Files.writeString(Files.createDirectories(tempDir.resolve("keep")).resolve("k.txt"), "k");
        Path dir = Files.createDirectories(tempDir.resolve("gone"));
        Files.writeString(dir.resolve("f.txt"), "f");
        try {
            Files.createSymbolicLink(dir.resolve("link"), keep.getParent());
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            // no symlink rights (Windows without developer mode): the rest still applies
        }
        ZipExtractor.deleteTree(dir);
        assertFalse(Files.exists(dir));
        assertEquals("k", Files.readString(keep));
    }

    @SuppressWarnings("unused")
    private static void write(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
    }
}
