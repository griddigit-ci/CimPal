/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.testsupport.SourceScan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** The shared CSV cell escape (attestation finding 4; SEC-5 put the CLI writers on it). */
class CsvCellsTest {

    @ParameterizedTest
    @ValueSource(strings = {"=1+1", "+1", "-1", "@SUM(A1)", "\tcmd", "\rcmd"})
    void formulaTriggersAreNeutralised(String payload) {
        String escaped = CsvCells.escape(payload);
        String content = escaped.startsWith("\"") ? escaped.substring(1) : escaped;

        assertThat(content).startsWith("'");
    }

    @Test
    void rfc4180QuotingIsKept() {
        assertThat(CsvCells.escape("plain")).isEqualTo("plain");
        assertThat(CsvCells.escape("a,b")).isEqualTo("\"a,b\"");
        assertThat(CsvCells.escape("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CsvCells.escape(null)).isEmpty();
        assertThat(CsvCells.escape("=a,b")).isEqualTo("\"'=a,b\"");
    }

    @Test
    void everyCliCsvWriterUsesTheSharedEscape() {
        // Each CLI command keeps a private csvEscape; it must delegate, not quote by itself.
        assertThat(SourceScan.linesMatching("return \"\\\\\"\" \\+ s\\.replace\\(", "CimPal-CLI")).isEmpty();
        assertThat(SourceScan.linesMatching("CsvCells\\.escape\\(", "CimPal-CLI")).hasSize(3);
    }
}
