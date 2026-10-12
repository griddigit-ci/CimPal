/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts an untrusted ZIP archive into a new folder (DEP-7: uploads to {@code serve}).
 *
 * <p>Every entry must stay inside the folder and is refused otherwise, so the whole extraction
 * fails and the folder is removed:
 * <ul>
 *   <li><b>Names:</b> {@link ModelFactory#safeZipEntryName} refuses absolute names, drive letters,
 *       streams, control characters and {@code ..} that climbs out. This class also refuses
 *       reserved Windows device names ({@code CON}, {@code nul.txt}), segments ending in a dot or
 *       a blank (Windows would silently rename them), names that differ only in case, and names
 *       longer than {@value #MAX_NAME_CHARS} characters or {@value #MAX_SEGMENTS} folders deep.</li>
 *   <li><b>Writing:</b> each file is created new ({@code CREATE_NEW}, no link followed) after
 *       the real path of its folder is checked to lie inside the target folder. Link entries
 *       (Unix mode bits) are written as plain files holding the link text.</li>
 *   <li><b>Size:</b> before {@link ZipFile} reads the central directory, its size and entry count
 *       are taken from the end record and refused when too large. Then the
 *       {@link ModelFactory.ZipBudget} limits are counted while reading; every file and every
 *       folder created counts as an entry and costs {@value #ENTRY_OVERHEAD_BYTES} bytes
 *       towards the total, so an archive of empty files or deep folders isn't free; and an entry
 *       may expand at most {@link Limits#maxRatio} times its compressed size once it is larger
 *       than {@link Limits#ratioThresholdBytes}.</li>
 * </ul>
 * Nested archives are written as files, never expanded. Error messages name entries, never the
 * folder on disk.
 */
public final class ZipExtractor {

    /** Folders an entry name may have, and its length. */
    static final int MAX_SEGMENTS = 32;
    static final int MAX_NAME_CHARS = 1024;
    /** What each created file or folder costs against the size limit (a block, an inode). */
    static final long ENTRY_OVERHEAD_BYTES = 4096;
    /** Largest central directory read at all: about 1,000 bytes per entry at the default limit. */
    static final long MAX_CENTRAL_DIRECTORY_BYTES = 16L << 20;

    /** Limits for one extraction. */
    public record Limits(int maxEntries, long maxTotalBytes, long maxEntryBytes, long maxRatio,
                         long ratioThresholdBytes) {

        /** 10,000 entries, 2 GiB in all, 1 GiB per entry, 100:1 above 1 MiB. */
        public static Limits defaults() {
            return new Limits(10_000, 2L << 30, 1L << 30, 100, 1L << 20);
        }

        public Limits withMaxTotalBytes(long bytes) {
            return new Limits(maxEntries, bytes, Math.min(maxEntryBytes, bytes), maxRatio, ratioThresholdBytes);
        }
    }

    /** One extracted file: its name inside the folder ({@code /}-separated) and its size. */
    public record Entry(String name, long size) {
    }

    /** The files written and what they cost against the limits (bytes plus per-entry overhead). */
    public record Result(List<Entry> files, long chargedBytes) {
    }

    private ZipExtractor() {
    }

    /** {@link #extractCharged} without the cost. */
    public static List<Entry> extract(Path zip, Path targetDir, Limits limits) throws IOException {
        return extractCharged(zip, targetDir, limits).files();
    }

    /**
     * Extracts {@code zip} into {@code targetDir}, which must not exist yet.
     *
     * @return the files written, in archive order, and their cost
     * @throws IOException for an unreadable or unsafe archive, or a limit hit; the message names
     *                     entries but never the folder on disk, and {@code targetDir} is removed again
     */
    public static Result extractCharged(Path zip, Path targetDir, Limits limits) throws IOException {
        precheck(zip, limits);
        if (Files.exists(targetDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Target folder already exists");
        }
        Files.createDirectories(targetDir.getParent());
        Files.createDirectory(targetDir);
        try {
            return extractInto(zip, targetDir.toRealPath(), limits);
        } catch (IOException | RuntimeException e) {
            deleteTree(targetDir);
            if (e instanceof IOException io && !(e instanceof FileSystemException)) {
                throw io;
            }
            throw new IOException("Not a readable ZIP archive");
        }
    }

    /**
     * Reads the end-of-central-directory record (and the ZIP64 one) from the end of the file and
     * refuses an archive that declares more entries than allowed or a central directory larger
     * than {@value #MAX_CENTRAL_DIRECTORY_BYTES} bytes, before {@link ZipFile} loads it into memory.
     */
    static void precheck(Path zip, Limits limits) throws IOException {
        long size = Files.size(zip);
        int tail = (int) Math.min(size, 65_535 + 22 + 20);
        ByteBuffer buffer = ByteBuffer.allocate(tail).order(ByteOrder.LITTLE_ENDIAN);
        try (SeekableByteChannel channel = Files.newByteChannel(zip)) {
            channel.position(size - tail);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // read the tail
            }
        }
        byte[] bytes = buffer.array();
        int eocd = -1;
        for (int i = tail - 22; i >= 0; i--) {
            if (bytes[i] == 0x50 && bytes[i + 1] == 0x4b && bytes[i + 2] == 0x05 && bytes[i + 3] == 0x06) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) {
            throw new IOException("Not a ZIP archive (no end of central directory)");
        }
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long entries = Short.toUnsignedInt(b.getShort(eocd + 10));
        long cenSize = Integer.toUnsignedLong(b.getInt(eocd + 12));
        int locator = eocd - 20;
        if (locator >= 0 && b.getInt(locator) == 0x07064b50) {
            long zip64Eocd = b.getLong(locator + 8);
            if (zip64Eocd < 0 || zip64Eocd > size - 56) {
                throw new IOException("Not a readable ZIP archive (bad ZIP64 record)");
            }
            ByteBuffer z = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);
            try (SeekableByteChannel channel = Files.newByteChannel(zip)) {
                channel.position(zip64Eocd);
                while (z.hasRemaining() && channel.read(z) >= 0) {
                    // read the record
                }
            }
            if (z.getInt(0) == 0x06064b50) {
                entries = Math.max(entries, z.getLong(32));
                cenSize = Math.max(cenSize, z.getLong(40));
            }
        }
        if (entries < 0 || entries > limits.maxEntries()) {
            throw new IOException("Archive exceeds the entry limit (" + limits.maxEntries() + ")");
        }
        if (cenSize < 0 || cenSize > MAX_CENTRAL_DIRECTORY_BYTES) {
            throw new IOException("Archive central directory is too large");
        }
    }

    private static Result extractInto(Path zip, Path root, Limits limits) throws IOException {
        ModelFactory.ZipBudget budget = new ModelFactory.ZipBudget(limits.maxEntries(), limits.maxTotalBytes(),
                limits.maxEntryBytes());
        List<Entry> written = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> folders = new HashSet<>();
        long charged = 0;
        byte[] buffer = new byte[64 * 1024];
        ZipFile opened;
        try {
            opened = new ZipFile(zip.toFile());
        } catch (IOException e) {
            throw new IOException("Not a readable ZIP archive"); // the JDK's message may name the file
        }
        try (ZipFile zipFile = opened) {
            if (zipFile.size() > limits.maxEntries()) {
                throw new IOException("Archive exceeds the entry limit (" + limits.maxEntries() + ")");
            }
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = checkedName(entry.getName());
                if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                    throw new IOException("Archive has the same name twice (ignoring case): "
                            + LogSanitizer.forLog(entry.getName()));
                }
                // Every folder the entry needs counts as an entry of its own, once.
                String[] segments = name.split("/");
                int folderCount = entry.isDirectory() ? segments.length : segments.length - 1;
                StringBuilder prefix = new StringBuilder();
                for (int i = 0; i < folderCount; i++) {
                    prefix.append(segments[i].toLowerCase(Locale.ROOT)).append('/');
                    if (folders.add(prefix.toString())) {
                        budget.enterEntry();
                        charged = charge(charged, ENTRY_OVERHEAD_BYTES, limits);
                    }
                }
                Path target = inside(root, name);
                if (entry.isDirectory()) {
                    write(() -> Files.createDirectories(target), name);
                    insideReal(root, target, name);
                    continue;
                }
                budget.enterEntry();
                charged = charge(charged, ENTRY_OVERHEAD_BYTES, limits);
                write(() -> Files.createDirectories(target.getParent()), name);
                insideReal(root, target.getParent(), name);
                long size;
                try (InputStream raw = zipFile.getInputStream(entry)) {
                    size = copy(entry, budget.limit(raw), target, limits, buffer, name);
                }
                charged = charge(charged, size, limits);
                written.add(new Entry(name, size));
            }
        }
        return new Result(List.copyOf(written), charged);
    }

    private static long charge(long charged, long more, Limits limits) throws IOException {
        long total = charged + more;
        if (total > limits.maxTotalBytes()) {
            throw new IOException("Archive exceeds the total uncompressed size limit (" + limits.maxTotalBytes()
                    + " bytes, counting " + ENTRY_OVERHEAD_BYTES + " bytes per file and folder)");
        }
        return total;
    }

    @FunctionalInterface
    private interface FileOp {
        void run() throws IOException;
    }

    /** A file system failure, reported with the entry's name instead of the server path. */
    private static void write(FileOp op, String name) throws IOException {
        try {
            op.run();
        } catch (FileSystemException e) {
            throw new IOException("Cannot write ZIP entry " + LogSanitizer.forLog(name)
                    + " (a file and a folder of that name, or a name the file system refuses)");
        }
    }

    private static long copy(ZipEntry entry, InputStream in, Path target, Limits limits, byte[] buffer, String name)
            throws IOException {
        long compressed = entry.getCompressedSize();
        long total = 0;
        OutputStream out;
        try {
            out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (FileSystemException e) {
            throw new IOException("Cannot write ZIP entry " + LogSanitizer.forLog(name)
                    + " (a file and a folder of that name, or a name the file system refuses)");
        }
        try (out) {
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                total += n;
                if (total > limits.ratioThresholdBytes()
                        && (compressed <= 0 || total > compressed * limits.maxRatio())) {
                    throw new IOException("Archive entry expands more than " + limits.maxRatio()
                            + " times its compressed size: " + LogSanitizer.forLog(name));
                }
                out.write(buffer, 0, n);
            }
        }
        return total;
    }

    /** The safe form of an entry name, or an exception naming the refused name. */
    private static String checkedName(String raw) throws IOException {
        String name = ModelFactory.safeZipEntryName(raw);
        String[] segments = name.split("/");
        if (name.length() > MAX_NAME_CHARS || segments.length > MAX_SEGMENTS) {
            throw new IOException("ZIP entry name too long or too deep (at most " + MAX_NAME_CHARS
                    + " characters and " + MAX_SEGMENTS + " levels): " + LogSanitizer.forLog(raw));
        }
        for (String segment : segments) {
            if (PathPolicy.isReservedName(segment) || segment.endsWith(".") || segment.endsWith(" ")
                    || segment.startsWith(" ")) {
                throw new IOException("Unsafe ZIP entry path: " + LogSanitizer.forLog(raw));
            }
        }
        return name;
    }

    private static Path inside(Path root, String name) throws IOException {
        Path target;
        try {
            target = root.resolve(name).normalize();
        } catch (InvalidPathException e) {
            throw new IOException("Unsafe ZIP entry path: " + LogSanitizer.forLog(name));
        }
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IOException("Unsafe ZIP entry path: " + LogSanitizer.forLog(name));
        }
        return target;
    }

    private static void insideReal(Path root, Path folder, String name) throws IOException {
        if (!folder.toRealPath().startsWith(root)) {
            throw new IOException("ZIP entry would leave the target folder: " + LogSanitizer.forLog(name));
        }
    }

    /**
     * Deletes {@code dir} and everything below it. Links are removed, never followed; so are
     * Windows junctions and other reparse points, which a plain directory walk would enter.
     * Best effort.
     */
    public static void deleteTree(Path dir) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isOther() || attrs.isSymbolicLink()) {
                        Files.deleteIfExists(d); // the junction itself, not its target
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.deleteIfExists(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // best effort: the caller reports the original failure
        }
    }
}
