package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
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
        DANGLINGREFERENCE,
        UNKNOWN
    }

    private static final String STATISTICS_SHEET_NAME = "Validation statistics";

    // Raw validation data: one header per sheet, no blank rows, no per-validation statistic rows.
    // The old Details column was intentionally removed.
    private static final String[] RAW_HEADER = new String[]{
            "XML files", "Constraint file",
            "Focus node", "Path", "Value", "Source", "Constraint Component",
            "Message", "Severity", "Description", "Order", "Name", "Group"
    };

    // One row per validation. Process errors also go here.
    private static final String[] STATISTICS_HEADER = new String[]{
            "Case folder", "Dataset", "XML files", "Constraint file",
            "All", "Warnings", "Infos", "Violations", "Conforms", "Validation error"
    };

    private final Workbook wb;
    private final Map<CaseFolder, Sheet> sheetByCase = new EnumMap<>(CaseFolder.class);
    private final Map<CaseFolder, Integer> nextRowByCase = new EnumMap<>(CaseFolder.class);
    private final Sheet statisticsSheet;
    private int nextStatisticsRow = 1;

    public ValidationExcelWriter() {
        this.wb = new XSSFWorkbook();

        CellStyle headerStyle = createHeaderStyle(wb);

        for (CaseFolder cf : CaseFolder.values()) {
            Sheet s = wb.createSheet(cf.name());
            sheetByCase.put(cf, s);
            nextRowByCase.put(cf, 1);
            writeHeader(s, RAW_HEADER, headerStyle);
            s.setAutoFilter(new CellRangeAddress(0, 0, 0, RAW_HEADER.length - 1));
            s.createFreezePane(0, 1);
        }

        statisticsSheet = wb.createSheet(STATISTICS_SHEET_NAME);
        writeHeader(statisticsSheet, STATISTICS_HEADER, headerStyle);
        statisticsSheet.setAutoFilter(new CellRangeAddress(0, 0, 0, STATISTICS_HEADER.length - 1));
        statisticsSheet.createFreezePane(0, 1);
    }

    /**
     * Appends raw SHACL result rows to the selected case sheet.
     * No section headers and no blank separator rows are written, so the sheet remains filterable.
     */
    public void appendValidation(CaseFolder cf,
                                 String datasetName,
                                 String xmlFiles,
                                 String constraintFile,
                                 List<SHACLValidationResult> results,
                                 boolean conforms) {

        writeStatisticsRow(cf, datasetName, xmlFiles, constraintFile, results, conforms, null);

        if (results == null || results.isEmpty()) {
            return;
        }

        Sheet s = sheetByCase.get(cf);
        int r = nextRowByCase.get(cf);

        for (SHACLValidationResult res : results) {
            Row dr = s.createRow(r++);
            dr.createCell(0).setCellValue(safe(xmlFiles));
            dr.createCell(1).setCellValue(safe(constraintFile));
            dr.createCell(2).setCellValue(safe(res.getFocusNode()));
            dr.createCell(3).setCellValue(safe(res.getPath()));
            dr.createCell(4).setCellValue(safe(res.getValue()));
            dr.createCell(5).setCellValue(safe(res.getSourceShape()));
            dr.createCell(6).setCellValue(safe(res.getConstraintComponent()));
            dr.createCell(7).setCellValue(safe(res.getMessage()));
            dr.createCell(8).setCellValue(safe(res.getSeverity()));
            dr.createCell(9).setCellValue(safe(res.getDescription()));
            dr.createCell(10).setCellValue(safe(res.getOrder()));
            dr.createCell(11).setCellValue(safe(res.getName()));
            dr.createCell(12).setCellValue(safe(res.getGroup()));
        }

        nextRowByCase.put(cf, r);
    }

    /** Backward-compatible entry point. Prefer appendValidation(...). */
    public void appendExcelBlock(CaseFolder cf,
                                 String datasetName,
                                 String ttlName,
                                 List<SHACLValidationResult> results) {
        appendValidation(cf, datasetName, datasetName, ttlName, results, false);
    }

    /** Backward-compatible entry point. Prefer appendValidation(...). */
    public void append(CaseFolder cf, String datasetName, String ttlName, List<SHACLValidationResult> results) {
        appendExcelBlock(cf, datasetName, ttlName, results);
    }

    public void appendError(CaseFolder cf,
                            String datasetName,
                            String xmlFiles,
                            String constraintFile,
                            Exception error) {
        writeStatisticsRow(cf, datasetName, xmlFiles, constraintFile, null, false, safeThrowable(error));
    }

    /** Backward-compatible entry point. Prefer appendError(cf, datasetName, xmlFiles, constraintFile, error). */
    public void appendError(CaseFolder cf, String datasetName, String ttlName, Exception error) {
        appendError(cf, datasetName, datasetName, ttlName, error);
    }

    private void writeStatisticsRow(CaseFolder cf,
                                    String datasetName,
                                    String xmlFiles,
                                    String constraintFile,
                                    List<SHACLValidationResult> results,
                                    boolean conforms,
                                    String validationError) {
        int vio = 0, warn = 0, info = 0;

        if (results != null) {
            for (SHACLValidationResult res : results) {
                String sev = safe(res.getSeverity()).toLowerCase(Locale.ROOT);
                if (sev.contains("violation")) vio++;
                else if (sev.contains("warning")) warn++;
                else info++;
            }
        }

        int all = warn + vio + info;
        Row row = statisticsSheet.createRow(nextStatisticsRow++);
        row.createCell(0).setCellValue(cf == null ? CaseFolder.UNKNOWN.name() : cf.name());
        row.createCell(1).setCellValue(safe(datasetName));
        row.createCell(2).setCellValue(safe(xmlFiles));
        row.createCell(3).setCellValue(safe(constraintFile));
        row.createCell(4).setCellValue(all);
        row.createCell(5).setCellValue(warn);
        row.createCell(6).setCellValue(info);
        row.createCell(7).setCellValue(vio);
        row.createCell(8).setCellValue(conforms && validationError == null);
        row.createCell(9).setCellValue(safe(validationError));
    }

    public Path saveTo(Path outputBaseDir) throws IOException {
        Files.createDirectories(outputBaseDir);

        for (CaseFolder cf : CaseFolder.values()) {
            Sheet s = sheetByCase.get(cf);
            autosize(s, RAW_HEADER.length);
        }
        autosize(statisticsSheet, STATISTICS_HEADER.length);

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

    private static void writeHeader(Sheet sheet, String[] header, CellStyle headerStyle) {
        Row hdr = sheet.createRow(0);
        for (int c = 0; c < header.length; c++) {
            Cell cell = hdr.createCell(c);
            cell.setCellValue(header[c]);
            cell.setCellStyle(headerStyle);
        }
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
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        return style;
    }

    private static void autosize(Sheet sheet, int columnCount) {
        for (int c = 0; c < columnCount; c++) {
            try {
                sheet.autoSizeColumn(c);
                int width = sheet.getColumnWidth(c);
                int maxWidth = 80 * 256;
                if (width > maxWidth) {
                    sheet.setColumnWidth(c, maxWidth);
                }
            } catch (Exception ignore) {
            }
        }
    }

    private static String safe(String s) {
        if (s == null) return "";
        if ("None".equalsIgnoreCase(s)) return "";
        return s;
    }

    private static String safeThrowable(Throwable t) {
        if (t == null) return "";
        String m = t.getMessage();
        if (m == null || m.isBlank()) return t.getClass().getName();
        return t.getClass().getName() + ": " + m;
    }
}
