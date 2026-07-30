/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.engine;

import eu.griddigit.cimpal.core.matching.model.ModelTables;
import eu.griddigit.cimpal.core.matching.model.SubstationRow;
import eu.griddigit.cimpal.core.matching.model.TerminalRow;
import eu.griddigit.cimpal.core.matching.model.TransformerEndRow;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The first, simplest matching pass: objects that carry the <b>same mRID and the
 * same type</b> in both models are the same object. This catches everything the
 * two models share directly, before any structural reasoning is attempted.
 *
 * <p>The remaining passes (substation/line/topology) skip anything matched here,
 * and the substation id-matches seed the structural propagation.</p>
 */
public final class SimpleIdMatcher {

    /** One direct match; because the id is identical, source id == matched id. */
    public record IdMatch(String id, String type, String name) {
    }

    public record Result(
            Map<String, IdMatch> matchesById,          // id -> match (all element types)
            Map<String, String> substationSeeds         // source sub id -> matched sub id (identity)
    ) {
    }

    private SimpleIdMatcher() {
    }

    public static Result match(ModelTables source, ModelTables matched) {
        Map<String, String> srcType = new LinkedHashMap<>();
        Map<String, String> srcName = new HashMap<>();
        collect(source, srcType, srcName);

        Map<String, String> matType = new HashMap<>();
        Map<String, String> matName = new HashMap<>();
        collect(matched, matType, matName);

        Map<String, IdMatch> matches = new LinkedHashMap<>();
        Map<String, String> substationSeeds = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : srcType.entrySet()) {
            String id = e.getKey();
            String type = e.getValue();
            if (type.equals(matType.get(id))) {
                String name = matName.getOrDefault(id, srcName.getOrDefault(id, ""));
                matches.put(id, new IdMatch(id, type, name));
                if ("Substation".equals(type)) substationSeeds.put(id, id);
            }
        }
        return new Result(matches, substationSeeds);
    }

    /** Index every enumerable element of a model by mRID -> local type name (and name). */
    private static void collect(ModelTables t, Map<String, String> typeById, Map<String, String> nameById) {
        for (TerminalRow tr : t.terminals()) {
            if (tr.eq() != null && tr.eqType() != null) {
                typeById.putIfAbsent(tr.eq(), Names.localName(tr.eqType()));
                if (tr.eqName() != null) nameById.putIfAbsent(tr.eq(), tr.eqName());
            }
        }
        for (SubstationRow s : t.substations()) {
            if (s.substation() != null) {
                typeById.putIfAbsent(s.substation(), "Substation");
                if (s.substationName() != null) nameById.putIfAbsent(s.substation(), s.substationName());
            }
        }
        for (TransformerEndRow te : t.transformerEnds()) {
            if (te.trafo() != null) {
                typeById.putIfAbsent(te.trafo(), "PowerTransformer");
                if (te.trafoName() != null) nameById.putIfAbsent(te.trafo(), te.trafoName());
            }
        }
    }
}
