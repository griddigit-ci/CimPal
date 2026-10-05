/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CLI version comes from the pom through resource filtering, so it can no longer drift from
 * the release tag (it was hard-coded as "2026.9" in three places before CI-3).
 */
class CliVersionTest {

    /** {@code ${project.version}}, passed in by the surefire configuration of the CLI pom. */
    static final String EXPECTED = System.getProperty("cimpal.expectedVersion");

    @Test
    void versionIsTheMavenProjectVersion() {
        assertThat(EXPECTED).as("system property cimpal.expectedVersion (set by surefire)").isNotBlank();
        assertThat(CliVersion.version()).isEqualTo(EXPECTED);
    }

    @Test
    void versionLineNamesTheCli() {
        assertThat(CliVersion.displayName()).isEqualTo("CimPal CLI " + EXPECTED);
        assertThat(new CliVersion().getVersion()).containsExactly("CimPal CLI " + EXPECTED);
    }
}
