/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */

package core;

import javafx.stage.FileChooser;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDFS;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;


public class ExportRDFSdescriptions {

    public static void rdfsDescriptions(Model model) throws FileNotFoundException {
        List<String> rdfsItem = new LinkedList<>(); // list for the element - item
        List<String> rdfsItemDescription = new LinkedList<>(); // list for the description of the item
        List<String> rdfsItemMultiplicity = new LinkedList<>(); // list for the multiplicity of the item
        List<String> rdfsItemtype = new LinkedList<>(); // list for the type of the item
        List<String> rdfsItemAssociationUsed = new LinkedList<>(); // list for the association used of the item
        String rdfNs = "http://iec.ch/TC57/1999/rdf-schema-extensions-19990926#";


        //iterate on all subjects
        for (ResIterator i = model.listSubjects(); i.hasNext(); ) {
            Resource resItem = i.next();
            int mult =0;

            rdfsItem.add(resItem.toString().split("#", 2)[1]);

            try {
                String rdfsComment = resItem.getRequiredProperty(RDFS.comment).getObject().toString().split("\\^\\^", 0)[0];
                rdfsItemDescription.add(rdfsComment);
            } catch (Exception e) {
                rdfsItemDescription.add("No description defined.");
            }


            for (NodeIterator j = model.listObjectsOfProperty(resItem, model.getProperty(rdfNs, "multiplicity")); j.hasNext(); ) {
                RDFNode resItemNode = j.next();
                try {

                    rdfsItemMultiplicity.add(resItemNode.toString().split("#M:", 2)[1]);
                    mult=1;
                } catch (Exception e) {

                    mult=0;
                }
            }
            if (mult==0) {
                rdfsItemMultiplicity.add("N/A");
            }

            int isAttr=0;
            if (model.listObjectsOfProperty(resItem, model.getProperty(rdfNs, "AssociationUsed")).hasNext()) {
                for (NodeIterator j = model.listObjectsOfProperty(resItem, model.getProperty(rdfNs, "AssociationUsed")); j.hasNext(); ) {
                    RDFNode resItemNode = j.next();
                    if (resItemNode.toString().equals("Yes")) {
                        isAttr = 0;
                        rdfsItemtype.add("Association"); // it is an association
                        rdfsItemAssociationUsed.add("Yes"); // index 1 gives the direction
                    } else if (resItemNode.toString().equals("No")) {
                        isAttr = 0;
                        rdfsItemtype.add("Association"); // it is an association
                        rdfsItemAssociationUsed.add("No"); // index 1 gives the direction
                    }
                }
            } else {
                isAttr = 1;
                rdfsItemtype.add("AttributeOrClass"); // it is an attribute
                rdfsItemAssociationUsed.add("N/A");
            }

        }
        // do the excel export
        exportDesciption(rdfsItem,rdfsItemDescription,"RDFS descriptions","RDFSdescription","Save descriptions from RDFS", rdfsItemMultiplicity, rdfsItemtype, rdfsItemAssociationUsed);
    }




    private static void exportDesciption(List rdfsItem, List rdfsItemDescription, String sheetname, String initialFileName, String title, List rdfsItemMultiplicity, List rdfsItemtype, List rdfsItemAssociationUsed) throws FileNotFoundException {

        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet(sheetname);
        XSSFRow firstRow= sheet.createRow(0);

        ///set titles of columns
        firstRow.createCell(0).setCellValue("Item");
        firstRow.createCell(1).setCellValue("Description");
        firstRow.createCell(2).setCellValue("Multiplicity");
        firstRow.createCell(3).setCellValue("Type");
        firstRow.createCell(4).setCellValue("Association used");

        for (int row=0; row<rdfsItem.size();row++){
            XSSFRow xssfRow= sheet.createRow(row+1);

            Object celValue = rdfsItem.get(row);
            try {
                if (celValue != null && Double.parseDouble(celValue.toString()) != 0.0) {
                    xssfRow.createCell(0).setCellValue(Double.parseDouble(celValue.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(0).setCellValue(celValue.toString());
            }

            Object celValue1 = rdfsItemDescription.get(row);
            try {
                if (celValue1 != null && Double.parseDouble(celValue1.toString()) != 0.0) {
                    xssfRow.createCell(1).setCellValue(Double.parseDouble(celValue1.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(1).setCellValue(celValue1.toString());
            }

            Object celValue2 = rdfsItemMultiplicity.get(row);
            try {
                if (celValue2 != null && Double.parseDouble(celValue2.toString()) != 0.0) {
                    xssfRow.createCell(2).setCellValue(Double.parseDouble(celValue2.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(2).setCellValue(celValue2.toString());
            }

            Object celValue3 = rdfsItemtype.get(row);
            try {
                if (celValue3 != null && Double.parseDouble(celValue3.toString()) != 0.0) {
                    xssfRow.createCell(3).setCellValue(Double.parseDouble(celValue3.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(3).setCellValue(celValue3.toString());
            }

            Object celValue4 = rdfsItemAssociationUsed.get(row);
            try {
                if (celValue4 != null && Double.parseDouble(celValue4.toString()) != 0.0) {
                    xssfRow.createCell(4).setCellValue(Double.parseDouble(celValue4.toString()));
                }
            } catch (NumberFormatException e ){
                xssfRow.createCell(4).setCellValue(celValue4.toString());
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
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
