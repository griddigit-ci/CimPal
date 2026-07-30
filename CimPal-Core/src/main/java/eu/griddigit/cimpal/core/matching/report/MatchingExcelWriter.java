/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.report;

import eu.griddigit.cimpal.core.matching.model.MatchingReport;
import eu.griddigit.cimpal.core.matching.model.MatchingReport.MatchedEntry;
import eu.griddigit.cimpal.core.matching.model.MatchingReport.StatEntry;
import eu.griddigit.cimpal.core.matching.model.MatchingReport.UnmatchedEntry;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes a {@link MatchingReport} to a two-sheet .xlsx workbook:
 * <ul>
 *   <li><b>Matched</b> - the four deliverable columns
 *       (PF_ID, Element_type, New_ID, New_2nd_name).</li>
 *   <li><b>Unmatched</b> - the residue for human processing.</li>
 * </ul>
 * Header styling follows CimPal's house convention (bold, sky-blue, framed).
 */
public final class MatchingExcelWriter {

    private static final String[] MATCHED_HEADER = {"Source_ID(IGMG)", "Element_type", "Matched_ID", "Matched_name", "Note"};
    private static final String[] UNMATCHED_HEADER = {"ID", "Element_type", "Name", "Side", "Reason"};
    private static final String[] STATISTICS_HEADER = {"Metric", "Value"};
    private static final String[] SUBDIAG_HEADER = {
            "Source_ID(IGMG)", "Source_name", "Connections", "Connections_by_voltage", "Transformers",
            "Status", "Match_method", "Matched_ID", "Matched_name", "Matched_connections", "Candidates"};

    private MatchingExcelWriter() {
    }

    public static void write(MatchingReport report, Path output) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            CellStyle header = createHeaderStyle(wb);

            Sheet matched = newSheet(wb, "Matched", header, MATCHED_HEADER);
            int r = 1;
            for (MatchedEntry e : report.matched()) {
                Row row = matched.createRow(r++);
                row.createCell(0).setCellValue(safe(e.sourceId()));
                row.createCell(1).setCellValue(safe(e.elementType()));
                row.createCell(2).setCellValue(safe(e.matchedId()));
                row.createCell(3).setCellValue(safe(e.matchedName()));
                row.createCell(4).setCellValue(safe(e.note()));
            }
            autosize(matched, MATCHED_HEADER.length);

            Sheet unmatched = newSheet(wb, "Unmatched", header, UNMATCHED_HEADER);
            r = 1;
            for (UnmatchedEntry e : report.unmatched()) {
                Row row = unmatched.createRow(r++);
                row.createCell(0).setCellValue(safe(e.id()));
                row.createCell(1).setCellValue(safe(e.elementType()));
                row.createCell(2).setCellValue(safe(e.name()));
                row.createCell(3).setCellValue(safe(e.side()));
                row.createCell(4).setCellValue(safe(e.reason()));
            }
            autosize(unmatched, UNMATCHED_HEADER.length);

            Sheet stats = newSheet(wb, "Statistics", header, STATISTICS_HEADER);
            r = 1;
            for (StatEntry e : report.statistics()) {
                Row row = stats.createRow(r++);
                row.createCell(0).setCellValue(safe(e.metric()));
                row.createCell(1).setCellValue(safe(e.value()));
            }
            autosize(stats, STATISTICS_HEADER.length);

            Sheet diag = newSheet(wb, "Substation_diagnostics", header, SUBDIAG_HEADER);
            r = 1;
            for (MatchingReport.SubDiagRow d : report.substationDiagnostics()) {
                Row row = diag.createRow(r++);
                row.createCell(0).setCellValue(safe(d.sourceId()));
                row.createCell(1).setCellValue(safe(d.sourceName()));
                row.createCell(2).setCellValue(d.connections());
                row.createCell(3).setCellValue(safe(d.connectionsByVoltage()));
                row.createCell(4).setCellValue(safe(d.transformers()));
                row.createCell(5).setCellValue(safe(d.status()));
                row.createCell(6).setCellValue(safe(d.method()));
                row.createCell(7).setCellValue(safe(d.matchedId()));
                row.createCell(8).setCellValue(safe(d.matchedName()));
                row.createCell(9).setCellValue(safe(d.matchedConnections()));
                row.createCell(10).setCellValue(d.candidateCount());
            }
            autosize(diag, SUBDIAG_HEADER.length);

            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            try (OutputStream os = Files.newOutputStream(output)) {
                wb.write(os);
            }
        }
    }

    private static Sheet newSheet(Workbook wb, String name, CellStyle header, String[] cols) {
        Sheet s = wb.createSheet(name);
        Row h = s.createRow(0);
        for (int i = 0; i < cols.length; i++) {
            Cell c = h.createCell(i);
            c.setCellValue(cols[i]);
            c.setCellStyle(header);
        }
        s.setAutoFilter(new CellRangeAddress(0, 0, 0, cols.length - 1));
        s.createFreezePane(0, 1);
        return s;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);
        style.setFillForegroundColor(IndexedColors.SKY_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private static void autosize(Sheet sheet, int columns) {
        for (int i = 0; i < columns; i++) {
            sheet.autoSizeColumn(i);
            int width = sheet.getColumnWidth(i);
            int cap = 80 * 256;
            if (width > cap) sheet.setColumnWidth(i, cap);
        }
    }
}
