package eu.griddigit.cimpal.core.shacl_tools;

import eu.griddigit.cimpal.core.interfaces.ShaclAutoTesterCallback;
import eu.griddigit.cimpal.core.models.SHACLRuleTestData;
import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import eu.griddigit.cimpal.core.utils.ExcelTools;
import eu.griddigit.cimpal.core.utils.ModelFactory;
import eu.griddigit.cimpal.core.utils.ShaclTools;
import eu.griddigit.cimpal.core.utils.ValidationTools;
import org.apache.commons.io.FilenameUtils;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;
import org.topbraid.shacl.vocabulary.SH;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ShaclAutoTester {

    private final SHACLValidationLogger logger;
    private final ShaclAutoTesterCallback callback;

    /**
     * Datatype map applied while parsing the models under test, or null to parse untyped.
     * Without it every literal arrives as a plain string, so a constraint on a numeric range or
     * a boolean value cannot fire and the test would silently pass.
     */
    private Map<String, RDFDatatype> dataTypeMap;

    /** Base URI for resolving relative URIs in the models under test. */
    private String xmlBase = "";

    private int validationWorkers = 1;
    private int maxResultsPerConstraint;

    public ShaclAutoTester() {
        this(null);
    }

    public ShaclAutoTester(ShaclAutoTesterCallback callback) {
        logger = new SHACLValidationLogger();
        this.callback = callback;
    }

    /**
     * Sets the datatype map and base URI used to load the models under test, so this workflow
     * types its literals the same way the mapping-driven validation workflows do.
     */
    public void setDatatypeMapping(Map<String, RDFDatatype> dataTypeMap, String xmlBase) {
        this.dataTypeMap = dataTypeMap;
        this.xmlBase = xmlBase == null ? "" : xmlBase;
    }

    /** Applies the shared validation controls used by all workflows. */
    public void setValidationOptions(int validationWorkers, int maxResultsPerConstraint) {
        this.validationWorkers = Math.max(1, validationWorkers);
        this.maxResultsPerConstraint = Math.max(0, maxResultsPerConstraint);
    }

    private static int workersForModel(int totalWorkers, int activeModels, int modelNumber) {
        int baseWorkers = totalWorkers / activeModels;
        int extraWorkers = totalWorkers % activeModels;
        return baseWorkers + (modelNumber < extraWorkers ? 1 : 0);
    }

    private void updateProgress(double progress) {
        if (callback != null) {
            callback.updateProgress(progress);
        }
    }

    private void appendOutput(String message) {
        if (callback != null) {
            callback.appendOutput(message);
        }
    }

    public void runTests(List<File> selectedFile, File selectedFolder, List<File> fileL, boolean exportReports) {
        // Run on a background thread
        Thread testThread = new Thread(() -> {
            try {
                runTestsInternal(selectedFile, selectedFolder, fileL, exportReports);
            } catch (IOException e) {
                appendOutput("Error during testing: " + e.getMessage() + "\n");
            }
        });
        testThread.setDaemon(true);
        testThread.start();
    }

    public void runTestsInternal(List<File> selectedFile, File selectedFolder, List<File> fileL, boolean exportReports) throws IOException {
        long runStart = System.currentTimeMillis();
        ValidationTools.logValidationDebug("manual: start shapes=" + selectedFile.size()
                + " archives=" + fileL.size());
        Map<String, Model> shaclMap = ModelFactory.modelLoad(selectedFile, "http://iec.ch/TC57/2013/CIM-schema-cim16", Lang.TURTLE, true, false);
        Model shaclModel = shaclMap.get("shacl");
        Map<String, SHACLRuleTestData> ruleTestDataMap = getRuleTestDataMap(selectedFolder, fileL);

        // shaclModel.getProperty(ResourceFactory.createResource("http://iec.ch/TC57/ns/CIM/constraints/QoCDC/Level3-IGM#ACDCTerminal.sequenceNumber-numbering"), ResourceFactory.createProperty("http://www.w3.org/ns/shacl#name"))

        Map<String, Model> modelCache = new ConcurrentHashMap<>();
        Map<String, ValidationReport> validationCache = new ConcurrentHashMap<>();

        // A model is shared by several rule folders. Load and validate each distinct archive once,
        // in parallel, before the rule-by-rule comparison below consumes the cached report.
        Set<File> uniqueModels = new HashSet<>();
        for (SHACLRuleTestData data : ruleTestDataMap.values()) {
            uniqueModels.addAll(data.getConformFiles());
            uniqueModels.addAll(data.getNonConformFiles());
        }
        ValidationTools.logValidationDebug("manual: prevalidate models=" + uniqueModels.size()
                + " workers=" + validationWorkers + " resultLimit=" + maxResultsPerConstraint);
        long validationStart = System.currentTimeMillis();
        int activeModels = Math.min(validationWorkers, uniqueModels.size());
        ExecutorService validationPool = Executors.newFixedThreadPool(Math.max(1, activeModels));
        try {
            int modelNumber = 0;
            for (File modelFile : uniqueModels) {
                int targetShapeWorkers = workersForModel(validationWorkers, Math.max(1, activeModels), modelNumber++);
                validationPool.submit(() -> {
                    String cacheKey = modelFile.getName();
                    try {
                        Model dataModel = loadDataModel(modelFile);
                        modelCache.put(cacheKey, dataModel);
                        validationCache.put(cacheKey, ValidationTools.validateJenaTargetShapes(
                                Shapes.parse(shaclModel.getGraph()), dataModel.getGraph(), targetShapeWorkers));
                    } catch (Exception e) {
                        logger.logValidationError(modelFile.getName(), e.getMessage());
                        ValidationTools.logValidationDebug("manual: validation error "
                                + modelFile.getName() + ": " + e.getMessage());
                    }
                });
            }
        } finally {
            validationPool.shutdown();
            try {
                if (!validationPool.awaitTermination(60, TimeUnit.MINUTES)) {
                    throw new IOException("Manual validation workers timed out after 60 minutes");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Manual validation interrupted", e);
            }
        }
        ValidationTools.logValidationDebug("manual: prevalidation completed in "
                + (System.currentTimeMillis() - validationStart) + " ms");

        ValidationReport report;
        int i = 0;
        for (Map.Entry<String, SHACLRuleTestData> ruleTestDataEntry : ruleTestDataMap.entrySet()) {
            updateProgress((double) i / (double) ruleTestDataMap.size());

            String ruleName = ruleTestDataEntry.getKey();
            SHACLRuleTestData testData = ruleTestDataEntry.getValue();

            appendOutput("SHACL Rule: " + ruleName + "\n");
            logger.startRule(ruleName);

            for (File conformFile : testData.getConformFiles()) {

                String cacheKey = conformFile.getName();

                // Check if model is already loaded
                Model dataModel = modelCache.get(cacheKey);
                try {
                    if (dataModel == null) {
                        dataModel = loadDataModel(conformFile);
                        modelCache.put(cacheKey, dataModel);
                    }

                } catch (Exception e) {
                    logger.logModelLoadError(conformFile.getName(), e.getMessage());
                    continue;
                }
                // Check if validation is already done
                report = validationCache.get(cacheKey);
                try {
                    if (report == null) {
                        report = ValidationTools.validateJenaTargetShapes(
                                Shapes.parse(shaclModel.getGraph()), dataModel.getGraph(), validationWorkers);
                        validationCache.put(cacheKey, report);
                    }
                } catch (Exception e) {
                    logger.logValidationError(conformFile.getName(), e.getMessage());
                    continue;
                }

                testData.addReport(conformFile.getName(), report, true);

                List<SHACLValidationResult> validationResults = limitResults(
                        ShaclTools.extractSHACLValidationResults(report, shaclModel));

                boolean found = validationResults.stream()
                        .anyMatch(result -> shaclModel.getProperty(ResourceFactory.createResource(result.getSourceShape()), SH.name)
                                .getObject().toString().equals(ruleName));

                if (found) {
                    logger.logUnexpectedTrigger(conformFile.getName(), true,
                            "Conform model triggered the rule that it should conform to.");
                    appendOutput("WARNING: Triggered rule: " + ruleName +
                            " in conform model: " + conformFile.getName() + "\n");
                }
                if (exportReports) {
                    ExcelTools.exportSHACLValidationToExcel(validationResults, new File(testData.getConformFolderPath()), FilenameUtils.removeExtension(conformFile.getName()) + "_report.xlsx");
                }
            }

            for (File nonConformFile : testData.getNonConformFiles()) {

                String cacheKey = nonConformFile.getName();

                Model dataModel = modelCache.get(cacheKey);
                try {
                    if (dataModel == null) {
                        dataModel = loadDataModel(nonConformFile);
                        modelCache.put(cacheKey, dataModel);
                    }
                } catch (Exception e) {
                    logger.logModelLoadError(nonConformFile.getName(), e.getMessage());
                    continue;
                }

                report = validationCache.get(cacheKey);
                try {
                    if (report == null) {
                        report = ValidationTools.validateJenaTargetShapes(
                                Shapes.parse(shaclModel.getGraph()), dataModel.getGraph(), validationWorkers);
                        validationCache.put(cacheKey, report);
                    }
                } catch (Exception e) {
                    logger.logValidationError(nonConformFile.getName(), e.getMessage());
                    continue;
                }

                testData.addReport(nonConformFile.getName(), report, false);

                List<SHACLValidationResult> validationResults = limitResults(
                        ShaclTools.extractSHACLValidationResults(report, shaclModel));

                boolean found = validationResults.stream()
                        .anyMatch(result -> shaclModel.getProperty(ResourceFactory.createResource(result.getSourceShape()), SH.name)
                                .getObject().toString().equals(ruleName));
                if (!found) {
                    logger.logExpectedTriggerNotFound(nonConformFile.getName(), false,
                            "Non-conform model didn't trigger rule.");
                    appendOutput("WARNING: Rule not triggered: " + ruleName +
                            " in non-conform model: " + nonConformFile.getName() + "\n");
                }
                if (exportReports) {
                    ExcelTools.exportSHACLValidationToExcel(validationResults, new File(testData.getNonConformFolderPath()), FilenameUtils.removeExtension(nonConformFile.getName()) + "_report.xlsx");
                }
            }
            i++;
        }

        try {
            File logFile = new File(selectedFolder, "validation_log_" +
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx");
            logger.saveToFile(logFile);
            appendOutput("\nValidation log saved to: " + logFile.getAbsolutePath() + "\n");
        } catch (IOException e) {
            appendOutput("Failed to save validation log: " + e.getMessage() + "\n");
        }

        updateProgress(1.0);
        ValidationTools.logValidationDebug("manual: completed in "
                + (System.currentTimeMillis() - runStart) + " ms");
    }

    /**
     * Loads one model under test. With a datatype map configured the parse applies it, matching
     * how the mapping-driven workflows load their models; without one it falls back to the plain
     * read this class used before the map became configurable.
     */
    private Model loadDataModel(File file) throws IOException {
        if (dataTypeMap == null || dataTypeMap.isEmpty()) {
            return ModelFactory.modelLoad(
                    new ArrayList<>(List.of(file)), xmlBase, Lang.RDFXML, false, false).get("unionModel");
        }
        return ModelFactory.modelLoadUnionWithDatatypeMap(List.of(file), dataTypeMap, xmlBase);
    }

    private List<SHACLValidationResult> limitResults(List<SHACLValidationResult> results) {
        if (maxResultsPerConstraint == 0) return results;
        Map<String, Integer> retained = new HashMap<>();
        List<SHACLValidationResult> limited = new ArrayList<>();
        for (SHACLValidationResult result : results) {
            String sourceShape = result.getSourceShape() == null ? "" : result.getSourceShape();
            int count = retained.getOrDefault(sourceShape, 0);
            if (count < maxResultsPerConstraint) {
                limited.add(result);
                retained.put(sourceShape, count + 1);
            }
        }
        return limited;
    }

    private static Map<String, SHACLRuleTestData> getRuleTestDataMap(File selectedFolder, List<File> fileL) {
        Map<String, SHACLRuleTestData> ruleTestDataMap = new HashMap<>();

        for (File file : fileL) {
            Path fullPath = file.toPath(); // Changed from Paths.get(file.getPath())
            Path inputFolderPath = selectedFolder.toPath();
            Path relativePath = inputFolderPath.relativize(fullPath);

            String ruleName = relativePath.getName(0).toString();
            String category = relativePath.getName(1).toString();

            SHACLRuleTestData data = ruleTestDataMap.get(ruleName);
            if (data == null) {
                data = new SHACLRuleTestData(ruleName);
                ruleTestDataMap.put(ruleName, data);
            }
            data.addFile(category, file);
        }
        return ruleTestDataMap;
    }


}
