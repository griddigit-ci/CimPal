/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2020, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.core.models;

/**
 * Options for exporting an RDF comparison result as KGCL (Knowledge Graph Change Language).
 * <p>
 * Immutable Builder-pattern DTO in the house style of {@link RDFConvertOptions}.
 */
public class KgclOptions {

    /**
     * Output serialization of the KGCL changeset. {@code CNL} is the human-readable controlled
     * natural language; {@code TURTLE}/{@code RDFXML} emit the KGCL RDF vocabulary.
     */
    public enum OutputFormat {
        CNL, TURTLE, RDFXML
    }

    /**
     * Which compared model is treated as the "before" state. KGCL describes how to transform the
     * before state into the after state, so this decides whether an item found only in model A is a
     * deletion ({@code A_TO_B}) or a creation ({@code B_TO_A}).
     */
    public enum Direction {
        A_TO_B, B_TO_A
    }

    private final OutputFormat outputFormat;
    private final Direction direction;

    private KgclOptions(Builder b) {
        this.outputFormat = b.outputFormat;
        this.direction = b.direction;
    }

    public static Builder builder() {
        return new Builder();
    }

    public OutputFormat getOutputFormat() {
        return outputFormat;
    }

    public Direction getDirection() {
        return direction;
    }

    // ============ the Builder ============

    public static class Builder {
        private OutputFormat outputFormat = null;
        private Direction direction = Direction.A_TO_B;

        public Builder outputFormat(OutputFormat outputFormat) {
            this.outputFormat = outputFormat;
            return this;
        }

        public Builder direction(Direction direction) {
            this.direction = direction;
            return this;
        }

        /**
         * Builds the immutable KgclOptions, validating required fields.
         */
        public KgclOptions build() {
            if (outputFormat == null) {
                throw new IllegalStateException("outputFormat must not be null");
            }
            if (direction == null) {
                throw new IllegalStateException("direction must not be null");
            }
            return new KgclOptions(this);
        }
    }
}
