/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 * @author Chavdar Ivanov
 */
package eu.griddigit.cimpal.core.utils;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Utility class for executing SPARQL queries and exporting results to Excel.
 * Separates business logic from GUI concerns.
 *
 * Note: Model loading is the responsibility of the caller to avoid dependencies on the Main module.
 */
public class SparqlTools {

    private SparqlTools() {
        // Utility class
    }

    /**
     * Executes a SPARQL SELECT query on the provided RDF model and exports results to Excel.
     *
     * @param queryFile File containing the SPARQL query (.rq or .sparql)
     * @param model The RDF model to query against (already loaded)
     * @param outputFile Output Excel file path
     * @throws Exception if query execution or Excel export fails
     */
    public static void executeSparqlQueryToExcelFile(
            File queryFile,
            Model model,
            File outputFile
    ) throws Exception {

        if (queryFile == null) {
            throw new IllegalArgumentException("Query file cannot be null");
        }

        String sparqlQuery = Files.readString(queryFile.toPath(), StandardCharsets.UTF_8);
        executeSparqlQueryToExcelFile(sparqlQuery, model, outputFile);
    }

    /**
     * Executes a SPARQL SELECT query text on the provided RDF model and exports results to Excel.
     *
     * @param sparqlQuery SPARQL query text
     * @param model The RDF model to query against (already loaded)
     * @param outputFile Output Excel file path
     * @throws Exception if query execution or Excel export fails
     */
    public static void executeSparqlQueryToExcelFile(
            String sparqlQuery,
            Model model,
            File outputFile
    ) throws Exception {

        if (model == null || model.isEmpty()) {
            throw new IllegalArgumentException("Model cannot be null or empty");
        }

        if (sparqlQuery == null || sparqlQuery.isBlank()) {
            throw new IllegalArgumentException("SPARQL query cannot be empty");
        }

        Query query = QueryFactory.create(sparqlQuery);

        if (!query.isSelectType()) {
            throw new IllegalArgumentException("Only SELECT SPARQL queries can be exported to Excel.");
        }

        try (
                QueryExecution queryExecution = QueryExecutionFactory.create(query, model);
                Workbook workbook = new XSSFWorkbook()
        ) {
            ResultSet resultSet = queryExecution.execSelect();
            List<String> columns = resultSet.getResultVars();

            Sheet sheet = workbook.createSheet("SPARQL Results");
            createHeaderRow(sheet, columns);
            fillDataRows(sheet, resultSet, columns, model);
            autoSizeColumns(sheet, columns.size());

            try (FileOutputStream fileOutput = new FileOutputStream(outputFile)) {
                workbook.write(fileOutput);
            }
        }
    }

    /**
     * Loads a single RDF model from a .xml (RDF/XML) file.
     * Note: For .zip files or custom loading, use the ModelFactory from the Main module.
     *
     * @param modelFile The .xml file to load
     * @return Loaded Model
     * @throws Exception if file reading fails
     */
    public static Model loadModelFromXML(File modelFile) throws Exception {
        String fileName = modelFile.getName().toLowerCase();

        if (!fileName.endsWith(".xml")) {
            throw new IllegalArgumentException("File must be an .xml file. For .zip support, use Main module's ModelFactory.");
        }

        try (InputStream inputStream = new FileInputStream(modelFile)) {
            Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
            String xmlBase = "http://iec.ch/TC57/2013/CIM-schema-cim16";
            RDFDataMgr.read(model, inputStream, xmlBase, Lang.RDFXML);
            return model;
        }
    }

    /**
     * Creates the header row in an Excel sheet with column names.
     *
     * @param sheet The Excel sheet to modify
     * @param columns List of column names
     */
    private static void createHeaderRow(Sheet sheet, List<String> columns) {
        Row headerRow = sheet.createRow(0);

        CellStyle headerStyle = sheet.getWorkbook().createCellStyle();
        org.apache.poi.ss.usermodel.Font font = sheet.getWorkbook().createFont();
        font.setBold(true);
        headerStyle.setFont(font);

        for (int i = 0; i < columns.size(); i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(columns.get(i));
            cell.setCellStyle(headerStyle);
        }
    }

    /**
     * Fills the Excel sheet with data from the SPARQL query results.
     *
     * @param sheet The Excel sheet to fill
     * @param resultSet SPARQL query results
     * @param columns Column names from the query
     * @param model The model used for formatting resources with prefixes
     */
    private static void fillDataRows(
            Sheet sheet,
            ResultSet resultSet,
            List<String> columns,
            Model model
    ) {
        int rowIndex = 1;

        while (resultSet.hasNext()) {
            QuerySolution solution = resultSet.nextSolution();
            Row row = sheet.createRow(rowIndex++);

            for (int colIndex = 0; colIndex < columns.size(); colIndex++) {
                String varName = columns.get(colIndex);
                RDFNode node = solution.get(varName);

                Cell cell = row.createCell(colIndex);
                cell.setCellValue(nodeToExcelValue(node, model));
            }
        }
    }

    /**
     * Converts an RDF node to a string representation suitable for Excel.
     * Handles literals, URI resources, and blank nodes.
     *
     * @param node The RDF node to convert
     * @param model The model for prefix-based resource formatting
     * @return String representation of the node
     */
    private static String nodeToExcelValue(RDFNode node, Model model) {
        if (node == null) {
            return "";
        }

        // Literals (strings, numbers, booleans)
        if (node.isLiteral()) {
            Literal literal = node.asLiteral();
            Object value = literal.getValue();
            return value == null ? literal.getString() : value.toString();
        }

        // Resources (URIs and blank nodes)
        if (node.isResource()) {
            Resource resource = node.asResource();

            // URI → use prefix if available
            if (resource.isURIResource()) {
                return model.shortForm(resource.getURI());
            }

            // Blank node → keep default representation
            return resource.toString();
        }

        return node.toString();
    }

    /**
     * Auto-sizes the columns in an Excel sheet based on content width.
     *
     * @param sheet The Excel sheet to modify
     * @param columnCount Number of columns to resize
     */
    private static void autoSizeColumns(Sheet sheet, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    /**
     * Data class to hold SPARQL query results
     */
    public static class QueryResults {
        public final List<String> columns;
        public final List<Map<String, String>> rows;

        public QueryResults(List<String> columns, List<Map<String, String>> rows) {
            this.columns = columns;
            this.rows = rows;
        }
    }

    /**
     * Executes a SPARQL SELECT query and returns results without saving to file.
     *
     * @param sparqlQuery SPARQL query text
     * @param model The RDF model to query against (already loaded)
     * @return QueryResults containing columns and data rows
     * @throws Exception if query execution fails
     */
    public static QueryResults executeSparqlQuery(
            String sparqlQuery,
            Model model
    ) throws Exception {

        if (model == null || model.isEmpty()) {
            throw new IllegalArgumentException("Model cannot be null or empty");
        }

        if (sparqlQuery == null || sparqlQuery.isBlank()) {
            throw new IllegalArgumentException("SPARQL query cannot be empty");
        }

        Query query = QueryFactory.create(sparqlQuery);

        if (!query.isSelectType()) {
            throw new IllegalArgumentException("Only SELECT SPARQL queries are supported.");
        }

        try (QueryExecution queryExecution = QueryExecutionFactory.create(query, model)) {
            ResultSet resultSet = queryExecution.execSelect();
            List<String> columns = resultSet.getResultVars();
            List<Map<String, String>> rows = new ArrayList<>();

            while (resultSet.hasNext()) {
                QuerySolution solution = resultSet.nextSolution();
                Map<String, String> row = new LinkedHashMap<>();

                for (String varName : columns) {
                    RDFNode node = solution.get(varName);
                    row.put(varName, nodeToExcelValue(node, model));
                }
                rows.add(row);
            }

            return new QueryResults(columns, rows);
        }
    }

    /**
     * Exports QueryResults to an Excel file.
     *
     * @param results The QueryResults to export
     * @param outputFile Output Excel file path
     * @throws Exception if Excel export fails
     */
    public static void exportResultsToExcel(QueryResults results, File outputFile) throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("SPARQL Results");
            createHeaderRow(sheet, results.columns);

            int rowIndex = 1;
            for (Map<String, String> row : results.rows) {
                Row excelRow = sheet.createRow(rowIndex++);
                for (int colIndex = 0; colIndex < results.columns.size(); colIndex++) {
                    Cell cell = excelRow.createCell(colIndex);
                    String value = row.get(results.columns.get(colIndex));
                    cell.setCellValue(value != null ? value : "");
                }
            }

            autoSizeColumns(sheet, results.columns.size());

            try (FileOutputStream fileOutput = new FileOutputStream(outputFile)) {
                workbook.write(fileOutput);
            }
        }
    }
}
