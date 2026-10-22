/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The configured Python interpreter's path check (SEC-3, gap G9, security review 2). */
class PythonExecutableTest {

    /**
     * A UNC or device path is refused before any file system call, so checking it contacts no
     * network share. The host names use the reserved {@code .invalid} domain.
     */
    @ParameterizedTest
    @ValueSource(strings = {"\\\\cimpal-test.invalid\\share\\python.exe", "//cimpal-test.invalid/share/p.exe",
            "\\/cimpal-test.invalid/s/p.exe", "\\\\?\\C:\\p.exe", "\\\\.\\pipe\\p.exe"})
    void uncAndDevicePathsAreRefused(String value) {
        assertThatThrownBy(() -> PythonExecutable.check(value, "--python-executable"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNC (network) and device paths are refused");
    }

    /**
     * The path that is checked is the one that is returned. {@code dir/link/../python} with
     * {@code link -> other/sub} names {@code other/python} for the file system but {@code dir/python}
     * lexically; the check used to look at the first and return the second.
     */
    @Test
    void theCheckedPathIsTheReturnedPath(@TempDir Path tempDir) throws IOException {
        Path sub = Files.createDirectories(tempDir.resolve("other/sub"));
        Files.writeString(tempDir.resolve("other/python"), "");
        Path dir = Files.createDirectories(tempDir.resolve("dir"));
        try {
            Files.createSymbolicLink(dir.resolve("link"), sub);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("symbolic links can't be created here: " + e.getMessage());
        }
        String value = dir + "/link/../python";

        assertThatThrownBy(() -> PythonExecutable.check(value, "--python-executable"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not exist");

        Files.writeString(dir.resolve("python"), "");
        assertThat(PythonExecutable.check(value, "--python-executable")).isEqualTo(dir.resolve("python"));
    }
}
