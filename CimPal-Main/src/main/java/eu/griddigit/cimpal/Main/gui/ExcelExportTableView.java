/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */

package eu.griddigit.cimpal.Main.gui;


import eu.griddigit.cimpal.Main.application.MainController;
import eu.griddigit.cimpal.Main.util.ModelFactory;
import javafx.scene.control.TableView;
import javafx.scene.control.TableColumn;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
//
//import java.io.File;
//import java.io.FileOutputStream;
//import java.io.IOException;
//import java.util.List;

import static eu.griddigit.cimpal.Main.util.ExcelTools.*;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

public class ExcelExportTableView {

    public static void export(TableView<RDFcomparisonResultModel> tableView, String sheetname, String initialFileName, String title) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook()) {
            workbook.setCompressTempFiles(true);

            // Create the sheet
            Sheet sheet = workbook.createSheet(sheetname);

            // Track all columns for auto-sizing
            if (sheet instanceof SXSSFSheet) {
                ((SXSSFSheet) sheet).trackAllColumnsForAutoSizing();
            }

            // Create reusable styles
            CellStyle headerCellStyle = createHeaderStyle(workbook);
            CellStyle dataCellStyle = createDataStyle(workbook);

            // Write header row
            Row headerRow = sheet.createRow(0);
            for (int colIndex = 0; colIndex < tableView.getColumns().size(); colIndex++) {
                TableColumn<RDFcomparisonResultModel, ?> column = tableView.getColumns().get(colIndex);
                String columnTitle = column.getText();
                Cell headerCell = headerRow.createCell(colIndex);
                headerCell.setCellValue(processHeaderTitle(columnTitle));
                headerCell.setCellStyle(headerCellStyle);
            }

            // Write data rows
            List<RDFcomparisonResultModel> items = tableView.getItems();
            for (int rowIndex = 0; rowIndex < items.size(); rowIndex++) {
                RDFcomparisonResultModel item = items.get(rowIndex);
                Row dataRow = sheet.createRow(rowIndex + 1);

                for (int colIndex = 0; colIndex < tableView.getColumns().size(); colIndex++) {
                    TableColumn<RDFcomparisonResultModel, ?> column = tableView.getColumns().get(colIndex);
                    Object cellValue = column.getCellData(item);

                    Cell dataCell = dataRow.createCell(colIndex);
                    dataCell.setCellValue(convertToCellContent(cellValue));
                    dataCell.setCellStyle(dataCellStyle);
                }
            }

            // Auto-size columns
            autoSizeColumns(sheet, tableView.getColumns().size(), 120);

            // Freeze top row
            sheet.createFreezePane(0, 1);

            // Apply auto-filters
            String filterRange = "A1:" + getColumnName(tableView.getColumns().size() - 1) + (items.size() + 1);
            sheet.setAutoFilter(CellRangeAddress.valueOf(filterRange));

            // Save to file
            File saveFile = ModelFactory.fileSaveCustom("Excel files", List.of("*.xlsx"), title, "");
            if (saveFile != null) {
                try (FileOutputStream outputStream = new FileOutputStream(saveFile)) {
                    workbook.write(outputStream);
                }
                MainController.prefs.put("LastWorkingFolder", saveFile.getParent());
            }
        }
    }

    private static String convertToCellContent(Object cellValue) {
        // Handle null case
        return switch (cellValue) {
            case null -> "";


            // Handle various types of objects
            case Number number ->
                // For numeric values, return the number as a string
                    cellValue.toString();
            case Boolean b ->
                // For boolean values, return "true" or "false"
                    b ? "true" : "false";
            case String s ->
                // For string values, return the value directly
                    cellValue.toString();
            default ->
                // Default option: convert to string using `toString`
                    cellValue.toString();
        };

    }

    private static String processHeaderTitle(String columnTitle) {
        // Handle null or empty column titles
        if (columnTitle == null || columnTitle.trim().isEmpty()) {
            return "Untitled Column"; // Default fallback title
        }

        // Special cases for specific column titles
        if ("Value Model A".equals(columnTitle)) {
            return columnTitle + ": " + MainController.rdfsCompareFiles.getFirst();
        } else if ("Value Model B".equals(columnTitle)) {
            return columnTitle + ": " + MainController.rdfsCompareFiles.get(1);
        }

        // Return title without modification for general cases
        return columnTitle;
    }


    // Auto-sizing columns logic - ensure max width is respected
    private static void autoSizeColumns(Sheet sheet, int columnCount, int maxWidthInCharacters) {
        final int defaultCharacterWidth = 256; // Default width of one character in Excel units
        for (int colIndex = 0; colIndex < columnCount; colIndex++) {
            sheet.autoSizeColumn(colIndex);

            // Clamp column width if it exceeds the maximum
            int currentWidthInUnits = sheet.getColumnWidth(colIndex);
            int currentWidthInCharacters = currentWidthInUnits / defaultCharacterWidth;
            if (currentWidthInCharacters > maxWidthInCharacters) {
                sheet.setColumnWidth(colIndex, maxWidthInCharacters * defaultCharacterWidth);
            }
        }
    }
//
//
//
//
//    public static void export(TableView<T> tableView, String sheetname, String initialFileName, String title) {
//
//        SXSSFWorkbook workbook = new SXSSFWorkbook();
//        SXSSFSheet sheet = workbook.createSheet(sheetname);
//        SXSSFRow firstRow= sheet.createRow(0);
//
//        // Track all columns for auto-sizing
//        sheet.trackAllColumnsForAutoSizing();
//
//        // Create cell style for header row
//        CellStyle headerCellStyle = createHeaderStyle(workbook);
//
//        // Create cell style for data rows
//        CellStyle dataCellStyle = createDataStyle(workbook);
//
//        ///set titles of columns
//        for (int i=0; i<tableView.getColumns().size();i++){
//            SXSSFCell cell = firstRow.createCell(i);
//            if (tableView.getColumns().get(i).getText().equals("Value Model A")) { // this is for the comparison RDFS profile table
//                cell.setCellValue(STR."\{tableView.getColumns().get(i).getText()}: \{MainController.rdfsCompareFiles.getFirst()}");
//            }else if (tableView.getColumns().get(i).getText().equals("Value Model B")){ // this is for the comparison RDFS profile table
//                cell.setCellValue(STR."\{tableView.getColumns().get(i).getText()}: \{MainController.rdfsCompareFiles.get(1)}");
//            }else {
//                cell.setCellValue(tableView.getColumns().get(i).getText());
//            }
//            cell.setCellStyle(headerCellStyle);
//        }
//
//        for (int row=0; row<tableView.getItems().size();row++){
//            SXSSFRow xssfRow= sheet.createRow(row+1);
//            for (int col=0; col<tableView.getColumns().size(); col++){
//                Object celValue = tableView.getColumns().get(col).getCellObservableValue(row).getValue();
//                SXSSFCell cellD = xssfRow.createCell(col);
//
//                String cellContent;
//                if (celValue != null && !celValue.toString().isEmpty()) {
//                    try {
//                        cellContent = String.valueOf(Double.parseDouble(celValue.toString()));
//                    } catch (NumberFormatException e) {
//                        cellContent = celValue.toString();
//                    }
//                } else if (celValue != null) {
//                    cellContent = celValue.toString();
//                } else {
//                    cellContent = "";
//                }
//                cellD.setCellValue(cellContent);
//                cellD.setCellStyle(dataCellStyle);
//            }
//        }
//
//        // Auto-size columns after populating the data
//        int maxWidthInCharacters = 120; // Maximum desired width in characters
//        int defaultCharacterWidth = 256; // Default width of one character
//        for (int i = 0; i < tableView.getItems().size(); i++) {
//            sheet.autoSizeColumn(i);
//
//            // Check the column width and adjust if needed
//            int currentWidthInUnits = sheet.getColumnWidth(i);
//            int currentWidthInCharacters = currentWidthInUnits / defaultCharacterWidth;
//
//            if (currentWidthInCharacters > maxWidthInCharacters) {
//                sheet.setColumnWidth(i, maxWidthInCharacters * defaultCharacterWidth);
//            }
//        }
//
//        // Freeze the top row
//        sheet.createFreezePane(0, 1); // Freeze the top row
//
//        // Add filter to the populated columns
//        int lastColumnIndex = tableView.getColumns().size() - 1;
//        int lastRowIndex = tableView.getItems().size() + 1; // assuming header row at row 1
//        sheet.setAutoFilter(CellRangeAddress.valueOf(STR."A1:\{getColumnName(lastColumnIndex)}\{lastRowIndex}"));
//
//        File saveFile = eu.griddigit.cimpal.util.ModelFactory.fileSaveCustom("Excel files", List.of("*.xlsx"),title,"");
//        if (saveFile != null) {
//            MainController.prefs.put("LastWorkingFolder", saveFile.getParent());
//            try {
//                FileOutputStream outputStream = new FileOutputStream(saveFile);
//                workbook.write(outputStream);
//                workbook.close();
//                outputStream.close();
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//        }
//    }
}
