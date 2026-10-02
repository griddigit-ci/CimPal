/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads repository files from tests: build files and production sources. Used by regression
 * tests that pin a structural fix (a pattern that must not come back), e.g. TEST-2's findings
 * on hardcoded paths and swallowed stack traces.
 */
public final class SourceScan {

    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");

    private SourceScan() {
    }

    /** The repository root: the nearest ancestor of the working directory with CimPal-Core/pom.xml. */
    public static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("CimPal-Core").resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Repository root not found above " + Path.of("").toAbsolutePath());
    }

    /** A repository file's text; {@code relative} uses forward slashes. */
    public static String read(String relative) {
        try {
            return Files.readString(repoRoot().resolve(relative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Code lines (comments skipped) in {@code <module>/src/main/java} matching {@code regex},
     * as {@code path:line: text}.
     */
    public static List<String> linesMatching(String regex, String... modules) {
        Pattern pattern = Pattern.compile(regex);
        List<String> hits = new ArrayList<>();
        forEachCodeLine(modules, (file, number, line) -> {
            if (pattern.matcher(line).find()) {
                hits.add(file + ":" + number + ": " + line.strip());
            }
        });
        return hits;
    }

    /**
     * String literals in code lines whose source text (escapes as written) matches
     * {@code regex}, as {@code path:line: literal}.
     */
    public static List<String> stringLiteralsMatching(String regex, String... modules) {
        Pattern pattern = Pattern.compile(regex);
        List<String> hits = new ArrayList<>();
        forEachCodeLine(modules, (file, number, line) -> {
            Matcher literal = STRING_LITERAL.matcher(line);
            while (literal.find()) {
                if (pattern.matcher(literal.group(1)).find()) {
                    hits.add(file + ":" + number + ": " + literal.group());
                }
            }
        });
        return hits;
    }

    private interface LineVisitor {
        void visit(Path file, int number, String line);
    }

    private static void forEachCodeLine(String[] modules, LineVisitor visitor) {
        Path root = repoRoot();
        for (String module : modules) {
            Path sources = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(sources)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(sources)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    boolean inBlockComment = false;
                    for (int i = 0; i < lines.size(); i++) {
                        String trimmed = lines.get(i).strip();
                        if (inBlockComment) {
                            inBlockComment = !trimmed.contains("*/");
                            continue;
                        }
                        if (trimmed.startsWith("/*")) {
                            inBlockComment = !trimmed.contains("*/");
                            continue;
                        }
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                            continue;
                        }
                        int lineComment = lines.get(i).indexOf("//");
                        String code = lineComment >= 0 && !lines.get(i).substring(0, lineComment).contains("\"")
                                ? lines.get(i).substring(0, lineComment) : lines.get(i);
                        visitor.visit(root.relativize(file), i + 1, code);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
