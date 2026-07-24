/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.model;

/**
 * One ConnectivityNode from Map09_ContainmentHealth. {@code substation} is null
 * when containment is broken; the share of null substations is the
 * containment-completeness metric reported in the Statistics sheet.
 */
public record ConnectivityNodeRow(
        String cn,
        String cnName,
        String container,
        String containerType,
        String substation,
        String source
) {
}
