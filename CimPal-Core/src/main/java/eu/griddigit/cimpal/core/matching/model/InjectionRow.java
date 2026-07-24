/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.model;

/**
 * One injection (generator, load, shunt, SVC, equivalent injection) from
 * Map08_Injections. ratedS/ratedU apply to rotating machines; nomU to shunts.
 */
public record InjectionRow(
        String id,
        String type,
        String name,
        Double ratedS,
        Double ratedU,
        Double nomU,
        String container,
        String substation,
        Double nomV,
        String source
) {
}
