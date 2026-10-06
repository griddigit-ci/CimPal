/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Writes a file so that a reader sees either the old content or the new, never a partial file
 * (DEP-3, {@code --summary-file}: Airflow's XCom sidecar may read it as soon as it appears).
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    /**
     * Writes {@code content} (UTF-8) to {@code target}: to a temp file in the same folder first,
     * then moved into place atomically where the file system supports it, else replacing the
     * target. Creates missing parent folders. When a {@link PathPolicy} is active (serve, mcp,
     * run), the target must lie under its write roots, and the temp file is checked again before
     * the move, so a parent folder swapped for a link in the meantime can't redirect the write.
     *
     * @throws PathNotAllowedException when the active policy refuses the target
     * @throws IOException             when the file can't be written, or the target is a folder or
     *                                 a root; no temp file is left behind
     */
    public static void writeString(Path target, String content) throws IOException {
        Path checked = PathPolicy.checkWriteIfActive(target).toAbsolutePath().normalize();
        Path folder = checked.getParent();
        if (folder == null || checked.getFileName() == null) {
            throw new IOException("Not a file path: " + LogSanitizer.forLog(target.toString()));
        }
        if (Files.isDirectory(checked)) {
            throw new IOException("Is a folder: " + LogSanitizer.forLog(target.toString()));
        }
        Files.createDirectories(folder);
        Path temp = Files.createTempFile(folder, "." + checked.getFileName(), ".tmp");
        try {
            PathPolicy.checkWriteIfActive(temp);
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                // createTempFile makes it owner-only; the summary is an ordinary output file.
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-r--r--"));
            }
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, checked, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, checked, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
