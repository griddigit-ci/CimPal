// ValidationExcelWriter.java
package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.shacl.ValidationReport;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ValidationExcelWriter implements Closeable {

    public enum CaseFolder {
        CGMES_SINGLE_PROFILE,
        CGMES_IGM_COMPLETE,
        CGMES_CGM,
        NC_SINGLE,
        UNKNOWN
    }

    // Valimate-style header
    private static final String[] HEADER = new String[]{
            "Focus node", "Path", "Value", "Source", "Constraint Component", "Details",
            "Message", "Severity", "Description", "Order", "Name", "Group"
    };

    private final Workbook wb;
    private final Map<CaseFolder, Sheet> sheetByCase = new EnumMap<>(CaseFolder.class);
    private final Map<CaseFolder, Integer> nextRowByCase = new EnumMap<>(CaseFolder.class);

    public ValidationExcelWriter() {
        this.wb = new XSSFWorkbook();
        for (CaseFolder cf : CaseFolder.values()) {
            Sheet s = wb.createSheet(cf.name());
            sheetByCase.put(cf, s);
            nextRowByCase.put(cf, 0);
            // No global header/title rows — MicroGrid format is per-dataset blocks
        }
    }

    /**
     * Valimate-style block writer:
     *  Row A: "ModelName TTLName #All Violations Warnings> Infos>" (+ error count in col 3)
     *  Row B: "# <violations> <warnings> <infos>"
     *  Row C: fixed header columns
     *  Row D..: one row per SHACL result
     *
     * datasetName + ttlName are stored in "Name" and "Group" columns on the counts row
     * so you can identify the block easily.
     */
    public void appendExcelBlock(CaseFolder cf,
                                 String datasetName,
                                 String ttlName,
                                 List<SHACLValidationResult> results) {

        Sheet s = sheetByCase.get(cf);
        int r = nextRowByCase.get(cf);

        int vio = 0, warn = 0, info = 0;

        if (results != null) {
            for (SHACLValidationResult res : results) {
                String sev = safe(res.getSeverity()).toLowerCase(Locale.ROOT);
                if (sev.contains("violation")) vio++;
                else if (sev.contains("warning")) warn++;
                else info++;
            }
        }

        // Row A: dataset + ttl + labels
        Row first = s.createRow(r++);
        first.createCell(0).setCellValue(datasetName == null ? "" : datasetName);
        first.createCell(1).setCellValue(ttlName == null ? "" : ttlName);
        first.createCell(2).setCellValue("All");
        first.createCell(3).setCellValue("Warnings");
        first.createCell(4).setCellValue("Infos");
        first.createCell(5).setCellValue("Violations");

        // Row B: counts
        int all = warn + vio + info;
        Row counts = s.createRow(r++);
        counts.createCell(2).setCellValue("# " + all);
        counts.createCell(3).setCellValue(warn);
        counts.createCell(4).setCellValue(info);
        counts.createCell(5).setCellValue(vio);

        // Row C: header row
        Row hdr = s.createRow(r++);
        for (int c = 0; c < HEADER.length; c++) {
            hdr.createCell(c).setCellValue(HEADER[c]);
        }

        // Row D..: details (if no results, still create no rows)
        if (results != null) {
            for (SHACLValidationResult res : results) {
                Row dr = s.createRow(r++);
                dr.createCell(0).setCellValue(safe(res.getFocusNode()));
                dr.createCell(1).setCellValue(safe(res.getPath()));
                dr.createCell(2).setCellValue(safe(res.getValue()));
                dr.createCell(3).setCellValue(safe(res.getSourceShape()));
                dr.createCell(4).setCellValue(safe(res.getConstraintComponent()));
                dr.createCell(5).setCellValue(safe(res.getDetails()));
                dr.createCell(6).setCellValue(safe(res.getMessage()));
                dr.createCell(7).setCellValue(safe(res.getSeverity()));
                dr.createCell(8).setCellValue(safe(res.getDescription()));
                dr.createCell(9).setCellValue(safe(res.getOrder()));
                dr.createCell(10).setCellValue(safe(res.getName()));
                dr.createCell(11).setCellValue(safe(res.getGroup()));
            }
        }

        r++; // blank line between datasets
        nextRowByCase.put(cf, r);
    }

    public void append(CaseFolder cf, String datasetName, String ttlName, List<SHACLValidationResult> results) {
        appendExcelBlock(cf, datasetName, ttlName, results);
    }

    public void appendError(CaseFolder cf, String datasetName, String ttlName, Exception error) {
        Sheet s = sheetByCase.get(cf);
        int r = nextRowByCase.get(cf);

        // Row A
        Row first = s.createRow(r++);
        first.createCell(0).setCellValue(datasetName == null ? "" : datasetName);
        first.createCell(1).setCellValue(ttlName == null ? "" : ttlName);
        first.createCell(2).setCellValue("All");
        first.createCell(3).setCellValue("Warnings");
        first.createCell(4).setCellValue("Infos");
        first.createCell(5).setCellValue("Violations");

        // Row B (all zeros)
        Row counts = s.createRow(r++);
        counts.createCell(2).setCellValue("# 0");
        counts.createCell(3).setCellValue(0);
        counts.createCell(4).setCellValue(0);
        counts.createCell(5).setCellValue(0);

        // Row C header
        Row hdr = s.createRow(r++);
        for (int c = 0; c < HEADER.length; c++) hdr.createCell(c).setCellValue(HEADER[c]);

        // One detail row with the exception
        Row dr = s.createRow(r++);
        dr.createCell(6).setCellValue(safeThrowable(error));
        dr.createCell(7).setCellValue("ERROR");

        r++;
        nextRowByCase.put(cf, r);
    }

    public Path saveTo(Path outputBaseDir) throws IOException {
        Files.createDirectories(outputBaseDir);

        // autosize 12 columns
        for (CaseFolder cf : CaseFolder.values()) {
            Sheet s = sheetByCase.get(cf);
            for (int c = 0; c < 12; c++) {
                try {
                    s.autoSizeColumn(c);
                } catch (Exception ignore) {
                }
            }
        }

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path out = outputBaseDir.resolve("validation_report__" + ts + ".xlsx");

        try (OutputStream os = Files.newOutputStream(out)) {
            wb.write(os);
        }
        return out;
    }

    @Override
    public void close() throws IOException {
        wb.close();
    }

    private static String safe(String s) {
        if (s == null) return "";
        if ("None".equalsIgnoreCase(s)) return "";
        return s;
    }

    private static String safeThrowable(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isBlank()) return t.getClass().getName();
        return t.getClass().getName() + ": " + m;
    }
}