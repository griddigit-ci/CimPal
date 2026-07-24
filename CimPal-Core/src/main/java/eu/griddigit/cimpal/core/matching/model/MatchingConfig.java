/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.matching.model;

import java.util.List;
import java.util.Set;

/**
 * Tunable parameters of the (deterministic) matching engine. Nothing tunable is
 * hardcoded in the engine; it reads everything from here. Immutable, built via
 * {@link Builder}. Loaded from an external JSON file (parsed in the Main module)
 * or from {@code MatchingConfigPresets.defaults()}.
 *
 * <p>Tolerances are fractional (0.20 == 20%) and compared against the summed
 * value of a whole logical line. Voltage tolerance is in kV.</p>
 */
public final class MatchingConfig {

    /** Which secondary name attribute to emit as New_2nd_name. */
    public enum SecondNameSource {
        NAME,
        ALIAS_NAME,
        DESCRIPTION
    }

    private final double rTolerance;
    private final double xTolerance;
    private final double lengthTolerance;
    private final double bchTolerance;
    private final double voltageTolerance;

    private final SecondNameSource secondNameSource;
    private final Set<String> classesToMap;
    private final String queryFolder;

    private MatchingConfig(Builder b) {
        this.rTolerance = b.rTolerance;
        this.xTolerance = b.xTolerance;
        this.lengthTolerance = b.lengthTolerance;
        this.bchTolerance = b.bchTolerance;
        this.voltageTolerance = b.voltageTolerance;
        this.secondNameSource = b.secondNameSource;
        this.classesToMap = b.classesToMap == null ? Set.of() : Set.copyOf(b.classesToMap);
        this.queryFolder = b.queryFolder;
    }

    public static Builder builder() {
        return new Builder();
    }

    public double getRTolerance() { return rTolerance; }
    public double getXTolerance() { return xTolerance; }
    public double getLengthTolerance() { return lengthTolerance; }
    public double getBchTolerance() { return bchTolerance; }
    public double getVoltageTolerance() { return voltageTolerance; }
    public SecondNameSource getSecondNameSource() { return secondNameSource; }
    public Set<String> getClassesToMap() { return classesToMap; }
    public String getQueryFolder() { return queryFolder; }

    public static final class Builder {
        private double rTolerance = 0.20;
        private double xTolerance = 0.20;
        private double lengthTolerance = 0.20;
        private double bchTolerance = 0.30;
        private double voltageTolerance = 1.0;
        private SecondNameSource secondNameSource = SecondNameSource.NAME;
        private Set<String> classesToMap = Set.of(
                "Substation", "VoltageLevel", "ACLineSegment", "PowerTransformer", "Line");
        private String queryFolder = null;

        public Builder rTolerance(double v) { this.rTolerance = v; return this; }
        public Builder xTolerance(double v) { this.xTolerance = v; return this; }
        public Builder lengthTolerance(double v) { this.lengthTolerance = v; return this; }
        public Builder bchTolerance(double v) { this.bchTolerance = v; return this; }
        public Builder voltageTolerance(double v) { this.voltageTolerance = v; return this; }
        public Builder secondNameSource(SecondNameSource v) { this.secondNameSource = v; return this; }
        public Builder classesToMap(Set<String> v) { this.classesToMap = v; return this; }
        public Builder classesToMap(List<String> v) { this.classesToMap = v == null ? null : Set.copyOf(v); return this; }
        public Builder queryFolder(String v) { this.queryFolder = v; return this; }

        public MatchingConfig build() {
            return new MatchingConfig(this);
        }
    }
}
