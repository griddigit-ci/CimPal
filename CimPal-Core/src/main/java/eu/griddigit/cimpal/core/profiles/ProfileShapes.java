/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.profiles;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * One profile of a {@link ProfileCatalog}: what a DX-PROF descriptor says about it.
 *
 * @param profFile         the descriptor file the profile was read from
 * @param library          the library folder that holds {@code profFile}
 * @param keys             the IRIs an instance file may declare to mean this profile: the profile's
 *                         own IRI and its {@code owl:versionIRI} and {@code owl:priorVersion} values
 * @param shapeFiles       the SHACL files of its {@code role:constraints} resources, sorted
 * @param missingArtifacts the artifact names of such resources that were not found in the library
 */
public record ProfileShapes(Path profFile, Path library, Set<String> keys, List<Path> shapeFiles,
                            List<String> missingArtifacts) {

    public ProfileShapes {
        keys = Set.copyOf(keys);
        shapeFiles = List.copyOf(shapeFiles);
        missingArtifacts = List.copyOf(missingArtifacts);
    }

    /** True when the profile has shapes and none of its artifacts is missing. */
    public boolean complete() {
        return !shapeFiles.isEmpty() && missingArtifacts.isEmpty();
    }
}
