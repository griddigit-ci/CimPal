/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

/**
 * One CSV cell escape for every CSV CimPal writes (finding 4 in SECURITY-SELF-ATTESTATION.md;
 * SEC-5 brought the CLI writers onto it).
 */
public final class CsvCells {

    private static final String FORMULA_TRIGGERS = "=+-@\t\r";

    private CsvCells() {
    }

    /**
     * Neutralises spreadsheet formulas, then applies RFC-4180 quoting. Excel and LibreOffice
     * evaluate a cell that begins with =, +, -, @, TAB or CR, and CSV values here are RDF
     * literals and IRIs from third-party models; a leading apostrophe forces literal
     * interpretation and is not itself displayed. The cell is quoted, with inner quotes doubled,
     * when it contains a comma, quote, line break, tab or semicolon. Null becomes empty.
     */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        String s = value;
        if (!s.isEmpty() && FORMULA_TRIGGERS.indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        boolean mustQuote = s.contains(",") || s.contains("\"") || s.contains("\n")
                || s.contains("\r") || s.contains("\t") || s.contains(";");
        if (mustQuote) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
