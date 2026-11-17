/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.profiles;

import java.nio.file.Path;
import java.util.List;

/**
 * What one instance document says about itself in its header.
 *
 * @param file      the file on disk: the document itself, or the archive that holds it
 * @param entry     the document's path inside {@code file} when that is an archive, otherwise null
 * @param kind      how the profiles are declared
 * @param profiles  the declared profile IRIs, sorted; empty when {@code kind} is {@link Kind#NONE}
 * @param modelIri  the IRI of the header node (the model or dataset), or null when there is none
 * @param dependsOn the IRIs of the models this one says it depends on
 *                  ({@code md:Model.DependentOn}, {@code dcterms:requires}), sorted
 */
public record InstanceHeader(Path file, String entry, Kind kind, List<String> profiles, String modelIri,
                             List<String> dependsOn) {

    /** How an instance document declares its profiles. */
    public enum Kind {
        /** {@code md:Model.profile}, as in a CGMES model header. */
        CGMES,
        /** {@code dcterms:conformsTo}, as in a dataset header of the network codes profiles. */
        NC,
        /** No profile declared: the document cannot be validated by profile. */
        NONE
    }

    public InstanceHeader {
        profiles = List.copyOf(profiles);
        dependsOn = List.copyOf(dependsOn);
    }

    /** The document's name for messages: the file name, followed by the archive entry if any. */
    public String source() {
        String name = file.getFileName() == null ? file.toString() : file.getFileName().toString();
        return entry == null ? name : name + "/" + entry;
    }
}
