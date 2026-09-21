package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;


public class ExcelTools {

    /**
     * Import an Excel (.xlsx) sheet by index into an ArrayList of rows.
     * Each row is a LinkedList of cell values (String or Double).
     */
    public static ArrayList<Object> importXLSX(String fileName, int sheetNum) {
        ArrayList<Object> dataExcel = new ArrayList<>();
        try {
            File excel = new File(fileName);
            FileInputStream fis = new FileInputStream(excel);
            XSSFWorkbook book = new XSSFWorkbook(fis);
            XSSFSheet sheet = book.getSheetAt(sheetNum);

            if (sheet == null) {
                System.err.println("Sheet No." + (sheetNum + 1) + " does not exist in the workbook.");
                return dataExcel;
            }

            for (Row cells : sheet) {
                LinkedList<Object> rowItem = new LinkedList<>();
                for (Cell currentCell : cells) {
                    if (currentCell.getCellType() == CellType.STRING) {
                        rowItem.add(currentCell.getStringCellValue());
                    } else if (currentCell.getCellType() == CellType.NUMERIC) {
                        rowItem.add(currentCell.getNumericCellValue());
                    }
                }
                dataExcel.add(rowItem);
            }
            book.close();
            fis.close();
        } catch (IOException e) {
            System.err.println("[ExcelTools] Error reading Excel file: " + e.getMessage());
        }
        return dataExcel;
    }

    /**
     * Import an Excel (.xlsx) sheet by name into an ArrayList of rows.
     * Each row is a LinkedList of cell values (String or Double).
     */
    public static ArrayList<Object> importXLSX(String fileName, String sheetName) {
        ArrayList<Object> dataExcel = new ArrayList<>();
        try {
            File excel = new File(fileName);
            FileInputStream fis = new FileInputStream(excel);
            XSSFWorkbook book = new XSSFWorkbook(fis);
            XSSFSheet sheet = book.getSheet(sheetName);

            if (sheet == null) {
                System.err.println("[ExcelTools] Sheet '" + sheetName + "' not found in workbook.");
                return dataExcel;
            }

            for (Row cells : sheet) {
                LinkedList<Object> rowItem = new LinkedList<>();
                for (Cell currentCell : cells) {
                    if (currentCell.getCellType() == CellType.STRING) {
                        rowItem.add(currentCell.getStringCellValue());
                    } else if (currentCell.getCellType() == CellType.NUMERIC) {
                        rowItem.add(currentCell.getNumericCellValue());
                    }
                }
                dataExcel.add(rowItem);
            }
            book.close();
            fis.close();
        } catch (IOException e) {
            System.err.println("[ExcelTools] Error reading Excel file: " + e.getMessage());
        }
        return dataExcel;
    }

    /**
     * Import an Excel (.xlsx) sheet by index into an ArrayList of rows, preserving null cells.
     * Each row is a LinkedList where empty/blank cells appear as null entries.
     * Stops reading when the first cell of a row is blank.
     */
    public static ArrayList<Object> importXLSXnullSupport(String fileName, int sheetNum) {
        ArrayList<Object> dataExcel = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(fileName);
             XSSFWorkbook book = new XSSFWorkbook(fis)) {

            XSSFSheet sheet = book.getSheetAt(sheetNum);
            if (sheet == null) {
                System.err.println("[ExcelTools] Sheet No." + (sheetNum + 1) + " does not exist in the workbook.");
                return dataExcel;
            }
            FormulaEvaluator evaluator = book.getCreationHelper().createFormulaEvaluator();

            int numColumns;
            for (Row currentRow : sheet) {
                Cell firstCell = currentRow.getCell(0, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                if (firstCell == null || firstCell.getCellType() == CellType.BLANK) {
                    break; // Stop processing if column 0 is empty
                }
                numColumns = currentRow.getLastCellNum();
                LinkedList<Object> rowItem = new LinkedList<>();
                for (int i = 0; i < numColumns; i++) {
                    Cell currentCell = currentRow.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    if (currentCell == null) {
                        rowItem.add(null);
                    } else if (currentCell.getCellType() == CellType.FORMULA) {
                        CellValue cellValue = evaluator.evaluate(currentCell);
                        switch (cellValue.getCellType()) {
                            case BOOLEAN -> rowItem.add(cellValue.getBooleanValue());
                            case NUMERIC -> rowItem.add(cellValue.getNumberValue());
                            case STRING  -> rowItem.add(cellValue.getStringValue());
                            default      -> rowItem.add(null);
                        }
                    } else if (currentCell.getCellType() == CellType.STRING) {
                        rowItem.add(currentCell.getStringCellValue());
                    } else if (currentCell.getCellType() == CellType.NUMERIC) {
                        rowItem.add(currentCell.getNumericCellValue());
                    } else {
                        rowItem.add(null);
                    }
                }
                dataExcel.add(rowItem);
            }
        } catch (IOException e) {
            System.err.println("[ExcelTools] Error reading Excel file (null-support): " + e.getMessage());
        }
        return dataExcel;
    }

    /**
     * Import an Excel (.xlsx) sheet by index into an ordered map of column-name → values.
     * Each column is collected until the first blank cell in that column is encountered.
     */
    public static Map<String, List<String>> importXLSXToColumnMap(String fileName, int sheetNum) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        try (FileInputStream fis = new FileInputStream(fileName);
             XSSFWorkbook book = new XSSFWorkbook(fis)) {

            XSSFSheet sheet = book.getSheetAt(sheetNum);
            if (sheet == null) {
                System.err.println("[ExcelTools] Sheet No." + (sheetNum + 1) + " does not exist in the workbook.");
                return result;
            }
            FormulaEvaluator evaluator = book.getCreationHelper().createFormulaEvaluator();
            int firstRowNum = sheet.getFirstRowNum();
            Row headerRow = sheet.getRow(firstRowNum);
            if (headerRow == null) return result;

            int colCount = headerRow.getLastCellNum();
            if (colCount <= 0) return result;

            Map<String, Integer> nameCounts = new HashMap<>();
            List<String> headers = new ArrayList<>(colCount);

            for (int c = 0; c < colCount; c++) {
                Cell hCell = headerRow.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                String raw = (hCell == null) ? "" : readCellAsString(hCell, evaluator).trim();
                String base = raw.isEmpty() ? "Column_" + (c + 1) : raw;
                int n = nameCounts.getOrDefault(base, 0);
                nameCounts.put(base, n + 1);
                String unique = (n == 0) ? base : base + "_" + (n + 1);
                headers.add(unique);
                result.put(unique, new ArrayList<>());
            }

            boolean[] columnOpen = new boolean[colCount];
            Arrays.fill(columnOpen, true);

            for (int r = firstRowNum + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                for (int c = 0; c < colCount; c++) {
                    if (!columnOpen[c]) continue;
                    String value = "";
                    if (row != null) {
                        Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        if (cell != null) {
                            value = readCellAsString(cell, evaluator).trim();
                        }
                    }
                    if (value.isEmpty()) {
                        columnOpen[c] = false;
                        continue;
                    }
                    result.get(headers.get(c)).add(value);
                }
            }
        } catch (IOException e) {
            System.err.println("[ExcelTools] Error reading Excel file (column-map): " + e.getMessage());
        }
        return result;
    }

    private static String readCellAsString(Cell cell, FormulaEvaluator evaluator) {
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            CellValue cv = evaluator.evaluate(cell);
            if (cv == null) return "";
            return switch (cv.getCellType()) {
                case STRING  -> cv.getStringValue();
                case NUMERIC -> String.valueOf(cv.getNumberValue());
                case BOOLEAN -> String.valueOf(cv.getBooleanValue());
                default      -> "";
            };
        }
        return switch (type) {
            case STRING  -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default      -> "";
        };
    }

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
            String[] headers = {"Focus Node", "SourceShape", "Severity", "Message", "Value", "Value kind", "Path"};
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
                row.createCell(5).setCellValue(result.getValueKind());
                row.createCell(6).setCellValue(result.getPath());
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
