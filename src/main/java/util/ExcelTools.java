/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */

package util;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;

public class ExcelTools {

    public static ArrayList<Object> importXLSX(String fileName, int sheetnum) {
        ArrayList<Object> dataExcel = new ArrayList();
        try {
            File excel = new File(fileName);
            FileInputStream fis = new FileInputStream(excel);
            XSSFWorkbook book = new XSSFWorkbook(fis);
            XSSFSheet sheet = book.getSheetAt(sheetnum);
            Iterator<Row> iterator = sheet.iterator();
            // Iterating over Excel file in Java

            while (iterator.hasNext()) {
                LinkedList<Object> rowItem= new LinkedList<>();
                Row currentRow = iterator.next();
                Iterator<Cell> cellIterator = currentRow.iterator();

                while (cellIterator.hasNext()) {

                    Cell currentCell = cellIterator.next();
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
}
