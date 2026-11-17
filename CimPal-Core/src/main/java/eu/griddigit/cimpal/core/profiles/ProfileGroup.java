/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.profiles;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Instance documents that are validated together, and the shapes that validate them.
 *
 * @param name          the group's name in reports
 * @param boundary      true for a group of boundary data, where boundary-only shapes apply
 * @param reported      the documents whose findings are reported
 * @param context       documents loaded with them so that references between documents resolve;
 *                      their findings are reported in their own group
 * @param unionShapes   the shape files that validate the documents together, sorted
 * @param datasetShapes by {@link InstanceHeader#id()} of a reported document, the shape files
 *                      that apply to it: those among them that hold a cardinality or closed-shape
 *                      constraint validate it on its own, since such constraints must not see
 *                      other documents
 */
public record ProfileGroup(String name, boolean boundary, List<InstanceHeader> reported, List<InstanceHeader> context,
                           List<Path> unionShapes, Map<String, List<Path>> datasetShapes) {

    public ProfileGroup {
        reported = List.copyOf(reported);
        context = List.copyOf(context);
        unionShapes = List.copyOf(unionShapes);
        Map<String, List<Path>> copy = new LinkedHashMap<>();
        datasetShapes.forEach((id, shapes) -> copy.put(id, List.copyOf(shapes)));
        datasetShapes = java.util.Collections.unmodifiableMap(copy);
    }
}
