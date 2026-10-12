/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.cimpal.core.utils.PathPolicy;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Allowed-root options shared by {@code serve}, {@code mcp} and {@code run} (SEC-2, gap G2).
 * File paths in requests, tool calls and pipeline steps must lie under these roots.
 */
final class RootOptions {

    @Option(names = "--root",
            description = "Folder whose files may be read and written (repeatable). "
                    + "Default: the working directory.")
    List<Path> roots = new ArrayList<>();

    @Option(names = "--read-root",
            description = "Folder whose files may only be read (repeatable).")
    List<Path> readRoots = new ArrayList<>();

    @Option(names = "--write-root",
            description = "Folder that outputs may be written to (repeatable). Its files can also be read.")
    List<Path> writeRoots = new ArrayList<>();

    @Option(names = "--allow-unc",
            description = "Accept UNC network paths (\\\\\\\\server\\\\share). They still have to be under a root.")
    boolean allowUnc;

    /**
     * The policy for these options. Without any root option, {@code defaults} become read and
     * write roots.
     *
     * @throws IllegalArgumentException when a root doesn't exist
     */
    PathPolicy policy(List<Path> defaults) {
        return policy(defaults, null);
    }

    /** As {@link #policy(List)}, with {@code excluded} (an existing folder, or null) refused inside every root. */
    PathPolicy policy(List<Path> defaults, Path excluded) {
        PathPolicy.Builder builder = PathPolicy.builder().allowUnc(allowUnc);
        for (Path root : explicit() ? roots : safeDefaults(defaults)) {
            builder.root(root);
        }
        readRoots.forEach(builder::readRoot);
        writeRoots.forEach(builder::writeRoot);
        if (excluded != null) {
            builder.exclude(excluded);
        }
        return builder.build();
    }

    /**
     * The policy of a {@code serve} job that reads an upload (DEP-7): its upload folder and the
     * {@code --read-root} folders may be read, only its {@code out/} written, and the rest of
     * {@code workRoot} (other uploads and jobs) is refused.
     */
    PathPolicy jobPolicy(Path uploadDir, Path outDir, Path workRoot) {
        PathPolicy.Builder builder = PathPolicy.builder().allowUnc(allowUnc)
                .readRoot(uploadDir)
                .writeRoot(outDir);
        readRoots.forEach(builder::readRoot);
        return builder.exclude(workRoot).build();
    }

    /** Default work area of {@code serve}: {@code .cimpal-work} in the first write root, else in {@link #base}. */
    Path defaultWorkRoot(List<Path> defaults) {
        Path parent = !writeRoots.isEmpty() ? writeRoots.getFirst().toAbsolutePath() : base(defaults);
        return parent.resolve(".cimpal-work");
    }

    /** Base for relative paths in requests: the first read-write root, else the first default. */
    Path base(List<Path> defaults) {
        return !roots.isEmpty() ? roots.getFirst().toAbsolutePath()
                : !writeRoots.isEmpty() ? writeRoots.getFirst().toAbsolutePath()
                : safeDefaults(defaults).getFirst().toAbsolutePath();
    }

    private boolean explicit() {
        return !roots.isEmpty() || !readRoots.isEmpty() || !writeRoots.isEmpty();
    }

    /**
     * The defaults minus the user's home folder and file-system roots: started from there, an
     * implicit default would open every file the user has (keys, browser profiles) to callers.
     *
     * @throws IllegalArgumentException when nothing is left, asking for an explicit --root
     */
    private static List<Path> safeDefaults(List<Path> defaults) {
        Path home = real(Path.of(System.getProperty("user.home")));
        List<Path> safe = defaults.stream()
                .map(RootOptions::real)
                .filter(p -> !p.equals(home) && p.getParent() != null)
                .toList();
        if (safe.isEmpty()) {
            throw new IllegalArgumentException("The working directory " + defaults.getFirst().toAbsolutePath()
                    + " is your home folder or a drive root, which is too broad to allow by default."
                    + " Pass --root <folder> with the folder that holds your data.");
        }
        return safe;
    }

    /** The real path (resolving 8.3 short names and links) where it exists, else the absolute one. */
    private static Path real(Path path) {
        try {
            return path.toAbsolutePath().toRealPath();
        } catch (java.io.IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }
}
