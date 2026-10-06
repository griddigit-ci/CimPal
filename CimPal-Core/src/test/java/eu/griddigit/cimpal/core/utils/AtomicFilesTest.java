/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code --summary-file} (DEP-3): a reader such as Airflow's XCom sidecar never sees half a file. */
class AtomicFilesTest {

    @TempDir
    Path tempDir;

    private long filesIn(Path dir) throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            return files.count();
        }
    }

    @Test
    void writesANewFileAndCreatesItsParentFolders() throws Exception {
        Path target = tempDir.resolve("airflow/xcom/return.json");

        AtomicFiles.writeString(target, "{\"hasViolations\":true}");

        assertThat(target).hasContent("{\"hasViolations\":true}");
        assertThat(filesIn(target.getParent())).as("no temp file left behind").isEqualTo(1);
    }

    @Test
    void replacesAnExistingFile() throws Exception {
        Path target = Files.writeString(tempDir.resolve("summary.json"), "old content that is longer");

        AtomicFiles.writeString(target, "new");

        assertThat(target).hasContent("new");
        assertThat(filesIn(tempDir)).isEqualTo(1);
    }

    @Test
    void writesUtf8() throws Exception {
        Path target = tempDir.resolve("summary.json");

        AtomicFiles.writeString(target, "{\"name\":\"Szeged–Pécs ✓\"}");

        assertThat(Files.readString(target)).isEqualTo("{\"name\":\"Szeged–Pécs ✓\"}");
    }

    @Test
    void aFailedMoveLeavesNoTempFileAndKeepsTheOldContent() throws Exception {
        // The target is a non-empty folder, so the move onto it fails.
        Path target = Files.createDirectories(tempDir.resolve("busy"));
        Files.writeString(target.resolve("inside.txt"), "x");

        assertThatThrownBy(() -> AtomicFiles.writeString(target, "content")).isInstanceOf(java.io.IOException.class);

        assertThat(filesIn(tempDir)).as("only the folder itself").isEqualTo(1);
        assertThat(target.resolve("inside.txt")).hasContent("x");
    }

    @Test
    void aFolderOrARootIsNotAFileToWrite() throws Exception {
        Path emptyFolder = Files.createDirectories(tempDir.resolve("empty"));

        assertThatThrownBy(() -> AtomicFiles.writeString(emptyFolder, "{}")).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> AtomicFiles.writeString(tempDir.getRoot(), "{}")).isInstanceOf(java.io.IOException.class);
        assertThat(emptyFolder).isEmptyDirectory();
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
    void theFileGetsTheUsualPermissionsNotThoseOfATempFile() throws Exception {
        Path target = tempDir.resolve("summary.json");

        AtomicFiles.writeString(target, "{}");

        // A temp file is created 0600; the summary must stay readable for e.g. a scheduler's worker.
        assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(target)))
                .isEqualTo("rw-r--r--");
    }

    @Test
    void anActivePolicyRefusesAPathOutsideItsRoots() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Path outside = tempDir.resolve("outside/summary.json");
        PathPolicy policy = PathPolicy.builder().root(root).build();

        assertThatThrownBy(() -> PathPolicy.runWith(policy, () -> {
            AtomicFiles.writeString(outside, "{}");
            return null;
        })).isInstanceOf(PathNotAllowedException.class);
        assertThat(outside.getParent()).doesNotExist();

        PathPolicy.runWith(policy, () -> {
            AtomicFiles.writeString(root.resolve("out/summary.json"), "{}");
            return null;
        });
        assertThat(root.resolve("out/summary.json")).hasContent("{}");
    }
}
