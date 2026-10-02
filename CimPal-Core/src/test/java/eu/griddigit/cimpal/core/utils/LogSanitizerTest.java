/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Log-injection defence shared by Core and the CLI (SEC-1 review, Low finding on serve logs). */
class LogSanitizerTest {

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r", "\u0085", " ", " ", "\u001b[31m", "\u0000", "\t"})
    void lineTerminatorsAndControlCharactersCannotForgeALogRecord(String unsafe) {
        String logged = LogSanitizer.forLog("/health" + unsafe + "[ERROR] serve: token accepted");

        assertThat(logged).doesNotContain(unsafe).contains("␞").startsWith("/health");
        assertThat(logged.lines()).hasSize(1);
    }

    @Test
    void plainTextIsUnchanged() {
        assertThat(LogSanitizer.forLog("POST /validate ümlaut")).isEqualTo("POST /validate ümlaut");
    }

    @Test
    void longValuesAreCut() {
        String logged = LogSanitizer.forLog("x".repeat(LogSanitizer.MAX_LENGTH + 10));

        assertThat(logged).hasSize(LogSanitizer.MAX_LENGTH + 3).endsWith("...");
    }

    @Test
    void nullIsLoggedAsNull() {
        assertThat(LogSanitizer.forLog(null)).isEqualTo("null");
    }
}
