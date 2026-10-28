/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.idmapping;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The matches found so far, one to one by object IRI, with how each was found, plus the reasons
 * and candidates recorded for objects that could not be matched.
 * <p>
 * The one exception to one-to-one is a line section split in two on the model 1 side and whole on the
 * model 2 side: {@link #addManyToOne} maps every model 1 section to the one model 2 section.
 */
final class Matches {

    private final Map<String, String> model1ToModel2 = new LinkedHashMap<>();
    private final Map<String, String> model2ToModel1 = new HashMap<>();
    private final Map<String, String> method = new HashMap<>();
    private final Map<String, String> note = new HashMap<>();
    private final Map<String, String> model1Reason = new HashMap<>();
    private final Map<String, String> model2Reason = new HashMap<>();
    private final Map<String, List<String>> model1Candidates = new HashMap<>();
    private final Map<String, List<String>> model2Candidates = new HashMap<>();
    private final Set<String> sharedTargets = new HashSet<>();
    private final Set<String> partial = new HashSet<>();
    private final Set<String> certain = new HashSet<>();
    /** Probable matches whose own evidence is sure: certain once their context (station or line) is. */
    private final Map<String, Object> pendingOnContext = new HashMap<>();

    /**
     * Records a match when neither object is matched yet; returns whether it did. {@code sure}
     * marks it certain: the evidence leaves no other reading. Otherwise it is probable, for review.
     */
    boolean add(String model1, String model2, String how, String why, boolean sure) {
        if (!add(model1, model2, how, why)) {
            return false;
        }
        if (sure) {
            certain.add(model1);
        }
        return true;
    }

    /** Records a probable match when neither object is matched yet; returns whether it did. */
    boolean add(String model1, String model2, String how, String why) {
        if (model1 == null || model2 == null || model1ToModel2.containsKey(model1) || model2ToModel1.containsKey(model2)) {
            return false;
        }
        model1ToModel2.put(model1, model2);
        model2ToModel1.put(model2, model1);
        method.put(model1, how);
        if (why != null && !why.isBlank()) {
            note.put(model1, why);
        }
        return true;
    }

    /** Maps {@code model1} to {@code model2} even though {@code model2} may already be taken by another model 1 object. */
    boolean addManyToOne(String model1, String model2, String how, String why, boolean sure) {
        if (!addManyToOne(model1, model2, how, why)) {
            return false;
        }
        if (sure) {
            certain.add(model1);
        }
        return true;
    }

    private boolean addManyToOne(String model1, String model2, String how, String why) {
        if (model1 == null || model2 == null || model1ToModel2.containsKey(model1)) {
            return false;
        }
        model1ToModel2.put(model1, model2);
        model2ToModel1.putIfAbsent(model2, model1);
        sharedTargets.add(model2);
        method.put(model1, how);
        if (why != null && !why.isBlank()) {
            note.put(model1, why);
        }
        return true;
    }

    /** Whether {@code model2} is the target of a many-to-one mapping (a line section split on the model 1 side). */
    boolean isSharedTarget(String model2) {
        return sharedTargets.contains(model2);
    }

    /** Marks a match as covering only part of the objects (a whole line section matched to one of several). */
    void markPartial(String model1) {
        partial.add(model1);
    }

    /** Whether the match of {@code model1} is partial or many-to-one, so its terminals pair only through matched nodes. */
    boolean isPartial(String model1) {
        return partial.contains(model1) || sharedTargets.contains(model1ToModel2.get(model1));
    }

    /**
     * Notes that {@code model1}'s match is sure in itself (its whole position agrees) and only waits
     * for its context, a station IRI or a {@link LogicalLine}, to become certain.
     */
    void certainWhen(String model1, Object context) {
        if (model1 != null && context != null && model1ToModel2.containsKey(model1) && !certain.contains(model1)) {
            pendingOnContext.put(model1, context);
        }
    }

    Map<String, Object> pendingOnContext() {
        return pendingOnContext;
    }

    /** Marks a probable match certain, after the evidence around it confirmed it. */
    void promote(String model1) {
        if (model1 != null && model1ToModel2.containsKey(model1)) {
            certain.add(model1);
            pendingOnContext.remove(model1);
        }
    }

    /** Whether the match of model 1 object {@code model1} is certain (false when unmatched or probable). */
    boolean isCertain(String model1) {
        return model1 != null && certain.contains(model1);
    }

    /** Whether the match of model 2 object {@code model2} is certain. */
    boolean isCertainModel2(String model2) {
        return isCertain(toModel1(model2));
    }

    String toModel2(String model1) {
        return model1 == null ? null : model1ToModel2.get(model1);
    }

    String toModel1(String model2) {
        return model2 == null ? null : model2ToModel1.get(model2);
    }

    boolean matched(Side side, String iri) {
        return side == Side.MODEL1 ? model1ToModel2.containsKey(iri) : model2ToModel1.containsKey(iri);
    }

    String method(String model1) {
        return method.get(model1);
    }

    String note(String model1) {
        return note.get(model1);
    }

    void appendNote(String model1, String text) {
        note.merge(model1, text, (a, b) -> a.contains(b) ? a : a + "; " + b);
    }

    Map<String, String> model1ToModel2() {
        return model1ToModel2;
    }

    int size() {
        return model1ToModel2.size();
    }

    /** Remembers why {@code iri} is unmatched (the latest reason wins) and its candidates, if any. */
    void reason(Side side, String iri, String why, List<String> candidates) {
        (side == Side.MODEL1 ? model1Reason : model2Reason).put(iri, why);
        if (candidates != null && !candidates.isEmpty()) {
            (side == Side.MODEL1 ? model1Candidates : model2Candidates).put(iri, List.copyOf(candidates));
        } else {
            (side == Side.MODEL1 ? model1Candidates : model2Candidates).remove(iri);
        }
    }

    String reason(Side side, String iri) {
        return (side == Side.MODEL1 ? model1Reason : model2Reason).get(iri);
    }

    List<String> candidates(Side side, String iri) {
        return (side == Side.MODEL1 ? model1Candidates : model2Candidates).getOrDefault(iri, List.of());
    }
}
