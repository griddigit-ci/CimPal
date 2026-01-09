package eu.griddigit.cimpal.main.application.datagenerator;

import application.taskControllers.CimPalWizardController;
import javafx.stage.FileChooser;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

public class ExportFactory {


    public static void exportQoCDC(List ruleName, List ruleSeverity, List ruleDescription, List ruleMessage, List ruleLevel, String sheetname, String initialFileName, String title) {

        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet(sheetname);
        XSSFRow firstRow= sheet.createRow(0);

        ///set titles of columns
        firstRow.createCell(0).setCellValue("Rule name");
        firstRow.createCell(1).setCellValue("Rule severity");
        firstRow.createCell(2).setCellValue("Rule description");
        firstRow.createCell(3).setCellValue("Rule message");
        firstRow.createCell(4).setCellValue("Rule level");

        for (int row=0; row<ruleName.size();row++){
            XSSFRow xssfRow= sheet.createRow(row+1);

            Object celValue = ruleName.get(row);
            try {
                if (celValue != null && Double.parseDouble(celValue.toString()) != 0.0) {
                    xssfRow.createCell(0).setCellValue(Double.parseDouble(celValue.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(0).setCellValue(celValue.toString());
            }

            Object celValue1 = ruleSeverity.get(row);
            try {
                if (celValue1 != null && Double.parseDouble(celValue1.toString()) != 0.0) {
                    xssfRow.createCell(1).setCellValue(Double.parseDouble(celValue1.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(1).setCellValue(celValue1.toString());
            }

            Object celValue2 = ruleDescription.get(row);
            try {
                if (celValue2 != null && Double.parseDouble(celValue2.toString()) != 0.0) {
                    xssfRow.createCell(2).setCellValue(Double.parseDouble(celValue2.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(2).setCellValue(celValue2.toString());
            }

            Object celValue3 = ruleMessage.get(row);
            try {
                if (celValue3 != null && Double.parseDouble(celValue3.toString()) != 0.0) {
                    xssfRow.createCell(3).setCellValue(Double.parseDouble(celValue3.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(3).setCellValue(celValue3.toString());
            }

            Object celValue4 = ruleLevel.get(row);
            try {
                if (celValue4 != null && Double.parseDouble(celValue4.toString()) != 0.0) {
                    xssfRow.createCell(4).setCellValue(Double.parseDouble(celValue4.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(4).setCellValue(celValue4.toString());
            }


        }
        FileChooser filechooser = new FileChooser();
        filechooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("QoCDC321", "*.xlsx"));
        filechooser.setInitialFileName(initialFileName);
        filechooser.setInitialDirectory(new File(CimPalWizardController.prefs.get("LastWorkingFolder","")));
        filechooser.setTitle(title);
        File saveFile = filechooser.showSaveDialog(null);
        if (saveFile != null) {
            CimPalWizardController.prefs.put("LastWorkingFolder", saveFile.getParent());
            try {
                FileOutputStream outputStream = new FileOutputStream(saveFile);
                workbook.write(outputStream);
                workbook.close();
                outputStream.flush();
                outputStream.close();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
