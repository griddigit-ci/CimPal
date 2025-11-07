package eu.griddigit.cimpal.core.shacl_tools;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class SHACLValidationLogger {
    private Workbook workbook;
    private Sheet summarySheet;
    private int summaryRowNum = 1;
    private String currentRuleName;
    private int currentRuleErrorCount = 0;

    public SHACLValidationLogger() {
        workbook = new XSSFWorkbook();
        summarySheet = workbook.createSheet("Summary");

        // Create header row
        Row headerRow = summarySheet.createRow(0);
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);

        String[] headers = {"Rule Name", "File Name", "Error Type", "Expected Conform", "Message"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
    }

    public void startRule(String ruleName) {
        this.currentRuleName = ruleName;
        this.currentRuleErrorCount = 0;
    }

    public void logModelLoadError(String fileName, String errorMessage) {
        addErrorRow("Model Load Error", fileName, "N/A", errorMessage);
        currentRuleErrorCount++;
    }

    public void logUnexpectedTrigger(String fileName, boolean isConform, String message) {
        addErrorRow("Unexpected Trigger", fileName, String.valueOf(isConform), message);
        currentRuleErrorCount++;
    }

    public void logExpectedTriggerNotFound(String fileName, boolean isConform, String message) {
        addErrorRow("Expected Trigger Not Found", fileName, String.valueOf(isConform), message);
        currentRuleErrorCount++;
    }

    public void logValidationError(String fileName, String errorMessage) {
        addErrorRow("Validation Error", fileName, "N/A", errorMessage);
        currentRuleErrorCount++;
    }

    private void addErrorRow(String errorType, String fileName, String expectedConform, String message) {
        Row row = summarySheet.createRow(summaryRowNum++);
        row.createCell(0).setCellValue(currentRuleName);
        row.createCell(1).setCellValue(fileName);
        row.createCell(2).setCellValue(errorType);
        row.createCell(3).setCellValue(expectedConform);
        row.createCell(4).setCellValue(message);
    }

    public void saveToFile(File outputFile) throws IOException {
        // Auto-size columns
        for (int i = 0; i < 5; i++) {
            summarySheet.autoSizeColumn(i);
        }

        try (FileOutputStream fileOut = new FileOutputStream(outputFile)) {
            workbook.write(fileOut);
        }
        workbook.close();
    }

    public int getCurrentRuleErrorCount() {
        return currentRuleErrorCount;
    }
}
