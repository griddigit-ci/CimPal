/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */

package util;

import org.apache.jena.datatypes.RDFDatatype;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class ExcelTools {

    public static ArrayList<Object> importXLSX(String fileName, int sheetNum) {
        ArrayList<Object> dataExcel = new ArrayList<>();
        try {
            File excel = new File(fileName);
            FileInputStream fis = new FileInputStream(excel);
            XSSFWorkbook book = new XSSFWorkbook(fis);
            XSSFSheet sheet = book.getSheetAt(sheetNum);
            // Iterating over Excel file in Java

            for (Row cells : sheet) {
                LinkedList<Object> rowItem = new LinkedList<>();
                Row currentRow = cells;

                for (Cell currentCell : currentRow) {

                    //getCellTypeEnum shown as deprecated for version 3.15
                    //getCellTypeEnum ill be renamed to getCellType starting from version 4.0
                    if (currentCell.getCellType() == CellType.STRING) {
                        //System.out.print(currentCell.getStringCellValue() + "--");
                        rowItem.add(currentCell.getStringCellValue());
                    } else if (currentCell.getCellType() == CellType.NUMERIC) {
                        //System.out.print(currentCell.getNumericCellValue() + "--");
                        rowItem.add(currentCell.getNumericCellValue());
                    }

                }
                dataExcel.add(rowItem);
                //System.out.println();

            }

        } catch (IOException e) {
            e.printStackTrace();
        }
        return dataExcel;
    }

    //TODO delete this method and use the exportMapToExcel
    public static void exportToExcelMap(Map<String, RDFDatatype> dataTypeMap, OutputStream outputStream) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("RDFS Datatypes");

            // Create header row
            Row headerRow = sheet.createRow(0);
            Cell headerCell1 = headerRow.createCell(0);
            headerCell1.setCellValue("Property");

            Cell headerCell2 = headerRow.createCell(1);
            headerCell2.setCellValue("Datatype");


            int rowNumber = 1; // Start from the second row for data
            for (Map.Entry<String, RDFDatatype> entry : dataTypeMap.entrySet()) {
                Row row = sheet.createRow(rowNumber++);
                Cell propertyNameCell = row.createCell(0);
                Cell typeCell = row.createCell(1);

                propertyNameCell.setCellValue(entry.getKey());
                typeCell.setCellValue(entry.getValue().getURI());
            }

            try (outputStream) {
                workbook.write(outputStream);
            }
        }
    }

    public static XSSFWorkbook exportMapToExcel(String sheetName, Map<String, List<String>> mapInfo, List<String> orderList, XSSFWorkbook workbook) {

        XSSFSheet sheet = workbook.createSheet(sheetName);

        // Create cell style for header row
        CellStyle headerCellStyle = createHeaderStyle(workbook);

        // Create cell style for data rows
        CellStyle dataCellStyle = createDataStyle(workbook);

        List<String> leadingList = mapInfo.get(orderList.getFirst());
        int columnIndex;
        for (int rowNum = 0; rowNum < leadingList.size(); rowNum++) {
            XSSFRow xssfRow = sheet.createRow(rowNum);
            columnIndex = 0;
            for (String key : orderList) {
                putExcelCellInfo(key, rowNum, columnIndex, xssfRow, mapInfo,headerCellStyle,dataCellStyle);
                columnIndex = columnIndex + 1;
            }
        }

        // Auto-size columns after populating the data
        int maxWidthInCharacters = 120; // Maximum desired width in characters
        int defaultCharacterWidth = 256; // Default width of one character
        for (int i = 0; i < orderList.size(); i++) {
            sheet.autoSizeColumn(i);

            // Check the column width and adjust if needed
            int currentWidthInUnits = sheet.getColumnWidth(i);
            int currentWidthInCharacters = currentWidthInUnits / defaultCharacterWidth;

            if (currentWidthInCharacters > maxWidthInCharacters) {
                sheet.setColumnWidth(i, maxWidthInCharacters * defaultCharacterWidth);
            }
        }

        // Freeze the top row
        sheet.createFreezePane(0, 1); // Freeze the top row

        // Add filter to the populated columns
        sheet.setAutoFilter(CellRangeAddress.valueOf("A1:" + getColumnName(orderList.size() - 1) + (mapInfo.get(orderList.getFirst()).size() + 1)));

        return workbook;

    }

    private static void putExcelCellInfo(String key, int rowNum, int columnIndex, XSSFRow xssfRow, Map<String, List<String>> mapInfo, CellStyle headerCellStyle, CellStyle dataCellStyle) {
        Object cellValue = mapInfo.get(key).get(rowNum);

        XSSFCell cell = xssfRow.createCell(columnIndex);
        String cellContent;
        if (cellValue != null && !cellValue.toString().isEmpty()) {
            try {
                cellContent = String.valueOf(Double.parseDouble(cellValue.toString()));
            } catch (NumberFormatException e) {
                cellContent = cellValue.toString();
            }
        } else if (cellValue != null) {
            cellContent = cellValue.toString();
        } else {
            cellContent = "";
        }
        cell.setCellValue(cellContent);

        CellStyle cellStyle = rowNum == 0 ? headerCellStyle : dataCellStyle;
        cell.setCellStyle(cellStyle);
    }

    private static CellStyle createHeaderStyle(Workbook workbook) {
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

    private static CellStyle createDataStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();

        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);

        // Add border
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);

        return style;
    }

    // Utility method to get Excel column name based on column index
    private static String getColumnName(int columnIndex) {
        StringBuilder columnName = new StringBuilder();
        while (columnIndex >= 0) {
            columnName.insert(0, (char) ('A' + columnIndex % 26));
            columnIndex = (columnIndex / 26) - 1;
        }
        return columnName.toString();
    }

    public static void saveExcelFile(XSSFWorkbook workbook, String title, String initialFileName) {
        File saveFile = util.ModelFactory.filesavecustom("Excel files", List.of("*.xlsx"), title, initialFileName);
        if (saveFile != null) {
            try {
                FileOutputStream outputStream = new FileOutputStream(saveFile);
                workbook.write(outputStream);
                workbook.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
