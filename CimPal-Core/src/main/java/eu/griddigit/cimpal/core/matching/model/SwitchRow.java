/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.model;

/**
 * One switching device from Map06_Switches. In EQ there is no open/closed
 * state, so Phase 3 treats every switch as a connector regardless of
 * {@code normalOpen}. Terminal-to-CN adjacency comes from the TerminalRow table.
 */
public record SwitchRow(
        String id,
        String type,
        String name,
        Boolean normalOpen,
        String container,
        String containerType,
        String substation,
        String source
) {
}
