package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

public class ExcelTools {

    public static void exportSHACLValidationToExcel(List<SHACLValidationResult> validationResults, File selectedFolder, String fileName) {
        if (selectedFolder == null || !selectedFolder.isDirectory()) {
            System.err.println("Invalid directory: " + selectedFolder);
            return;
        }

        File outputFile = new File(selectedFolder, fileName);

        try (Workbook workbook = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(outputFile)) {
            Sheet sheet = workbook.createSheet("SHACL Report");

            // Create header row
            Row headerRow = sheet.createRow(0);
            String[] headers = {"Focus Node", "SourceShape", "Severity", "Message", "Value", "Path"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(createHeaderStyle(workbook)); // Apply bold style
            }

            // Write validation results to rows
            int rowNum = 1;
            for (SHACLValidationResult result : validationResults) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(result.getFocusNode());
                row.createCell(1).setCellValue(result.getSourceShape());
                row.createCell(2).setCellValue(result.getSeverity());
                row.createCell(3).setCellValue(result.getMessage());
                row.createCell(4).setCellValue(result.getValue());
                row.createCell(5).setCellValue(result.getPath());
            }

            // Auto-size columns
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(fos);
            System.out.println("SHACL report successfully exported to: " + outputFile.getAbsolutePath());

        } catch (IOException e) {
            System.err.println("Error exporting SHACL report: " + e.getMessage());
        }
    }

    public static CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);
        // Set fill foreground color
        style.setFillForegroundColor(IndexedColors.SKY_BLUE.getIndex());

        // Set fill pattern (solid fill)
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // Add border
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);

        return style;
    }
}
