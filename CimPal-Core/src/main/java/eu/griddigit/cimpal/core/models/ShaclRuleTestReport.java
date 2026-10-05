/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.models;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Outcome of a {@link eu.griddigit.cimpal.core.utils.ShaclRuleTester} run: for each rule folder of
 * the test suite, whether the rule fired on the models that must break it and stayed silent on
 * the models that must pass it.
 */
public final class ShaclRuleTestReport {

    /** How a rule came out of its test. */
    public enum Verdict {
        /**
         * No Conform model triggered the rule and every NonConform model did; there was at least
         * one of each, and nothing in the rule's folder was left untested.
         */
        PASS("Pass"),
        /**
         * A Conform model triggered the rule, or a NonConform model did not. The message also says
         * what was left untested, if anything.
         */
        FAIL("Fail"),
        /** The rule could not be tested, or not completely; the message says why. */
        ERROR("Error");

        private final String label;

        Verdict(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** How one model came out of its rule's test. */
    public enum Outcome {
        /** A Conform model that did not trigger the rule, or a NonConform model that did. */
        PASS("Pass"),
        /** A Conform model triggered the rule it must conform to. */
        UNEXPECTED_TRIGGER("Unexpected trigger"),
        /** A NonConform model did not trigger the rule it must break. */
        EXPECTED_TRIGGER_NOT_FOUND("Expected trigger not found"),
        /** The model could not be read or validated. */
        ERROR("Error"),
        /** No shape carries the rule's name, so there was nothing to check the model against. */
        NOT_TESTED("Not tested");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * One model of a rule's test.
     *
     * @param model         the model archive
     * @param conform       true for a model of the rule's Conform folder, which must not trigger it
     * @param ruleFindings  findings whose source shape has the rule's {@code sh:name}
     * @param otherFindings findings of every other shape
     * @param message       why the outcome is not {@link Outcome#PASS}; empty for a pass
     */
    public record ModelResult(Path model, boolean conform, int ruleFindings, int otherFindings,
                              Outcome outcome, String message) {
    }

    /**
     * One rule folder of the suite: the rule, named by the folder, and its models.
     *
     * @param message why the verdict is not {@link Verdict#PASS}; empty for a pass
     */
    public record RuleResult(String rule, Verdict verdict, String message, List<ModelResult> models) {

        public RuleResult {
            models = List.copyOf(models);
        }

        public long conformModels() {
            return models.stream().filter(ModelResult::conform).count();
        }

        public long conformPassed() {
            return models.stream().filter(m -> m.conform() && m.outcome() == Outcome.PASS).count();
        }

        public long nonConformModels() {
            return models.stream().filter(m -> !m.conform()).count();
        }

        public long nonConformPassed() {
            return models.stream().filter(m -> !m.conform() && m.outcome() == Outcome.PASS).count();
        }
    }

    /** A file or folder of the suite that is not part of any rule's test, or a report that could not be written. */
    public record Note(Path path, String message) {
    }

    private final Path suiteFolder;
    private final List<Path> shapeFiles;
    private final String datatypeMap;
    private final String xmlBase;
    private final LocalDateTime started;
    private final List<RuleResult> rules;
    private final List<Note> notes;
    private final List<String> warnings;
    private final int modelFiles;
    private final int validatedModels;
    private final Path workbook;

    /**
     * @param datatypeMap     the datatype map the models were parsed with, for the reader
     * @param modelFiles      model archives found in the rules' Conform and NonConform folders
     * @param validatedModels distinct models among them, each validated once
     * @param workbook        the results workbook, or null when it could not be written
     */
    public ShaclRuleTestReport(Path suiteFolder, List<Path> shapeFiles, String datatypeMap, String xmlBase,
                               LocalDateTime started, List<RuleResult> rules, List<Note> notes,
                               List<String> warnings, int modelFiles, int validatedModels, Path workbook) {
        this.suiteFolder = suiteFolder;
        this.shapeFiles = List.copyOf(shapeFiles);
        this.datatypeMap = datatypeMap;
        this.xmlBase = xmlBase;
        this.started = started;
        this.rules = List.copyOf(rules);
        this.notes = List.copyOf(notes);
        this.warnings = List.copyOf(warnings);
        this.modelFiles = modelFiles;
        this.validatedModels = validatedModels;
        this.workbook = workbook;
    }

    /** This report with {@code workbook} as its results workbook. */
    public ShaclRuleTestReport withWorkbook(Path workbook) {
        return new ShaclRuleTestReport(suiteFolder, shapeFiles, datatypeMap, xmlBase, started, rules, notes,
                warnings, modelFiles, validatedModels, workbook);
    }

    /**
     * True when the suite has at least one rule, every rule passed, and there are no notes:
     * nothing in the suite was left untested and every report was written.
     */
    public boolean passed() {
        return !rules.isEmpty() && notes.isEmpty()
                && rules.stream().allMatch(rule -> rule.verdict() == Verdict.PASS);
    }

    public long count(Verdict verdict) {
        return rules.stream().filter(rule -> rule.verdict() == verdict).count();
    }

    public Path getSuiteFolder() {
        return suiteFolder;
    }

    public List<Path> getShapeFiles() {
        return shapeFiles;
    }

    public String getDatatypeMap() {
        return datatypeMap;
    }

    public String getXmlBase() {
        return xmlBase;
    }

    public LocalDateTime getStarted() {
        return started;
    }

    /** One entry per rule folder, ordered by rule name. */
    public List<RuleResult> getRules() {
        return rules;
    }

    /** Files and folders of the suite that were not tested, and reports that could not be written. */
    public List<Note> getNotes() {
        return notes;
    }

    /** Problems with the inputs that did not stop the run but may make its outcome misleading. */
    public List<String> getWarnings() {
        return warnings;
    }

    public int getModelFiles() {
        return modelFiles;
    }

    public int getValidatedModels() {
        return validatedModels;
    }

    /** The results workbook in the suite folder, or null when it could not be written. */
    public Path getWorkbook() {
        return workbook;
    }
}
