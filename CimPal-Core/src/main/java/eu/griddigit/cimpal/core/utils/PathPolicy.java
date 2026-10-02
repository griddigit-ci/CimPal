/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/**
 * Allowed read and write roots for file paths that arrive through {@code serve}, {@code mcp} and
 * {@code run} (SEC-2, gap G2). Those entry points take paths from request, tool or pipeline JSON,
 * so without a policy any caller could make CimPal read or overwrite any file the user can.
 *
 * <ul>
 *   <li>{@link #checkRead} resolves the path with {@code toRealPath} (following symlinks and
 *       junctions) and requires it to lie under a read or write root. Write roots are readable
 *       too, because commands read their own outputs back.</li>
 *   <li>{@link #checkWrite} resolves the real path of the nearest existing ancestor and requires
 *       the result under a write root. An existing regular file is refused unless overwriting is
 *       allowed.</li>
 *   <li>UNC paths ({@code \\server\share}, {@code //server/share}) and device paths
 *       ({@code \\.\}, {@code \\?\}) are refused unless {@link Builder#allowUnc}. Reserved Windows
 *       device names (CON, NUL, COM1, ...) and NTFS alternate data streams are always refused.</li>
 * </ul>
 *
 * <p>The entry points also make a policy {@linkplain #runWith active} while a command runs, so
 * paths Core resolves internally (mapping CSV cells, {@code owl:imports}, organizer outputs) go
 * through the same check via {@link #checkReadIfActive} / {@link #checkWriteIfActive}. With no
 * active policy, as in direct CLI and GUI use, those calls change nothing.
 */
public final class PathPolicy {

    private static final Pattern DEVICE_NAME = Pattern.compile(
            "(?i)(con|prn|aux|nul|com[0-9\u00b9\u00b2\u00b3]|lpt[0-9\u00b9\u00b2\u00b3]|conin\\$|conout\\$)");

    /** The policy in force for the command currently running, if any (see {@link #runWith}). */
    private static volatile PathPolicy active;

    private final List<Path> readRoots;
    private final List<Path> writeRoots;
    private final boolean allowUnc;

    private PathPolicy(List<Path> readRoots, List<Path> writeRoots, boolean allowUnc) {
        this.readRoots = List.copyOf(readRoots);
        this.writeRoots = List.copyOf(writeRoots);
        this.allowUnc = allowUnc;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Collects roots; each must be an existing directory and is stored as its real path. */
    public static final class Builder {
        private final List<Path> readRoots = new ArrayList<>();
        private final List<Path> writeRoots = new ArrayList<>();
        private boolean allowUnc;

        /** A root that may be read and written. */
        public Builder root(Path root) {
            readRoot(root);
            return writeRoot(root);
        }

        public Builder readRoot(Path root) {
            readRoots.add(realDirectory(root));
            return this;
        }

        public Builder writeRoot(Path root) {
            writeRoots.add(realDirectory(root));
            return this;
        }

        /** Accept UNC paths ({@code \\server\share}); they still have to be under a root. */
        public Builder allowUnc(boolean allowUnc) {
            this.allowUnc = allowUnc;
            return this;
        }

        public PathPolicy build() {
            if (readRoots.isEmpty() && writeRoots.isEmpty()) {
                throw new IllegalArgumentException("A path policy needs at least one root.");
            }
            return new PathPolicy(readRoots, writeRoots, allowUnc);
        }

        private static Path realDirectory(Path root) {
            try {
                Path real = root.toAbsolutePath().toRealPath();
                if (!Files.isDirectory(real)) {
                    throw new IllegalArgumentException("Root is not a directory: " + root);
                }
                return real;
            } catch (IOException e) {
                throw new IllegalArgumentException("Root does not exist: " + root);
            }
        }
    }

    public List<Path> readRoots() {
        return readRoots;
    }

    public List<Path> writeRoots() {
        return writeRoots;
    }

    // ---- checks --------------------------------------------------------------------------

    /** {@link #checkRead(Path)} for a path given as text, relative paths resolved against {@code base}. */
    public Path checkRead(String path, Path base) {
        return checkRead(parse(path, base));
    }

    /** {@link #checkWrite(Path, boolean)} for a path given as text, relative to {@code base}. */
    public Path checkWrite(String path, Path base, boolean overwrite) {
        return checkWrite(parse(path, base), overwrite);
    }

    /**
     * Returns the real path of {@code path} if it exists under a read or write root.
     *
     * @throws PathNotAllowedException otherwise
     */
    public Path checkRead(Path path) {
        rejectSpecial(path.toString());
        Path real;
        try {
            real = path.toAbsolutePath().normalize().toRealPath();
        } catch (IOException e) {
            throw new PathNotAllowedException("Input path does not exist: " + LogSanitizer.forLog(path.toString()));
        }
        if (!under(real, readRoots) && !under(real, writeRoots)) {
            throw new PathNotAllowedException("Input path is outside the allowed roots: "
                    + LogSanitizer.forLog(path.toString()) + rootsHint());
        }
        return real;
    }

    /**
     * Returns where writing to {@code path} would really go, if that is under a write root. An
     * existing regular file there is refused unless {@code overwrite}; an existing directory
     * (an output folder) is fine.
     *
     * @throws PathNotAllowedException otherwise
     */
    public Path checkWrite(Path path, boolean overwrite) {
        rejectSpecial(path.toString());
        Path absolute = path.toAbsolutePath().normalize();
        Path target;
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            try {
                target = absolute.toRealPath();
            } catch (IOException e) {
                throw new PathNotAllowedException("Output path is a broken link: " + LogSanitizer.forLog(path.toString()));
            }
            if (Files.isRegularFile(target) && !overwrite) {
                throw new PathNotAllowedException("Output file already exists: " + LogSanitizer.forLog(path.toString())
                        + ". Set \"overwrite\": true to replace it.");
            }
        } else {
            Path existing = absolute.getParent();
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null) {
                throw new PathNotAllowedException("Output path has no existing parent: " + LogSanitizer.forLog(path.toString()));
            }
            try {
                target = existing.toRealPath().resolve(existing.relativize(absolute));
            } catch (IOException e) {
                throw new PathNotAllowedException("Output path has a broken parent: " + LogSanitizer.forLog(path.toString()));
            }
        }
        if (!under(target, writeRoots)) {
            throw new PathNotAllowedException("Output path is outside the allowed write roots: "
                    + LogSanitizer.forLog(path.toString()) + rootsHint());
        }
        return target;
    }

    private static boolean under(Path real, List<Path> roots) {
        for (Path root : roots) {
            if (real.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    private String rootsHint() {
        List<Path> all = new ArrayList<>(readRoots);
        writeRoots.stream().filter(r -> !all.contains(r)).forEach(all::add);
        return " (allowed: " + all + "; add one with --root)";
    }

    private Path parse(String path, Path base) {
        if (path == null || path.isBlank()) {
            throw new PathNotAllowedException("Empty path.");
        }
        rejectSpecial(path);
        try {
            Path parsed = Path.of(path);
            return parsed.isAbsolute() ? parsed : base.resolve(parsed);
        } catch (InvalidPathException e) {
            throw new PathNotAllowedException("Invalid path: " + LogSanitizer.forLog(path));
        }
    }

    /**
     * True for a UNC or device path: it starts with two separators in any mix ({@code \\},
     * {@code //}, {@code \/}, {@code /\}), which Windows treats as {@code \\host\share} (or
     * {@code \\.\}, {@code \\?\}). Touching such a path, even to test whether it exists, can send
     * the user's credentials to that host, so callers check this before any file system call.
     */
    public static boolean isUncOrDevice(String text) {
        if (text == null) {
            return false;
        }
        String t = text.strip();
        return t.length() >= 2 && isSeparator(t.charAt(0)) && isSeparator(t.charAt(1));
    }

    private static boolean isSeparator(char c) {
        return c == '\\' || c == '/';
    }

    /** UNC / device prefixes, reserved device names and NTFS streams. */
    private void rejectSpecial(String text) {
        String t = text.strip();
        if (isUncOrDevice(t) && t.length() >= 4 && (t.charAt(2) == '.' || t.charAt(2) == '?') && isSeparator(t.charAt(3))) {
            throw new PathNotAllowedException("Device paths are not allowed: " + LogSanitizer.forLog(text));
        }
        if (!allowUnc && isUncOrDevice(t)) {
            throw new PathNotAllowedException("UNC (network) paths are not allowed: " + LogSanitizer.forLog(text)
                    + " (use --allow-unc)");
        }
        String[] segments = t.split("[\\\\/]+");
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isEmpty()) {
                continue;
            }
            boolean driveSpec = i == 0 && segment.length() == 2 && segment.charAt(1) == ':';
            if (!driveSpec && segment.indexOf(':') >= 0 && isWindows()) {
                throw new PathNotAllowedException("Alternate data streams are not allowed: " + LogSanitizer.forLog(text));
            }
            String base = segment.split("\\.", 2)[0].strip();
            if (DEVICE_NAME.matcher(base).matches()) {
                throw new PathNotAllowedException("Reserved device name in path: " + LogSanitizer.forLog(text));
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    // ---- active policy -------------------------------------------------------------------

    /** The policy in force for the running command, if any. */
    public static Optional<PathPolicy> active() {
        return Optional.ofNullable(active);
    }

    /**
     * Runs {@code action} with {@code policy} in force, then restores the previous state.
     * {@code serve}, {@code mcp} and {@code run} run commands one at a time, so a single
     * process-wide slot is enough and also covers worker threads a command starts.
     */
    public static <T> T runWith(PathPolicy policy, Callable<T> action) throws Exception {
        PathPolicy previous = active;
        active = policy;
        try {
            return action.call();
        } finally {
            active = previous;
        }
    }

    /**
     * {@link #checkRead(Path)} when a policy is active; otherwise {@code path} unchanged. Call it
     * before any other file system call on {@code path}: it refuses network paths without
     * touching them.
     */
    public static Path checkReadIfActive(Path path) {
        PathPolicy policy = active;
        return policy == null ? path : policy.checkRead(path);
    }

    /**
     * When a policy is active, refuses a UNC or device path (unless the policy allows UNC)
     * without touching it; for paths that may legitimately not exist, before probing them.
     */
    public static void refuseNetworkPathIfActive(Path path) {
        PathPolicy policy = active;
        if (policy != null) {
            policy.rejectSpecial(path.toString());
        }
    }

    /**
     * {@link #checkWrite(Path, boolean)} for a file a command creates inside an output folder
     * when a policy is active; otherwise {@code path} unchanged. Only the location is checked:
     * files a command names itself inside its output folder are replaced, like its reports
     * on every run. The overwrite rule applies to output files named in the request.
     */
    public static Path checkWriteIfActive(Path path) {
        PathPolicy policy = active;
        return policy == null ? path : policy.checkWrite(path, true);
    }
}
