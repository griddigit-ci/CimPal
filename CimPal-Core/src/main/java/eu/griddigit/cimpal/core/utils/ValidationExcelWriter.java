package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.xddf.usermodel.XDDFColor;
import org.apache.poi.xddf.usermodel.XDDFShapeProperties;
import org.apache.poi.xddf.usermodel.XDDFSolidFillProperties;
import org.apache.poi.xddf.usermodel.chart.*;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;

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

    private static final String VALIDATION_RESULTS_SHEET_NAME = "Validation results";
    private static final String CHARTS_SHEET_NAME = "Charts";

    // Office default palette used in the requested screenshots.
    private static final byte[] COLOR_WARNING   = new byte[]{(byte) 0x44, (byte) 0x72, (byte) 0xC4}; // blue
    private static final byte[] COLOR_INFO      = new byte[]{(byte) 0xED, (byte) 0x7D, (byte) 0x31}; // orange
    private static final byte[] COLOR_VIOLATION = new byte[]{(byte) 0x70, (byte) 0xAD, (byte) 0x47}; // green

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
            "Country",
            "Timestamp",
            "Mapping row",
            "Constraint file",
            "Requested input",
            "Expected file count",
            "Resolved file count",
            "Status",
            "Resolved files",
            "Missing input",
            "Message"
    };

    // Raw validation data: one header per sheet, no blank rows, no per-validation statistic rows.
    private static final String[] RAW_HEADER = new String[]{
            "Dataset", "XML files", "Constraint file",
            "Focus node", "Path", "Value", "Value kind", "Source", "Constraint Component",
            "Message", "Severity", "Description", "Order", "Name", "Group"
    };

    // NOTE: a "Chart name" column (index 10) was added. It holds the mapping column C display
    // name and is what the charts use for the category (x) axis. It falls back to Dataset.
    private static final String[] STATISTICS_HEADER = new String[]{
            "Dataset",
            "XML files",
            "Constraint file",
            "All",
            "Warnings",
            "Infos",
            "Violations",
            "Conforms",
            "Validation error",
            "Missing XML files",
            "Chart name"
    };

    private static final int STAT_COL_DATASET = 0;
    private static final int STAT_COL_WARNINGS = 4;
    private static final int STAT_COL_INFOS = 5;
    private static final int STAT_COL_VIOLATIONS = 6;
    private static final int STAT_COL_CHART_NAME = 10;

    // One row per unique constraint over the whole report.
    private static final String[] STATISTICS_CONSTRAINT_HEADER = new String[]{
            "Dataset", "Path", "Source", "Count", "Constraint Component", "Message", "Severity",
            "Description", "Order", "Name", "Group"
    };

    private final Workbook wb;
    private Sheet validationResultsSheet;
    private Sheet chartsSheet;
    private int nextValidationResultsRow = 1;
    private final Map<ConstraintStatisticKey, Integer> constraintStatistics = new LinkedHashMap<>();
    private final Sheet statisticsSheet;
    private final Sheet statisticsConstraintSheet;
    private int nextStatisticsRow = 1;

    // Context used to build chart titles: "<analysisName> (<tso>) <timestamp> - ...".
    private String analysisName = "Validation Analysis";
    private String reportTso = "";
    private String reportTimestamp = "";

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

        validationResultsSheet = wb.createSheet(VALIDATION_RESULTS_SHEET_NAME);
        writeHeader(validationResultsSheet, RAW_HEADER, headerStyle);
        validationResultsSheet.setAutoFilter(new CellRangeAddress(0, 0, 0, RAW_HEADER.length - 1));
        validationResultsSheet.createFreezePane(0, 1);

        statisticsSheet = wb.createSheet(STATISTICS_SHEET_NAME);
        writeHeader(statisticsSheet, STATISTICS_HEADER, headerStyle);
        statisticsSheet.setAutoFilter(new CellRangeAddress(0, 0, 0, STATISTICS_HEADER.length - 1));
        statisticsSheet.createFreezePane(0, 1);

        statisticsConstraintSheet = wb.createSheet(STATISTICS_CONSTRAINT_SHEET_NAME);
        writeHeader(statisticsConstraintSheet, STATISTICS_CONSTRAINT_HEADER, headerStyle);
        statisticsConstraintSheet.setAutoFilter(new CellRangeAddress(0, 0, 0, STATISTICS_CONSTRAINT_HEADER.length - 1));
        statisticsConstraintSheet.createFreezePane(0, 1);

        chartsSheet = wb.createSheet(CHARTS_SHEET_NAME);
    }

    public static ValidationExcelWriter createTimestampedSummaryWriter() {
        return new ValidationExcelWriter(true);
    }

    /**
     * Sets the context used for chart titles. Call before {@link #saveTo(Path)} on per-timestamp
     * writers so the charts read "<analysisName> (<tso>) <timestamp> - ...".
     */
    public void setReportContext(String analysisName, String tso, String timestamp) {
        if (analysisName != null && !analysisName.isBlank()) {
            this.analysisName = analysisName;
        }
        this.reportTso = safe(tso);
        this.reportTimestamp = safe(timestamp);
    }

    /**
     * Appends raw SHACL result rows to the selected case sheet.
     * {@code displayName} (mapping column C) is stored so charts can use it on the x axis.
     */
    public void appendValidation(CaseFolder cf,
                                 String datasetName,
                                 String xmlFiles,
                                 String missingXmlFiles,
                                 String constraintFile,
                                 List<SHACLValidationResult> results,
                                 boolean conforms,
                                 String displayName) {

        String reportXmlFiles = toReportFileNames(xmlFiles);
        String reportConstraintFile = toReportFileNames(constraintFile);
        String reportDataset = safe(datasetName);

        if (reportDataset.isBlank()) {
            reportDataset = toReportDataset(reportXmlFiles, reportConstraintFile);
        }
        writeStatisticsRow(
                cf,
                reportDataset,
                reportXmlFiles,
                reportConstraintFile,
                results,
                conforms,
                null,
                missingXmlFiles,
                displayName
        );
        if (results == null || results.isEmpty()) {
            return;
        }

        Sheet s = validationResultsSheet;
        int r = nextValidationResultsRow;

        for (SHACLValidationResult res : results) {
            addConstraintStatistic(reportDataset, reportXmlFiles, reportConstraintFile, res);
            Row dr = s.createRow(r++);
            dr.createCell(0).setCellValue(reportDataset);
            dr.createCell(1).setCellValue(reportXmlFiles);
            dr.createCell(2).setCellValue(reportConstraintFile);
            dr.createCell(3).setCellValue(safe(res.getFocusNode()));
            dr.createCell(4).setCellValue(safe(res.getPath()));
            dr.createCell(5).setCellValue(safe(res.getValue()));
            dr.createCell(6).setCellValue(safe(res.getValueKind()));
            dr.createCell(7).setCellValue(safe(res.getSourceShape()));
            dr.createCell(8).setCellValue(safe(res.getConstraintComponent()));
            dr.createCell(9).setCellValue(cleanValidationMessage(res.getMessage()));
            dr.createCell(10).setCellValue(safe(res.getSeverity()));
            dr.createCell(11).setCellValue(safe(res.getDescription()));
            dr.createCell(12).setCellValue(safe(res.getOrder()));
            dr.createCell(13).setCellValue(safe(res.getName()));
            dr.createCell(14).setCellValue(safe(res.getGroup()));
        }

        nextValidationResultsRow = r;
    }

    /** Backward-compatible: no display name. */
    public void appendValidation(CaseFolder cf,
                                 String datasetName,
                                 String xmlFiles,
                                 String missingXmlFiles,
                                 String constraintFile,
                                 List<SHACLValidationResult> results,
                                 boolean conforms) {

        appendValidation(cf, datasetName, xmlFiles, missingXmlFiles, constraintFile, results, conforms, "");
    }

    /** Backward-compatible: no missing files, no display name. */
    public void appendValidation(CaseFolder cf,
                                 String datasetName,
                                 String xmlFiles,
                                 String constraintFile,
                                 List<SHACLValidationResult> results,
                                 boolean conforms) {

        appendValidation(cf, datasetName, xmlFiles, "", constraintFile, results, conforms, "");
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
                            String missingXmlFiles,
                            String constraintFile,
                            Exception error,
                            String displayName) {

        String reportXmlFiles = toReportFileNames(xmlFiles);
        String reportConstraintFile = toReportFileNames(constraintFile);
        String reportDataset = safe(datasetName);

        if (reportDataset.isBlank()) {
            reportDataset = toReportDataset(reportXmlFiles, reportConstraintFile);
        }
        writeStatisticsRow(
                cf,
                reportDataset,
                reportXmlFiles,
                reportConstraintFile,
                null,
                false,
                safeThrowable(error),
                missingXmlFiles,
                displayName
        );
    }

    public void appendError(CaseFolder cf,
                            String datasetName,
                            String xmlFiles,
                            String missingXmlFiles,
                            String constraintFile,
                            Exception error) {

        appendError(cf, datasetName, xmlFiles, missingXmlFiles, constraintFile, error, "");
    }

    public void appendError(CaseFolder cf,
                            String datasetName,
                            String xmlFiles,
                            String constraintFile,
                            Exception error) {

        appendError(cf, datasetName, xmlFiles, "", constraintFile, error, "");
    }

    /** Backward-compatible entry point. */
    public void appendError(CaseFolder cf, String datasetName, String ttlName, Exception error) {
        appendError(cf, datasetName, datasetName, "", ttlName, error, "");
    }

    private void writeStatisticsRow(CaseFolder cf,
                                    String reportDataset,
                                    String reportXmlFiles,
                                    String reportConstraintFile,
                                    List<SHACLValidationResult> results,
                                    boolean conforms,
                                    String validationError,
                                    String missingXmlFiles,
                                    String displayName) {
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
        row.createCell(0).setCellValue(safe(reportDataset));
        row.createCell(1).setCellValue(safe(reportXmlFiles));
        row.createCell(2).setCellValue(safe(reportConstraintFile));
        row.createCell(3).setCellValue(all);
        row.createCell(4).setCellValue(warn);
        row.createCell(5).setCellValue(info);
        row.createCell(6).setCellValue(vio);
        row.createCell(7).setCellValue(conforms && validationError == null);
        row.createCell(8).setCellValue(safe(validationError));
        row.createCell(9).setCellValue(formatMissingXmlFiles(missingXmlFiles));
        row.createCell(STAT_COL_CHART_NAME).setCellValue(safe(displayName));
    }

    private void addConstraintStatistic(String dataset,
                                        String xmlFiles,
                                        String constraintFile,
                                        SHACLValidationResult res) {
        if (res == null) {
            return;
        }

        ConstraintStatisticKey key = new ConstraintStatisticKey(
                safe(dataset),
                safe(res.getPath()),
                safe(res.getSourceShape()),
                safe(res.getConstraintComponent()),
                cleanValidationMessage(res.getMessage()),
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
            row.createCell(0).setCellValue(key.dataset);
            row.createCell(1).setCellValue(key.path);
            row.createCell(2).setCellValue(key.source);
            row.createCell(3).setCellValue(entry.getValue());
            row.createCell(4).setCellValue(key.constraintComponent);
            row.createCell(5).setCellValue(key.message);
            row.createCell(6).setCellValue(key.severity);
            row.createCell(7).setCellValue(key.description);
            row.createCell(8).setCellValue(key.order);
            row.createCell(9).setCellValue(key.name);
            row.createCell(10).setCellValue(key.group);
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
        writeChartsSheet();

        if (AUTO_SIZE_COLUMNS) {
            autosize(validationResultsSheet, RAW_HEADER.length);
            autosize(statisticsSheet, STATISTICS_HEADER.length);
            autosize(statisticsConstraintSheet, STATISTICS_CONSTRAINT_HEADER.length);

            if (chartsSheet != null) {
                autosize(chartsSheet, 9);
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

    private static String cleanValidationMessage(String message) {
        String text = safe(message);

        if (text.isBlank()) {
            return "";
        }

        Pattern classUriPattern = Pattern.compile(
                "The class\\s+<([^>]+)>\\s+appears\\s+(\\d+)\\s+times?\\s+in the data graph\\.",
                Pattern.CASE_INSENSITIVE
        );

        Matcher matcher = classUriPattern.matcher(text);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String uri = matcher.group(1);
            String countText = matcher.group(2);

            String className = localNameFromUri(uri);

            long count;
            try {
                count = Long.parseLong(countText);
            } catch (NumberFormatException ex) {
                count = 0;
            }

            String occurrenceWord = count == 1 ? "time" : "times";

            String replacement = "The class "
                    + className
                    + " appears "
                    + countText
                    + " "
                    + occurrenceWord
                    + " in the data graph.";

            matcher.appendReplacement(
                    result,
                    Matcher.quoteReplacement(replacement)
            );
        }

        matcher.appendTail(result);
        return result.toString();
    }

    private static String localNameFromUri(String uri) {
        String value = safe(uri).trim();

        if (value.isEmpty()) {
            return "";
        }

        int hash = value.lastIndexOf('#');
        int slash = value.lastIndexOf('/');
        int separator = Math.max(hash, slash);

        if (separator >= 0 && separator + 1 < value.length()) {
            return value.substring(separator + 1);
        }

        return value;
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

    private static String formatMissingXmlFiles(String value) {
        String s = safe(value).trim();

        if (s.isEmpty()) {
            return "";
        }

        return Arrays.stream(s.split(";"))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .collect(java.util.stream.Collectors.joining(
                        System.lineSeparator()
                ));
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
        private final String dataset;

        private ConstraintStatisticKey(String dataset,
                                       String path,
                                       String source,
                                       String constraintComponent,
                                       String message,
                                       String severity,
                                       String description,
                                       String order,
                                       String name,
                                       String group) {
            this.dataset = dataset;
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
                    cleanValidationMessage(res.getMessage()),
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
                                        int mappingRow,
                                        String constraintFile,
                                        String requestedInput,
                                        int expectedFileCount,
                                        int resolvedFileCount,
                                        String status,
                                        String resolvedFiles,
                                        String missingInput,
                                        String message) {
        ensureTimestampedSummaryMode();

        Row row = inputCompletenessSheet.createRow(nextInputCompletenessRow++);
        row.createCell(0).setCellValue(safe(country));
        row.createCell(1).setCellValue(safe(timestamp));
        row.createCell(2).setCellValue(mappingRow);
        row.createCell(3).setCellValue(toReportFileNames(constraintFile));
        row.createCell(4).setCellValue(safe(requestedInput));
        row.createCell(5).setCellValue(expectedFileCount);
        row.createCell(6).setCellValue(resolvedFileCount);
        row.createCell(7).setCellValue(safe(status));
        row.createCell(8).setCellValue(toReportFileNames(resolvedFiles));
        row.createCell(9).setCellValue(safe(missingInput));
        row.createCell(10).setCellValue(safe(message));
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

    // ============================================================================
    // Charts
    // ============================================================================

    /**
     * Builds the chart data tables and up to four charts:
     *   Table A (columns 0-3): only datasets with at least one hit.
     *   Table B (columns 5-8): ALL datasets from the statistics sheet, including zero-hit rows.
     * For each table an absolute (stacked) and a relative (percent-stacked) chart is produced.
     * The category (x) axis uses the mapping column C display name (falls back to Dataset).
     */
    private void writeChartsSheet() {
        if (chartsSheet == null || statisticsSheet == null) {
            return;
        }

        Row header = chartsSheet.createRow(0);
        header.createCell(0).setCellValue("Dataset");
        header.createCell(1).setCellValue("Warnings");
        header.createCell(2).setCellValue("Infos");
        header.createCell(3).setCellValue("Violations");
        header.createCell(5).setCellValue("Dataset (all)");
        header.createCell(6).setCellValue("Warnings");
        header.createCell(7).setCellValue("Infos");
        header.createCell(8).setCellValue("Violations");

        int outRowA = 1; // datasets with hits
        int outRowB = 1; // all datasets

        for (int r = 1; r < nextStatisticsRow; r++) {
            Row statsRow = statisticsSheet.getRow(r);
            if (statsRow == null) {
                continue;
            }

            String rawDataset = getString(statsRow, STAT_COL_DATASET);
            String chartName = getString(statsRow, STAT_COL_CHART_NAME);
            String label = chartName.isBlank() ? rawDataset : chartName;

            if (label.isBlank()) {
                continue;
            }

            double warnings = getNumeric(statsRow, STAT_COL_WARNINGS);
            double infos = getNumeric(statsRow, STAT_COL_INFOS);
            double violations = getNumeric(statsRow, STAT_COL_VIOLATIONS);
            double total = warnings + infos + violations;

            // Table B: every dataset, including zero-hit ones.
            Row rowB = getOrCreateChartRow(outRowB);
            rowB.createCell(5).setCellValue(label);
            rowB.createCell(6).setCellValue(warnings);
            rowB.createCell(7).setCellValue(infos);
            rowB.createCell(8).setCellValue(violations);
            outRowB++;

            // Table A: only datasets that triggered something.
            if (total > 0) {
                Row rowA = getOrCreateChartRow(outRowA);
                rowA.createCell(0).setCellValue(label);
                rowA.createCell(1).setCellValue(warnings);
                rowA.createCell(2).setCellValue(infos);
                rowA.createCell(3).setCellValue(violations);
                outRowA++;
            }
        }

        if (outRowA <= 1 && outRowB <= 1) {
            return;
        }

        chartsSheet.createFreezePane(0, 1);
        for (int c = 0; c <= 8; c++) {
            chartsSheet.autoSizeColumn(c);
        }

        XSSFSheet xs = (XSSFSheet) chartsSheet;
        String prefix = chartTitlePrefix();

        if (outRowA > 1) {
            createStackedBarChart(xs, prefix + " - Total Number of Hits",
                    0, 1, outRowA - 1, false, 10, 1, 28, 22);
            createStackedBarChart(xs, prefix + " - Total Distributed Number of Hits",
                    0, 1, outRowA - 1, true, 10, 24, 28, 45);
        }

        if (outRowB > 1) {
            createStackedBarChart(xs, prefix + " - Total Number of Hits (all datasets)",
                    5, 1, outRowB - 1, false, 10, 47, 28, 68);
            createStackedBarChart(xs, prefix + " - Total Distributed Number of Hits (all datasets)",
                    5, 1, outRowB - 1, true, 10, 70, 28, 91);
        }
    }

    private String chartTitlePrefix() {
        StringBuilder sb = new StringBuilder();
        sb.append((analysisName == null || analysisName.isBlank()) ? "Validation Analysis" : analysisName);
        if (!safe(reportTso).isBlank()) {
            sb.append(" (").append(reportTso).append(")");
        }
        if (!safe(reportTimestamp).isBlank()) {
            sb.append(" ").append(reportTimestamp);
        }
        return sb.toString();
    }

    private Row getOrCreateChartRow(int rowIndex) {
        Row row = chartsSheet.getRow(rowIndex);
        if (row == null) {
            row = chartsSheet.createRow(rowIndex);
        }
        return row;
    }

    /**
     * Creates a stacked (or percent-stacked) column chart. Category names come from {@code catCol},
     * the three series from {@code catCol+1..catCol+3} (Warnings, Infos, Violations). Adds fixed
     * colours, a bottom legend, and value data labels inside the bars.
     */
    private void createStackedBarChart(XSSFSheet sheet,
                                       String title,
                                       int catCol,
                                       int firstDataRow,
                                       int lastDataRow,
                                       boolean percent,
                                       int anchorCol1,
                                       int anchorRow1,
                                       int anchorCol2,
                                       int anchorRow2) {

        XSSFDrawing drawing = sheet.createDrawingPatriarch();

        XSSFClientAnchor anchor = drawing.createAnchor(
                0, 0, 0, 0,
                anchorCol1, anchorRow1, anchorCol2, anchorRow2
        );

        XSSFChart chart = drawing.createChart(anchor);
        chart.setTitleText(title);
        chart.setTitleOverlay(false);

        XDDFChartLegend legend = chart.getOrAddLegend();
        legend.setPosition(LegendPosition.BOTTOM);

        XDDFCategoryAxis bottomAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
        bottomAxis.setTitle("Datasets");

        XDDFValueAxis leftAxis = chart.createValueAxis(AxisPosition.LEFT);
        leftAxis.setTitle(percent
                ? "Total distribution of the number of triggered constraints"
                : "Number of triggered constraints");
        leftAxis.setCrosses(AxisCrosses.AUTO_ZERO);
        if (percent) {
            leftAxis.setNumberFormat("0%");
        }

        XDDFDataSource<String> categories =
                XDDFDataSourcesFactory.fromStringCellRange(
                        sheet,
                        new CellRangeAddress(firstDataRow, lastDataRow, catCol, catCol)
                );

        XDDFNumericalDataSource<Double> warnings =
                XDDFDataSourcesFactory.fromNumericCellRange(
                        sheet,
                        new CellRangeAddress(firstDataRow, lastDataRow, catCol + 1, catCol + 1)
                );

        XDDFNumericalDataSource<Double> infos =
                XDDFDataSourcesFactory.fromNumericCellRange(
                        sheet,
                        new CellRangeAddress(firstDataRow, lastDataRow, catCol + 2, catCol + 2)
                );

        XDDFNumericalDataSource<Double> violations =
                XDDFDataSourcesFactory.fromNumericCellRange(
                        sheet,
                        new CellRangeAddress(firstDataRow, lastDataRow, catCol + 3, catCol + 3)
                );

        XDDFBarChartData data = (XDDFBarChartData) chart.createData(
                ChartTypes.BAR,
                bottomAxis,
                leftAxis
        );

        data.setBarDirection(BarDirection.COL);
        data.setBarGrouping(percent ? BarGrouping.PERCENT_STACKED : BarGrouping.STACKED);
        data.setVaryColors(false);

        XDDFBarChartData.Series warningsSeries =
                (XDDFBarChartData.Series) data.addSeries(categories, warnings);
        warningsSeries.setTitle("Warnings", null);
        setSeriesColor(warningsSeries, COLOR_WARNING);

        XDDFBarChartData.Series infosSeries =
                (XDDFBarChartData.Series) data.addSeries(categories, infos);
        infosSeries.setTitle("Infos", null);
        setSeriesColor(infosSeries, COLOR_INFO);

        XDDFBarChartData.Series violationsSeries =
                (XDDFBarChartData.Series) data.addSeries(categories, violations);
        violationsSeries.setTitle("Violations", null);
        setSeriesColor(violationsSeries, COLOR_VIOLATION);

        chart.plot(data);

        // Must run after plot(): tweak the underlying XML for data labels + stacked overlap.
        addValueDataLabels(chart);
        setBarOverlap(chart, (byte) 100);
    }

    private static void setSeriesColor(XDDFChartData.Series series, byte[] rgb) {
        XDDFSolidFillProperties fill = new XDDFSolidFillProperties(XDDFColor.from(rgb));
        XDDFShapeProperties properties = series.getShapeProperties();
        if (properties == null) {
            properties = new XDDFShapeProperties();
        }
        properties.setFillProperties(fill);
        series.setShapeProperties(properties);
    }

    private static void addValueDataLabels(XSSFChart chart) {
        var plotArea = chart.getCTChart().getPlotArea();
        if (plotArea.sizeOfBarChartArray() == 0) {
            return;
        }
        var barChart = plotArea.getBarChartArray(0);

        org.openxmlformats.schemas.drawingml.x2006.chart.CTDLbls dLbls =
                barChart.isSetDLbls() ? barChart.getDLbls() : barChart.addNewDLbls();

        setShow(dLbls.isSetShowVal() ? dLbls.getShowVal() : dLbls.addNewShowVal(), true);
        setShow(dLbls.isSetShowLegendKey() ? dLbls.getShowLegendKey() : dLbls.addNewShowLegendKey(), false);
        setShow(dLbls.isSetShowCatName() ? dLbls.getShowCatName() : dLbls.addNewShowCatName(), false);
        setShow(dLbls.isSetShowSerName() ? dLbls.getShowSerName() : dLbls.addNewShowSerName(), false);
        setShow(dLbls.isSetShowPercent() ? dLbls.getShowPercent() : dLbls.addNewShowPercent(), false);
        setShow(dLbls.isSetShowBubbleSize() ? dLbls.getShowBubbleSize() : dLbls.addNewShowBubbleSize(), false);
    }

    private static void setShow(org.openxmlformats.schemas.drawingml.x2006.chart.CTBoolean bool, boolean value) {
        bool.setVal(value);
    }

    private static void setBarOverlap(XSSFChart chart, byte overlapPercent) {
        var plotArea = chart.getCTChart().getPlotArea();
        if (plotArea.sizeOfBarChartArray() == 0) {
            return;
        }
        var barChart = plotArea.getBarChartArray(0);
        if (barChart.isSetOverlap()) {
            barChart.getOverlap().setVal(overlapPercent);
        } else {
            barChart.addNewOverlap().setVal(overlapPercent);
        }
    }

    private static String getString(Row row, int cellIndex) {
        if (row == null) {
            return "";
        }

        Cell cell = row.getCell(cellIndex);

        if (cell == null) {
            return "";
        }

        if (cell.getCellType() == CellType.STRING) {
            return safe(cell.getStringCellValue());
        }

        if (cell.getCellType() == CellType.NUMERIC) {
            return String.valueOf(cell.getNumericCellValue());
        }

        return "";
    }

    private static double getNumeric(Row row, int cellIndex) {
        if (row == null) {
            return 0;
        }

        Cell cell = row.getCell(cellIndex);

        if (cell == null) {
            return 0;
        }

        if (cell.getCellType() == CellType.NUMERIC) {
            return cell.getNumericCellValue();
        }

        if (cell.getCellType() == CellType.STRING) {
            try {
                return Double.parseDouble(cell.getStringCellValue());
            } catch (NumberFormatException ignore) {
                return 0;
            }
        }

        return 0;
    }

    /**
     * Produces a separate "comparison" workbook:
     *   - one sheet per region (a.k.a. TSO / input group) holding the per-timestamp data
     *     used for the comparison (Warnings / Infos / Violations / Total per dataset);
     *   - a "Charts" sheet holding, for each region, the comparison table
     *     (Chart dataset | previous total | current total | delta) plus a Delta bar chart.
     *
     * The previous run's totals are read from a CSV (the new CSV input) with the header:
     *
     *     region,dataset,total
     *
     * where {@code total = warnings + infos + violations}. The current run emits exactly this
     * shape into each region sheet, so today's output can feed tomorrow's comparison.
     */
    public static final class ComparisonExcelWriter implements Closeable {

        private static final String CHARTS_SHEET_NAME = "Charts";

        private static final byte[][] DELTA_PALETTE = new byte[][]{
                {(byte) 0x44, (byte) 0x72, (byte) 0xC4},
                {(byte) 0xED, (byte) 0x7D, (byte) 0x31},
                {(byte) 0x70, (byte) 0xAD, (byte) 0x47},
                {(byte) 0xFF, (byte) 0xC0, (byte) 0x00},
                {(byte) 0x5B, (byte) 0x9B, (byte) 0xD5},
                {(byte) 0xA5, (byte) 0xA5, (byte) 0xA5},
                {(byte) 0x26, (byte) 0x44, (byte) 0x78},
        };

        private final Workbook wb = new XSSFWorkbook();
        private final CellStyle headerStyle;

        private final String previousLabel;
        private String currentLabel;

        // region -> dataset -> total (from previous run CSV)
        private final Map<String, Map<String, Integer>> previousTotals = new LinkedHashMap<>();
        // region -> per-timestamp raw records (current run)
        private final Map<String, List<Rec>> currentRaw = new LinkedHashMap<>();
        // region -> dataset -> aggregated total (current run)
        private final Map<String, Map<String, Integer>> currentTotals = new LinkedHashMap<>();

        public ComparisonExcelWriter(Path previousCsv, String previousLabel, String currentLabel) throws IOException {
            this.previousLabel = blankTo(previousLabel, "Previous");
            this.currentLabel = blankTo(currentLabel, "Current");
            this.headerStyle = createHeaderStyle(wb);
            if (previousCsv != null && Files.isRegularFile(previousCsv)) {
                loadPreviousCsv(previousCsv);
            }
        }

        /**
         * Overrides the label used for the "current" column and the delta chart title.
         * Call this once the analysis name has been derived from the data (mid-process).
         * Ignored if blank.
         */
        public void setCurrentLabel(String label) {
            if (label != null && !label.isBlank()) {
                this.currentLabel = label;
            }
        }

        /** Feed one dataset run inside one timestamp for one region. */
        public void addTimestampDataset(String region,
                                        String timestamp,
                                        String dataset,
                                        int warnings,
                                        int infos,
                                        int violations) {
            String reg = blankTo(region, "UNKNOWN");
            String ds = blankTo(dataset, "UNKNOWN");
            int total = warnings + infos + violations;

            currentRaw.computeIfAbsent(reg, k -> new ArrayList<>())
                    .add(new Rec(safe(timestamp), ds, warnings, infos, violations));

            currentTotals.computeIfAbsent(reg, k -> new LinkedHashMap<>())
                    .merge(ds, total, Integer::sum);
        }

        public Path saveTo(Path outputBaseDir) throws IOException {
            Files.createDirectories(outputBaseDir);

            // Region order = current regions first, then any previous-only region.
            LinkedHashSet<String> regions = new LinkedHashSet<>(currentRaw.keySet());
            regions.addAll(previousTotals.keySet());

            for (String region : regions) {
                writeRegionSheet(region);
            }

            writeChartsSheet(regions);

            if (wb.getNumberOfSheets() == 0) {
                wb.createSheet("Comparison");
            }

            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path out = outputBaseDir.resolve("validation_comparison__" + ts + ".xlsx");
            try (OutputStream os = Files.newOutputStream(out)) {
                wb.write(os);
            }
            return out;
        }

        @Override
        public void close() throws IOException {
            wb.close();
        }

        // ---------------- region (per-timestamp) sheets ----------------

        private void writeRegionSheet(String region) {
            Sheet sheet = wb.createSheet(sanitizeSheetName(region));

            String[] header = {"Timestamp", "Dataset", "Warnings", "Infos", "Violations", "Total"};
            Row hdr = sheet.createRow(0);
            for (int c = 0; c < header.length; c++) {
                Cell cell = hdr.createCell(c);
                cell.setCellValue(header[c]);
                cell.setCellStyle(headerStyle);
            }
            sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, header.length - 1));
            sheet.createFreezePane(0, 1);

            List<Rec> recs = new ArrayList<>(currentRaw.getOrDefault(region, List.of()));
            recs.sort(Comparator.comparing((Rec r) -> r.timestamp).thenComparing(r -> r.dataset));

            int r = 1;
            for (Rec rec : recs) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(rec.timestamp);
                row.createCell(1).setCellValue(rec.dataset);
                row.createCell(2).setCellValue(rec.warnings);
                row.createCell(3).setCellValue(rec.infos);
                row.createCell(4).setCellValue(rec.violations);
                row.createCell(5).setCellValue(rec.total());
            }

            for (int c = 0; c < header.length; c++) {
                try {
                    sheet.autoSizeColumn(c);
                } catch (Exception ignore) {
                }
            }
        }

        // ---------------- charts sheet (prev vs current + delta) ----------------

        private void writeChartsSheet(Collection<String> regions) {
            XSSFSheet sheet = (XSSFSheet) wb.createSheet(CHARTS_SHEET_NAME);

            int blockTop = 0;
            int regionIndex = 0;

            for (String region : regions) {
                // union of datasets seen in current and previous runs for this region
                LinkedHashSet<String> datasets = new LinkedHashSet<>(
                        currentTotals.getOrDefault(region, Map.of()).keySet());
                datasets.addAll(previousTotals.getOrDefault(region, Map.of()).keySet());

                if (datasets.isEmpty()) {
                    continue;
                }

                List<String> sorted = new ArrayList<>(datasets);
                sorted.sort(Comparator.naturalOrder());

                // title row
                Row titleRow = sheet.createRow(blockTop);
                Cell titleCell = titleRow.createCell(0);
                titleCell.setCellValue(region);
                titleCell.setCellStyle(headerStyle);

                // header row
                int headerRow = blockTop + 1;
                Row hdr = sheet.createRow(headerRow);
                String[] cols = {
                        "Chart dataset",
                        previousLabel + " total",
                        currentLabel + " total",
                        "Delta " + currentLabel + "-" + previousLabel
                };
                for (int c = 0; c < cols.length; c++) {
                    Cell cell = hdr.createCell(c);
                    cell.setCellValue(cols[c]);
                    cell.setCellStyle(headerStyle);
                }

                int firstDataRow = headerRow + 1;
                int r = firstDataRow;

                for (String ds : sorted) {
                    int prev = previousTotals.getOrDefault(region, Map.of()).getOrDefault(ds, 0);
                    int cur = currentTotals.getOrDefault(region, Map.of()).getOrDefault(ds, 0);
                    int delta = cur - prev;

                    Row row = sheet.createRow(r++);
                    row.createCell(0).setCellValue(ds);
                    row.createCell(1).setCellValue(prev);
                    row.createCell(2).setCellValue(cur);
                    row.createCell(3).setCellValue(delta);
                }

                int lastDataRow = r - 1;

                // Delta chart to the right of the table.
                int chartTopRow = blockTop;
                createDeltaChart(
                        sheet,
                        region + " - Delta " + currentLabel + " vs " + previousLabel,
                        firstDataRow,
                        lastDataRow,
                        6, chartTopRow, 22, chartTopRow + 22
                );

                int rows = sorted.size();
                int stride = Math.max(rows + 4, 24);
                blockTop += stride;
                regionIndex++;
            }

            for (int c = 0; c <= 3; c++) {
                try {
                    sheet.autoSizeColumn(c);
                } catch (Exception ignore) {
                }
            }

            if (regionIndex == 0) {
                Row row = sheet.createRow(0);
                row.createCell(0).setCellValue("No comparison data available.");
            }
        }

        private void createDeltaChart(XSSFSheet sheet,
                                      String title,
                                      int firstDataRow,
                                      int lastDataRow,
                                      int anchorCol1,
                                      int anchorRow1,
                                      int anchorCol2,
                                      int anchorRow2) {

            XSSFDrawing drawing = sheet.createDrawingPatriarch();
            XSSFClientAnchor anchor = drawing.createAnchor(
                    0, 0, 0, 0, anchorCol1, anchorRow1, anchorCol2, anchorRow2);

            XSSFChart chart = drawing.createChart(anchor);
            chart.setTitleText(title);
            chart.setTitleOverlay(false);
            chart.getOrAddLegend().setPosition(LegendPosition.BOTTOM);

            XDDFCategoryAxis bottomAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
            bottomAxis.setTitle("Dataset");

            XDDFValueAxis leftAxis = chart.createValueAxis(AxisPosition.LEFT);
            leftAxis.setTitle("Delta in the number of triggered constraints");
            leftAxis.setCrosses(AxisCrosses.AUTO_ZERO);

            XDDFDataSource<String> categories = XDDFDataSourcesFactory.fromStringCellRange(
                    sheet, new CellRangeAddress(firstDataRow, lastDataRow, 0, 0));

            XDDFNumericalDataSource<Double> deltas = XDDFDataSourcesFactory.fromNumericCellRange(
                    sheet, new CellRangeAddress(firstDataRow, lastDataRow, 3, 3));

            XDDFBarChartData data = (XDDFBarChartData) chart.createData(ChartTypes.BAR, bottomAxis, leftAxis);
            data.setBarDirection(BarDirection.COL);
            data.setVaryColors(true);

            XDDFBarChartData.Series series = (XDDFBarChartData.Series) data.addSeries(categories, deltas);
            series.setTitle("Delta " + currentLabel + "-" + previousLabel, null);

            chart.plot(data);

            addValueDataLabels(chart);
            colorEachBar(chart, lastDataRow - firstDataRow + 1);
        }

        // ---------------- POI low-level helpers ----------------

        private static void addValueDataLabels(XSSFChart chart) {
            var plotArea = chart.getCTChart().getPlotArea();
            if (plotArea.sizeOfBarChartArray() == 0) {
                return;
            }
            var barChart = plotArea.getBarChartArray(0);
            org.openxmlformats.schemas.drawingml.x2006.chart.CTDLbls dLbls =
                    barChart.isSetDLbls() ? barChart.getDLbls() : barChart.addNewDLbls();
            setShow(dLbls.isSetShowVal() ? dLbls.getShowVal() : dLbls.addNewShowVal(), true);
            setShow(dLbls.isSetShowLegendKey() ? dLbls.getShowLegendKey() : dLbls.addNewShowLegendKey(), false);
            setShow(dLbls.isSetShowCatName() ? dLbls.getShowCatName() : dLbls.addNewShowCatName(), false);
            setShow(dLbls.isSetShowSerName() ? dLbls.getShowSerName() : dLbls.addNewShowSerName(), false);
            setShow(dLbls.isSetShowPercent() ? dLbls.getShowPercent() : dLbls.addNewShowPercent(), false);
            setShow(dLbls.isSetShowBubbleSize() ? dLbls.getShowBubbleSize() : dLbls.addNewShowBubbleSize(), false);
        }

        private static void setShow(org.openxmlformats.schemas.drawingml.x2006.chart.CTBoolean bool, boolean value) {
            bool.setVal(value);
        }

        /** Applies per-point colours (dPt) so a single-series bar chart shows multicoloured bars. */
        private static void colorEachBar(XSSFChart chart, int pointCount) {
            var plotArea = chart.getCTChart().getPlotArea();
            if (plotArea.sizeOfBarChartArray() == 0 || pointCount <= 0) {
                return;
            }
            var barChart = plotArea.getBarChartArray(0);
            if (barChart.sizeOfSerArray() == 0) {
                return;
            }
            var ser = barChart.getSerArray(0);

            for (int i = 0; i < pointCount; i++) {
                byte[] rgb = DELTA_PALETTE[i % DELTA_PALETTE.length];
                var dPt = ser.addNewDPt();
                dPt.addNewIdx().setVal(i);
                dPt.addNewInvertIfNegative().setVal(false);
                dPt.addNewBubble3D().setVal(false);
                var spPr = dPt.addNewSpPr();
                var solidFill = spPr.addNewSolidFill();
                var srgb = solidFill.addNewSrgbClr();
                srgb.setVal(rgb);
            }
        }

        // ---------------- previous run CSV ----------------

        private void loadPreviousCsv(Path csv) throws IOException {
            boolean headerSkipped = false;

            try (BufferedReader br = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }

                    if (!headerSkipped) {
                        String lower = line.replace("\uFEFF", "").toLowerCase(Locale.ROOT);
                        if (lower.startsWith("region")) {
                            headerSkipped = true;
                            continue;
                        }
                        headerSkipped = true;
                    }

                    List<String> cols = parseCsvLine(line);
                    if (cols.size() < 3) {
                        continue;
                    }

                    String region = blankTo(cols.get(0).replace("\uFEFF", "").trim(), "UNKNOWN");
                    String dataset = blankTo(cols.get(1).replace("\uFEFF", "").trim(), "UNKNOWN");
                    int total;
                    try {
                        total = (int) Math.round(Double.parseDouble(cols.get(2).trim()));
                    } catch (NumberFormatException ex) {
                        continue;
                    }

                    previousTotals.computeIfAbsent(region, k -> new LinkedHashMap<>())
                            .merge(dataset, total, Integer::sum);
                }
            }
        }

        private static List<String> parseCsvLine(String line) {
            List<String> cols = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean inQuotes = false;

            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') {
                    if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = !inQuotes;
                    }
                } else if (c == ',' && !inQuotes) {
                    cols.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
            cols.add(cur.toString());
            return cols;
        }

        // ---------------- misc ----------------

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

        private static String sanitizeSheetName(String name) {
            String s = blankTo(name, "Region");
            s = s.replaceAll("[\\\\/:*?\\[\\]]", "_");
            if (s.length() > 31) {
                s = s.substring(0, 31);
            }
            return s;
        }

        private static String blankTo(String s, String fallback) {
            return (s == null || s.isBlank()) ? fallback : s;
        }

        private static String safe(String s) {
            return s == null ? "" : s;
        }

        private static final class Rec {
            final String timestamp;
            final String dataset;
            final int warnings;
            final int infos;
            final int violations;

            Rec(String timestamp, String dataset, int warnings, int infos, int violations) {
                this.timestamp = timestamp;
                this.dataset = dataset;
                this.warnings = warnings;
                this.infos = infos;
                this.violations = violations;
            }

            int total() {
                return warnings + infos + violations;
            }
        }
    }
}