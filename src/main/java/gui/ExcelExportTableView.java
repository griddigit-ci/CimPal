/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */

package gui;


import application.MainController;
import javafx.scene.control.TableView;
import javafx.stage.FileChooser;
import org.apache.poi.ss.formula.functions.T;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

import static util.ExcelTools.*;


public class ExcelExportTableView {
    public static void export(TableView<T> tableView, String sheetname, String initialFileName, String title) {

        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet(sheetname);
        XSSFRow firstRow= sheet.createRow(0);

        // Create cell style for header row
        CellStyle headerCellStyle = createHeaderStyle(workbook);

        // Create cell style for data rows
        CellStyle dataCellStyle = createDataStyle(workbook);

        ///set titles of columns
        for (int i=0; i<tableView.getColumns().size();i++){
            XSSFCell cell = firstRow.createCell(i);
            if (tableView.getColumns().get(i).getText().equals("Value Model A")) { // this is for the comparison RDFS profile table
                cell.setCellValue(tableView.getColumns().get(i).getText()+": "+ MainController.rdfsCompareFiles.get(0));
            }else if (tableView.getColumns().get(i).getText().equals("Value Model B")){ // this is for the comparison RDFS profile table
                cell.setCellValue(tableView.getColumns().get(i).getText()+": "+ MainController.rdfsCompareFiles.get(1));
            }else {
                cell.setCellValue(tableView.getColumns().get(i).getText());
            }
            CellStyle cellStyle = headerCellStyle;
            cell.setCellStyle(cellStyle);
        }

        for (int row=0; row<tableView.getItems().size();row++){
            XSSFRow xssfRow= sheet.createRow(row+1);
            for (int col=0; col<tableView.getColumns().size(); col++){
                Object celValue = tableView.getColumns().get(col).getCellObservableValue(row).getValue();
                XSSFCell cellD = xssfRow.createCell(col);
//                try {
//                    if (celValue != null && Double.parseDouble(celValue.toString()) != 0.0) {
//                        cellD.setCellValue(Double.parseDouble(celValue.toString()));
//                    }
//                } catch (NumberFormatException e ){
//                    cellD.setCellValue(celValue.toString());
//                }

                String cellContent;
                if (celValue != null && !celValue.toString().isEmpty()) {
                    try {
                        cellContent = String.valueOf(Double.parseDouble(celValue.toString()));
                    } catch (NumberFormatException e) {
                        cellContent = celValue.toString();
                    }
                } else if (celValue != null) {
                    cellContent = celValue.toString();
                } else {
                    cellContent = "";
                }
                cellD.setCellValue(cellContent);
                CellStyle cellStyleD = dataCellStyle;
                cellD.setCellStyle(cellStyleD);
            }
        }

        // Auto-size columns after populating the data
        int maxWidthInCharacters = 120; // Maximum desired width in characters
        int defaultCharacterWidth = 256; // Default width of one character
        for (int i = 0; i < tableView.getItems().size(); i++) {
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
        sheet.setAutoFilter(CellRangeAddress.valueOf("A1:" + getColumnName(tableView.getItems().size() - 1) + (tableView.getItems().size() + 1)));




//        FileChooser filechooser = new FileChooser();
//        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Excel files", "*.xlsx"));
//        filechooser.setInitialFileName(initialFileName);
//        filechooser.setInitialDirectory(new File(MainController.prefs.get("LastWorkingFolder","")));
//        filechooser.setTitle(title);
//        File saveFile = filechooser.showSaveDialog(null);
        File saveFile = util.ModelFactory.filesavecustom("Excel files", List.of("*.xlsx"),title,"");
        if (saveFile != null) {
            MainController.prefs.put("LastWorkingFolder", saveFile.getParent());
            try {
                FileOutputStream outputStream = new FileOutputStream(saveFile);
                workbook.write(outputStream);
                workbook.close();
                outputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
