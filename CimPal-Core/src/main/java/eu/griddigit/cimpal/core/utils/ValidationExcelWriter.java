/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.apache.poi.xssf.usermodel.XSSFFormulaEvaluator;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import javax.xml.parsers.ParserConfigurationException;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
    /** Raw validation rows kept in memory before SXSSF flushes them to compressed temporary files. */
    private static final int RAW_RESULTS_ROW_WINDOW = 200;

    private static final String VALIDATION_RESULTS_SHEET_NAME = "Validation results";
    private static final String CHARTS_SHEET_NAME = "Charts";

    // Office default palette used in the requested screenshots.
    private static final byte[] COLOR_WARNING   = new byte[]{(byte) 0x44, (byte) 0x72, (byte) 0xC4}; // blue
    private static final byte[] COLOR_INFO      = new byte[]{(byte) 0xED, (byte) 0x7D, (byte) 0x31}; // orange
    private static final byte[] COLOR_VIOLATION = new byte[]{(byte) 0x70, (byte) 0xAD, (byte) 0x47}; // green

    private static final String[] TIMESTAMP_OVERVIEW_HEADER = new String[]{
            "Country", "Timestamp", "Report file",
            "Validation count", "Conform validations", "Non-conform validations", "Validation errors",
            "Total results", "Violations", "Warnings", "Infos", "Worst severity", "Check priority",
            "Partial validations", "Result limit"
    };

    private static final String[] TIMESTAMP_CONSTRAINT_HEADER = new String[]{
            "Country", "Timestamp", "Report file",
            "Constraint file", "Source", "Constraint Component", "Description", "Message", "Severity", "Count"
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
            "Chart name",
            "Validation scope"
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
    private final SXSSFWorkbook streamingWorkbook;
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

    /** Charts and column widths are final layout work: perform them once, after all rows exist. */
    private boolean detailedLayoutWritten;
    private boolean summaryLayoutWritten;

    private final Map<TimestampConstraintKey, Integer> timestampConstraintStatistics = new LinkedHashMap<>();

    public ValidationExcelWriter() {
        this(false);
    }

    private ValidationExcelWriter(boolean timestampedSummaryMode) {
        this.timestampedSummaryMode = timestampedSummaryMode;
        this.wb = new XSSFWorkbook();

        CellStyle headerStyle = createHeaderStyle(wb);

        if (timestampedSummaryMode) {
            streamingWorkbook = null;
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

        // Keep headers, statistics and charts in the XSSF template so chart building
        // can read them. Append only raw result rows through SXSSF, with bounded heap
        // and compressed temporary files. Never append through both views of a sheet.
        streamingWorkbook = new SXSSFWorkbook((XSSFWorkbook) wb, RAW_RESULTS_ROW_WINDOW, true, false);
        validationResultsSheet = streamingWorkbook.getSheet(VALIDATION_RESULTS_SHEET_NAME);
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
        appendValidation(cf, datasetName, xmlFiles, missingXmlFiles, constraintFile, results,
                conforms, displayName, false, 0);
    }

    /**
     * Appends validation results and records whether the validation was intentionally cut short.
     */
    public void appendValidation(CaseFolder cf,
                                 String datasetName,
                                 String xmlFiles,
                                 String missingXmlFiles,
                                 String constraintFile,
                                 List<SHACLValidationResult> results,
                                 boolean conforms,
                                 String displayName,
                                 boolean partial,
                                 int resultLimit) {

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
                displayName,
                partial,
                resultLimit
        );
        if (results == null || results.isEmpty()) {
            return;
        }

        Sheet s = validationResultsSheet;
        int r = nextValidationResultsRow;

        for (SHACLValidationResult res : results) {
            addConstraintStatistic(reportDataset, reportXmlFiles, reportConstraintFile, res);
            writeRow(s, r++,
                    reportDataset, reportXmlFiles, reportConstraintFile,
                    safe(res.getFocusNode()), safe(res.getPath()), safe(res.getValue()),
                    safe(res.getValueKind()), safe(res.getSourceShape()),
                    safe(res.getConstraintComponent()), cleanValidationMessage(res.getMessage()),
                    safe(res.getSeverity()), safe(res.getDescription()), safe(res.getOrder()),
                    safe(res.getName()), safe(res.getGroup()));
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
                displayName,
                false,
                0
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
                                    String displayName,
                                    boolean partial,
                                    int resultLimit) {
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

        writeRow(statisticsSheet, nextStatisticsRow++,
                safe(reportDataset), safe(reportXmlFiles), safe(reportConstraintFile), all, warn, info, vio,
                conforms && validationError == null, safe(validationError), formatMissingXmlFiles(missingXmlFiles),
                safe(displayName), partial ? "Partial — stopped after " + resultLimit + " results per shape"
                        : "Complete");
    }

    private void addConstraintStatistic(String dataset,
                                        String xmlFiles,
                                        String constraintFile,
                                        SHACLValidationResult res) {
        if (res == null) {
            return;
        }

        // ClassCount constraints are global (not per-dataset); aggregate under "All".
        String effectiveDataset = safe(res.getConstraintComponent())
                .toLowerCase(Locale.ROOT).contains("classcount")
                ? "All"
                : safe(dataset);

        ConstraintStatisticKey key = new ConstraintStatisticKey(
                effectiveDataset,
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

            writeRow(statisticsConstraintSheet, r++,
                    key.dataset, key.path, key.source, entry.getValue(), key.constraintComponent, key.message,
                    key.severity, key.description, key.order, key.name, key.group);
        }
    }

    /** Writes the workbook into {@code outputBaseDir} under a name stamped with the current time. */
    public Path saveTo(Path outputBaseDir) throws IOException {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String prefix = timestampedSummaryMode ? "timestamped_validation_summary__" : "validation_report__";
        return saveAs(outputBaseDir.resolve(prefix + ts + ".xlsx"));
    }

    /** Writes the workbook to exactly {@code out}, creating its folder if needed. */
    public Path saveAs(Path out) throws IOException {
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        if (timestampedSummaryMode) {
            if (!summaryLayoutWritten) {
                writeTimestampConstraintStatisticsRows();
                // Only text columns need content measurement; numeric columns retain Excel's
                // standard width. This avoids scanning every summary cell during layout.
                autosizeColumns(timestampOverviewSheet, 0, 1, 2);
                autosizeColumns(timestampConstraintSheet, 0, 1, 2, 3, 4, 5, 6, 7, 8);
                autosizeColumns(inputCompletenessSheet, 0, 1, 3, 4, 7, 8, 9, 10);
                summaryLayoutWritten = true;
            }

            try (OutputStream os = Files.newOutputStream(out)) {
                wb.write(os);
            }
            return out;
        }

        if (!detailedLayoutWritten) {
            writeConstraintStatisticsRows();
            writeChartsSheet();
            detailedLayoutWritten = true;
        }

        if (AUTO_SIZE_COLUMNS) {
            autosize(validationResultsSheet, RAW_HEADER.length);
            autosize(statisticsSheet, STATISTICS_HEADER.length);
            autosize(statisticsConstraintSheet, STATISTICS_CONSTRAINT_HEADER.length);

            if (chartsSheet != null) {
                autosize(chartsSheet, 9);
            }
        }

        try (OutputStream os = Files.newOutputStream(out)) {
            streamingWorkbook.write(os);
        }
        return out;
    }

    @Override
    public void close() throws IOException {
        if (streamingWorkbook != null) {
            // POI 5.5.1 closes the template and deletes SXSSF temporary files here.
            streamingWorkbook.close();
        } else {
            wb.close();
        }
    }

    private static void writeHeader(Sheet sheet, String[] header, CellStyle headerStyle) {
        Row hdr = sheet.createRow(0);
        for (int c = 0; c < header.length; c++) {
            Cell cell = hdr.createCell(c);
            cell.setCellValue(header[c]);
        }
        applyStyleToRange(sheet, 0, 0, 0, header.length - 1, headerStyle);
    }

    /** POI has no native range-style API, so one shared style is applied after all cell values exist. */
    private static void applyStyleToRange(Sheet sheet, int firstRow, int lastRow,
                                          int firstColumn, int lastColumn, CellStyle style) {
        for (int rowIndex = firstRow; rowIndex <= lastRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            for (int columnIndex = firstColumn; columnIndex <= lastColumn; columnIndex++) {
                Cell cell = row.getCell(columnIndex);
                if (cell != null) cell.setCellStyle(style);
            }
        }
    }

    /** Writes a complete row before any later layout work is performed. */
    private static void writeRow(Sheet sheet, int rowIndex, Object... values) {
        Row row = sheet.createRow(rowIndex);
        for (int columnIndex = 0; columnIndex < values.length; columnIndex++) {
            Cell cell = row.createCell(columnIndex);
            Object value = values[columnIndex];
            if (value instanceof Number number) cell.setCellValue(number.doubleValue());
            else if (value instanceof Boolean bool) cell.setCellValue(bool);
            else cell.setCellValue(value == null ? "" : value.toString());
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

    // ============================================================================
    // Static reader utilities (used by regenerateComparisonXlsx)
    // ============================================================================

    public record TimestampOverviewRow(String country, String timestamp, String reportFileName) {}

    public record StatisticsRow(String label, int warnings, int infos, int violations) {}

    /**
     * Reads every data row from the {@code TimestampOverview} sheet in a
     * {@code timestamped_validation_summary__*.xlsx} file produced by a previous run.
     */
    public static List<TimestampOverviewRow> readTimestampOverview(Path summaryXlsx) throws IOException {
        List<TimestampOverviewRow> rows = new ArrayList<>();
        for (Map<Integer, String> row : readSheetRows(summaryXlsx, TIMESTAMP_OVERVIEW_SHEET_NAME)) {
            String country    = cellStr(row, 0);
            String timestamp  = cellStr(row, 1);
            String reportFile = cellStr(row, 2);
            if (country.isBlank() && timestamp.isBlank()) continue;
            rows.add(new TimestampOverviewRow(country, timestamp, reportFile));
        }
        return rows;
    }

    /**
     * Reads every statistics row from a per-timestamp {@code validation_report_*.xlsx} file.
     * The label is taken from the Chart-name column (col 10) when non-blank, otherwise from
     * the Dataset column (col 0). Rows with blank labels are skipped.
     */
    public static List<StatisticsRow> readStatisticsSheet(Path reportXlsx) throws IOException {
        List<StatisticsRow> rows = new ArrayList<>();
        for (Map<Integer, String> row : readSheetRows(reportXlsx, STATISTICS_SHEET_NAME)) {
            String chartName = cellStr(row, 10);
            String dataset   = cellStr(row, 0);
            String label = chartName.isBlank() ? dataset : chartName;
            if (label.isBlank()) continue;
            int warnings   = (int) Math.round(cellNum(row, 4));
            int infos      = (int) Math.round(cellNum(row, 5));
            int violations = (int) Math.round(cellNum(row, 6));
            rows.add(new StatisticsRow(label, warnings, infos, violations));
        }
        return rows;
    }

    /**
     * The data rows (every row after the first) of one sheet, as column index -> cell text, or an
     * empty list when the sheet does not exist. Only that sheet is parsed, as a stream: a report's
     * Validation results sheet can be far larger than POI lets {@code WorkbookFactory} hold in
     * memory (100 MB per part). Numbers come back as {@link Double#toString}, as
     * {@code getNumericCellValue()} did; formulas give their cached result.
     */
    static List<Map<Integer, String>> readSheetRows(Path xlsx, String sheetName) throws IOException {
        List<Map<Integer, String>> rows = new ArrayList<>();
        try (OPCPackage pkg = OPCPackage.open(xlsx.toFile(), PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg);
            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (sheets.hasNext()) {
                try (InputStream sheetXml = sheets.next()) {
                    if (!sheetName.equals(sheets.getSheetName())) {
                        continue;
                    }
                    DataFormatter rawNumbers = new DataFormatter() {
                        @Override
                        public String formatRawCellContents(double value, int formatIndex, String formatString) {
                            return Double.toString(value);
                        }
                    };
                    XMLReader parser = XMLHelper.newXMLReader();
                    parser.setContentHandler(new XSSFSheetXMLHandler(reader.getStylesTable(),
                            new ReadOnlySharedStringsTable(pkg), new RowCollector(rows), rawNumbers, false));
                    parser.parse(new InputSource(sheetXml));
                    return rows;
                }
            }
        } catch (OpenXML4JException | SAXException | ParserConfigurationException e) {
            throw new IOException("Cannot read sheet '" + sheetName + "' of " + xlsx.getFileName() + ": "
                    + e.getMessage(), e);
        }
        return rows;
    }

    /** Collects the cells of every row after the first. */
    private record RowCollector(List<Map<Integer, String>> rows)
            implements XSSFSheetXMLHandler.SheetContentsHandler {
        @Override
        public void startRow(int rowNum) {
            rows.add(new HashMap<>());
        }

        @Override
        public void endRow(int rowNum) {
            if (rowNum == 0) {
                rows.remove(rows.size() - 1);
            }
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            if (cellReference != null && formattedValue != null) {
                rows.get(rows.size() - 1).put((int) new CellReference(cellReference).getCol(), formattedValue);
            }
        }
    }

    private static String cellStr(Map<Integer, String> row, int col) {
        return safe(row.get(col));
    }

    private static double cellNum(Map<Integer, String> row, int col) {
        String value = row.get(col);
        if (value == null) return 0;
        try { return Double.parseDouble(value); }
        catch (NumberFormatException ignore) { return 0; }
    }

    private static void autosize(Sheet sheet, int columnCount) {
        for (int c = 0; c < columnCount; c++) {
            autosizeColumn(sheet, c);
        }
    }

    private static void autosizeColumns(Sheet sheet, int... columns) {
        for (int c : columns) {
            autosizeColumn(sheet, c);
        }
    }

    private static void autosizeColumn(Sheet sheet, int column) {
        try {
            sheet.autoSizeColumn(column);
            int width = sheet.getColumnWidth(column);
            int maxWidth = 80 * 256;
            if (width > maxWidth) {
                sheet.setColumnWidth(column, maxWidth);
            }
        } catch (Exception ignore) {
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
                                        int infoCount,
                                        int partialValidationCount,
                                        int resultLimit) {
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
        row.createCell(13).setCellValue(partialValidationCount);
        row.createCell(14).setCellValue(partialValidationCount > 0
                ? resultLimit + " results per shape"
                : "");
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
                    safe(res.getDescription()),
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
            row.createCell(6).setCellValue(key.description);
            row.createCell(7).setCellValue(key.message);
            row.createCell(8).setCellValue(key.severity);
            row.createCell(9).setCellValue(entry.getValue());
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
        private final String description;
        /** Not part of the grouping key — stores the first message seen for this description group. */
        private final String message;
        private final String severity;

        private TimestampConstraintKey(String country,
                                       String timestamp,
                                       String reportFile,
                                       String constraintFile,
                                       String source,
                                       String constraintComponent,
                                       String description,
                                       String message,
                                       String severity) {
            this.country = country;
            this.timestamp = timestamp;
            this.reportFile = reportFile;
            this.constraintFile = constraintFile;
            this.source = source;
            this.constraintComponent = constraintComponent;
            this.description = description;
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
                    && Objects.equals(description, that.description)
                    && Objects.equals(severity, that.severity);
        }

        @Override
        public int hashCode() {
            return Objects.hash(country, timestamp, reportFile, constraintFile, source, constraintComponent, description, severity);
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
        // Dataset labels are the only variable-width chart columns. Numeric series use a
        // stable readable width, avoiding eight full-sheet autosize scans per report.
        autosizeColumns(chartsSheet, 0, 5);
        for (int c : new int[]{1, 2, 3, 6, 7, 8}) {
            chartsSheet.setColumnWidth(c, 12 * 256);
        }

        XSSFSheet xs = (XSSFSheet) chartsSheet;
        String prefix = chartTitlePrefix();

        if (outRowA > 1) {
            createStackedBarChart(xs, prefix + " - Total Number of Hits",
                    0, 1, outRowA - 1, false, 10, 1, 28, 29);
            createStackedBarChart(xs, prefix + " - Total Distributed Number of Hits",
                    0, 1, outRowA - 1, true, 10, 31, 28, 59);
        }

        if (outRowB > 1) {
            createStackedBarChart(xs, prefix + " - Total Number of Hits (all datasets)",
                    5, 1, outRowB - 1, false, 10, 61, 28, 89);
            createStackedBarChart(xs, prefix + " - Total Distributed Number of Hits (all datasets)",
                    5, 1, outRowB - 1, true, 10, 91, 28, 119);
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
     *   - one sheet per run (named after the run's month), one row per region, timestamp and
     *     sets of data, i.e. mapping row (Warnings / Infos / Violations / Total); earlier runs
     *     are carried over from the previous comparison XLSX;
     *   - a "Charts" sheet with, per region and combined, the distribution over the sets of data
     *     and the delta to the previous run, then the compliance rates of every run, each table
     *     with a description row;
     *   - a "Compliance basis" sheet: per run, region and sets of data, the counts the compliance
     *     rates are taken from.
     *
     * Every number on the Charts and Compliance basis sheets is an Excel formula over the run
     * sheets, so users can see how it is calculated. The Charts and Compliance basis sheets are
     * skipped when a previous XLSX is read; older files with one sheet per region (legacy) are
     * still read.
     */
    public static final class ComparisonExcelWriter implements Closeable {

        private static final String CHARTS_SHEET_NAME = "Charts";
        private static final String BASIS_SHEET_NAME = "Compliance basis";
        private static final String COMBINED = "Combined";

        // Description rows on the Charts and Compliance basis sheets.
        private static final String DESC_INTRO =
                "Every run has its own sheet (named after the month, e.g. \"May 2026\") with one row per region, "
                + "timestamp and sets of data. Sets of data are the rows of the mapping file: model files "
                + "(e.g. EQ + SSH) validated together against one set of constraints. A hit is one SHACL result. "
                + "Only Warnings and Violations count; Infos are ignored. The hits of the sets of data are added "
                + "up over all timestamps of that run.\n"
                + "Every number on this sheet is a formula: click a cell to see how it is calculated. "
                + "Hover over a column header for its description.";
        private static final String DESC_REGION =
                "Shows which sets of data cause the warnings and violations in this region. \"%\" is the share "
                + "of the sets of data in the region's total, so each % column adds up to 100 %.";
        private static final String DESC_DELTA =
                "Delta is the change in the number of warnings + violations since the previous run: "
                + "below zero is an improvement.";
        private static final String DESC_COMBINED =
                "The same as the per-region blocks, with all regions added together.";
        private static final String DESC_COMPLIANCE =
                "Compliance rate = the percentage of sets of data that are completely clean in a run: no warnings "
                + "and no violations (summed over all timestamps). Only the sets of data validated for that region "
                + "in that run are counted. Combined counts the sets of data of all regions together, so it is not "
                + "the average of the region columns.\n"
                + "Formula: compliance rate = (clean sets of data ÷ validated sets of data) × 100. "
                + "Example: 8 clean sets of data out of 21 → 8 ÷ 21 × 100 = 38.10 %. "
                + "The sets of data are counted on the \"Compliance basis\" sheet.";
        private static final String DESC_COMPLIANCE_VIOLATIONS =
                "Same as above, but sets of data are clean when they have no violations. Warnings are ignored here.";
        private static final String DESC_COMPLIANCE_WARNINGS =
                "Same as above, but sets of data are clean when they have no warnings. Violations are ignored here.";
        private static final String DESC_BASIS =
                "One row per run, region and sets of data, with their warnings and violations for the run "
                + "(summed over all timestamps). The compliance rates on the Charts sheet count the rows where "
                + "the value is 0.";
        private static final String SETS_OF_DATA = "Sets of data";

        private static final Pattern DATE_TIME_SHEET_PATTERN = Pattern.compile("\\d{8}_\\d{6}");

        private static final byte[] COLOR_WARNINGS   = new byte[]{(byte) 0x44, (byte) 0x72, (byte) 0xC4};
        private static final byte[] COLOR_INFOS      = new byte[]{(byte) 0xED, (byte) 0x7D, (byte) 0x31};
        private static final byte[] COLOR_VIOLATIONS = new byte[]{(byte) 0x70, (byte) 0xAD, (byte) 0x47};

        private static final byte[][] DELTA_PALETTE = new byte[][]{
                {(byte) 0x44, (byte) 0x72, (byte) 0xC4},
                {(byte) 0xED, (byte) 0x7D, (byte) 0x31},
                {(byte) 0x70, (byte) 0xAD, (byte) 0x47},
                {(byte) 0xFF, (byte) 0xC0, (byte) 0x00},
                {(byte) 0x5B, (byte) 0x9B, (byte) 0xD5},
                {(byte) 0xA5, (byte) 0xA5, (byte) 0xA5},
                {(byte) 0x26, (byte) 0x44, (byte) 0x78},
        };

        private final String previousLabel;
        private String currentLabel;

        // region -> dataset -> total (W+V) from the most recent previous run sheet
        private final Map<String, Map<String, Integer>> previousTotals    = new LinkedHashMap<>();
        private final Map<String, Map<String, Integer>> previousWarnings   = new LinkedHashMap<>();
        private final Map<String, Map<String, Integer>> previousViolations = new LinkedHashMap<>();
        // date-time label -> recs for every previous run sheet carried over from the previous XLSX
        private final LinkedHashMap<String, List<Rec>> historicalSheets = new LinkedHashMap<>();
        // region -> recs for the current run
        private final Map<String, List<Rec>> currentRaw = new LinkedHashMap<>();
        // region -> dataset -> aggregated total for the current run
        private final Map<String, Map<String, Integer>> currentTotals = new LinkedHashMap<>();

        public ComparisonExcelWriter(Path previousXlsx, String previousLabel, String currentLabel)
                throws IOException {
            this.previousLabel = blankTo(previousLabel, "Previous");
            this.currentLabel  = blankTo(currentLabel,  "Current");
            if (previousXlsx != null && Files.isRegularFile(previousXlsx)) {
                loadPreviousData(previousXlsx);
            }
        }

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
            String ds  = blankTo(dataset, "UNKNOWN");
            int total  = warnings + infos + violations;

            currentRaw.computeIfAbsent(reg, k -> new ArrayList<>())
                    .add(new Rec(reg, safe(timestamp), ds, warnings, infos, violations));

            currentTotals.computeIfAbsent(reg, k -> new LinkedHashMap<>())
                    .merge(ds, total, Integer::sum);
        }

        public Path saveTo(Path outputBaseDir) throws IOException {
            Files.createDirectories(outputBaseDir);

            String currentTs = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path out = outputBaseDir.resolve("validation_comparison__" + currentTs + ".xlsx");

            try (XSSFWorkbook wb = new XSSFWorkbook()) {
                CellStyle hStyle = createHeaderStyle(wb);

                // Write historical run sheets first (oldest → newest).
                List<RunSheet> runs = new ArrayList<>();
                for (Map.Entry<String, List<Rec>> entry : historicalSheets.entrySet()) {
                    String name = writeRunSheet(wb, hStyle, entry.getKey(), entry.getValue());
                    runs.add(new RunSheet(entry.getKey(), name, entry.getValue()));
                }
                RunSheet previousRun = runs.isEmpty() ? null : runs.get(runs.size() - 1);

                // Write the current run. Use month label as sheet name (e.g. "July 2026"),
                // falling back to the creation datetime only if label was never set.
                List<Rec> currentRecs = currentRaw.values().stream()
                        .flatMap(List::stream)
                        .toList();
                String sheetLabel = currentLabel.equals("Current") ? currentTs : currentLabel;
                // Avoid duplicate sheet names if the previous XLSX already has this month.
                String uniqueLabel = sheetLabel;
                int dupIdx = 2;
                while (wb.getSheet(sanitizeSheetName(uniqueLabel)) != null) {
                    uniqueLabel = sheetLabel + " (" + dupIdx++ + ")";
                }
                RunSheet currentRun = new RunSheet(uniqueLabel,
                        writeRunSheet(wb, hStyle, uniqueLabel, currentRecs), currentRecs);
                if (!currentRecs.isEmpty()) {
                    runs.add(currentRun);
                }

                // Build region list for charts (current first, then previous-only regions).
                LinkedHashSet<String> regions = new LinkedHashSet<>(currentRaw.keySet());
                regions.addAll(previousTotals.keySet());

                writeChartsSheet(wb, hStyle, regions, currentRun, previousRun, runs);

                if (wb.getNumberOfSheets() == 0) {
                    wb.createSheet("Comparison");
                }

                // Cache the formula results so previews that do not recalculate still show numbers.
                try {
                    XSSFFormulaEvaluator.evaluateAllFormulaCells(wb);
                } catch (RuntimeException ignore) {
                    // Excel recalculates on open anyway (see below).
                }
                wb.setForceFormulaRecalculation(true);

                try (OutputStream os = Files.newOutputStream(out)) {
                    wb.write(os);
                }
            }
            return out;
        }

        @Override
        public void close() throws IOException {
            // Workbook is created and closed inside saveTo(); nothing to release here.
        }

        // ---- single run sheet (date-time named, one row per region/timestamp/dataset) ----

        /** Writes one run sheet and returns its name. Rows follow {@link RunSheet}'s layout. */
        private static String writeRunSheet(XSSFWorkbook wb,
                                            CellStyle hStyle,
                                            String dateTimeLabel,
                                            List<Rec> recs) {
            Sheet sheet = wb.createSheet(sanitizeSheetName(dateTimeLabel));

            String[] header = {"Region", "Timestamp", SETS_OF_DATA,
                               "Warnings", "Infos", "Violations", "Total"};
            Row hdr = sheet.createRow(0);
            for (int c = 0; c < header.length; c++) {
                Cell cell = hdr.createCell(c);
                cell.setCellValue(header[c]);
                cell.setCellStyle(hStyle);
            }
            sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, header.length - 1));
            sheet.createFreezePane(0, 1);

            List<Rec> sorted = recs.stream()
                    .sorted(Comparator.comparing((Rec r) -> r.region)
                            .thenComparing(r -> r.timestamp)
                            .thenComparing(r -> r.dataset))
                    .toList();

            int r = 1;
            for (Rec rec : sorted) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(rec.region);
                row.createCell(1).setCellValue(rec.timestamp);
                row.createCell(2).setCellValue(rec.dataset);
                row.createCell(3).setCellValue(rec.warnings);
                row.createCell(4).setCellValue(rec.infos);
                row.createCell(5).setCellValue(rec.violations);
                row.createCell(6).setCellValue(rec.total());
            }

            for (int c = 0; c < header.length; c++) {
                try {
                    sheet.autoSizeColumn(c);
                } catch (Exception ignore) {
                }
            }
            return sheet.getSheetName();
        }

        // ---- charts sheet: per region distribution + delta, then compliance rate ----

        /**
         * Every number on the Charts sheet is a formula over the run sheets (counts via SUMIFS) or the
         * "Compliance basis" sheet (compliance rates via COUNTIFS), so a user can trace it by clicking
         * the cell. Each table gets a description row and header comments.
         */
        private void writeChartsSheet(XSSFWorkbook wb,
                                      CellStyle hStyle,
                                      Collection<String> regions,
                                      RunSheet currentRun,
                                      RunSheet previousRun,
                                      List<RunSheet> runs) {
            XSSFSheet sheet = (XSSFSheet) wb.createSheet(CHARTS_SHEET_NAME);
            CellStyle descStyle = createDescriptionStyle(wb);
            CellStyle numStyle = wb.createCellStyle();
            numStyle.setDataFormat(wb.createDataFormat().getFormat("0.00"));

            Row introTitle = sheet.createRow(0);
            Cell introTitleCell = introTitle.createCell(0);
            introTitleCell.setCellValue("How to read this sheet");
            introTitleCell.setCellStyle(hStyle);
            writeDescription(sheet, descStyle, 1, 9, DESC_INTRO);

            int blockTop = 3;
            boolean anyData = false;

            for (String region : regions) {
                // Aggregate current run metrics per dataset.
                Map<String, int[]> curMetrics = new LinkedHashMap<>();
                for (Rec rec : currentRaw.getOrDefault(region, List.of())) {
                    curMetrics.merge(rec.dataset,
                            new int[]{rec.warnings, rec.infos, rec.violations},
                            (a, b) -> new int[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]});
                }

                Map<String, Integer> prevByDs = previousTotals.getOrDefault(region, Map.of());

                LinkedHashSet<String> dsSet = new LinkedHashSet<>(curMetrics.keySet());
                dsSet.addAll(prevByDs.keySet());
                if (dsSet.isEmpty()) {
                    continue;
                }

                List<String> sorted = new ArrayList<>(dsSet);
                sorted.sort(Comparator.naturalOrder());

                anyData = true;
                boolean hasPrev = !prevByDs.isEmpty() && previousRun != null;

                // Title row.
                Row titleRow = sheet.createRow(blockTop);
                Cell titleCell = titleRow.createCell(0);
                titleCell.setCellValue(region);
                titleCell.setCellStyle(hStyle);
                writeDescription(sheet, descStyle, blockTop + 1, 9,
                        hasPrev ? DESC_REGION + " " + DESC_DELTA : DESC_REGION);

                // Header row: 10 columns.
                int headerRow = blockTop + 2;
                Row hdrRow = sheet.createRow(headerRow);
                setCellHdr(hdrRow, hStyle, 0, SETS_OF_DATA);
                setCellHdr(hdrRow, hStyle, 1, currentLabel + " Warnings+Violations total",
                        "Warnings + violations of these sets of data in this region, summed over all timestamps of the run.");
                setCellHdr(hdrRow, hStyle, 2, currentLabel + " Warnings+Violations %",
                        "Share of these sets of data in all warnings + violations of the region. The column adds up to 100 %.");
                setCellHdr(hdrRow, hStyle, 3, currentLabel + " Warnings",
                        "Warnings of these sets of data in this region, summed over all timestamps of the run.");
                setCellHdr(hdrRow, hStyle, 4, currentLabel + " Warnings %",
                        "Share of these sets of data in all warnings of the region. The column adds up to 100 %.");
                setCellHdr(hdrRow, hStyle, 5, currentLabel + " Violations",
                        "Violations of these sets of data in this region, summed over all timestamps of the run.");
                setCellHdr(hdrRow, hStyle, 6, currentLabel + " Violations %",
                        "Share of these sets of data in all violations of the region. The column adds up to 100 %.");
                if (hasPrev) {
                    setCellHdr(hdrRow, hStyle, 7, previousLabel + " Warnings+Violations total",
                            "Warnings + violations of these sets of data in this region in the previous run.");
                    setCellHdr(hdrRow, hStyle, 8, previousLabel + " Warnings+Violations %",
                            "Share of these sets of data in all warnings + violations of the region in the previous run.");
                    setCellHdr(hdrRow, hStyle, 9, "Delta (cur−prev)",
                            "Change in warnings + violations since the previous run. Negative = fewer problems.");
                }

                int firstDataRow = headerRow + 1;
                int lastDataRow  = firstDataRow + sorted.size() - 1;

                int r = firstDataRow;

                for (String ds : sorted) {
                    Row row = sheet.createRow(r);
                    int rowNum = r + 1; // 1-based, for formulas

                    row.createCell(0).setCellValue(ds);
                    setFormula(row, 1, "D" + rowNum + "+F" + rowNum, null);
                    setFormula(row, 3, currentRun.sumIfs(3, region, ds), null);
                    setFormula(row, 5, currentRun.sumIfs(5, region, ds), null);
                    if (hasPrev) {
                        setFormula(row, 7, previousRun.sumIfs(3, region, ds)
                                + "+" + previousRun.sumIfs(5, region, ds), null);
                    }

                    writePctFormula(row, 2, 1, firstDataRow, lastDataRow, numStyle);
                    writePctFormula(row, 4, 3, firstDataRow, lastDataRow, numStyle);
                    writePctFormula(row, 6, 5, firstDataRow, lastDataRow, numStyle);
                    if (hasPrev) {
                        writePctFormula(row, 8, 7, firstDataRow, lastDataRow, numStyle);
                        writeDeltaFormula(row, 9, 1, 7);
                    }

                    r++;
                }

                int chartBot = Math.max(blockTop + 22, lastDataRow + 4);

                // Chart 1: W+V % distribution (col 2)
                createDistributionChart(sheet, region + " – " + currentLabel + " Warnings+Violations % of total",
                        firstDataRow, lastDataRow, 2, currentLabel + " Warnings+Violations %",
                        11, blockTop, 22, chartBot);

                // Chart 2: Warnings % (col 4)
                createDistributionChart(sheet, region + " – " + currentLabel + " Warnings % of total",
                        firstDataRow, lastDataRow, 4, currentLabel + " Warnings %",
                        23, blockTop, 34, chartBot);

                // Chart 3: Violations % (col 6)
                createDistributionChart(sheet, region + " – " + currentLabel + " Violations % of total",
                        firstDataRow, lastDataRow, 6, currentLabel + " Violations %",
                        35, blockTop, 46, chartBot);

                // Chart 4: Delta (col 9)
                if (hasPrev) {
                    createDeltaPctChart(sheet,
                            region + " – Delta " + currentLabel + " vs " + previousLabel,
                            firstDataRow, lastDataRow, 9,
                            47, blockTop, 58, chartBot);
                }

                blockTop = chartBot + 2;
            }

            // Combined section (all regions aggregated into one set of charts).
            blockTop = writeCombinedSection(sheet, hStyle, descStyle, numStyle, blockTop,
                    currentRun, previousRun) + 2;

            // Compliance rate section: per-region lines + Combined line, over the "Compliance basis" sheet.
            if (!runs.isEmpty()) {
                // Real regions alphabetically, Combined always last.
                List<String> sortedCompRegions = runs.stream()
                        .flatMap(run -> run.recs().stream())
                        .map(rec -> rec.region)
                        .distinct()
                        .sorted()
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
                sortedCompRegions.add(COMBINED);
                BasisSheet basis = writeBasisSheet(wb, hStyle, descStyle, runs);
                writeComplianceSection(sheet, hStyle, descStyle, numStyle, blockTop, basis, runs, sortedCompRegions);
                anyData = true;
            }

            for (int c = 0; c <= 9; c++) {
                try {
                    sheet.autoSizeColumn(c);
                } catch (Exception ignore) {
                }
            }
            fitDescriptionRows(sheet, descStyle);

            if (!anyData) {
                sheet.createRow(3).createCell(0).setCellValue("No comparison data available.");
            }
        }

        private static void setCellHdr(Row row, CellStyle hStyle, int col, String value) {
            Cell cell = row.createCell(col);
            cell.setCellValue(value);
            cell.setCellStyle(hStyle);
        }

        /** Header cell with a hover comment that explains the column. */
        private static void setCellHdr(Row row, CellStyle hStyle, int col, String value, String note) {
            setCellHdr(row, hStyle, col, value);
            Sheet sheet = row.getSheet();
            CreationHelper factory = sheet.getWorkbook().getCreationHelper();
            ClientAnchor anchor = factory.createClientAnchor();
            anchor.setCol1(col);
            anchor.setCol2(col + 3);
            anchor.setRow1(row.getRowNum());
            anchor.setRow2(row.getRowNum() + 4);
            Comment comment = sheet.createDrawingPatriarch().createCellComment(anchor);
            comment.setString(factory.createRichTextString(note));
            row.getCell(col).setCellComment(comment);
        }

        private static void setFormula(Row row, int col, String formula, CellStyle style) {
            Cell cell = row.createCell(col);
            cell.setCellFormula(formula);
            if (style != null) {
                cell.setCellStyle(style);
            }
        }

        /** Share of the column total in %, 0 when the column adds up to 0. */
        private static void writePctFormula(Row row, int pctCol, int rawCol,
                                            int firstDataRow, int lastDataRow, CellStyle style) {
            String rawColStr = CellReference.convertNumToColString(rawCol);
            int rowNum = row.getRowNum() + 1; // 1-based
            String sum = "SUM($" + rawColStr + "$" + (firstDataRow + 1)
                    + ":$" + rawColStr + "$" + (lastDataRow + 1) + ")";
            setFormula(row, pctCol, "IF(" + sum + "=0,0," + rawColStr + rowNum + "/" + sum + "*100)", style);
        }

        private static void writeDeltaFormula(Row row, int deltaCol, int curCol, int prevCol) {
            int rowNum = row.getRowNum() + 1;
            String formula = CellReference.convertNumToColString(curCol) + rowNum
                    + "-"
                    + CellReference.convertNumToColString(prevCol) + rowNum;
            row.createCell(deltaCol).setCellFormula(formula);
        }

        // ---- description rows ----

        private static CellStyle createDescriptionStyle(Workbook wb) {
            CellStyle style = wb.createCellStyle();
            Font font = wb.createFont();
            font.setItalic(true);
            style.setFont(font);
            style.setWrapText(true);
            style.setVerticalAlignment(VerticalAlignment.TOP);
            return style;
        }

        /** An italic, wrapped text row merged over columns 0..lastCol. Its height is set by {@link #fitDescriptionRows}. */
        private static void writeDescription(Sheet sheet, CellStyle descStyle, int rowIdx, int lastCol, String text) {
            Row row = sheet.getRow(rowIdx) != null ? sheet.getRow(rowIdx) : sheet.createRow(rowIdx);
            Cell cell = row.createCell(0);
            cell.setCellValue(text);
            cell.setCellStyle(descStyle);
            if (lastCol > 0) {
                sheet.addMergedRegion(new CellRangeAddress(rowIdx, rowIdx, 0, lastCol));
            }
        }

        /**
         * Excel does not grow merged wrapped rows by itself, so estimate the line count from the merged
         * width (call after the columns are sized).
         */
        private static void fitDescriptionRows(Sheet sheet, CellStyle descStyle) {
            float lineHeight = sheet.getDefaultRowHeightInPoints();
            for (CellRangeAddress region : sheet.getMergedRegions()) {
                Row row = sheet.getRow(region.getFirstRow());
                Cell cell = row == null ? null : row.getCell(region.getFirstColumn());
                if (cell == null || cell.getCellStyle().getIndex() != descStyle.getIndex()) {
                    continue;
                }
                int widthChars = 0;
                for (int c = region.getFirstColumn(); c <= region.getLastColumn(); c++) {
                    widthChars += sheet.getColumnWidth(c) / 256;
                }
                int lines = 0;
                for (String paragraph : cell.getStringCellValue().split("\n")) {
                    lines += Math.max(1, (int) Math.ceil(paragraph.length() / (double) Math.max(20, widthChars)));
                }
                row.setHeightInPoints(lines * lineHeight + 2);
            }
        }

        // ---- compliance basis sheet ----

        /** Where the per run/region/dataset counts are: data rows 3..lastRow (1-based). */
        private record BasisSheet(String sheetName, int lastRow) {
            String range(int col) {
                return absRange(sheetName, col, 3, lastRow);
            }
        }

        /** One row per run, region and dataset: warnings, violations and both, each a SUMIFS over the run sheet. */
        private static BasisSheet writeBasisSheet(XSSFWorkbook wb, CellStyle hStyle, CellStyle descStyle,
                                                  List<RunSheet> runs) {
            Sheet sheet = wb.createSheet(BASIS_SHEET_NAME);
            String[] header = {"Run", "Region", SETS_OF_DATA, "Warnings", "Violations", "Warnings+Violations"};
            writeDescription(sheet, descStyle, 0, header.length - 1, DESC_BASIS);
            Row hdr = sheet.createRow(1);
            for (int c = 0; c < header.length; c++) {
                setCellHdr(hdr, hStyle, c, header[c]);
            }
            sheet.createFreezePane(0, 2);

            int r = 2;
            for (RunSheet run : runs) {
                TreeMap<String, TreeSet<String>> datasetsByRegion = new TreeMap<>();
                for (Rec rec : run.recs()) {
                    datasetsByRegion.computeIfAbsent(rec.region, k -> new TreeSet<>()).add(rec.dataset);
                }
                for (Map.Entry<String, TreeSet<String>> e : datasetsByRegion.entrySet()) {
                    for (String ds : e.getValue()) {
                        Row row = sheet.createRow(r);
                        int rowNum = r + 1;
                        row.createCell(0).setCellValue(run.sheetName());
                        row.createCell(1).setCellValue(e.getKey());
                        row.createCell(2).setCellValue(ds);
                        setFormula(row, 3, run.sumIfs(3, e.getKey(), ds), null);
                        setFormula(row, 4, run.sumIfs(5, e.getKey(), ds), null);
                        setFormula(row, 5, "D" + rowNum + "+E" + rowNum, null);
                        r++;
                    }
                }
            }
            sheet.setAutoFilter(new CellRangeAddress(1, Math.max(1, r - 1), 0, header.length - 1));
            for (int c = 0; c < header.length; c++) {
                try {
                    sheet.autoSizeColumn(c);
                } catch (Exception ignore) {
                }
            }
            fitDescriptionRows(sheet, descStyle);
            return new BasisSheet(sheet.getSheetName(), Math.max(3, r));
        }

        // ---- formula text helpers ----

        /** {@code 'Sheet'!$C$from:$C$to} with the sheet name quoted for Excel. */
        private static String absRange(String sheetName, int col, int fromRow, int toRow) {
            String c = CellReference.convertNumToColString(col);
            return "'" + sheetName.replace("'", "''") + "'!$" + c + "$" + fromRow + ":$" + c + "$" + toRow;
        }

        /**
         * A SUMIFS/COUNTIFS criterion that matches {@code value} literally: wildcards and a leading
         * comparison operator are escaped, and the result is a quoted Excel string.
         */
        private static String criterion(String value) {
            String s = value.replace("~", "~~").replace("*", "~*").replace("?", "~?");
            if (s.startsWith("=") || s.startsWith("<") || s.startsWith(">")) {
                s = "=" + s;
            }
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }

        /** Single-series distribution bar chart. {@code dataCol} is 2 for current, 4 for previous. */
        private static void createDistributionChart(XSSFSheet sheet,
                                                    String title,
                                                    int firstDataRow,
                                                    int lastDataRow,
                                                    int dataCol,
                                                    String seriesLabel,
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
            // No legend — single series, title is self-explanatory.

            XDDFCategoryAxis bottomAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
            bottomAxis.setTitle(SETS_OF_DATA);
            XDDFValueAxis leftAxis = chart.createValueAxis(AxisPosition.LEFT);
            leftAxis.setTitle("% of total hits (all profiles = 100%)");
            leftAxis.setCrosses(AxisCrosses.AUTO_ZERO);
            leftAxis.setNumberFormat("0.00");

            XDDFDataSource<String> categories = XDDFDataSourcesFactory.fromStringCellRange(
                    sheet, new CellRangeAddress(firstDataRow, lastDataRow, 0, 0));
            XDDFNumericalDataSource<Double> shares = XDDFDataSourcesFactory.fromNumericCellRange(
                    sheet, new CellRangeAddress(firstDataRow, lastDataRow, dataCol, dataCol));

            XDDFBarChartData data = (XDDFBarChartData) chart.createData(
                    ChartTypes.BAR, bottomAxis, leftAxis);
            data.setBarDirection(BarDirection.COL);
            data.setVaryColors(true);

            XDDFBarChartData.Series series = (XDDFBarChartData.Series) data.addSeries(categories, shares);
            series.setTitle(seriesLabel, null);

            chart.plot(data);
            addValueDataLabels(chart);
            setDataLabelNumberFormat(chart, "0.00");
            colorEachBar(chart, lastDataRow - firstDataRow + 1);
            setXAxisLabelPositionLow(chart);
        }

        private void createDeltaPctChart(XSSFSheet sheet,
                                         String title,
                                         int firstDataRow,
                                         int lastDataRow,
                                         int deltaCol,
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
            // No legend — single series.

            XDDFCategoryAxis bottomAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
            bottomAxis.setTitle(SETS_OF_DATA);
            XDDFValueAxis leftAxis = chart.createValueAxis(AxisPosition.LEFT);
            leftAxis.setTitle("Warnings + violations difference (current − previous)");
            leftAxis.setCrosses(AxisCrosses.AUTO_ZERO);

            // Categories at col 0, delta at deltaCol.
            XDDFDataSource<String> categories = XDDFDataSourcesFactory.fromStringCellRange(
                    sheet, new CellRangeAddress(firstDataRow, lastDataRow, 0, 0));
            XDDFNumericalDataSource<Double> deltas = XDDFDataSourcesFactory.fromNumericCellRange(
                    sheet, new CellRangeAddress(firstDataRow, lastDataRow, deltaCol, deltaCol));

            XDDFBarChartData data = (XDDFBarChartData) chart.createData(
                    ChartTypes.BAR, bottomAxis, leftAxis);
            data.setBarDirection(BarDirection.COL);
            data.setVaryColors(true);

            XDDFBarChartData.Series series = (XDDFBarChartData.Series) data.addSeries(categories, deltas);
            series.setTitle("Delta " + currentLabel + " − " + previousLabel, null);

            chart.plot(data);
            addValueDataLabels(chart);
            colorEachBar(chart, lastDataRow - firstDataRow + 1);
            setXAxisLabelPositionLow(chart);
        }

        // ---- combined (all-regions) section ----

        private int writeCombinedSection(XSSFSheet sheet, CellStyle hStyle, CellStyle descStyle, CellStyle numStyle,
                                         int blockTop, RunSheet currentRun, RunSheet previousRun) {
            // Aggregate current metrics across all regions.
            Map<String, int[]> combinedCurMetrics = new LinkedHashMap<>();
            for (Rec rec : currentRaw.values().stream().flatMap(List::stream).toList()) {
                combinedCurMetrics.merge(rec.dataset,
                        new int[]{rec.warnings, rec.infos, rec.violations},
                        (a, b) -> new int[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]});
            }

            // Aggregate previous totals across all regions.
            Map<String, Integer> combinedPrevTotals = new LinkedHashMap<>();
            for (Map<String, Integer> byDs : previousTotals.values()) {
                byDs.forEach((ds, v) -> combinedPrevTotals.merge(ds, v, Integer::sum));
            }

            LinkedHashSet<String> dsSet = new LinkedHashSet<>(combinedCurMetrics.keySet());
            dsSet.addAll(combinedPrevTotals.keySet());
            if (dsSet.isEmpty()) {
                return blockTop;
            }

            List<String> sorted = new ArrayList<>(dsSet);
            sorted.sort(Comparator.naturalOrder());

            boolean hasPrev = !combinedPrevTotals.isEmpty() && previousRun != null;

            // Title row.
            Row titleRow = sheet.createRow(blockTop);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("Combined (all regions)");
            titleCell.setCellStyle(hStyle);
            writeDescription(sheet, descStyle, blockTop + 1, 5, DESC_COMBINED);

            // Header: Sets of data | Cur W+V total | Cur W+V% | Prev W+V total | Prev W+V% | Delta
            int headerRow = blockTop + 2;
            Row hdrRow = sheet.createRow(headerRow);
            setCellHdr(hdrRow, hStyle, 0, SETS_OF_DATA);
            setCellHdr(hdrRow, hStyle, 1, currentLabel + " Warnings+Violations total",
                    "Warnings + violations of these sets of data in all regions, summed over all timestamps of the run.");
            setCellHdr(hdrRow, hStyle, 2, currentLabel + " Warnings+Violations %",
                    "Share of these sets of data in all warnings + violations. The column adds up to 100 %.");
            if (hasPrev) {
                setCellHdr(hdrRow, hStyle, 3, previousLabel + " Warnings+Violations total",
                        "Warnings + violations of these sets of data in all regions in the previous run.");
                setCellHdr(hdrRow, hStyle, 4, previousLabel + " Warnings+Violations %",
                        "Share of these sets of data in all warnings + violations in the previous run.");
                setCellHdr(hdrRow, hStyle, 5, "Delta (cur − prev)",
                        "Change in warnings + violations since the previous run. Negative = fewer problems.");
            }

            int firstDataRow = headerRow + 1;
            int lastDataRow  = firstDataRow + sorted.size() - 1;
            int r = firstDataRow;

            for (String ds : sorted) {
                Row row = sheet.createRow(r);
                row.createCell(0).setCellValue(ds);
                setFormula(row, 1, currentRun.sumIfs(3, null, ds) + "+" + currentRun.sumIfs(5, null, ds), null);
                if (hasPrev) {
                    setFormula(row, 3, previousRun.sumIfs(3, null, ds) + "+" + previousRun.sumIfs(5, null, ds), null);
                }

                writePctFormula(row, 2, 1, firstDataRow, lastDataRow, numStyle);
                if (hasPrev) {
                    writePctFormula(row, 4, 3, firstDataRow, lastDataRow, numStyle);
                    writeDeltaFormula(row, 5, 1, 3);
                }

                r++;
            }

            int chartBot = Math.max(blockTop + 22, lastDataRow + 4);

            // Chart 1: current run W+V % distribution.
            createDistributionChart(sheet,
                    "Combined – " + currentLabel + " Warnings+Violations % of total",
                    firstDataRow, lastDataRow, 2, currentLabel + " Warnings+Violations %",
                    7, blockTop, 18, chartBot);

            // Chart 2: previous run W+V % distribution.
            if (hasPrev) {
                createDistributionChart(sheet,
                        "Combined – " + previousLabel + " Warnings+Violations % of total",
                        firstDataRow, lastDataRow, 4, previousLabel + " Warnings+Violations %",
                        19, blockTop, 30, chartBot);
            }

            // Chart 3: absolute delta (current − previous).
            if (hasPrev) {
                createDeltaPctChart(sheet,
                        "Combined – Delta " + currentLabel + " vs " + previousLabel,
                        firstDataRow, lastDataRow, 5,
                        31, blockTop, 42, chartBot);
            }

            return chartBot;
        }

        // ---- compliance rate section (line chart, one line per region over all runs) ----

        private static void writeComplianceSection(XSSFSheet sheet,
                                                   CellStyle hStyle,
                                                   CellStyle descStyle,
                                                   CellStyle numStyle,
                                                   int blockTop,
                                                   BasisSheet basis,
                                                   List<RunSheet> runs,
                                                   List<String> sortedRegions) {
            // Basis columns: 5 = Warnings+Violations, 4 = Violations, 3 = Warnings.
            int bp = writeOneComplianceBlock(sheet, hStyle, descStyle, numStyle, blockTop, basis, 5, runs,
                    sortedRegions, "Compliance Rate (Warnings and Violations) by Region", DESC_COMPLIANCE);
            bp = writeOneComplianceBlock(sheet, hStyle, descStyle, numStyle, bp + 2, basis, 4, runs,
                    sortedRegions, "Violations Compliance Rate (Violations) by Region", DESC_COMPLIANCE_VIOLATIONS);
            writeOneComplianceBlock(sheet, hStyle, descStyle, numStyle, bp + 2, basis, 3, runs,
                    sortedRegions, "Warnings Compliance Rate (Warnings) by Region", DESC_COMPLIANCE_WARNINGS);
        }

        /**
         * Compliance rate = clean sets of data / validated sets of data × 100, counted on the basis sheet:
         * {@code COUNTIFS(run, region, metric = 0) / COUNTIFS(run, region) * 100}, 0 when the region has
         * no sets of data in that run. Combined drops the region condition. Headers carry " (%)".
         */
        private static int writeOneComplianceBlock(XSSFSheet sheet,
                                                   CellStyle hStyle,
                                                   CellStyle descStyle,
                                                   CellStyle numStyle,
                                                   int blockTop,
                                                   BasisSheet basis,
                                                   int metricCol,
                                                   List<RunSheet> runs,
                                                   List<String> sortedRegions,
                                                   String title,
                                                   String description) {
            int descLastCol = Math.max(sortedRegions.size(), 9);

            // Title.
            Row titleRow = sheet.createRow(blockTop);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue(title);
            titleCell.setCellStyle(hStyle);
            writeDescription(sheet, descStyle, blockTop + 1, descLastCol, description);

            // Header row: Run | R1 | R2 | R3 ...
            int headerRow = blockTop + 2;
            Row hdr = sheet.createRow(headerRow);
            Cell runHdr = hdr.createCell(0); runHdr.setCellValue("Run"); runHdr.setCellStyle(hStyle);
            for (int c = 0; c < sortedRegions.size(); c++) {
                Cell cell = hdr.createCell(1 + c);
                cell.setCellValue(sortedRegions.get(c) + " (%)");
                cell.setCellStyle(hStyle);
            }

            int firstDataRow = headerRow + 1;
            int r = firstDataRow;

            for (RunSheet run : runs) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(dateTimeToMonthLabel(run.key()));
                for (int c = 0; c < sortedRegions.size(); c++) {
                    String region = sortedRegions.get(c);
                    String scope = basis.range(0) + "," + criterion(run.sheetName());
                    if (!COMBINED.equals(region)) {
                        scope += "," + basis.range(1) + "," + criterion(region);
                    }
                    String validated = "COUNTIFS(" + scope + ")";
                    String clean = "COUNTIFS(" + scope + "," + basis.range(metricCol) + ",0)";
                    setFormula(row, 1 + c,
                            "IF(" + validated + "=0,0," + clean + "/" + validated + "*100)", numStyle);
                }
            }

            int lastDataRow = r - 1;
            int numRuns = runs.size();
            int chartBot = blockTop + Math.max(25, numRuns + 7);
            int chartStartCol = descLastCol + 2;

            createComplianceLineChart(sheet,
                    title + " (%)",
                    firstDataRow, lastDataRow,
                    sortedRegions,
                    chartStartCol, blockTop, chartStartCol + 16, chartBot);

            return chartBot;
        }

        private static void createComplianceLineChart(XSSFSheet sheet,
                                                      String title,
                                                      int firstDataRow,
                                                      int lastDataRow,
                                                      List<String> regionNames,
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
            bottomAxis.setTitle("Run");
            XDDFValueAxis leftAxis = chart.createValueAxis(AxisPosition.LEFT);
            leftAxis.setTitle("Compliance rate (%)");
            leftAxis.setCrosses(AxisCrosses.AUTO_ZERO);
            leftAxis.setNumberFormat("0.0\"%\"");

            XDDFDataSource<String> categories = XDDFDataSourcesFactory.fromStringCellRange(
                    sheet, new CellRangeAddress(firstDataRow, lastDataRow, 0, 0));

            XDDFLineChartData data = (XDDFLineChartData) chart.createData(
                    ChartTypes.LINE, bottomAxis, leftAxis);

            byte[][] lineColors = {
                    COLOR_WARNINGS, COLOR_VIOLATIONS, COLOR_INFOS,
                    DELTA_PALETTE[3], DELTA_PALETTE[4], DELTA_PALETTE[5], DELTA_PALETTE[6]
            };

            for (int i = 0; i < regionNames.size(); i++) {
                XDDFNumericalDataSource<Double> values = XDDFDataSourcesFactory.fromNumericCellRange(
                        sheet, new CellRangeAddress(firstDataRow, lastDataRow, 1 + i, 1 + i));

                XDDFLineChartData.Series series =
                        (XDDFLineChartData.Series) data.addSeries(categories, values);
                series.setTitle(regionNames.get(i), null);
                series.setSmooth(false);
                series.setMarkerStyle(MarkerStyle.CIRCLE);

                // Set line colour via shape properties.
                byte[] rgb = lineColors[i % lineColors.length];
                XDDFSolidFillProperties fill = new XDDFSolidFillProperties(XDDFColor.from(rgb));
                XDDFShapeProperties sp = series.getShapeProperties();
                if (sp == null) sp = new XDDFShapeProperties();
                sp.setFillProperties(fill);
                series.setShapeProperties(sp);
            }

            chart.plot(data);
        }

        // ---- x-axis label position helper ----

        private static void setXAxisLabelPositionLow(XSSFChart chart) {
            var plotArea = chart.getCTChart().getPlotArea();
            for (int i = 0; i < plotArea.sizeOfCatAxArray(); i++) {
                var catAx = plotArea.getCatAxArray(i);
                var tlp = catAx.isSetTickLblPos() ? catAx.getTickLblPos() : catAx.addNewTickLblPos();
                tlp.setVal(org.openxmlformats.schemas.drawingml.x2006.chart.STTickLblPos.LOW);
            }
        }

        private static String dateTimeToMonthLabel(String dt) {
            if (dt == null || dt.length() < 15) return safe(dt);
            try {
                return LocalDateTime.parse(dt, DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                        .format(DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.ENGLISH));
            } catch (Exception ignore) {
                return safe(dt);
            }
        }

        // ---- loading previous run data ----

        private void loadPreviousData(Path xlsx) throws IOException {
            try (InputStream is = Files.newInputStream(xlsx);
                 Workbook prevWb = WorkbookFactory.create(is)) {

                // Detect format by the header row: new format has "Region" in cell (0,0).
                List<String> newFormatNames = new ArrayList<>();
                List<String> legacyNames    = new ArrayList<>();

                for (int i = 0; i < prevWb.getNumberOfSheets(); i++) {
                    Sheet sh = prevWb.getSheetAt(i);
                    String name = sh.getSheetName();
                    if (CHARTS_SHEET_NAME.equalsIgnoreCase(name) || BASIS_SHEET_NAME.equalsIgnoreCase(name)) {
                        continue;
                    }
                    Row hdr = sh.getRow(0);
                    if (hdr != null && "Region".equalsIgnoreCase(getCellStr(hdr, 0))) {
                        newFormatNames.add(name);
                    } else {
                        legacyNames.add(name);
                    }
                }

                if (!newFormatNames.isEmpty()) {
                    // New format: cols Region=0, Timestamp=1, Dataset=2, Warnings=3, Infos=4,
                    // Violations=5, Total=6. Sheet names are month labels (e.g. "July 2026").
                    // Preserve workbook order — the previous file's sheets are already in
                    // chronological order (oldest first). Sorting by name would break for months
                    // because alphabetical order != chronological order ("June" > "July").
                    for (String name : newFormatNames) {
                        historicalSheets.put(name, readRunSheet(prevWb.getSheet(name)));
                    }
                    // The last sheet in workbook order is the most recent previous run.
                    String mostRecent = newFormatNames.get(newFormatNames.size() - 1);
                    for (Rec rec : historicalSheets.get(mostRecent)) {
                        previousTotals.computeIfAbsent(rec.region, k -> new LinkedHashMap<>())
                                .merge(rec.dataset, rec.warnings + rec.violations, Integer::sum);  // W+V only
                        previousWarnings.computeIfAbsent(rec.region, k -> new LinkedHashMap<>())
                                .merge(rec.dataset, rec.warnings, Integer::sum);
                        previousViolations.computeIfAbsent(rec.region, k -> new LinkedHashMap<>())
                                .merge(rec.dataset, rec.violations, Integer::sum);
                    }

                } else if (!legacyNames.isEmpty()) {
                    // Legacy format: sheet name = region, cols: Timestamp=0, Dataset=1,
                    // Warnings=2, Infos=3, Violations=4, Total=5 (no Region column).
                    String syntheticName = deriveLegacySheetName(xlsx);
                    List<Rec> recs = new ArrayList<>();
                    for (String regionName : legacyNames) {
                        Sheet sheet = prevWb.getSheet(regionName);
                        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                            Row row = sheet.getRow(r);
                            if (row == null) {
                                continue;
                            }
                            String timestamp = getCellStr(row, 0);
                            String dataset   = getCellStr(row, 1);
                            if (dataset.isBlank()) {
                                continue;
                            }
                            int warnings   = (int) Math.round(getCellNum(row, 2));
                            int infos      = (int) Math.round(getCellNum(row, 3));
                            int violations = (int) Math.round(getCellNum(row, 4));
                            recs.add(new Rec(regionName, timestamp, dataset,
                                    warnings, infos, violations));
                            previousTotals.computeIfAbsent(regionName, k -> new LinkedHashMap<>())
                                    .merge(dataset, warnings + violations, Integer::sum);
                            previousWarnings.computeIfAbsent(regionName, k -> new LinkedHashMap<>())
                                    .merge(dataset, warnings, Integer::sum);
                            previousViolations.computeIfAbsent(regionName, k -> new LinkedHashMap<>())
                                    .merge(dataset, violations, Integer::sum);
                        }
                    }
                    if (!recs.isEmpty()) {
                        historicalSheets.put(syntheticName, recs);
                    }
                }
            }
        }

        private static List<Rec> readRunSheet(Sheet sheet) {
            List<Rec> recs = new ArrayList<>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String region    = getCellStr(row, 0);
                String timestamp = getCellStr(row, 1);
                String dataset   = getCellStr(row, 2);
                if (dataset.isBlank()) {
                    continue;
                }
                int warnings   = (int) Math.round(getCellNum(row, 3));
                int infos      = (int) Math.round(getCellNum(row, 4));
                int violations = (int) Math.round(getCellNum(row, 5));
                recs.add(new Rec(region, timestamp, dataset, warnings, infos, violations));
            }
            return recs;
        }

        private static String deriveLegacySheetName(Path xlsx) {
            String fileName = xlsx.getFileName().toString();
            Matcher m = DATE_TIME_SHEET_PATTERN.matcher(fileName);
            return m.find() ? m.group() : "legacy_run";
        }

        // ---- POI low-level helpers ----

        private static String getCellStr(Row row, int col) {
            Cell cell = row.getCell(col);
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

        private static double getCellNum(Row row, int col) {
            Cell cell = row.getCell(col);
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

        private static void setSeriesColor(XDDFChartData.Series series, byte[] rgb) {
            XDDFSolidFillProperties fill = new XDDFSolidFillProperties(XDDFColor.from(rgb));
            XDDFShapeProperties properties = series.getShapeProperties();
            if (properties == null) {
                properties = new XDDFShapeProperties();
            }
            properties.setFillProperties(fill);
            series.setShapeProperties(properties);
        }

        private static void setDataLabelNumberFormat(XSSFChart chart, String formatCode) {
            var plotArea = chart.getCTChart().getPlotArea();
            if (plotArea.sizeOfBarChartArray() == 0) return;
            var barChart = plotArea.getBarChartArray(0);
            if (!barChart.isSetDLbls()) return;
            var dLbls = barChart.getDLbls();
            var numFmt = dLbls.isSetNumFmt() ? dLbls.getNumFmt() : dLbls.addNewNumFmt();
            numFmt.setFormatCode(formatCode);
            numFmt.setSourceLinked(false);
        }

        private static void addValueDataLabels(XSSFChart chart) {
            var plotArea = chart.getCTChart().getPlotArea();
            if (plotArea.sizeOfBarChartArray() == 0) {
                return;
            }
            var barChart = plotArea.getBarChartArray(0);
            org.openxmlformats.schemas.drawingml.x2006.chart.CTDLbls dLbls =
                    barChart.isSetDLbls() ? barChart.getDLbls() : barChart.addNewDLbls();
            setShow(dLbls.isSetShowVal()        ? dLbls.getShowVal()        : dLbls.addNewShowVal(),        true);
            setShow(dLbls.isSetShowLegendKey()  ? dLbls.getShowLegendKey()  : dLbls.addNewShowLegendKey(),  false);
            setShow(dLbls.isSetShowCatName()    ? dLbls.getShowCatName()    : dLbls.addNewShowCatName(),    false);
            setShow(dLbls.isSetShowSerName()    ? dLbls.getShowSerName()    : dLbls.addNewShowSerName(),    false);
            setShow(dLbls.isSetShowPercent()    ? dLbls.getShowPercent()    : dLbls.addNewShowPercent(),    false);
            setShow(dLbls.isSetShowBubbleSize() ? dLbls.getShowBubbleSize() : dLbls.addNewShowBubbleSize(), false);
        }

        private static void setShow(
                org.openxmlformats.schemas.drawingml.x2006.chart.CTBoolean bool, boolean value) {
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
                spPr.addNewSolidFill().addNewSrgbClr().setVal(rgb);
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

        private static String sanitizeSheetName(String name) {
            String s = blankTo(name, "Run");
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
            final String region;
            final String timestamp;
            final String dataset;
            final int warnings;
            final int infos;
            final int violations;

            Rec(String region, String timestamp, String dataset,
                int warnings, int infos, int violations) {
                this.region    = region;
                this.timestamp = timestamp;
                this.dataset   = dataset;
                this.warnings  = warnings;
                this.infos     = infos;
                this.violations = violations;
            }

            int total() {
                return warnings + infos + violations;
            }
        }

        /**
         * A run sheet written by {@link #writeRunSheet}: header in row 1, data in rows 2..recs+1, columns
         * Region=A, Timestamp=B, Dataset=C, Warnings=D, Infos=E, Violations=F, Total=G.
         *
         * @param key       the run's label (month label or {@code yyyyMMdd_HHmmss})
         * @param sheetName the sheet's name in the workbook
         */
        private record RunSheet(String key, String sheetName, List<Rec> recs) {
            /** {@code SUMIFS} of column {@code valueCol} for one dataset, in one region or (null) all regions. */
            String sumIfs(int valueCol, String region, String dataset) {
                int lastRow = Math.max(2, recs.size() + 1);
                String f = "SUMIFS(" + absRange(sheetName, valueCol, 2, lastRow);
                if (region != null) {
                    f += "," + absRange(sheetName, 0, 2, lastRow) + "," + criterion(region);
                }
                return f + "," + absRange(sheetName, 2, 2, lastRow) + "," + criterion(dataset) + ")";
            }
        }
    }
}
