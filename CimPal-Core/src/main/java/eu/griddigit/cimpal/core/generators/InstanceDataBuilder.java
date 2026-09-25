/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
/**
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.generators;

import eu.griddigit.cimpal.writer.formats.CustomRDFFormat;
import eu.griddigit.cimpal.core.utils.ExcelTools;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.util.*;

/**
 * Builds a Jena {@link Model} from a CimPal Excel generation template (.xlsx).
 *
 * <p>This class contains the model-building logic extracted from
 * {@code ModelManipulationFactory.generateDataFromXlsV2} in the Main module. It has no
 * dependency on JavaFX or any GUI class. The result is returned as a {@link BuildResult}
 * record that carries the model, a ready-to-use serialisation properties map, and a
 * suggested output filename.
 *
 * <p>Callers that need to write the model to disk should pass the model and saveProperties
 * to {@link InstanceDataWriter#write} or {@link InstanceDataWriter#writeToPath}.
 */
public class InstanceDataBuilder {

    /**
     * The result of building a model from an Excel template.
     *
     * @param model             the populated Jena model
     * @param saveProperties    ready-to-use serialisation properties; the caller should set
     *                          {@code "filename"} and {@code "fileFolder"} before writing
     * @param suggestedFilename suggested output filename (without directory), derived from the
     *                          Excel template file name by stripping {@code _template} and
     *                          {@code .xlsx}; does not contain a path separator
     */
    public record BuildResult(
            Model model,
            Map<String, Object> saveProperties,
            String suggestedFilename) {
    }

    /**
     * Build a Jena model from a CimPal Excel generation template.
     *
     * @param xmlBase           the RDF base URI (e.g. {@code "http://iec.ch/TC57/CIM100"})
     * @param xlsFile           the .xlsx template file
     * @param stripPrefixes     if {@code true}, unused namespace prefixes are removed from the model
     * @param exportExtensions  if {@code true}, columns marked as extensions are included
     * @return a {@link BuildResult} containing the built model and serialisation properties
     * @throws Exception if the template is malformed or a required sheet/column is missing
     */
    public static BuildResult buildFromXls(String xmlBase, File xlsFile,
                                           boolean stripPrefixes, boolean exportExtensions)
            throws Exception {

        ArrayList<Object> headerXlsData = null;
        String headerClassName = "";
        Map<String, ArrayList<Object>> classesXlsData = new HashMap<>();
        Map<String, String> prefMap = new HashMap<>();
        Set<Resource> rdfEnumList = new HashSet<>();
        Set<Resource> rdfAboutList = new HashSet<>();

        try (FileInputStream fis = new FileInputStream(xlsFile);
             XSSFWorkbook book = new XSSFWorkbook(fis)) {

            // ------------------------------------------------------------------
            // Read the Config sheet
            // ------------------------------------------------------------------
            XSSFSheet configSheet = book.getSheet("Config");
            if (configSheet == null) {
                throw new Exception("Config sheet is missing from the xls data.");
            }

            Map<String, List<String>> configColumns =
                    ExcelTools.importXLSXToColumnMap(xlsFile.toString(), book.getSheetIndex(configSheet));
            List<List<String>> cols = new ArrayList<>(configColumns.values());

            List<String> col1 = !cols.isEmpty()      ? cols.get(0) : Collections.emptyList();
            List<String> col2 = cols.size() > 1      ? cols.get(1) : Collections.emptyList();
            List<String> col3 = cols.size() > 2      ? cols.get(2) : Collections.emptyList();
            List<String> col5 = cols.size() > 4      ? cols.get(4) : Collections.emptyList();
            List<String> col6 = cols.size() > 5      ? cols.get(5) : Collections.emptyList();

            // Collect namespace prefix → URI mappings (col3 == "Yes")
            int nsRows = Math.min(col1.size(), Math.min(col2.size(), col3.size()));
            for (int i = 0; i < nsRows; i++) {
                String yesno = col3.get(i) == null ? "" : col3.get(i).trim();
                if ("Yes".equalsIgnoreCase(yesno)) {
                    String pref = col1.get(i) == null ? "" : col1.get(i).trim();
                    String ns   = col2.get(i) == null ? "" : col2.get(i).trim();
                    if (!pref.isEmpty() && !ns.isEmpty()) {
                        prefMap.putIfAbsent(pref, ns);
                    }
                }
            }

            // Collect class sheet names (col5, then remainder of col1)
            Set<String> classNames = new LinkedHashSet<>();
            for (String v : col5) {
                String className = v == null ? "" : v.trim();
                if (!className.isEmpty()) classNames.add(className);
            }
            for (int i = nsRows; i < col1.size(); i++) {
                String className = col1.get(i) == null ? "" : col1.get(i).trim();
                if (!className.isEmpty()) classNames.add(className);
            }

            // Load each class sheet
            for (String className : classNames) {
                int classSheetIdx = book.getSheetIndex(className);
                if (classSheetIdx != -1) {
                    classesXlsData.putIfAbsent(className,
                            ExcelTools.importXLSXnullSupport(xlsFile.toString(), classSheetIdx));
                } else {
                    throw new Exception("Couldn't find the sheet for class: " + className);
                }
            }

            // Determine header class name (first non-blank entry of col6)
            for (String v : col6) {
                String candidate = v == null ? "" : v.trim();
                if (!candidate.isEmpty()) {
                    headerClassName = candidate;
                    break;
                }
            }
            if (headerClassName.isEmpty()) {
                throw new NoSuchElementException("Missing header class name from config tab.");
            }

            // Load header class sheet
            int headerSheetIdx = book.getSheetIndex(headerClassName);
            if (headerSheetIdx != -1) {
                headerXlsData = ExcelTools.importXLSXnullSupport(xlsFile.toString(), headerSheetIdx);
            } else {
                throw new Exception("Couldn't find header class sheet.");
            }

        } // FileInputStream and XSSFWorkbook are closed here

        // -----------------------------------------------------------------------
        // Build the Jena model
        // -----------------------------------------------------------------------
        Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        model.setNsPrefixes(prefMap);

        List<String> modelProfileURIs = new LinkedList<>();
        Set<Resource> prettyTypes = new LinkedHashSet<>();

        // ---- Header class ----
        int headerCols = ((LinkedList<?>) headerXlsData.get(1)).size();
        try {
            headerClassName = ((LinkedList<?>) headerXlsData.getFirst()).get(1).toString();
        } catch (NullPointerException e) {
            throw new Exception("Missing header class name in header sheet.", e);
        }

        // Find rdf:id column in header sheet
        int rdfidCol = -1;
        for (int i = 0; i < headerCols; i++) {
            if (((LinkedList<?>) headerXlsData.get(1)).get(i).equals("rdf:id")) {
                rdfidCol = i;
                break;
            }
        }
        if (rdfidCol == -1) throw new Exception("Header rdf:id missing from xls.");

        // Find where data rows start
        int dataStartFrom = 6;
        for (int i = 0; i < headerXlsData.size(); i++) {
            if (((LinkedList<?>) headerXlsData.get(i)).getFirst().equals("Mapping")) {
                dataStartFrom = i + 1;
                break;
            }
        }

        // Find IsExtension row in header sheet
        int isExtensionRow = findRowIndexByFirstCell(headerXlsData, "IsExtension");

        String headerSheetName = "";

        // Initialise saveProperties with sensible defaults (same as the GUI controller)
        Map<String, Object> saveProperties = new HashMap<>();
        saveProperties.put("showXmlDeclaration",      "true");
        saveProperties.put("showDoctypeDeclaration",   "false");
        saveProperties.put("tab",                      "2");
        saveProperties.put("relativeURIs",             "same-document");
        saveProperties.put("showXmlEncoding",          "true");
        saveProperties.put("xmlBase",                  xmlBase);
        saveProperties.put("rdfFormat",                CustomRDFFormat.RDFXML_CUSTOM_PLAIN_PRETTY);
        saveProperties.put("useAboutRules",            true);
        saveProperties.put("useEnumRules",             true);
        saveProperties.put("useFileDialog",            false);
        saveProperties.put("fileFolder",               "");
        saveProperties.put("dozip",                    false);
        saveProperties.put("instanceData",             "false");
        saveProperties.put("showXmlBaseDeclaration",   "false");
        saveProperties.put("sortRDF",                  false);
        saveProperties.put("sortRDFprefix",            false);
        saveProperties.put("putHeaderOnTop",           true);
        saveProperties.put("headerClassResource",
                "http://iec.ch/TC57/61970-552/ModelDescription/1#FullModel");
        saveProperties.put("extensionName",            "RDF XML");
        saveProperties.put("fileExtension",            "*.xml");
        saveProperties.put("fileDialogTitle",          "Save RDF XML");
        saveProperties.put("filename",                 "");

        if (headerXlsData.size() > dataStartFrom) {

            Object headIdObj = ((LinkedList<?>) headerXlsData.get(dataStartFrom)).get(rdfidCol);
            if (headIdObj == null || headIdObj.toString().trim().isEmpty()) {
                throw new Exception("Header rdf:id value is empty at row " + (dataStartFrom + 1)
                        + ", column " + (rdfidCol + 1) + ". Expected value from A8.");
            }
            String headIdXls = headIdObj.toString().trim();

            String headRdfid;
            if (headIdXls.startsWith("http") || headIdXls.startsWith("urn:uuid:")) {
                headRdfid = headIdXls;
            } else {
                headRdfid = xmlBase + "#" + headIdXls;
            }
            Resource headRdfidRes = ResourceFactory.createResource(headRdfid);

            // Determine fully-qualified class name and update saveProperties
            String[] splitClassName = headerClassName.split(":");
            String headerClassWNS;
            try {
                String namePref = prefMap.get(splitClassName[0]);
                headerClassWNS = namePref + splitClassName[1];
                saveProperties.put("headerClassResource", namePref + splitClassName[1]);
            } catch (NullPointerException e) {
                throw new Exception("Missing prefix in config for class: " + headerClassName
                        + "\nMissing prefix: " + splitClassName[0]);
            }

            headerSheetName = ResourceFactory.createResource(headerClassWNS).getLocalName();

            model.add(ResourceFactory.createStatement(
                    headRdfidRes, RDF.type, ResourceFactory.createProperty(headerClassWNS)));
            prettyTypes.add(ResourceFactory.createResource(headerClassWNS));
            rdfAboutList.add(ResourceFactory.createResource(headerClassWNS));

            // Process header properties
            for (int i = dataStartFrom; i < headerXlsData.size(); i++) {
                for (int j = 0; j < headerCols; j++) {
                    if (j != rdfidCol && j < ((LinkedList<?>) headerXlsData.get(i)).size()) {
                        if (!exportExtensions && isExtensionColumn(headerXlsData, isExtensionRow, j)) {
                            continue;
                        }
                        Object value         = ((LinkedList<?>) headerXlsData.get(i)).get(j);
                        Object propertyURI_obj = ((LinkedList<?>) headerXlsData.get(1)).get(j);
                        if (value != null && propertyURI_obj != null) {
                            String propertyURI = propertyURI_obj.toString();
                            try {
                                String[] splitPropUri;
                                String propPref;
                                if (propertyURI.startsWith("http")) {
                                    splitPropUri = propertyURI.split("#");
                                    propPref = splitPropUri[0] + "#";
                                } else {
                                    splitPropUri = propertyURI.split(":");
                                    propPref = prefMap.get(splitPropUri[0]);
                                }
                                if (propPref == null || !prefMap.containsValue(propPref)) {
                                    throw new Exception("Property URI not found: "
                                            + splitPropUri[0] + " in class: " + headerClassName);
                                }
                                propertyURI = propPref + splitPropUri[1];
                            } catch (NullPointerException e) {
                                throw new Exception(
                                        "Missing prefix in config for property: " + propertyURI);
                            }
                            Property propertyURIProp = ResourceFactory.createProperty(propertyURI);
                            String propertyType =
                                    ((LinkedList<?>) headerXlsData.get(2)).get(j).toString();
                            String object = value.toString();

                            if (propertyURI.contains("Model.profile")) {
                                modelProfileURIs.add(object);
                            }

                            switch (propertyType) {
                                case "Literal" -> {
                                    String datatype;
                                    try {
                                        datatype = resolveDatatype(
                                                ((LinkedList<?>) headerXlsData.get(3)).get(j), object);
                                    } catch (Exception e) {
                                        datatype = "string";
                                    }
                                    if (datatype.equalsIgnoreCase("float"))
                                        model.add(ResourceFactory.createStatement(headRdfidRes,
                                                propertyURIProp,
                                                ResourceFactory.createPlainLiteral(
                                                        String.valueOf(Float.parseFloat(object)))));
                                    else if (datatype.equalsIgnoreCase("integer"))
                                        model.add(ResourceFactory.createStatement(headRdfidRes,
                                                propertyURIProp,
                                                ResourceFactory.createPlainLiteral(
                                                        String.valueOf(Math.round(Float.parseFloat(object))))));
                                    else
                                        model.add(ResourceFactory.createStatement(headRdfidRes,
                                                propertyURIProp,
                                                ResourceFactory.createPlainLiteral(object)));
                                }
                                case "LiteralLangEN" -> model.add(ResourceFactory.createStatement(
                                        headRdfidRes, propertyURIProp,
                                        ResourceFactory.createLangLiteral(object, "en")));
                                case "Resource" -> {
                                    if (object.startsWith("http") || object.startsWith("urn:uuid:")) {
                                        model.add(ResourceFactory.createStatement(headRdfidRes,
                                                propertyURIProp, ResourceFactory.createResource(object)));
                                    } else {
                                        model.add(ResourceFactory.createStatement(headRdfidRes,
                                                propertyURIProp,
                                                ResourceFactory.createResource(xmlBase + "#" + object)));
                                    }
                                }
                                case "Enumeration" -> {
                                    if (object.split("#").length > 1 && object.startsWith("http")) {
                                        model.add(ResourceFactory.createStatement(headRdfidRes,
                                                propertyURIProp, ResourceFactory.createResource(object)));
                                        rdfEnumList.add(ResourceFactory.createResource(object));
                                    } else if (object.split("#").length > 1) {
                                        model.add(ResourceFactory.createStatement(headRdfidRes,
                                                propertyURIProp,
                                                ResourceFactory.createResource("http://" + object)));
                                        rdfEnumList.add(ResourceFactory.createResource("http://" + object));
                                    } else {
                                        String[] objSplit = object.split(":", 2);
                                        String prefixUri = prefMap.get(objSplit[0]);
                                        model.add(ResourceFactory.createStatement(headRdfidRes,
                                                propertyURIProp,
                                                ResourceFactory.createResource(prefixUri + objSplit[1])));
                                        rdfEnumList.add(ResourceFactory.createResource(prefixUri + objSplit[1]));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            System.out.println("[InstanceDataBuilder] Header class sheet is empty!");
        }

        // ---- Non-header class sheets ----
        for (Map.Entry<String, ArrayList<Object>> entry : classesXlsData.entrySet()) {
            if (entry.getKey().equals(headerSheetName)) continue;

            ArrayList<Object> classXlsData = entry.getValue();
            String className;
            try {
                className = ((LinkedList<?>) classXlsData.getFirst()).get(1).toString();
            } catch (IndexOutOfBoundsException | NullPointerException e) {
                System.out.println("[InstanceDataBuilder] Missing class name at sheet: " + entry.getKey());
                continue;
            }

            int cols = ((LinkedList<?>) classXlsData.get(1)).size();
            rdfidCol = -1;
            for (int i = 0; i < cols; i++) {
                if (((LinkedList<?>) classXlsData.get(1)).get(i).equals("rdf:id")) {
                    rdfidCol = i;
                    break;
                }
            }
            if (rdfidCol == -1)
                throw new Exception("rdf:id missing at class sheet: " + className);

            String[] splitClassName = className.split(":");
            String classPrefix;
            String classWNS;
            try {
                classPrefix = prefMap.get(splitClassName[0]);
                classWNS = classPrefix + splitClassName[1];
                prettyTypes.add(ResourceFactory.createResource(classWNS));
            } catch (NullPointerException e) {
                throw new Exception("Missing prefix in config for class: " + className
                        + "\nMissing prefix: " + splitClassName[0]);
            }

            int classIsExtensionRow = findRowIndexByFirstCell(classXlsData, "IsExtension");

            for (int i = dataStartFrom; i < classXlsData.size(); i++) {
                Object idObj = ((LinkedList<?>) classXlsData.get(i)).get(rdfidCol);
                if (idObj == null || idObj.toString().trim().isEmpty()) continue;

                String idxls = idObj.toString().trim();
                String rdfid;
                if (idxls.startsWith("http") || idxls.startsWith("urn:uuid")) {
                    rdfid = idxls;
                } else {
                    rdfid = xmlBase + "#" + idxls;
                }
                Resource rdfidRes = ResourceFactory.createResource(rdfid);

                if (((LinkedList<?>) classXlsData.getFirst()).get(3).toString().equals("true")) {
                    rdfAboutList.add(ResourceFactory.createResource(classWNS));
                }

                for (int j = 0; j < cols; j++) {
                    if (j != rdfidCol && j < ((LinkedList<?>) classXlsData.get(i)).size()) {
                        if (!exportExtensions && isExtensionColumn(classXlsData, classIsExtensionRow, j)) {
                            continue;
                        }
                        Object value         = ((LinkedList<?>) classXlsData.get(i)).get(j);
                        Object propertyURI_obj = ((LinkedList<?>) classXlsData.get(1)).get(j);
                        if (value != null && propertyURI_obj != null) {
                            String propertyURI = propertyURI_obj.toString();
                            try {
                                String[] splitPropUri;
                                String propPref;
                                if (propertyURI.startsWith("http")) {
                                    splitPropUri = propertyURI.split("#");
                                    propPref = splitPropUri[0] + "#";
                                } else {
                                    splitPropUri = propertyURI.split(":");
                                    propPref = prefMap.get(splitPropUri[0]);
                                }
                                if (propPref == null || !prefMap.containsValue(propPref)) {
                                    throw new Exception("Property URI not found: "
                                            + splitPropUri[0] + " in class: " + className);
                                }
                                propertyURI = propPref + splitPropUri[1];
                            } catch (ArrayIndexOutOfBoundsException e) {
                                throw new Exception(
                                        "Invalid property URI format (namespace:property) at class: "
                                                + className + " property: " + propertyURI);
                            } catch (NullPointerException e) {
                                throw new Exception(
                                        "Missing prefix in config for property: " + propertyURI);
                            }
                            Property propertyURIProp = ResourceFactory.createProperty(propertyURI);
                            String propertyType =
                                    ((LinkedList<?>) classXlsData.get(2)).get(j).toString();
                            String object = value.toString();

                            switch (propertyType) {
                                case "Literal" -> {
                                    String datatype;
                                    try {
                                        datatype = ((LinkedList<?>) classXlsData.get(3)).get(j).toString();
                                    } catch (Exception e) {
                                        datatype = "";
                                    }
                                    if (datatype.equalsIgnoreCase("float"))
                                        model.add(ResourceFactory.createStatement(rdfidRes,
                                                propertyURIProp,
                                                ResourceFactory.createPlainLiteral(
                                                        String.valueOf(Float.parseFloat(object)))));
                                    else if (datatype.equalsIgnoreCase("integer"))
                                        model.add(ResourceFactory.createStatement(rdfidRes,
                                                propertyURIProp,
                                                ResourceFactory.createPlainLiteral(
                                                        String.valueOf(Math.round(Float.parseFloat(object))))));
                                    else
                                        model.add(ResourceFactory.createStatement(rdfidRes,
                                                propertyURIProp,
                                                ResourceFactory.createPlainLiteral(object)));
                                }
                                case "LiteralLangEN" -> model.add(ResourceFactory.createStatement(
                                        rdfidRes, propertyURIProp,
                                        ResourceFactory.createLangLiteral(object, "en")));
                                case "Resource" -> {
                                    if (object.startsWith("http") || object.startsWith("urn:uuid:")) {
                                        model.add(ResourceFactory.createStatement(rdfidRes,
                                                propertyURIProp, ResourceFactory.createResource(object)));
                                    } else if (!object.contains("http") && object.contains(":")) {
                                        String[] objSplit = object.split(":", 2);
                                        String prefixUri;
                                        String objData;
                                        if (objSplit.length == 1) {
                                            prefixUri = prefMap.get(classPrefix);
                                            objData = object;
                                        } else {
                                            prefixUri = prefMap.get(objSplit[0]);
                                            objData = objSplit[1];
                                        }
                                        model.add(ResourceFactory.createStatement(rdfidRes,
                                                propertyURIProp,
                                                ResourceFactory.createResource(prefixUri + objData)));
                                    } else {
                                        model.add(ResourceFactory.createStatement(rdfidRes,
                                                propertyURIProp,
                                                ResourceFactory.createResource(xmlBase + "#" + object)));
                                    }
                                }
                                case "Enumeration" -> {
                                    if (object.split("#").length > 1 && object.startsWith("http")) {
                                        model.add(ResourceFactory.createStatement(rdfidRes,
                                                propertyURIProp, ResourceFactory.createResource(object)));
                                        rdfEnumList.add(ResourceFactory.createResource(object));
                                    } else if (object.split("#").length > 1) {
                                        model.add(ResourceFactory.createStatement(rdfidRes,
                                                propertyURIProp,
                                                ResourceFactory.createResource("http://" + object)));
                                        rdfEnumList.add(ResourceFactory.createResource("http://" + object));
                                    } else {
                                        String[] objSplit = object.split(":", 2);
                                        String prefixUri;
                                        String objData;
                                        if (objSplit.length == 1) {
                                            prefixUri = prefMap.get(classPrefix);
                                            objData = object;
                                        } else {
                                            prefixUri = prefMap.get(objSplit[0]);
                                            objData = objSplit[1];
                                        }
                                        model.add(ResourceFactory.createStatement(rdfidRes,
                                                propertyURIProp,
                                                ResourceFactory.createResource(prefixUri + objData)));
                                        rdfEnumList.add(ResourceFactory.createResource(prefixUri + objData));
                                    }
                                }
                            }
                        }
                    }

                    if (model.listStatements(rdfidRes, null, (RDFNode) null).hasNext()) {
                        model.add(ResourceFactory.createStatement(
                                rdfidRes, RDF.type, ResourceFactory.createProperty(classWNS)));
                    }
                }
            }
        }

        // ---- Strip unused prefixes ----
        if (stripPrefixes) {
            Map<String, String> modelPrefMap = model.getNsPrefixMap();
            LinkedList<String> uniqueNamespacesList = new LinkedList<>();
            for (StmtIterator ns = model.listStatements(); ns.hasNext(); ) {
                Statement stmtNS = ns.next();
                if (!uniqueNamespacesList.contains(stmtNS.getSubject().getNameSpace()))
                    uniqueNamespacesList.add(stmtNS.getSubject().getNameSpace());
                if (!uniqueNamespacesList.contains(stmtNS.getPredicate().getNameSpace()))
                    uniqueNamespacesList.add(stmtNS.getPredicate().getNameSpace());
                if (stmtNS.getObject().isResource()) {
                    if (!uniqueNamespacesList.contains(stmtNS.getObject().asResource().getNameSpace()))
                        uniqueNamespacesList.add(stmtNS.getObject().asResource().getNameSpace());
                }
            }
            LinkedList<Map.Entry<String, String>> entryToRemove = new LinkedList<>();
            for (Map.Entry<String, String> entry : modelPrefMap.entrySet()) {
                if (!uniqueNamespacesList.contains(entry.getValue())) {
                    entryToRemove.add(entry);
                }
            }
            for (Map.Entry<String, String> entryTR : entryToRemove) {
                model.removeNsPrefix(entryTR.getKey());
            }
        }

        // ---- Finalise saveProperties with computed values ----
        saveProperties.put("rdfAboutList", rdfAboutList);
        saveProperties.put("rdfEnumList",  rdfEnumList);
        if (!prettyTypes.isEmpty()) {
            saveProperties.put("prettyTypes", prettyTypes.toArray(Resource[]::new));
        }

        // ---- Derive suggested filename from template file name ----
        String suggestedFilename = xlsFile.getName()
                .replaceAll("(?i)_template", "")
                .replaceAll("(?i)\\.xlsx$", "")
                .replaceAll("\\s+", " ")
                .trim();

        return new BuildResult(model, saveProperties, suggestedFilename);
    }

    // -------------------------------------------------------------------------
    // Private helpers (mirrors of the same methods in Main's ModelManipulationFactory)
    // -------------------------------------------------------------------------

    private static String resolveDatatype(Object datatypeCell, String value) {
        String datatype = "string";
        if (datatypeCell != null) {
            String dt = datatypeCell.toString().trim();
            if (!dt.isEmpty()) return dt;
        }
        if (value == null) return datatype;
        String s = value.trim();
        if (s.isEmpty()) return datatype;
        try {
            if (!s.contains(".") && !s.contains(",")
                    && !s.toLowerCase(java.util.Locale.ROOT).contains("e")) {
                Long.parseLong(s);
                return "integer";
            }
            Double.parseDouble(s.replace(',', '.'));
            return "float";
        } catch (NumberFormatException e) {
            return datatype;
        }
    }

    private static int findRowIndexByFirstCell(ArrayList<Object> xlsData, String label) {
        if (xlsData == null || label == null) return -1;
        for (int i = 0; i < xlsData.size(); i++) {
            Object rowObj = xlsData.get(i);
            if (!(rowObj instanceof LinkedList<?> row) || row.isEmpty()) continue;
            Object first = row.getFirst();
            if (first != null && label.equals(first.toString().trim())) return i;
        }
        return -1;
    }

    private static boolean isExtensionColumn(ArrayList<Object> xlsData, int extensionRow, int columnIndex) {
        if (extensionRow < 0 || xlsData == null || extensionRow >= xlsData.size()) return false;
        Object rowObj = xlsData.get(extensionRow);
        if (!(rowObj instanceof LinkedList<?> row) || columnIndex < 0 || columnIndex >= row.size())
            return false;
        Object cell = row.get(columnIndex);
        return cell != null && "Yes".equalsIgnoreCase(cell.toString().trim());
    }
}
