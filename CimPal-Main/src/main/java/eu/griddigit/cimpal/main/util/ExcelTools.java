/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */

package eu.griddigit.cimpal.main.util;

import eu.griddigit.cimpal.main.model.GenDataTemplateMapInfo;
import eu.griddigit.cimpal.main.model.RDFAttributeData;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.riot.Lang;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

public class ExcelTools {

    public static ArrayList<Object> importXLSX(String fileName, int sheetNum) {
        ArrayList<Object> dataExcel = new ArrayList<>();
        try {
            File excel = new File(fileName);
            FileInputStream fis = new FileInputStream(excel);
            XSSFWorkbook book = new XSSFWorkbook(fis);
            XSSFSheet sheet = book.getSheetAt(sheetNum);

            if (sheet == null) {
                System.out.println("Sheet No." + sheetNum + 1 + " does not exist in the workbook.");
                return dataExcel;
            }

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

    public static ArrayList<Object> importXLSX(String fileName, String sheetName) {
        ArrayList<Object> dataExcel = new ArrayList<>();
        try {
            File excel = new File(fileName);
            FileInputStream fis = new FileInputStream(excel);
            XSSFWorkbook book = new XSSFWorkbook(fis);
            XSSFSheet sheet = book.getSheet(sheetName);

            if (sheet == null) {
                System.err.println("Sheet with name '" + sheetName + "' not found in the workbook.");
                return dataExcel; // Return empty list if sheet not found
            }

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

    public static ArrayList<Object> importXLSXnullSupport(String fileName, int sheetNum) {
        ArrayList<Object> dataExcel = new ArrayList<>();
        try {
            File excel = new File(fileName);
            FileInputStream fis = new FileInputStream(excel);
            XSSFWorkbook book = new XSSFWorkbook(fis);
            XSSFSheet sheet = book.getSheetAt(sheetNum);
            FormulaEvaluator evaluator = book.getCreationHelper().createFormulaEvaluator();
            // Iterating over Excel file in Java

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
                            case BOOLEAN:
                                rowItem.add(cellValue.getBooleanValue());
                                break;
                            case NUMERIC:
                                rowItem.add(cellValue.getNumberValue());
                                break;
                            case STRING:
                                rowItem.add(cellValue.getStringValue());
                                break;
                            default:
                                rowItem.add(null); // Handle other cell types as null
                        }
                    } else if (currentCell.getCellType() == CellType.STRING) {
                        rowItem.add(currentCell.getStringCellValue());
                    } else if (currentCell.getCellType() == CellType.NUMERIC) {
                        rowItem.add(currentCell.getNumericCellValue());
                    } else {
                        rowItem.add(null); // Handle other cell types as null
                    }
                }
                dataExcel.add(rowItem);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
        return dataExcel;
    }

    public static Map<String, List<String>> importXLSXToColumnMap(String fileName, int sheetNum) {
        Map<String, List<String>> result = new LinkedHashMap<>();

        try (FileInputStream fis = new FileInputStream(fileName);
             XSSFWorkbook book = new XSSFWorkbook(fis)) {

            XSSFSheet sheet = book.getSheetAt(sheetNum);

            if (sheet == null) {
                System.err.println("Sheet No." + (sheetNum + 1) + " does not exist in the workbook.");
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

            // Build unique headers
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

            // Each column is "open" until its first blank cell is found.
            boolean[] columnOpen = new boolean[colCount];
            Arrays.fill(columnOpen, true);

            for (int r = firstRowNum + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);

                for (int c = 0; c < colCount; c++) {
                    if (!columnOpen[c]) {
                        continue; // This column already ended at a previous blank cell.
                    }

                    String value = "";
                    if (row != null) {
                        Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        if (cell != null) {
                            value = readCellAsString(cell, evaluator).trim();
                        }
                    }

                    if (value.isEmpty()) {
                        columnOpen[c] = false; // Stop collecting values for this column.
                        continue;
                    }

                    result.get(headers.get(c)).add(value);
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        return result;
    }

    private static String readCellAsString(Cell cell, FormulaEvaluator evaluator) {
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            CellValue cv = evaluator.evaluate(cell);
            if (cv == null) return "";
            return switch (cv.getCellType()) {
                case STRING -> cv.getStringValue();
                case NUMERIC -> String.valueOf(cv.getNumberValue());
                case BOOLEAN -> String.valueOf(cv.getBooleanValue());
                default -> "";
            };
        }

        return switch (type) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
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
        XSSFSheet sheet;
        if (workbook.getSheet(sheetName) == null) {
            sheet = workbook.createSheet(sheetName);
        } else {
            sheet = workbook.getSheet(sheetName);
        }

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
                putExcelCellInfo(key, rowNum, columnIndex, xssfRow, mapInfo, headerCellStyle, dataCellStyle);
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

    public static CellStyle createHeaderStyle(Workbook workbook) {
        return createHeaderStyleWithColor(workbook, IndexedColors.SKY_BLUE);
    }

    public static CellStyle createHeaderStyleWithColor(Workbook workbook, IndexedColors color) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);
        style.setFillForegroundColor(color.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        return style;
    }

    public static CellStyle createDataStyle(Workbook workbook) {
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
    public static String getColumnName(int columnIndex) {
        StringBuilder columnName = new StringBuilder();
        while (columnIndex >= 0) {
            columnName.insert(0, (char) ('A' + columnIndex % 26));
            columnIndex = (columnIndex / 26) - 1;
        }
        return columnName.toString();
    }

    public static void saveExcelFile(XSSFWorkbook workbook, String title, String initialFileName) {
        File saveFile = ModelFactory.fileSaveCustom("Excel files", List.of("*.xlsx"), title, initialFileName);
        if (saveFile != null) {
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


    public static void exportMapToExcelv2(Map<String, List<String>> mapInfo, Map<String, String> prefMap,
                                          Map<String, List<List<RDFAttributeData>>> instanceClassData,
                                          XSSFWorkbook workbook, boolean hide) {

        // Create cell style for header row
        CellStyle staticHeaderCellStyle = createHeaderStyleWithColor(workbook, IndexedColors.SKY_BLUE);
        CellStyle mandatoryHeaderCellStyle = createHeaderStyleWithColor(workbook, IndexedColors.LIGHT_YELLOW);
        CellStyle optionalHeaderCellStyle = createHeaderStyleWithColor(workbook, IndexedColors.LIGHT_GREEN);

        List<GenDataTemplateMapInfo> genDataInfos = new ArrayList<>();

        List<String> classNames = mapInfo.get("ClassName");
        List<String> classes = mapInfo.get("Class");
        List<String> classDescription = mapInfo.get("ClassIfDescription");
        List<String> props = mapInfo.get("Property-AttributeAssociation");
        List<String> multiplicities = mapInfo.get("Multiplicity");
        List<String> datatypes = mapInfo.get("Datatype");
        List<String> types = mapInfo.get("Type");

        for (int i = 0; i < mapInfo.get("ClassName").size(); i++) {
            genDataInfos.add(new GenDataTemplateMapInfo(classNames.get(i), classes.get(i), props.get(i),
                    multiplicities.get(i), datatypes.get(i), types.get(i), classDescription.get(i)));
        }
        genDataInfos.sort(Comparator.comparing(GenDataTemplateMapInfo::getClassName));

        boolean hasInstanceData = instanceClassData != null;

        String headerClass = getHeaderClass(instanceClassData, classNames);

        AddConfigSheet(genDataInfos, prefMap, headerClass, staticHeaderCellStyle, workbook, instanceClassData);

        // Inverted HashMap
        HashMap<String, String> invertedPrefMap = new HashMap<>();

        // Invert the HashMap
        for (Map.Entry<String, String> entry : prefMap.entrySet()) {
            invertedPrefMap.put(entry.getValue(), entry.getKey());
        }

        // make a sheet for the first class
        String sheetName = genDataInfos.getFirst().getSheetClassName();
        XSSFSheet classSheet;
        int sColN = 1;
        if (workbook.getSheetIndex(sheetName) != -1) { // if the sheet already exists, we just get it and continue on the last column
            classSheet = workbook.getSheet(sheetName);
            sColN = classSheet.getRow(1).getLastCellNum();
        } else {
            classSheet = CreateTemplateSheetBase(sheetName,
                    genDataInfos.getFirst().getFullClassName(), staticHeaderCellStyle, mandatoryHeaderCellStyle,
                    workbook, invertedPrefMap, hasInstanceData, genDataInfos.getFirst().getClsDescr());
        }

        int maxWidthInCharacters = 150; // Maximum desired width in characters
        int defaultCharacterWidth = 256; // Default width of one character
        GenDataTemplateMapInfo prevgenDataInfo = genDataInfos.getFirst();
        for (GenDataTemplateMapInfo genDataInfo : genDataInfos) {
            sheetName = genDataInfo.getSheetClassName();
            if (!classSheet.getSheetName().equals(sheetName)) { // move to the other sheet if new class comes in the list
                if (workbook.getSheetIndex(sheetName) != -1) { // if the sheet already exists, we just get it and continue on the last column
                    classSheet = workbook.getSheet(sheetName);
                    sColN = classSheet.getRow(1).getLastCellNum();
                } else {
                    classSheet = CreateTemplateSheetBase(sheetName, genDataInfo.getFullClassName(),
                            staticHeaderCellStyle, mandatoryHeaderCellStyle,
                            workbook, invertedPrefMap, hasInstanceData, genDataInfo.getClsDescr());
                    sColN = 1;
                }
            }
            XSSFRow attrRow = classSheet.getRow(1);
            XSSFRow typeRow = classSheet.getRow(2);
            XSSFRow datatypeRow = classSheet.getRow(3);
            XSSFRow multiRow = classSheet.getRow(4);
            XSSFRow isExtensionRow = classSheet.getRow(5);
            XSSFRow mappingRow = classSheet.getRow(6);

            XSSFCell attrCell = attrRow.createCell(sColN);
            attrCell.setCellStyle(mandatoryHeaderCellStyle);
            XSSFCell typeCell = typeRow.createCell(sColN);
            typeCell.setCellStyle(mandatoryHeaderCellStyle);
            XSSFCell datatypeCell = datatypeRow.createCell(sColN);
            datatypeCell.setCellStyle(optionalHeaderCellStyle);
            XSSFCell multiCell = multiRow.createCell(sColN);
            multiCell.setCellStyle(optionalHeaderCellStyle);
            XSSFCell isExtensionCell = isExtensionRow.createCell(sColN);
            isExtensionCell.setCellStyle(optionalHeaderCellStyle);
            isExtensionCell.setCellValue("No");
            XSSFCell mappingCell = mappingRow.createCell(sColN);
            mappingCell.setCellStyle(optionalHeaderCellStyle);

            Resource propRes = ResourceFactory.createResource(genDataInfo.getProp());
            String propNameShort = genDataInfo.getProp();
            if (invertedPrefMap.containsKey(propRes.getNameSpace())) {
                propNameShort = invertedPrefMap.get(propRes.getNameSpace()) + ":" + propRes.getLocalName();
            }
            attrCell.setCellValue(propNameShort);
            String typeValue = genDataInfo.getTpe();
            if (typeValue.equals("Attribute")) {
                String dataType = genDataInfo.getDatatype();
                if (dataType.equalsIgnoreCase("LangString"))
                    typeCell.setCellValue("LiteralLangEN");
                else if (dataType.equalsIgnoreCase("URI"))
                    typeCell.setCellValue("Resource");
                else
                    typeCell.setCellValue("Literal");
            } else if (typeValue.equals("Association"))
                typeCell.setCellValue("Resource");
            else
                typeCell.setCellValue(typeValue);
            String enumValuesFull = genDataInfo.getDatatype();
            if (typeValue.equals("Enumeration")) {
                String cleanEnumInput = enumValuesFull.replaceAll("[\\[\\]]", "");

                StringBuilder enumNameShort = new StringBuilder("[");
                for (String value : cleanEnumInput.split(",")) {
                    String singleValue = value.trim();
                    Resource datatypeRes = ResourceFactory.createResource(singleValue);
                    if (invertedPrefMap.containsKey(datatypeRes.getNameSpace())) {
                        if (enumNameShort.length() > 3) {
                            enumNameShort.append("; ").append(invertedPrefMap.get(datatypeRes.getNameSpace())).append(":").append(datatypeRes.getLocalName());
                        } else {
                            enumNameShort.append(invertedPrefMap.get(datatypeRes.getNameSpace())).append(":").append(datatypeRes.getLocalName());
                        }
                    } else {
                        if (enumNameShort.length() > 3) {
                            enumNameShort.append("; ").append(singleValue);
                        } else {
                            enumNameShort.append(singleValue);
                        }
                    }
                }
                enumNameShort.append("]");
                datatypeCell.setCellValue(enumNameShort.toString());

            } else {
                datatypeCell.setCellValue(enumValuesFull);
            }
            multiCell.setCellValue(genDataInfo.getMultiplicity());

            classSheet.autoSizeColumn(sColN);

            // Check the column width and adjust if needed
            int currentWidthInUnits = classSheet.getColumnWidth(sColN);
            int currentWidthInCharacters = currentWidthInUnits / defaultCharacterWidth;

            if (currentWidthInCharacters > maxWidthInCharacters) {
                classSheet.setColumnWidth(sColN, maxWidthInCharacters * defaultCharacterWidth);
            }
            // Freeze when data starts
            classSheet.createFreezePane(0, 6); // Freeze when data starts

            sColN++;
            prevgenDataInfo = genDataInfo;
        }

        if (hasInstanceData) { // if instance data is available, fill the sheets with it
            FillSheetWithInstanceData(workbook, instanceClassData, invertedPrefMap, genDataInfos);
        }

        //reordering sheets
        //Capture all sheet names
        List<String> allSheetNames = new ArrayList<>();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            allSheetNames.add(workbook.getSheetName(i));
        }

        //Config sheet always first
        List<String> orderedSheets = new ArrayList<>();
        String configSheetName = "Config";  // or "Template"/"RDFS_info" if those are used
        orderedSheets.add(configSheetName);

        //Extract sheet name from cell E2
        XSSFSheet configSheet = workbook.getSheet(configSheetName);
        if (configSheet != null) {
            Row row = configSheet.getRow(1);
            String name = row.getCell(4).getStringCellValue();
            if (name != null && !name.isBlank() && workbook.getSheet(name) != null) {
                orderedSheets.add(name);
            }

            //Divide empty and filled sheets
            Set<String> dataSheets = new TreeSet<>();
            Set<String> emptySheets = new TreeSet<>();

            for (String orderSheetName : allSheetNames) {
                if (orderedSheets.contains(orderSheetName)) continue; // already ordered

                Sheet sheet = workbook.getSheet(orderSheetName);
                Row row8 = (sheet != null) ? sheet.getRow(7) : null;
                boolean isEmpty = true;
                if (row8 != null) {
                    for (Cell cell : row8) {
                        if (cell.getCellType() != CellType.BLANK && !cell.toString().trim().isEmpty()) {
                            isEmpty = false;
                            break;
                        }
                    }
                }
                if (isEmpty) {
                    emptySheets.add(orderSheetName);
                } else {
                    dataSheets.add(orderSheetName);
                }
            }
            orderedSheets.addAll(dataSheets);
            int emptySheetsFrom = orderedSheets.size();
            orderedSheets.addAll(emptySheets);

            //Apply ordering
            for (int i = 0; i < orderedSheets.size(); i++) {
                workbook.setSheetOrder(orderedSheets.get(i), i);
            }

            //hiding empty sheets
            if (hide) {
                for (int i = emptySheetsFrom; i < orderedSheets.size(); i++) {
                    workbook.setSheetHidden(i, true);
                }
            }

        }
    }

    private static String getHeaderClass(Map<String, List<List<RDFAttributeData>>> instanceClassData, List<String> classNames) {
        // Give the config a header class if instance data has the info if not than we pick from the RDFS class names
        String headerClass = "";
        String[] priorityClasses = {"Dataset", "DifferenceSet", "FullModel", "DifferenceModel"};
        
        if (instanceClassData != null) {
            for (String priority : priorityClasses) {
                String found = findClassKeyByLocalName(instanceClassData.keySet(), priority);
                if (found != null) {
                    headerClass = priority;  // Return the local name, not the full URI
                    break;
                }
            }
        } else {
            for (String priority : priorityClasses) {
                if (classNames.contains(priority)) {
                    headerClass = priority;
                    break;
                }
                // Try to find by local name
                for (String className : classNames) {
                    if (getLocalName(className).equals(priority)) {
                        headerClass = priority;
                        break;
                    }
                }
                if (!headerClass.isEmpty()) {
                    break;
                }
            }
        }
        return headerClass;
    }
    
    private static String findClassKeyByLocalName(Set<String> keys, String localName) {
        // Try exact match first
        if (keys.contains(localName)) {
            return localName;
        }
        // Try to find by local name (handles URIs and prefixed names)
        for (String key : keys) {
            if (getLocalName(key).equals(localName)) {
                return key;
            }
        }
        return null;
    }
    
    private static String getLocalName(String className) {
        if (className == null || className.isBlank()) {
            return className;
        }
        // Handle URI format (e.g., http://example.com#Dataset or http://example.com/Dataset)
        if (className.contains("#")) {
            return className.substring(className.lastIndexOf("#") + 1);
        }
        if (className.contains("/")) {
            return className.substring(className.lastIndexOf("/") + 1);
        }
        // Handle prefixed format (e.g., nc:Dataset)
        if (className.contains(":")) {
            return className.substring(className.lastIndexOf(":") + 1);
        }
        return className;
    }

    private static void FillSheetWithInstanceData(XSSFWorkbook workbook, Map<String,
                                                          List<List<RDFAttributeData>>> instanceClassData,
                                                  Map<String,String> invertedPrefMap,
                                                  List<GenDataTemplateMapInfo> genDataInfos) {
        if (instanceClassData == null || instanceClassData.isEmpty()) {
            return;
        }

        Map<String, GenDataTemplateMapInfo> classInfoByName = new LinkedHashMap<>();
        for (GenDataTemplateMapInfo info : genDataInfos) {
            classInfoByName.putIfAbsent(info.getClassName(), info);
        }

        for (Map.Entry<String, List<List<RDFAttributeData>>> classEntry : instanceClassData.entrySet()) {
            String classNameWithNS = classEntry.getKey();
            String className = classNameWithNS.split("#", 2)[1];
            GenDataTemplateMapInfo classInfo = classInfoByName.get(className);

            String sheetName = classInfo != null
                    ? classInfo.getSheetClassName()
                    : getSheetNameFromClassName(className);
            String fullClassName = classInfo != null
                    ? classInfo.getFullClassName()
                    : classNameWithNS;
            String classDescr = classInfo != null
                    ? classInfo.getClsDescr()
                    : "false";

            XSSFSheet sheet = workbook.getSheet(sheetName);

            if (sheet == null) {
                CellStyle staticHeaderCellStyle = createHeaderStyleWithColor(workbook, IndexedColors.SKY_BLUE);
                CellStyle mandatoryHeaderCellStyle = createHeaderStyleWithColor(workbook, IndexedColors.LIGHT_YELLOW);
                sheet = CreateTemplateSheetBase(
                        sheetName,
                        fullClassName,
                        staticHeaderCellStyle,
                        mandatoryHeaderCellStyle,
                        workbook,
                        invertedPrefMap,
                        true,
                        classDescr);
                markClassNameCellRed(workbook, sheet);
            }

            List<List<RDFAttributeData>> dataInClass = classEntry.getValue();

            if (dataInClass == null)
                continue;

            CellStyle headerStyleRed = createHeaderStyle(workbook);
            headerStyleRed.setFillForegroundColor(IndexedColors.RED.getIndex());
            CellStyle dataStyle = createDataStyle(workbook);
            XSSFRow attrRow = sheet.getRow(1) != null ? sheet.getRow(1) : sheet.createRow(1);
            XSSFRow typeRow = sheet.getRow(2) != null ? sheet.getRow(2) : sheet.createRow(2);
            XSSFRow isExtensionRow = sheet.getRow(5) != null ? sheet.getRow(5) : sheet.createRow(5);

            if (attrRow.getCell(0) == null) {
                XSSFCell attrCell = attrRow.createCell(0);
                attrCell.setCellStyle(createHeaderStyle(workbook));
                attrCell.setCellValue("rdf:id");
            }
            if (typeRow.getCell(0) == null) {
                XSSFCell typeCell = typeRow.createCell(0);
                typeCell.setCellStyle(createHeaderStyle(workbook));
                typeCell.setCellValue("Resource");
            }
            if (isExtensionRow.getCell(0) == null) {
                XSSFCell isExtensionCell = isExtensionRow.createCell(0);
                isExtensionCell.setCellStyle(createHeaderStyle(workbook));
                isExtensionCell.setCellValue("IsExtension");
            }

            int rowNumber = 7;

            for (List<RDFAttributeData> attrList : dataInClass) { // looping through the class instances
                XSSFRow row = sheet.createRow(rowNumber);
                RDFAttributeData idAttribute = attrList.stream().filter(data -> "id".equals(data.getName())).findFirst().orElse(null);
                if (idAttribute == null) {
                    System.out.println("No id attribute found for class: " + className);
                    continue;
                }
                int idCol = getCellNumber(attrRow, idAttribute.getFullName());
                if (idCol == -1) {
                    idCol = Math.max(attrRow.getLastCellNum(), 0);
                    XSSFCell attrCell = attrRow.createCell(idCol);
                    attrCell.setCellStyle(headerStyleRed);
                    attrCell.setCellValue(idAttribute.getFullName());
                    XSSFCell typeCell = typeRow.createCell(idCol);
                    typeCell.setCellStyle(headerStyleRed);
                    typeCell.setCellValue("Resource");
                    XSSFCell isExtensionCell = isExtensionRow.createCell(idCol);
                    isExtensionCell.setCellStyle(headerStyleRed);
                    isExtensionCell.setCellValue("Yes");
                }
                int rowOffset = 0;

                for (RDFAttributeData data : attrList) { // loop on every attribute (creating new rows in the xls)
                    if (data.getName().equals("type"))
                        continue;
                    int valueCol = getCellNumber(attrRow, data.getFullName());
                    if (valueCol == -1) {  // add a new attribute to the end of the row
                        valueCol = Math.max(attrRow.getLastCellNum(), 0);
                        XSSFCell attrCell = attrRow.createCell(valueCol);
                        attrCell.setCellStyle(headerStyleRed);
                        attrCell.setCellValue(data.getFullName());
                        XSSFCell typeCell = typeRow.createCell(valueCol);
                        typeCell.setCellStyle(headerStyleRed);
                        typeCell.setCellValue(data.getTpe());
                        XSSFCell isExtensionCell = isExtensionRow.createCell(valueCol);
                        isExtensionCell.setCellStyle(headerStyleRed);
                        isExtensionCell.setCellValue("Yes");
                    }
                    XSSFCell valueCell = row.getCell(valueCol);
                    int currentRowOffset = 0;
                    while (valueCell != null) {
                        currentRowOffset++;
                        XSSFRow nextRow = sheet.getRow(rowNumber + currentRowOffset);
                        if (nextRow != null)
                            valueCell = nextRow.getCell(valueCol);
                        else
                            break;
                    }
                    if (currentRowOffset > rowOffset)
                        rowOffset = currentRowOffset;

                    if (currentRowOffset == 0) {
                        valueCell = row.createCell(valueCol);
                        valueCell.setCellStyle(dataStyle);
                        valueCell.setCellValue(data.getValue());
                    } else { // Already has data in the instance, so we make a new row for the new data for the same attribute
                        XSSFRow offsetRow = sheet.getRow(rowNumber + currentRowOffset);
                        if (offsetRow == null) {
                            offsetRow = sheet.createRow(rowNumber + currentRowOffset);
                        }
                        valueCell = offsetRow.createCell(valueCol);
                        valueCell.setCellStyle(dataStyle);
                        valueCell.setCellValue(data.getValue());

                        // Set the id to the first column of the new row
                        XSSFCell idCell = offsetRow.createCell(idCol);
                        idCell.setCellStyle(dataStyle);
                        idCell.setCellValue(idAttribute.getValue());
                    }
                }
                rowNumber = rowNumber + rowOffset + 1;
            }
        }

    }

    private static String getSheetNameFromClassName(String className) {
        String safeName = className == null ? "" : className.replaceAll("[:\\\\/?*\\[\\]]", "_").trim();
        if (safeName.isEmpty()) {
            return "UnknownClass";
        }
        return safeName.length() > 31 ? safeName.substring(0, 31) : safeName;
    }

    private static void markClassNameCellRed(XSSFWorkbook workbook, XSSFSheet sheet) {
        XSSFRow classRow = sheet.getRow(0) != null ? sheet.getRow(0) : sheet.createRow(0);
        XSSFCell classCell = classRow.getCell(1) != null ? classRow.getCell(1) : classRow.createCell(1);

        CellStyle redClassStyle = createHeaderStyle(workbook);
        redClassStyle.setFillForegroundColor(IndexedColors.RED.getIndex());
        classCell.setCellStyle(redClassStyle);
    }

    private static int getCellNumber(XSSFRow attrRow, String attrName) {
        // Gives back the index of the column where the given attribute is found in the attributes row
        // if not found return -1
        for (int i = 0; i < attrRow.getLastCellNum(); i++) {
            XSSFCell cell = attrRow.getCell(i);
            if (cell.getStringCellValue().equals(attrName)) {
                return i;
            }
        }
        return -1;
    }

    private static void AddConfigSheet(List<GenDataTemplateMapInfo> genDataInfos, Map<String, String> prefMap, String headerClass,
                                       CellStyle headerCellStyle, XSSFWorkbook workbook,
                                       Map<String, List<List<RDFAttributeData>>> instanceClassData) {
        XSSFSheet configSheet = workbook.createSheet("Config");
        CellStyle mandatoryHeaderCellStyle = createHeaderStyleWithColor(workbook, IndexedColors.LIGHT_YELLOW);
        // Write header row
        XSSFRow headerRow = configSheet.createRow(0);
        XSSFCell hCell1 = headerRow.createCell(0);
        XSSFCell hCell2 = headerRow.createCell(1);
        XSSFCell hCell3 = headerRow.createCell(2);
        XSSFCell hCell4 = headerRow.createCell(3);
        XSSFCell hCell5 = headerRow.createCell(4);
        XSSFCell hCell6 = headerRow.createCell(5);

        hCell1.setCellStyle(headerCellStyle);
        hCell2.setCellStyle(headerCellStyle);
        hCell3.setCellStyle(headerCellStyle);
        hCell4.setCellStyle(headerCellStyle);
        hCell5.setCellStyle(headerCellStyle);
        hCell6.setCellStyle(headerCellStyle);

        hCell1.setCellValue("Namespace prefix");
        hCell2.setCellValue("Namespace URI");
        hCell3.setCellValue("Include Namespace [Yes,No]");
        hCell4.setCellValue("All classes (not used for xml generation)");
        hCell5.setCellValue("Classes to print [Refer to the name of the tab]");
        hCell6.setCellValue("Header class");

        List<String> sheetClassNames = new ArrayList<>();
        List<String> classNames = new ArrayList<>();
        Set<String> seenSheetNames = new HashSet<>();

        for (GenDataTemplateMapInfo info : genDataInfos) {
            String sheetName = info.getSheetClassName();
            if (seenSheetNames.add(sheetName)) {
                sheetClassNames.add(sheetName);
                classNames.add(info.getFullClassName());
            }
        }

        if (instanceClassData != null) {
            for (String xmlClassName : instanceClassData.keySet()) {
                String xmlSheetName = getSheetNameFromClassName(xmlClassName.split("#", 2)[1]);
                if (seenSheetNames.add(xmlSheetName)) {
                    sheetClassNames.add(xmlSheetName);
                    classNames.add(xmlClassName);
                }
            }
        }

        Map<String, List<String>> invertedPrefMap = prefMap.entrySet().stream()
                .collect(Collectors.groupingBy(
                        Map.Entry::getValue,
                        Collectors.mapping(Map.Entry::getKey, Collectors.toList())
                ));

        for (int i = 0; i < classNames.size(); i++) {
            String className = classNames.get(i);
            String[] parts = className.split("#", 2);
            if (parts.length == 2) {
                List<String> prefixes = invertedPrefMap.get(parts[0] + "#");
                if (prefixes != null && !prefixes.isEmpty()) {
                    className = prefixes.getFirst() + ":" + parts[1]; // Add prefix to class name
                    classNames.set(i, className); // Update the class name in the list
                }
            }
        }

        // Create rows
        List<XSSFRow> dataRows = new ArrayList<>();
        int maxRowNumber = Math.max(classNames.size(), prefMap.size());

        for (int i = 1; i < maxRowNumber + 1; i++) {
            dataRows.add(configSheet.createRow(i));
        }

        int rowN = 0;
        var prefKeys = prefMap.keySet().toArray();

        for (XSSFRow row : dataRows) {
            // add namespaces
            if (prefKeys.length > rowN) {
                row.createCell(0).setCellValue((String) prefKeys[rowN]); // Namespace prefix
                row.createCell(1).setCellValue(prefMap.get((String) prefKeys[rowN])); // Namespace URI
                row.createCell(2).setCellValue("Yes"); // Include Namespace [Yes,No]
            }

            // add classes to print
            if (classNames.size() > rowN) {
                row.createCell(3).setCellValue(classNames.get(rowN));
                // add class sheet names to print
                row.createCell(4).setCellValue(sheetClassNames.get(rowN));
            }

            // Header class is mandatory for generation; highlight the input cell even when auto-detection fails.
            if (rowN == 0) {
                XSSFCell headerClassCell = row.createCell(5);
                if (!headerClass.isEmpty()) {
                    headerClassCell.setCellValue(headerClass);
                }
                headerClassCell.setCellStyle(mandatoryHeaderCellStyle);
            }

            rowN++;
        }

    }

    private static XSSFSheet CreateTemplateSheetBase(String sheetName, String className,
                                                     CellStyle staticHeaderCellStyle,
                                                     CellStyle mandatoryHeaderCellStyle,
                                                     XSSFWorkbook workbook, Map<String, String> invertedPrefMap,
                                                     boolean hasInstanceData, String classDescr) {
        XSSFSheet sheet = workbook.createSheet(sheetName);

        // Class row
        XSSFRow firstRow = sheet.createRow(0);
        XSSFCell firstCell = firstRow.createCell(0);
        firstCell.setCellValue("Class");
        firstCell.setCellStyle(staticHeaderCellStyle);
        XSSFCell cellClass = firstRow.createCell(1);
        Resource classNameRes = ResourceFactory.createResource(className);
        String classNameShort = className;
        if (invertedPrefMap.containsKey(classNameRes.getNameSpace())) {
            classNameShort = invertedPrefMap.get(classNameRes.getNameSpace()) + ":" + classNameRes.getLocalName();
        }
        cellClass.setCellValue(classNameShort);
        cellClass.setCellStyle(mandatoryHeaderCellStyle);

        XSSFCell cellClassDescAbout = firstRow.createCell(2);
        cellClassDescAbout.setCellValue("rdf:about");
        cellClassDescAbout.setCellStyle(staticHeaderCellStyle);

        XSSFCell cellClassDesc = firstRow.createCell(3);
        if (classNameRes.getLocalName().equals("Dataset") || classNameRes.getLocalName().equals("DifferenceSet") || classNameRes.getLocalName().equals("FullModel") || classNameRes.getLocalName().equals("DifferenceModel")) {
            cellClassDesc.setCellValue("true");
        } else {
            cellClassDesc.setCellValue(classDescr);
        }
        cellClassDesc.setCellStyle(mandatoryHeaderCellStyle);

        // Attribute row
        XSSFRow row = sheet.createRow(1);
        XSSFCell cell = row.createCell(0);
        cell.setCellValue("rdf:id"); // setting rdf:id in the first column
        cell.setCellStyle(staticHeaderCellStyle);
        // Property type row
        row = sheet.createRow(2);
        cell = row.createCell(0);
        cell.setCellValue("Resource");
        cell.setCellStyle(staticHeaderCellStyle);
        // Datatype row
        row = sheet.createRow(3);
        cell = row.createCell(0);
        cell.setCellValue("Datatype");
        cell.setCellStyle(staticHeaderCellStyle);
        // Multiplicity row
        row = sheet.createRow(4);
        cell = row.createCell(0);
        cell.setCellValue("1..1");
        cell.setCellStyle(staticHeaderCellStyle);
        // Extension row
        row = sheet.createRow(5);
        cell = row.createCell(0);
        cell.setCellValue("IsExtension");
        cell.setCellStyle(staticHeaderCellStyle);
        // Mapping row
        row = sheet.createRow(6);
        cell = row.createCell(0);
        cell.setCellValue("Mapping");
        cell.setCellStyle(staticHeaderCellStyle);


        return sheet;
    }

    public static void CreateTemplateFromXMLQAR(List<File> files) throws IOException {
        Map<String, Model> modelMap = eu.griddigit.cimpal.core.utils.ModelFactory.modelLoadPerFiles(files, "http://griddigit.eu#", Lang.RDFXML);

        // Create workbook
        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet("FileData");

        // Create header style
        CellStyle headerCellStyle = createHeaderStyle(workbook);
        CellStyle dataCellStyle = createDataStyle(workbook);

        // Create header row
        XSSFRow headerRow = sheet.createRow(0);
        String[] headers = {"FileName", "FileProfile", "MAS", "FileID"};
        for (int i = 0; i < headers.length; i++) {
            XSSFCell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerCellStyle);
        }

        // Process each model and extract data
        int rowNum = 1;
        for (Map.Entry<String, Model> entry : modelMap.entrySet()) {
            String fileName = entry.getKey();
            Model model = entry.getValue();

            // Extract FileProfile from model (e.g., "EQ", "SSH", "SV", "TP")
            String fileProfile = eu.griddigit.cimpal.core.utils.ModelFactory.getProfileKeyword(model);

            // Query model for MAS and FileID
            String mas = extractMAS(model);
            String fileID = extractFileID(model);

            // Create data row
            XSSFRow dataRow = sheet.createRow(rowNum++);

            XSSFCell fileNameCell = dataRow.createCell(0);
            fileNameCell.setCellValue(fileName);
            fileNameCell.setCellStyle(dataCellStyle);

            XSSFCell profileCell = dataRow.createCell(1);
            profileCell.setCellValue(fileProfile);
            profileCell.setCellStyle(dataCellStyle);

            XSSFCell masCell = dataRow.createCell(2);
            masCell.setCellValue(mas);
            masCell.setCellStyle(dataCellStyle);

            XSSFCell fileIDCell = dataRow.createCell(3);
            fileIDCell.setCellValue(fileID);
            fileIDCell.setCellStyle(dataCellStyle);
        }

        // Auto-size columns
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }

        // Freeze header row
        sheet.createFreezePane(0, 1);

        // Add filter
        sheet.setAutoFilter(new CellRangeAddress(0, rowNum - 1, 0, headers.length - 1));

        // Save the file
        saveExcelFile(workbook, "Save QAR template", "QAR_template.xlsx");
    }

    private static String extractMAS(Model model) {
        // Query for md:Model.modelingAuthoritySet
        String query = "PREFIX md: <http://iec.ch/TC57/61970-552/ModelDescription/1#> " +
                "SELECT ?mas WHERE { ?model md:Model.modelingAuthoritySet ?mas }";

        try (org.apache.jena.query.QueryExecution qexec =
                     org.apache.jena.query.QueryExecutionFactory.create(query, model)) {
            org.apache.jena.query.ResultSet results = qexec.execSelect();
            if (results.hasNext()) {
                return results.next().get("mas").toString();
            }
        }
        return "";
    }

    private static String extractFileID(Model model) {
        // Query for rdf:about or rdf:ID of the main model resource
        String query = "PREFIX md: <http://iec.ch/TC57/61970-552/ModelDescription/1#> " +
                "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> " +
                "SELECT ?id WHERE { ?model rdf:type md:FullModel . BIND(str(?model) AS ?id) }";

        try (org.apache.jena.query.QueryExecution qexec =
                     org.apache.jena.query.QueryExecutionFactory.create(query, model)) {
            org.apache.jena.query.ResultSet results = qexec.execSelect();
            if (results.hasNext()) {
                return results.next().get("id").toString();
            }
        }
        return "";
    }

    public static Map<String, List<String>> ExcelDataToColumnMap(ArrayList<Object> sheetData) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (sheetData == null || sheetData.isEmpty()) {
            return result;
        }

        LinkedList<?> headerRow = (LinkedList<?>) sheetData.getFirst();
        int colCount = headerRow.size();

        Map<String, Integer> nameCounts = new HashMap<>();
        List<String> headers = new ArrayList<>(colCount);

        for (int c = 0; c < colCount; c++) {
            String raw = headerRow.get(c) == null ? "" : headerRow.get(c).toString().trim();
            String base = raw.isEmpty() ? "Column_" + (c + 1) : raw;
            int n = nameCounts.getOrDefault(base, 0);
            nameCounts.put(base, n + 1);

            String unique = (n == 0) ? base : base + "_" + (n + 1);
            headers.add(unique);
            result.put(unique, new ArrayList<>());
        }

        for (int r =1; r < sheetData.size(); r++) {
            LinkedList<?> row = (LinkedList<?>) sheetData.get(r);
            for (int c = 0; c < colCount; c++) {
                String v = (c < row.size() && row.get(c) != null) ? row.get(c).toString().trim() : "";
                result.get(headers.get(c)).add(v);
            }
        }
        return result;
    }

}
