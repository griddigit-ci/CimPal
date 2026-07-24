/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.model;

/**
 * One BusbarSection from Map07_Busbars.
 */
public record BusbarRow(
        String id,
        String name,
        String container,
        String substation,
        Double nomV,
        String source
) {
}
