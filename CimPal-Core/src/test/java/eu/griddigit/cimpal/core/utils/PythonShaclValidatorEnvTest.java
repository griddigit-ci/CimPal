/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The Python validator's child process doesn't inherit serve's secrets (DEP-11 review). */
class PythonShaclValidatorEnvTest {

    @Test
    void secretsAreLeftOutOfTheChildsEnvironment() {
        ProcessBuilder builder = new ProcessBuilder("python");
        builder.environment().put("CIMPAL_API_TOKEN", "t");
        builder.environment().put("CIMPAL_JOB_STORE_PASSWORD", "p");
        builder.environment().put("GITHUB_TOKEN", "g");
        builder.environment().put("CIMPAL_JOB_STORE_URL", "jdbc:postgresql://db/c");

        PythonShaclValidator.withoutSecrets(builder);

        assertThat(builder.environment()).doesNotContainKeys("CIMPAL_API_TOKEN", "CIMPAL_JOB_STORE_PASSWORD",
                "GITHUB_TOKEN").containsKey("CIMPAL_JOB_STORE_URL");
    }
}
