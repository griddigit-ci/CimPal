/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.diffexport;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Serializes {@link ComparisonOperationRow}s as a patch-oriented CSV with the columns
 * {@code Item,Class,Property,Operation,Value}.
 * <p>
 * A one-sided difference is a single row ({@code add} carries the Model B value, {@code remove} the
 * Model A value); an {@link Operation#UPDATE} is split into two rows — an {@code add} for the new
 * value and a {@code remove} for the old value — so the CSV reads as an apply/undo patch and maps
 * cleanly onto added/removed RDF statements.
 * <p>
 * Manual RFC-4180 escaping (mirrors {@code ExportSHACLInformation.csvEscape}); no CSV library is
 * present in the project and none is needed.
 */
public class ComparisonCsvWriter {

    private static final String HEADER = "Item,Class,Property,Operation,Value";

    /** Renders the operation rows as CSV text. */
    public String toCsv(List<ComparisonOperationRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append('\n');
        for (ComparisonOperationRow row : rows) {
            switch (row.getOperation()) {
                case ADD -> appendLine(sb, row, Operation.ADD.label(), row.getValueB());
                case REMOVE -> appendLine(sb, row, Operation.REMOVE.label(), row.getValueA());
                case UPDATE -> {
                    // a changed value becomes an add (new) plus a remove (old)
                    appendLine(sb, row, Operation.ADD.label(), row.getValueB());
                    appendLine(sb, row, Operation.REMOVE.label(), row.getValueA());
                }
            }
        }
        return sb.toString();
    }

    /** Writes the CSV text (UTF-8) to the given stream. The stream is not closed. */
    public void write(OutputStream outputStream, List<ComparisonOperationRow> rows) throws IOException {
        Writer writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
        writer.write(toCsv(rows));
        writer.flush();
    }

    private static void appendLine(StringBuilder sb, ComparisonOperationRow row, String operation, String value) {
        sb.append(csvEscape(row.getItem())).append(',')
                .append(csvEscape(row.getRdfType())).append(',')
                .append(csvEscape(row.getProperty())).append(',')
                .append(csvEscape(operation)).append(',')
                .append(csvEscape(value)).append('\n');
    }

    /** Wraps a field in quotes (doubling internal quotes) when it contains a CSV-significant char. */
    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        String s = value;
        boolean mustQuote = s.contains(",") || s.contains("\"") || s.contains("\n")
                || s.contains("\r") || s.contains("\t") || s.contains(";");
        if (mustQuote) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
