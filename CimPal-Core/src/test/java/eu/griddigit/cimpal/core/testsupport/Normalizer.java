/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.testsupport;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites volatile parts of a value (timestamps, machine-specific paths, durations) into stable
 * placeholders before a snapshot comparison. Applied to every string value of a JSON document and
 * to every cell of a flattened workbook.
 */
@FunctionalInterface
public interface Normalizer {

    String apply(String value);

    default Normalizer andThen(Normalizer next) {
        return value -> next.apply(apply(value));
    }

    /** Applies the given normalizers in order. */
    static Normalizer chain(Normalizer... normalizers) {
        return value -> {
            String result = value;
            for (Normalizer normalizer : normalizers) {
                result = normalizer.apply(result);
            }
            return result;
        };
    }

    /** ISO-8601 date-times and compact {@code yyyyMMdd_HHmmss} stamps become {@code <TIMESTAMP>}. */
    static Normalizer timestamps() {
        Pattern iso = Pattern.compile(
                "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}[:_]\\d{2}(?:[:_]\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:?\\d{2})?");
        Pattern compact = Pattern.compile("\\d{8}_\\d{6}");
        return value -> compact.matcher(iso.matcher(value).replaceAll("<TIMESTAMP>")).replaceAll("<TIMESTAMP>");
    }

    /**
     * Occurrences of {@code base} (with either separator style) become {@code <BASE>}, and
     * separators after it are unified to {@code /}, so snapshots are identical on Windows and Linux.
     */
    static Normalizer paths(Path base) {
        String absolute = base.toAbsolutePath().normalize().toString();
        String forward = absolute.replace('\\', '/');
        Pattern pattern = Pattern.compile(
                "(?:" + Pattern.quote(absolute) + "|" + Pattern.quote(forward) + ")([^\\s\"',;|]*)");
        return value -> {
            Matcher matcher = pattern.matcher(value);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(out,
                        Matcher.quoteReplacement("<BASE>" + matcher.group(1).replace('\\', '/')));
            }
            matcher.appendTail(out);
            return out.toString();
        };
    }

    /**
     * Any remaining absolute path (POSIX or Windows drive) becomes {@code <PATH>}. A path directly
     * after a placeholder such as {@code <BASE>} is left alone.
     */
    static Normalizer absolutePaths() {
        Pattern pattern = Pattern.compile("(?:(?<![\\w>])[A-Za-z]:[\\\\/]|(?<![\\w:<>])/(?=[\\w.-]+/))[^\\s\"',;|]*");
        return value -> pattern.matcher(value).replaceAll("<PATH>");
    }

    /**
     * Jena blank-node labels (32 lowercase hex digits) become {@code <BNODE>}. Reports write the
     * label of an anonymous shape as a plain string (e.g. {@code sh:sourceShape}), which changes on
     * every run and so can't be matched by graph isomorphism.
     */
    static Normalizer blankNodeLabels() {
        Pattern label = Pattern.compile("\\b[0-9a-f]{32}\\b");
        return value -> label.matcher(value).replaceAll("<BNODE>");
    }

    static Normalizer regex(String regex, String replacement) {
        Pattern pattern = Pattern.compile(regex);
        return value -> pattern.matcher(value).replaceAll(replacement);
    }
}
