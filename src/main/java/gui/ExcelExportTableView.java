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
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;


public class ExcelExportTableView {
    public static void export(TableView<T> tableView, String sheetname, String initialFileName, String title) {

        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet(sheetname);
        XSSFRow firstRow= sheet.createRow(0);

        ///set titles of columns
        for (int i=0; i<tableView.getColumns().size();i++){
            if (tableView.getColumns().get(i).getText().equals("Value Model A")) { // this is for the comparison RDFS profile table
                firstRow.createCell(i).setCellValue(tableView.getColumns().get(i).getText()+": "+ MainController.rdfsCompareFiles.get(0));
            }else if (tableView.getColumns().get(i).getText().equals("Value Model B")){ // this is for the comparison RDFS profile table
                firstRow.createCell(i).setCellValue(tableView.getColumns().get(i).getText()+": "+ MainController.rdfsCompareFiles.get(1));
            }else {
                firstRow.createCell(i).setCellValue(tableView.getColumns().get(i).getText());
            }
        }

        for (int row=0; row<tableView.getItems().size();row++){
            XSSFRow xssfRow= sheet.createRow(row+1);
            for (int col=0; col<tableView.getColumns().size(); col++){
                Object celValue = tableView.getColumns().get(col).getCellObservableValue(row).getValue();
                try {
                    if (celValue != null && Double.parseDouble(celValue.toString()) != 0.0) {
                        xssfRow.createCell(col).setCellValue(Double.parseDouble(celValue.toString()));
                    }
                } catch (NumberFormatException e ){
                    xssfRow.createCell(col).setCellValue(celValue.toString());
                }
            }
        }
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("Excel files", "*.xlsx"));
        filechooser.setInitialFileName(initialFileName);
        filechooser.setTitle(title);
        File saveFile = filechooser.showSaveDialog(null);
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
