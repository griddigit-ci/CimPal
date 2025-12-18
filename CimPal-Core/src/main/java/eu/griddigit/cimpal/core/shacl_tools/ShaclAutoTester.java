package eu.griddigit.cimpal.core.shacl_tools;

import eu.griddigit.cimpal.core.interfaces.ShaclAutoTesterCallback;
import eu.griddigit.cimpal.core.models.SHACLRuleTestData;
import eu.griddigit.cimpal.core.models.SHACLValidationResult;
import eu.griddigit.cimpal.core.utils.ExcelTools;
import eu.griddigit.cimpal.core.utils.ModelFactory;
import eu.griddigit.cimpal.core.utils.ShaclTools;
import org.apache.commons.io.FilenameUtils;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.ValidationReport;
import org.topbraid.shacl.vocabulary.SH;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ShaclAutoTester {

    private SHACLValidationLogger logger;
    private ShaclAutoTesterCallback callback;

    public ShaclAutoTester() {
        this(null);
    }

    public ShaclAutoTester(ShaclAutoTesterCallback callback) {
        logger = new SHACLValidationLogger();
        this.callback = callback;
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

    public void runTests(List<File> selectedFile, File selectedFolder, List<File> fileL) throws IOException {
        // Run on a background thread
        Thread testThread = new Thread(() -> {
            try {
                runTestsInternal(selectedFile, selectedFolder, fileL);
            } catch (IOException e) {
                appendOutput("Error during testing: " + e.getMessage() + "\n");
            }
        });
        testThread.setDaemon(true);
        testThread.start();
    }

    public void runTestsInternal(List<File> selectedFile, File selectedFolder, List<File> fileL) throws IOException {
        Map<String, Model> shaclMap = ModelFactory.modelLoad(selectedFile, "", Lang.TURTLE, true, false);
        Model shaclModel = shaclMap.get("shacl");
        Map<String, SHACLRuleTestData> ruleTestDataMap = getRuleTestDataMap(selectedFolder, fileL);

        // shaclModel.getProperty(ResourceFactory.createResource("http://iec.ch/TC57/ns/CIM/constraints/QoCDC/Level3-IGM#ACDCTerminal.sequenceNumber-numbering"), ResourceFactory.createProperty("http://www.w3.org/ns/shacl#name"))

        Map<String, Model> modelCache = new HashMap<>();
        Map<String, ValidationReport> validationCache = new HashMap<>();

        ValidationReport report;
        int i = 0;
        for (Map.Entry<String, SHACLRuleTestData> ruleTestDataEntry : ruleTestDataMap.entrySet()) {
            updateProgress((double) i / (double) ruleTestDataMap.size());

            String ruleName = ruleTestDataEntry.getKey();
            SHACLRuleTestData testData = ruleTestDataEntry.getValue();

            appendOutput("SHACL Rule: " + ruleName + "\n");
            logger.startRule(ruleName);

            for (File conformFile : testData.getConformFiles()) {

                String filePath = conformFile.getAbsolutePath();
                String cacheKey = conformFile.getName();

                // Check if model is already loaded
                Model dataModel = modelCache.get(filePath);
                try {
                    if (dataModel == null) {
                        Map<String, Model> modelMap = ModelFactory.modelLoad(
                                new ArrayList<>(List.of(conformFile)), "", Lang.RDFXML, false, false);
                        dataModel = modelMap.get("unionModel");
                        modelCache.put(cacheKey, dataModel);
                    }

                } catch (Exception e) {
                    logger.logModelLoadError(conformFile.getName(), e.getMessage());
                    continue;
                }
                // Check if validation is already done
                report = validationCache.get(filePath);
                try {


                    if (report == null) {
                        report = ShaclValidator.get().validate(
                                shaclModel.getGraph(), dataModel.getGraph());
                        validationCache.put(cacheKey, report);
                    }
                } catch (Exception e) {
                    logger.logValidationError(conformFile.getName(), e.getMessage());
                    continue;
                }

                testData.addReport(conformFile.getName(), report, true);

                List<SHACLValidationResult> validationResults = ShaclTools.extractSHACLValidationResults(report);

                boolean found = validationResults.stream()
                        .anyMatch(result -> shaclModel.getProperty(ResourceFactory.createResource(result.getSourceShape()), SH.name)
                                .getObject().toString().equals(ruleName));

                if (found) {
                    logger.logUnexpectedTrigger(conformFile.getName(), true,
                            "Conform model triggered the rule that it should conform to.");
                    appendOutput("WARNING: Triggered rule: " + ruleName +
                            " in conform model: " + conformFile.getName() + "\n");
                }
                ExcelTools.exportSHACLValidationToExcel(validationResults, new File(testData.getConformFolderPath()), FilenameUtils.removeExtension(conformFile.getName()) + "_report.xlsx");
            }

            for (File nonConformFile : testData.getNonConformFiles()) {

                String filePath = nonConformFile.getAbsolutePath();
                String cacheKey = nonConformFile.getName();

                Model dataModel = modelCache.get(filePath);
                try {
                    if (dataModel == null) {
                        Map<String, Model> modelMap = ModelFactory.modelLoad(
                                new ArrayList<>(List.of(nonConformFile)), "", Lang.RDFXML, false, false);
                        dataModel = modelMap.get("unionModel");
                        modelCache.put(cacheKey, dataModel);
                    }
                } catch (Exception e) {
                    logger.logModelLoadError(nonConformFile.getName(), e.getMessage());
                    continue;
                }

                report = validationCache.get(filePath);
                try {
                    if (report == null) {
                        report = ShaclValidator.get().validate(
                                shaclModel.getGraph(), dataModel.getGraph());
                        validationCache.put(cacheKey, report);
                    }
                } catch (Exception e) {
                    logger.logValidationError(nonConformFile.getName(), e.getMessage());
                    continue;
                }

                testData.addReport(nonConformFile.getName(), report, false);

                List<SHACLValidationResult> validationResults = ShaclTools.extractSHACLValidationResults(report);

                boolean found = validationResults.stream()
                        .anyMatch(result -> shaclModel.getProperty(ResourceFactory.createResource(result.getSourceShape()), SH.name)
                                .getObject().toString().equals(ruleName));
                if (!found) {
                    logger.logExpectedTriggerNotFound(nonConformFile.getName(), false,
                            "Non-conform model didn't trigger rule.");
                    appendOutput("WARNING: Rule not triggered: " + ruleName +
                            " in non-conform model: " + nonConformFile.getName() + "\n");
                }

                ExcelTools.exportSHACLValidationToExcel(validationResults, new File(testData.getNonConformFolderPath()), FilenameUtils.removeExtension(nonConformFile.getName()) + "_report.xlsx");
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
