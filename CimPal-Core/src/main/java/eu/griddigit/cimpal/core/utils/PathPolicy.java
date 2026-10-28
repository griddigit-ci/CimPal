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
 *   <li>{@linkplain Builder#exclude Excluded} folders are refused for reads and writes even inside
 *       a root, e.g. {@code serve}'s own work area with other callers' uploads (DEP-7).</li>
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

    private final List<Path> readRoots;
    private final List<Path> writeRoots;
    /** Every root as given and as its real path, for {@link #mayReadIfActive}'s lexical test. */
    private final List<Path> spelledRoots;
    /** Real paths of excluded folders, and as given (for the lexical test). */
    private final List<Path> excluded;
    private final List<Path> spelledExcluded;
    private final boolean allowUnc;

    private PathPolicy(List<Path> readRoots, List<Path> writeRoots, List<Path> spelledRoots, List<Path> excluded,
                       List<Path> spelledExcluded, boolean allowUnc) {
        this.readRoots = List.copyOf(readRoots);
        this.writeRoots = List.copyOf(writeRoots);
        this.spelledRoots = List.copyOf(spelledRoots);
        this.excluded = List.copyOf(excluded);
        this.spelledExcluded = List.copyOf(spelledExcluded);
        this.allowUnc = allowUnc;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Collects roots; each must be an existing directory and is stored as its real path. */
    public static final class Builder {
        private final List<Path> readRoots = new ArrayList<>();
        private final List<Path> writeRoots = new ArrayList<>();
        private final List<Path> spelledRoots = new ArrayList<>();
        private final List<Path> excluded = new ArrayList<>();
        private final List<Path> spelledExcluded = new ArrayList<>();
        private boolean allowUnc;

        /** A root that may be read and written. */
        public Builder root(Path root) {
            readRoot(root);
            return writeRoot(root);
        }

        public Builder readRoot(Path root) {
            readRoots.add(spelled(root, realDirectory(root)));
            return this;
        }

        public Builder writeRoot(Path root) {
            writeRoots.add(spelled(root, realDirectory(root)));
            return this;
        }

        /** Remembers how the root was written too: a path built from that spelling is under it. */
        private Path spelled(Path given, Path real) {
            spelledRoots.add(real);
            spelledRoots.add(given.toAbsolutePath().normalize());
            return real;
        }

        /**
         * A folder (an existing directory) that is refused for reading and writing even where it
         * lies inside a root, together with everything below it.
         */
        public Builder exclude(Path folder) {
            excluded.add(realDirectory(folder));
            spelledExcluded.add(folder.toAbsolutePath().normalize());
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
            return new PathPolicy(readRoots, writeRoots, spelledRoots, excluded, spelledExcluded, allowUnc);
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
        refuseExcluded(real, path);
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
        refuseExcluded(target, path);
        return target;
    }

    /**
     * Refuses a path in an excluded folder, unless it is also under a root that itself lies
     * inside that folder: the more specific root wins. So a job may use its own upload and output
     * folders inside {@code serve}'s excluded work area, and nothing else there (DEP-7).
     */
    private void refuseExcluded(Path real, Path given) {
        if (!allowedDespiteExclusion(real, excluded, readRoots, writeRoots)) {
            throw new PathNotAllowedException("Path is in a folder that is not available here: "
                    + LogSanitizer.forLog(given.toString()));
        }
    }

    private static boolean allowedDespiteExclusion(Path path, List<Path> excludedFolders, List<Path> rootsA,
                                                   List<Path> rootsB) {
        for (Path folder : excludedFolders) {
            if (path.startsWith(folder) && !underRootInside(path, folder, rootsA) && !underRootInside(path, folder, rootsB)) {
                return false;
            }
        }
        return true;
    }

    private static boolean underRootInside(Path path, Path folder, List<Path> roots) {
        for (Path root : roots) {
            // Strictly inside: a root that is the excluded folder itself would re-open all of it.
            if (root.startsWith(folder) && !root.equals(folder) && path.startsWith(root)) {
                return true;
            }
        }
        return false;
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
            if (isReservedName(segment)) {
                throw new PathNotAllowedException("Reserved device name in path: " + LogSanitizer.forLog(text));
            }
        }
    }

    /**
     * True for a reserved Windows device name such as {@code CON}, {@code nul.txt} or
     * {@code COM1}: the part before the first dot, ignoring case and surrounding blanks.
     */
    public static boolean isReservedName(String segment) {
        if (segment == null) {
            return false;
        }
        String base = segment.split("\\.", 2)[0].strip();
        return DEVICE_NAME.matcher(base).matches();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    // ---- active policy -------------------------------------------------------------------

    /** The policy of one {@link #runWith} call; closed when the call returns. */
    private static final class Context {
        final PathPolicy policy;
        volatile boolean closed;

        Context(PathPolicy policy) {
            this.policy = policy;
        }
    }

    /**
     * The policy in force on this thread. Inherited by the threads a command starts (Core
     * creates its worker pools per run, inside {@link #runWith}), so concurrent commands each
     * see their own policy (DEP-8).
     */
    private static final InheritableThreadLocal<Context> CONTEXT = new InheritableThreadLocal<>();

    /** While positive, every check needs a policy on its thread ({@link #requireContext}). */
    private static final java.util.concurrent.atomic.AtomicInteger REQUIRED = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * The policy for this thread, or null when none applies (direct CLI and GUI use). Fails
     * closed: a thread that inherited the policy of a command that has ended, or a thread without
     * one while {@link #requireContext} is held, gets an exception instead of unchecked access.
     */
    private static PathPolicy current() {
        Context context = CONTEXT.get();
        if (context != null && !context.closed) {
            return context.policy;
        }
        if (context != null || REQUIRED.get() > 0) {
            throw new PathNotAllowedException("No path policy applies on this thread ("
                    + Thread.currentThread().getName() + "); the file is refused.");
        }
        return null;
    }

    /** The policy in force for the running command on this thread, if any. */
    public static Optional<PathPolicy> active() {
        Context context = CONTEXT.get();
        return context == null || context.closed ? Optional.empty() : Optional.of(context.policy);
    }

    /**
     * Runs {@code action} with {@code policy} in force on this thread and on the threads it
     * starts, then restores the previous state. Threads still running afterwards (or created
     * then and reused later) see the policy as closed and are refused, never unrestricted.
     */
    public static <T> T runWith(PathPolicy policy, Callable<T> action) throws Exception {
        Context previous = CONTEXT.get();
        Context context = new Context(java.util.Objects.requireNonNull(policy, "policy"));
        CONTEXT.set(context);
        try {
            return action.call();
        } finally {
            context.closed = true;
            if (previous == null) {
                CONTEXT.remove();
            } else {
                CONTEXT.set(previous);
            }
        }
    }

    /**
     * Requires a policy on every thread that checks a path until the returned handle is closed:
     * a check on a thread without one is refused. {@code serve} holds it while it runs commands
     * concurrently, so no code path can fall back to unrestricted access.
     */
    public static Requirement requireContext() {
        REQUIRED.incrementAndGet();
        java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                REQUIRED.decrementAndGet();
            }
        };
    }

    /** Held by {@link #requireContext()}; closing it (once counts) ends the requirement. */
    public interface Requirement extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * {@link #checkRead(Path)} when a policy is active; otherwise {@code path} unchanged. Call it
     * before any other file system call on {@code path}: it refuses network paths without
     * touching them.
     */
    public static Path checkReadIfActive(Path path) {
        PathPolicy policy = current();
        return policy == null ? path : policy.checkRead(path);
    }

    /**
     * Without touching the file system: false when a policy is active and {@code path}, made
     * absolute and normalised, lies outside every root. A caller looking for a file among
     * candidates (where does this import live?) skips such a path, so the search can't reveal
     * whether a file outside the roots exists. {@link #checkRead} still decides for a path that
     * passes, following links.
     */
    public static boolean mayReadIfActive(Path path) {
        PathPolicy policy = current();
        if (policy == null) {
            return true;
        }
        Path normalized = path.toAbsolutePath().normalize();
        return under(normalized, policy.spelledRoots)
                && allowedDespiteExclusion(normalized, policy.spelledExcluded, policy.spelledRoots, List.of())
                && allowedDespiteExclusion(normalized, policy.excluded, policy.spelledRoots, List.of());
    }

    /**
     * When a policy is active, refuses a UNC or device path (unless the policy allows UNC)
     * without touching it; for paths that may legitimately not exist, before probing them.
     */
    public static void refuseNetworkPathIfActive(Path path) {
        PathPolicy policy = current();
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
        PathPolicy policy = current();
        return policy == null ? path : policy.checkWrite(path, true);
    }
}
