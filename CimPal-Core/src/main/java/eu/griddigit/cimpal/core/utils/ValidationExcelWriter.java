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
import java.util.*;

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
    private static final String STATISTICS_CONSTRAINT_SHEET_NAME = "StatisticsConstraint";

    private static final String TIMESTAMP_OVERVIEW_SHEET_NAME = "TimestampOverview";
    private static final String TIMESTAMP_CONSTRAINT_SHEET_NAME = "TopConstraints";
    private static final String INPUT_COMPLETENESS_SHEET_NAME = "InputCompleteness";
    private static final boolean AUTO_SIZE_COLUMNS = false;

    private static final String[] TIMESTAMP_OVERVIEW_HEADER = new String[]{
            "Country", "Timestamp", "Report file",
            "Validation count", "Conform validations", "Non-conform validations", "Validation errors",
            "Total results", "Violations", "Warnings", "Infos", "Worst severity", "Check priority"
    };

    private static final String[] TIMESTAMP_CONSTRAINT_HEADER = new String[]{
            "Country", "Timestamp", "Report file",
            "Constraint file", "Source", "Constraint Component", "Message", "Severity", "Count"
    };

    private static final String[] INPUT_COMPLETENESS_HEADER = new String[]{
            "Country", "Timestamp", "Profile", "Expected count", "Actual count", "Status", "Files", "Message"
    };

    // Raw validation data: one header per sheet, no blank rows, no per-validation statistic rows.
    // The old Details column was intentionally removed.
    private static final String[] RAW_HEADER = new String[]{
            "Dataset", "XML files", "Constraint file",
            "Focus node", "Path", "Value", "Source", "Constraint Component",
            "Message", "Severity", "Description", "Order", "Name", "Group"
    };

    private static final String[] STATISTICS_HEADER = new String[]{
            "Case folder", "Dataset", "XML files", "Constraint file",
            "All", "Warnings", "Infos", "Violations", "Conforms", "Validation error"
    };

    // One row per unique constraint over the whole report.
    // Count shows how many validation results used that same constraint information.
    private static final String[] STATISTICS_CONSTRAINT_HEADER = new String[]{
            "Path", "Source", "Count", "Constraint Component", "Message", "Severity",
            "Description", "Order", "Name", "Group"
    };

    private final Workbook wb;
    private final Map<CaseFolder, Sheet> sheetByCase = new EnumMap<>(CaseFolder.class);
    private final Map<CaseFolder, Integer> nextRowByCase = new EnumMap<>(CaseFolder.class);
    private final Map<ConstraintStatisticKey, Integer> constraintStatistics = new LinkedHashMap<>();
    private final Sheet statisticsSheet;
    private final Sheet statisticsConstraintSheet;
    private int nextStatisticsRow = 1;

    private final boolean timestampedSummaryMode;
    private Sheet timestampOverviewSheet;
    private Sheet timestampConstraintSheet;
    private Sheet inputCompletenessSheet;
    private int nextTimestampOverviewRow = 1;
    private int nextInputCompletenessRow = 1;

    private final Map<TimestampConstraintKey, Integer> timestampConstraintStatistics = new LinkedHashMap<>();

    public ValidationExcelWriter() {
        this(false);
    }

    private ValidationExcelWriter(boolean timestampedSummaryMode) {
        this.timestampedSummaryMode = timestampedSummaryMode;
        this.wb = new XSSFWorkbook();

        CellStyle headerStyle = createHeaderStyle(wb);

        if (timestampedSummaryMode) {
            timestampOverviewSheet = wb.createSheet(TIMESTAMP_OVERVIEW_SHEET_NAME);
            writeHeader(timestampOverviewSheet, TIMESTAMP_OVERVIEW_HEADER, headerStyle);
            timestampOverviewSheet.setAutoFilter(new CellRangeAddress(0, 0, 0, TIMESTAMP_OVERVIEW_HEADER.length - 1));
            timestampOverviewSheet.createFreezePane(0, 1);

            timestampConstraintSheet = wb.createSheet(TIMESTAMP_CONSTRAINT_SHEET_NAME);
            writeHeader(timestampConstraintSheet, TIMESTAMP_CONSTRAINT_HEADER, headerStyle);
            timestampConstraintSheet.setAutoFilter(new CellRangeAddress(0, 0, 0, TIMESTAMP_CONSTRAINT_HEADER.length - 1));
            timestampConstraintSheet.createFreezePane(0, 1);

            inputCompletenessSheet = wb.createSheet(INPUT_COMPLETENESS_SHEET_NAME);
            writeHeader(inputCompletenessSheet, INPUT_COMPLETENESS_HEADER, headerStyle);
            inputCompletenessSheet.setAutoFilter(new CellRangeAddress(0, 0, 0, INPUT_COMPLETENESS_HEADER.length - 1));
            inputCompletenessSheet.createFreezePane(0, 1);

            statisticsSheet = null;
            statisticsConstraintSheet = null;
            return;
        }

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

        statisticsConstraintSheet = wb.createSheet(STATISTICS_CONSTRAINT_SHEET_NAME);
        writeHeader(statisticsConstraintSheet, STATISTICS_CONSTRAINT_HEADER, headerStyle);
        statisticsConstraintSheet.setAutoFilter(new CellRangeAddress(0, 0, 0, STATISTICS_CONSTRAINT_HEADER.length - 1));
        statisticsConstraintSheet.createFreezePane(0, 1);
    }

    public static ValidationExcelWriter createTimestampedSummaryWriter() {
        return new ValidationExcelWriter(true);
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

        String reportXmlFiles = toReportFileNames(xmlFiles);
        String reportConstraintFile = toReportFileNames(constraintFile);
        String reportDataset = safe(datasetName);

        if (reportDataset.isBlank()) {
            reportDataset = toReportDataset(reportXmlFiles, reportConstraintFile);
        }
        writeStatisticsRow(cf, reportDataset, reportXmlFiles, reportConstraintFile, results, conforms, null);

        if (results == null || results.isEmpty()) {
            return;
        }

        Sheet s = sheetByCase.get(cf);
        int r = nextRowByCase.get(cf);

        for (SHACLValidationResult res : results) {
            addConstraintStatistic(reportXmlFiles, reportConstraintFile, res);

            Row dr = s.createRow(r++);
            dr.createCell(0).setCellValue(reportDataset);
            dr.createCell(1).setCellValue(reportXmlFiles);
            dr.createCell(2).setCellValue(reportConstraintFile);
            dr.createCell(3).setCellValue(safe(res.getFocusNode()));
            dr.createCell(4).setCellValue(safe(res.getPath()));
            dr.createCell(5).setCellValue(safe(res.getValue()));
            dr.createCell(6).setCellValue(safe(res.getSourceShape()));
            dr.createCell(7).setCellValue(safe(res.getConstraintComponent()));
            dr.createCell(8).setCellValue(safe(res.getMessage()));
            dr.createCell(9).setCellValue(safe(res.getSeverity()));
            dr.createCell(10).setCellValue(safe(res.getDescription()));
            dr.createCell(11).setCellValue(safe(res.getOrder()));
            dr.createCell(12).setCellValue(safe(res.getName()));
            dr.createCell(13).setCellValue(safe(res.getGroup()));
        }

        nextRowByCase.put(cf, r);
    }

    private static String toReportDataset(String xmlFiles, String constraintFile) {
        String constraint = safe(constraintFile);

        if (constraint.replace(" ", "").toLowerCase(Locale.ROOT).contains("danglingreference")) {
            return "Dangling References (other)";
        }

        LinkedHashSet<String> profiles = new LinkedHashSet<>();

        addProfileIfPresent(profiles, xmlFiles, "EQ");
        addProfileIfPresent(profiles, xmlFiles, "TP");
        addProfileIfPresent(profiles, xmlFiles, "SSH");
        addProfileIfPresent(profiles, xmlFiles, "SV");

        addProfileIfPresent(profiles, xmlFiles, "AE");
        addProfileIfPresent(profiles, xmlFiles, "AP");
        addProfileIfPresent(profiles, xmlFiles, "PS");
        addProfileIfPresent(profiles, xmlFiles, "AS");
        addProfileIfPresent(profiles, xmlFiles, "CO");
        addProfileIfPresent(profiles, xmlFiles, "ER");
        addProfileIfPresent(profiles, xmlFiles, "IAM");
        addProfileIfPresent(profiles, xmlFiles, "RA");
        addProfileIfPresent(profiles, xmlFiles, "RAS");
        addProfileIfPresent(profiles, xmlFiles, "OP");
        addProfileIfPresent(profiles, xmlFiles, "SAR");
        addProfileIfPresent(profiles, xmlFiles, "SSI");
        addProfileIfPresent(profiles, xmlFiles, "SIS");

        if (profiles.isEmpty()) {
            addProfileIfPresent(profiles, constraintFile, "EQ");
            addProfileIfPresent(profiles, constraintFile, "TP");
            addProfileIfPresent(profiles, constraintFile, "SSH");
            addProfileIfPresent(profiles, constraintFile, "SV");

            addProfileIfPresent(profiles, constraintFile, "AE");
            addProfileIfPresent(profiles, constraintFile, "AP");
            addProfileIfPresent(profiles, constraintFile, "PS");
            addProfileIfPresent(profiles, constraintFile, "AS");
            addProfileIfPresent(profiles, constraintFile, "CO");
            addProfileIfPresent(profiles, constraintFile, "ER");
            addProfileIfPresent(profiles, constraintFile, "IAM");
            addProfileIfPresent(profiles, constraintFile, "RA");
            addProfileIfPresent(profiles, constraintFile, "RAS");
            addProfileIfPresent(profiles, constraintFile, "OP");
            addProfileIfPresent(profiles, constraintFile, "SAR");
            addProfileIfPresent(profiles, constraintFile, "SSI");
            addProfileIfPresent(profiles, constraintFile, "SIS");
        }

        return String.join(", ", profiles);
    }

    private static void addProfileIfPresent(LinkedHashSet<String> profiles, String value, String profile) {
        String normalized = safe(value)
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_");

        String p = safe(profile).toUpperCase(Locale.ROOT);

        if (normalized.isBlank() || p.isBlank()) {
            return;
        }

        if (normalized.matches(".*(^|_)" + java.util.regex.Pattern.quote(p) + "([0-9]+)?($|_).*")) {
            profiles.add(profile);
        }
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

        String reportXmlFiles = toReportFileNames(xmlFiles);
        String reportConstraintFile = toReportFileNames(constraintFile);
        String reportDataset = safe(datasetName);

        if (reportDataset.isBlank()) {
            reportDataset = toReportDataset(reportXmlFiles, reportConstraintFile);
        }
        writeStatisticsRow(cf, reportDataset, reportXmlFiles, reportConstraintFile, null, false, safeThrowable(error));
    }

    /** Backward-compatible entry point. Prefer appendError(cf, datasetName, xmlFiles, constraintFile, error). */
    public void appendError(CaseFolder cf, String datasetName, String ttlName, Exception error) {
        appendError(cf, datasetName, datasetName, ttlName, error);
    }

    private void writeStatisticsRow(CaseFolder cf,
                                    String reportDataset,
                                    String reportXmlFiles,
                                    String reportConstraintFile,
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
        row.createCell(0).setCellValue(cf == null ? "" : cf.name());
        row.createCell(1).setCellValue(safe(reportDataset));
        row.createCell(2).setCellValue(safe(reportXmlFiles));
        row.createCell(3).setCellValue(safe(reportConstraintFile));
        row.createCell(4).setCellValue(all);
        row.createCell(5).setCellValue(warn);
        row.createCell(6).setCellValue(info);
        row.createCell(7).setCellValue(vio);
        row.createCell(8).setCellValue(conforms && validationError == null);
        row.createCell(9).setCellValue(safe(validationError));
    }
    private void addConstraintStatistic(String xmlFiles,
                                        String constraintFile,
                                        SHACLValidationResult res) {
        if (res == null) {
            return;
        }

        ConstraintStatisticKey key = new ConstraintStatisticKey(
                safe(res.getPath()),
                safe(res.getSourceShape()),
                safe(res.getConstraintComponent()),
                safe(res.getMessage()),
                safe(res.getSeverity()),
                safe(res.getDescription()),
                safe(res.getOrder()),
                safe(res.getName()),
                safe(res.getGroup())
        );

        constraintStatistics.merge(key, 1, Integer::sum);
    }

    private void writeConstraintStatisticsRows() {
        int r = 1;

        for (Map.Entry<ConstraintStatisticKey, Integer> entry : constraintStatistics.entrySet()) {
            ConstraintStatisticKey key = entry.getKey();
            Row row = statisticsConstraintSheet.createRow(r++);
            row.createCell(0).setCellValue(key.path);
            row.createCell(1).setCellValue(key.source);
            row.createCell(2).setCellValue(entry.getValue());
            row.createCell(3).setCellValue(key.constraintComponent);
            row.createCell(4).setCellValue(key.message);
            row.createCell(5).setCellValue(key.severity);
            row.createCell(6).setCellValue(key.description);
            row.createCell(7).setCellValue(key.order);
            row.createCell(8).setCellValue(key.name);
            row.createCell(9).setCellValue(key.group);
        }
    }

    public Path saveTo(Path outputBaseDir) throws IOException {
        Files.createDirectories(outputBaseDir);

        if (timestampedSummaryMode) {
            writeTimestampConstraintStatisticsRows();

            autosize(timestampOverviewSheet, TIMESTAMP_OVERVIEW_HEADER.length);
            autosize(timestampConstraintSheet, TIMESTAMP_CONSTRAINT_HEADER.length);
            autosize(inputCompletenessSheet, INPUT_COMPLETENESS_HEADER.length);

            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path out = outputBaseDir.resolve("timestamped_validation_summary__" + ts + ".xlsx");

            try (OutputStream os = Files.newOutputStream(out)) {
                wb.write(os);
            }
            return out;
        }

        writeConstraintStatisticsRows();

        if (AUTO_SIZE_COLUMNS) {
            for (CaseFolder cf : CaseFolder.values()) {
                Sheet s = sheetByCase.get(cf);
                autosize(s, RAW_HEADER.length);
            }

            autosize(statisticsSheet, STATISTICS_HEADER.length);
            autosize(statisticsConstraintSheet, STATISTICS_CONSTRAINT_HEADER.length);
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

    private static String toReportFileNames(String value) {
        String s = safe(value).trim();
        if (s.isEmpty()) return "";

        String[] parts = s.split(";");
        StringBuilder out = new StringBuilder();

        for (String part : parts) {
            String cleaned = fileNameOnly(part);
            if (cleaned.isEmpty()) continue;

            if (out.length() > 0) {
                out.append("; ");
            }
            out.append(cleaned);
        }

        return out.length() == 0 ? fileNameOnly(s) : out.toString();
    }

    private static String fileNameOnly(String value) {
        String s = safe(value).trim();
        if (s.isEmpty()) return "";

        s = s.replace("\\", "/");

        while (s.endsWith("/") && s.length() > 1) {
            s = s.substring(0, s.length() - 1);
        }

        int slash = s.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < s.length()) {
            return s.substring(slash + 1);
        }

        return s;
    }

    private static class ConstraintStatisticKey {
        private final String path;
        private final String source;
        private final String constraintComponent;
        private final String message;
        private final String severity;
        private final String description;
        private final String order;
        private final String name;
        private final String group;

        private ConstraintStatisticKey(String path,
                                       String source,
                                       String constraintComponent,
                                       String message,
                                       String severity,
                                       String description,
                                       String order,
                                       String name,
                                       String group) {
            this.path = path;
            this.source = source;
            this.constraintComponent = constraintComponent;
            this.message = message;
            this.severity = severity;
            this.description = description;
            this.order = order;
            this.name = name;
            this.group = group;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ConstraintStatisticKey)) return false;
            ConstraintStatisticKey that = (ConstraintStatisticKey) o;
            return Objects.equals(path, that.path)
                    && Objects.equals(source, that.source)
                    && Objects.equals(constraintComponent, that.constraintComponent);
        }

        @Override
        public int hashCode() {
            return Objects.hash(path, source, constraintComponent);
        }
    }

        //Aggregated report helpers
        //
        //
        //

    public void appendTimestampOverview(String country,
                                        String timestamp,
                                        Path reportPath,
                                        int validationCount,
                                        int conformCount,
                                        int nonConformCount,
                                        int errorCount,
                                        int totalResults,
                                        int violationCount,
                                        int warningCount,
                                        int infoCount) {
        ensureTimestampedSummaryMode();

        String worstSeverity = "";
        if (violationCount > 0) {
            worstSeverity = "Violation";
        } else if (warningCount > 0) {
            worstSeverity = "Warning";
        } else if (infoCount > 0) {
            worstSeverity = "Info";
        } else if (errorCount > 0) {
            worstSeverity = "Validation error";
        }

        String priority;
        if (errorCount > 0) {
            priority = "Open - validation errors";
        } else if (violationCount > 0) {
            priority = "Open - violations";
        } else if (warningCount > 0) {
            priority = "Maybe check - warnings";
        } else {
            priority = "Probably OK";
        }

        Row row = timestampOverviewSheet.createRow(nextTimestampOverviewRow++);
        row.createCell(0).setCellValue(safe(country));
        row.createCell(1).setCellValue(safe(timestamp));
        row.createCell(2).setCellValue(reportPath == null ? "" : reportPath.getFileName().toString());
        row.createCell(3).setCellValue(validationCount);
        row.createCell(4).setCellValue(conformCount);
        row.createCell(5).setCellValue(nonConformCount);
        row.createCell(6).setCellValue(errorCount);
        row.createCell(7).setCellValue(totalResults);
        row.createCell(8).setCellValue(violationCount);
        row.createCell(9).setCellValue(warningCount);
        row.createCell(10).setCellValue(infoCount);
        row.createCell(11).setCellValue(worstSeverity);
        row.createCell(12).setCellValue(priority);
    }

    public void collectTimestampConstraintStatistics(String country,
                                                     String timestamp,
                                                     Path reportPath,
                                                     String constraintFile,
                                                     List<SHACLValidationResult> results) {
        ensureTimestampedSummaryMode();

        if (results == null || results.isEmpty()) {
            return;
        }

        String reportConstraintFile = toReportFileNames(constraintFile);
        String reportFile = reportPath == null ? "" : reportPath.getFileName().toString();

        for (SHACLValidationResult res : results) {
            if (res == null) {
                continue;
            }

            TimestampConstraintKey key = new TimestampConstraintKey(
                    safe(country),
                    safe(timestamp),
                    reportFile,
                    reportConstraintFile,
                    safe(res.getSourceShape()),
                    safe(res.getConstraintComponent()),
                    safe(res.getMessage()),
                    safe(res.getSeverity())
            );

            timestampConstraintStatistics.merge(key, 1, Integer::sum);
        }
    }

    private void writeTimestampConstraintStatisticsRows() {
        if (!timestampedSummaryMode || timestampConstraintSheet == null) {
            return;
        }

        int r = 1;

        for (Map.Entry<TimestampConstraintKey, Integer> entry : timestampConstraintStatistics.entrySet()) {
            TimestampConstraintKey key = entry.getKey();

            Row row = timestampConstraintSheet.createRow(r++);
            row.createCell(0).setCellValue(key.country);
            row.createCell(1).setCellValue(key.timestamp);
            row.createCell(2).setCellValue(key.reportFile);
            row.createCell(3).setCellValue(key.constraintFile);
            row.createCell(4).setCellValue(key.source);
            row.createCell(5).setCellValue(key.constraintComponent);
            row.createCell(6).setCellValue(key.message);
            row.createCell(7).setCellValue(key.severity);
            row.createCell(8).setCellValue(entry.getValue());
        }
    }

    public void appendInputCompleteness(String country,
                                        String timestamp,
                                        String profile,
                                        int expectedCount,
                                        int actualCount,
                                        String files,
                                        String message) {
        ensureTimestampedSummaryMode();

        String status;
        if (actualCount == expectedCount) {
            status = "OK";
        } else if (actualCount == 0) {
            status = "Missing";
        } else if (actualCount < expectedCount) {
            status = "Too few";
        } else {
            status = "Too many";
        }

        Row row = inputCompletenessSheet.createRow(nextInputCompletenessRow++);
        row.createCell(0).setCellValue(safe(country));
        row.createCell(1).setCellValue(safe(timestamp));
        row.createCell(2).setCellValue(safe(profile));
        row.createCell(3).setCellValue(expectedCount);
        row.createCell(4).setCellValue(actualCount);
        row.createCell(5).setCellValue(status);
        row.createCell(6).setCellValue(toReportFileNames(files));
        row.createCell(7).setCellValue(safe(message));
    }

    private void ensureTimestampedSummaryMode() {
        if (!timestampedSummaryMode) {
            throw new IllegalStateException("This method can only be used with createTimestampedSummaryWriter().");
        }
    }

    private static class TimestampConstraintKey {
        private final String country;
        private final String timestamp;
        private final String reportFile;
        private final String constraintFile;
        private final String source;
        private final String constraintComponent;
        private final String message;
        private final String severity;

        private TimestampConstraintKey(String country,
                                       String timestamp,
                                       String reportFile,
                                       String constraintFile,
                                       String source,
                                       String constraintComponent,
                                       String message,
                                       String severity) {
            this.country = country;
            this.timestamp = timestamp;
            this.reportFile = reportFile;
            this.constraintFile = constraintFile;
            this.source = source;
            this.constraintComponent = constraintComponent;
            this.message = message;
            this.severity = severity;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TimestampConstraintKey)) return false;
            TimestampConstraintKey that = (TimestampConstraintKey) o;
            return Objects.equals(country, that.country)
                    && Objects.equals(timestamp, that.timestamp)
                    && Objects.equals(reportFile, that.reportFile)
                    && Objects.equals(constraintFile, that.constraintFile)
                    && Objects.equals(source, that.source)
                    && Objects.equals(constraintComponent, that.constraintComponent)
                    && Objects.equals(message, that.message)
                    && Objects.equals(severity, that.severity);
        }

        @Override
        public int hashCode() {
            return Objects.hash(country, timestamp, reportFile, constraintFile, source, constraintComponent, message, severity);
        }
    }
}
