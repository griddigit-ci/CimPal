/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.cimpal.core.utils.PathPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Default allowed roots must never be the whole home folder or a drive (SEC-2). */
class RootOptionsTest {

    @TempDir
    Path tempDir;

    private static final Path HOME = Path.of(System.getProperty("user.home"));

    @Test
    void homeFolderIsNotAnImplicitDefault() {
        assertThatThrownBy(() -> new RootOptions().policy(List.of(HOME)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--root");
    }

    @Test
    void driveRootIsNotAnImplicitDefault() {
        Path driveRoot = tempDir.toAbsolutePath().getRoot();

        assertThatThrownBy(() -> new RootOptions().policy(List.of(driveRoot)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void otherDefaultsSurviveWhenOneIsTheHomeFolder() throws Exception {
        PathPolicy policy = new RootOptions().policy(List.of(HOME, tempDir));

        assertThat(policy.writeRoots()).containsExactly(tempDir.toRealPath());
        assertThat(new RootOptions().base(List.of(HOME, tempDir))).isEqualTo(tempDir.toRealPath());
    }

    @Test
    void anExplicitRootMayBeTheHomeFolder() {
        RootOptions options = new RootOptions();
        options.roots.add(HOME);

        assertThat(options.policy(List.of(tempDir)).writeRoots()).hasSize(1);
    }
}
