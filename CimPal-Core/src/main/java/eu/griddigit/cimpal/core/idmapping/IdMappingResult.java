/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.idmapping;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The outcome of an {@link IdMapper} run: the mapped objects, the objects left for a person on
 * either side, and counts per class, per method and per model.
 */
public final class IdMappingResult {

    /**
     * Objects of one class: how many each model has, and how many were mapped with certainty, as
     * probable, or not at all. A class only one model has is counted but not listed as unmatched.
     */
    public record TypeCount(String elementType, int model1Objects, int certain, int probable, int model1Unmatched,
                            int model2Objects, int model2Certain, int model2Probable, int model2Unmatched) {

        public int matched() {
            return certain + probable;
        }

        /** Whether both models have objects of this class. */
        public boolean inBothModels() {
            return model1Objects > 0 && model2Objects > 0;
        }
    }

    /**
     * One model's objects, over the classes both models have: certain, probable (to review) and
     * not matched.
     */
    public record Overview(int objects, int certain, int probable, int unmatched) {

        public double percent(int count) {
            return objects == 0 ? 0 : 100.0 * count / objects;
        }
    }

    /** One line of model statistics, e.g. the number of logical lines on each side. */
    public record Statistic(String label, String model1, String model2) {
    }

    private final List<MatchedRow> matched;
    private final List<UnmatchedRow> unmatched;
    private final List<TypeCount> typeCounts;
    private final Map<String, Integer> methodCounts;
    private final List<Statistic> statistics;
    private final List<Statistic> inputs;

    IdMappingResult(List<MatchedRow> matched, List<UnmatchedRow> unmatched, List<TypeCount> typeCounts,
                    Map<String, Integer> methodCounts, List<Statistic> statistics, List<Statistic> inputs) {
        this.matched = List.copyOf(matched);
        this.unmatched = List.copyOf(unmatched);
        this.typeCounts = List.copyOf(typeCounts);
        this.methodCounts = Map.copyOf(methodCounts);
        this.statistics = List.copyOf(statistics);
        this.inputs = List.copyOf(inputs);
    }

    /** The mapped objects, model 1 to new: the {@code Matched} sheet. */
    public List<MatchedRow> getMatched() {
        return matched;
    }

    /** The objects of either model left unmapped: the {@code Unmatched} sheet. */
    public List<UnmatchedRow> getUnmatched() {
        return unmatched;
    }

    public List<TypeCount> getTypeCounts() {
        return typeCounts;
    }

    /** Number of matched rows per method. */
    public Map<String, Integer> getMethodCounts() {
        return methodCounts;
    }

    public List<Statistic> getStatistics() {
        return statistics;
    }

    /** The files and options of the run, for the summary sheet. */
    public List<Statistic> getInputs() {
        return inputs;
    }

    /** The model 1 over the classes both models have: certain, probable, not matched. */
    public Overview getModel1Overview() {
        int objects = 0;
        int certain = 0;
        int probable = 0;
        int unmatched = 0;
        for (TypeCount t : typeCounts) {
            if (t.inBothModels()) {
                objects += t.model1Objects();
                certain += t.certain();
                probable += t.probable();
                unmatched += t.model1Unmatched();
            }
        }
        return new Overview(objects, certain, probable, unmatched);
    }

    /** The model 2 over the classes both models have: certain, probable, not matched. */
    public Overview getModel2Overview() {
        int objects = 0;
        int certain = 0;
        int probable = 0;
        int unmatched = 0;
        for (TypeCount t : typeCounts) {
            if (t.inBothModels()) {
                objects += t.model2Objects();
                certain += t.model2Certain();
                probable += t.model2Probable();
                unmatched += t.model2Unmatched();
            }
        }
        return new Overview(objects, certain, probable, unmatched);
    }

    /** All model 1 objects counted, classes model 2 lacks included. */
    public int getModel1ObjectCount() {
        return typeCounts.stream().mapToInt(TypeCount::model1Objects).sum();
    }

    public long getCertainCount() {
        return matched.stream().filter(m -> MatchedRow.CERTAIN.equals(m.confidence())).count();
    }

    public int getMatchedCount() {
        return matched.size();
    }

    public long getModel1UnmatchedCount() {
        return unmatched.stream().filter(u -> u.side() == Side.MODEL1).count();
    }

    public long getModel2UnmatchedCount() {
        return unmatched.stream().filter(u -> u.side() == Side.MODEL2).count();
    }

    /**
     * Writes the report workbook: {@code Matched} (Model1_ID, Element_type, Model2_ID, Model2_2nd_name, then
     * PF_name, New_type, Method, Note), {@code Unmatched} and {@code Summary}. A {@code .xls} name
     * gives the old binary format (at most 65,535 rows a sheet), anything else {@code .xlsx}.
     */
    public void writeExcel(Path file) throws IOException {
        IdMappingWorkbook.write(this, file);
    }
}
