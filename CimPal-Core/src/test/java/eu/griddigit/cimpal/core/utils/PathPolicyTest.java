/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Allowed read/write roots for serve, mcp and run (SEC-2, gap G2). */
class PathPolicyTest {

    @TempDir
    Path tempDir;

    private Path root;
    private Path outside;
    private PathPolicy policy;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectory(tempDir.resolve("root"));
        outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.writeString(root.resolve("in.ttl"), "x");
        Files.writeString(outside.resolve("secret.ttl"), "x");
        policy = PathPolicy.builder().root(root).build();
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }

    @Test
    void fileInsideTheRootIsAllowed() {
        assertThat(policy.checkRead(root.resolve("in.ttl"))).isEqualTo(realPath(root.resolve("in.ttl")));
        Path out = policy.checkWrite(root.resolve("out/report.xlsx"), false);
        assertThat(out.startsWith(realPath(root))).as(out.toString()).isTrue();
    }

    @Test
    void anExcludedFolderIsRefusedEvenInsideARoot() throws Exception {
        // DEP-7: serve's work area lies inside a root but holds other callers' uploads and outputs.
        Path work = Files.createDirectories(root.resolve(".cimpal-work/uploads/u1"));
        Files.writeString(work.resolve("model.xml"), "x");
        PathPolicy excluding = PathPolicy.builder().root(root).exclude(root.resolve(".cimpal-work")).build();

        assertThat(excluding.checkRead(root.resolve("in.ttl"))).isEqualTo(realPath(root.resolve("in.ttl")));
        assertThatThrownBy(() -> excluding.checkRead(work.resolve("model.xml")))
                .isInstanceOf(PathNotAllowedException.class).hasMessageContaining("not available");
        assertThatThrownBy(() -> excluding.checkRead(root.resolve("x/../.cimpal-work/uploads/u1/model.xml")))
                .isInstanceOf(PathNotAllowedException.class);
        assertThatThrownBy(() -> excluding.checkWrite(root.resolve(".cimpal-work/jobs/j1/out/r.xlsx"), false))
                .isInstanceOf(PathNotAllowedException.class);
        assertThatThrownBy(() -> excluding.checkWrite(root.resolve(".cimpal-work"), true))
                .isInstanceOf(PathNotAllowedException.class);
        PathPolicy.runWith(excluding, () -> {
            assertThat(PathPolicy.mayReadIfActive(work.resolve("model.xml"))).isFalse();
            assertThat(PathPolicy.mayReadIfActive(root.resolve("in.ttl"))).isTrue();
            return null;
        });
    }

    @Test
    void aRootInsideAnExcludedFolderStaysUsableButNothingElseThere() throws Exception {
        // A job's policy: its upload (read) and out/ (write) inside the excluded work area.
        Path work = Files.createDirectories(root.resolve(".cimpal-work"));
        Path upload = Files.createDirectories(work.resolve("uploads/mine"));
        Path other = Files.createDirectories(work.resolve("uploads/theirs"));
        Path out = Files.createDirectories(work.resolve("jobs/j1/out"));
        Files.writeString(upload.resolve("eq.xml"), "x");
        Files.writeString(other.resolve("eq.xml"), "x");
        PathPolicy job = PathPolicy.builder().readRoot(upload).writeRoot(out).readRoot(root).exclude(work).build();

        assertThat(job.checkRead(upload.resolve("eq.xml"))).isEqualTo(realPath(upload.resolve("eq.xml")));
        assertThat(job.checkWrite(out.resolve("report.xlsx"), false).startsWith(realPath(out))).isTrue();
        assertThat(job.checkRead(root.resolve("in.ttl"))).isEqualTo(realPath(root.resolve("in.ttl")));
        assertThatThrownBy(() -> job.checkRead(other.resolve("eq.xml"))).isInstanceOf(PathNotAllowedException.class);
        assertThatThrownBy(() -> job.checkRead(upload.resolve("../theirs/eq.xml"))).isInstanceOf(PathNotAllowedException.class);
        assertThatThrownBy(() -> job.checkWrite(upload.resolve("new.xml"), false)).isInstanceOf(PathNotAllowedException.class);
        assertThatThrownBy(() -> job.checkWrite(work.resolve("jobs/j2/out/x"), false)).isInstanceOf(PathNotAllowedException.class);
    }

    @Test
    void aRootThatIsTheExcludedFolderItselfDoesNotReopenIt() throws Exception {
        // serve --root D:\data --work-root D:\data must not expose the uploads to shared requests.
        Path work = Files.createDirectories(root.resolve("uploads/u1"));
        Files.writeString(work.resolve("eq.xml"), "x");
        PathPolicy same = PathPolicy.builder().root(root).exclude(root).build();

        assertThatThrownBy(() -> same.checkRead(work.resolve("eq.xml"))).isInstanceOf(PathNotAllowedException.class);
        assertThatThrownBy(() -> same.checkWrite(root.resolve("new.xml"), false)).isInstanceOf(PathNotAllowedException.class);
    }

    @Test
    void anExcludedFolderIsRefusedThroughALinkToo() throws Exception {
        Path work = Files.createDirectories(root.resolve(".cimpal-work"));
        Files.writeString(work.resolve("other.xml"), "x");
        Path link = root.resolve("shortcut");
        try {
            Files.createSymbolicLink(link, work);
        } catch (Exception e) {
            abort("symbolic links not available here: " + e.getMessage());
        }
        PathPolicy excluding = PathPolicy.builder().root(root).exclude(work).build();

        assertThatThrownBy(() -> excluding.checkRead(link.resolve("other.xml"))).isInstanceOf(PathNotAllowedException.class);
        assertThatThrownBy(() -> excluding.checkWrite(link.resolve("new.xml"), false)).isInstanceOf(PathNotAllowedException.class);
    }

    @Test
    void reservedNamesAreRecognised() {
        assertThat(PathPolicy.isReservedName("CON")).isTrue();
        assertThat(PathPolicy.isReservedName("nul.txt")).isTrue();
        assertThat(PathPolicy.isReservedName("Com1.xml")).isTrue();
        assertThat(PathPolicy.isReservedName("console.xml")).isFalse();
        assertThat(PathPolicy.isReservedName("model.xml")).isFalse();
        assertThat(PathPolicy.isReservedName(null)).isFalse();
    }

    // ---- per-thread context (DEP-8) ----------------------------------------------------------

    @Test
    void threadsACommandStartsSeeItsPolicy() throws Exception {
        PathPolicy.runWith(policy, () -> {
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(1);
            try {
                assertThat(pool.submit(() -> PathPolicy.checkReadIfActive(root.resolve("in.ttl"))).get())
                        .isEqualTo(realPath(root.resolve("in.ttl")));
                assertThatThrownBy(() -> pool.submit(() -> PathPolicy.checkReadIfActive(outside.resolve("secret.ttl"))).get())
                        .hasCauseInstanceOf(PathNotAllowedException.class);
            } finally {
                pool.shutdownNow();
            }
            return null;
        });
    }

    @Test
    void aThreadStillHoldingAnEndedCommandsPolicyIsRefused() throws Exception {
        // E.g. a pool thread created during one command and reused after it: fail closed.
        java.util.concurrent.atomic.AtomicReference<Thread> later = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> seen = new java.util.concurrent.atomic.AtomicReference<>();
        PathPolicy.runWith(policy, () -> {
            later.set(new Thread(() -> {
                try {
                    PathPolicy.checkReadIfActive(root.resolve("in.ttl"));
                } catch (Throwable t) {
                    seen.set(t);
                }
            }));
            return null;
        });
        later.get().start();
        later.get().join();

        assertThat(seen.get()).isInstanceOf(PathNotAllowedException.class);
        assertThat(PathPolicy.active()).isEmpty();
        assertThat(PathPolicy.checkReadIfActive(outside.resolve("secret.ttl"))).as("no policy here: unchanged")
                .isEqualTo(outside.resolve("secret.ttl"));
    }

    @Test
    void whileAContextIsRequiredAThreadWithoutOneIsRefused() throws Exception {
        try (PathPolicy.Requirement required = PathPolicy.requireContext()) {
            assertThatThrownBy(() -> PathPolicy.checkReadIfActive(root.resolve("in.ttl")))
                    .isInstanceOf(PathNotAllowedException.class);
            assertThatThrownBy(() -> PathPolicy.checkWriteIfActive(root.resolve("x.ttl")))
                    .isInstanceOf(PathNotAllowedException.class);
            assertThat(PathPolicy.runWith(policy, () -> PathPolicy.checkReadIfActive(root.resolve("in.ttl"))))
                    .isEqualTo(realPath(root.resolve("in.ttl")));
        }
        assertThat(PathPolicy.checkReadIfActive(outside.resolve("secret.ttl"))).isEqualTo(outside.resolve("secret.ttl"));
    }

    @Test
    void concurrentCommandsEachKeepTheirOwnPolicy() throws Exception {
        PathPolicy other = PathPolicy.builder().root(outside).build();
        java.util.concurrent.CyclicBarrier together = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> PathPolicy.runWith(policy, () -> {
                together.await(10, java.util.concurrent.TimeUnit.SECONDS);
                PathPolicy.checkReadIfActive(root.resolve("in.ttl"));
                assertThatThrownBy(() -> PathPolicy.checkReadIfActive(outside.resolve("secret.ttl")))
                        .isInstanceOf(PathNotAllowedException.class);
                return PathPolicy.active().orElseThrow();
            }));
            var b = pool.submit(() -> PathPolicy.runWith(other, () -> {
                together.await(10, java.util.concurrent.TimeUnit.SECONDS);
                PathPolicy.checkReadIfActive(outside.resolve("secret.ttl"));
                assertThatThrownBy(() -> PathPolicy.checkReadIfActive(root.resolve("in.ttl")))
                        .isInstanceOf(PathNotAllowedException.class);
                return PathPolicy.active().orElseThrow();
            }));
            assertThat(a.get()).isSameAs(policy);
            assertThat(b.get()).isSameAs(other);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void traversalOutOfTheRootIsRefused() {
        Path sneaky = root.resolve("../outside/secret.ttl");

        assertThatThrownBy(() -> policy.checkRead(sneaky)).isInstanceOf(PathNotAllowedException.class);
        assertThatThrownBy(() -> policy.checkWrite(root.resolve("../outside/new.ttl"), false))
                .isInstanceOf(PathNotAllowedException.class);
    }

    @Test
    void absolutePathOutsideTheRootIsRefused() {
        assertThatThrownBy(() -> policy.checkRead(outside.resolve("secret.ttl")))
                .isInstanceOf(PathNotAllowedException.class)
                .hasMessageContaining("outside the allowed");
        assertThatThrownBy(() -> policy.checkWrite(outside.resolve("x.ttl"), false))
                .isInstanceOf(PathNotAllowedException.class);
    }

    @Test
    void rootThatIsOnlyAPrefixOfAnotherFolderDoesNotCoverIt() throws Exception {
        Path sibling = Files.createDirectory(tempDir.resolve("root-other"));
        Files.writeString(sibling.resolve("f.ttl"), "x");

        assertThatThrownBy(() -> policy.checkRead(sibling.resolve("f.ttl"))).isInstanceOf(PathNotAllowedException.class);
    }

    @Test
    void symlinkEscapeIsRefused() throws Exception {
        Path link = root.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (Exception e) {
            abort("symbolic links not available here: " + e.getMessage());
        }

        assertThatThrownBy(() -> policy.checkRead(link.resolve("secret.ttl"))).isInstanceOf(PathNotAllowedException.class);
        assertThatThrownBy(() -> policy.checkWrite(link.resolve("new.ttl"), false)).isInstanceOf(PathNotAllowedException.class);
    }

    @Test
    void junctionEscapeIsRefusedOnWindows() throws Exception {
        assumeTrue(windows(), "Windows junctions only");
        Path junction = root.resolve("junction");
        Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), outside.toString())
                .redirectErrorStream(true).start();
        assumeTrue(mklink.waitFor(20, TimeUnit.SECONDS) && mklink.exitValue() == 0, "mklink /J failed");

        assertThatThrownBy(() -> policy.checkRead(junction.resolve("secret.ttl"))).isInstanceOf(PathNotAllowedException.class);
        assertThatThrownBy(() -> policy.checkWrite(junction.resolve("new.ttl"), false)).isInstanceOf(PathNotAllowedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\/server/share/f.ttl", "/\\server\\share\\f.ttl", " \\\\server\\share\\f.ttl"})
    void mixedSeparatorUncPathsCountAsUnc(String path) {
        assertThat(PathPolicy.isUncOrDevice(path)).isTrue();
        assertThatThrownBy(() -> policy.checkRead(path, root))
                .isInstanceOf(PathNotAllowedException.class)
                .hasMessageContaining("UNC");
    }

    @Test
    void ordinaryPathsAreNotUnc() {
        assertThat(PathPolicy.isUncOrDevice("C:\\data\\x.ttl")).isFalse();
        assertThat(PathPolicy.isUncOrDevice("/data/x.ttl")).isFalse();
        assertThat(PathPolicy.isUncOrDevice("x.ttl")).isFalse();
        assertThat(PathPolicy.isUncOrDevice(null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\\\server\\share\\f.ttl", "//server/share/f.ttl", "\\\\?\\C:\\f.ttl", "\\\\.\\PhysicalDrive0"})
    void uncAndDevicePathsAreRefused(String path) {
        assertThatThrownBy(() -> policy.checkRead(path, root)).isInstanceOf(PathNotAllowedException.class);
        assertThatThrownBy(() -> policy.checkWrite(path, root, false)).isInstanceOf(PathNotAllowedException.class);
    }

    @Test
    void uncPathsCanBeAllowedExplicitly() throws Exception {
        PathPolicy unc = PathPolicy.builder().root(root).allowUnc(true).build();

        // Allowed past the UNC check; still has to be under a root, which this one isn't.
        assertThatThrownBy(() -> unc.checkRead("\\\\server\\share\\f.ttl", root))
                .isInstanceOf(PathNotAllowedException.class)
                .hasMessageNotContaining("UNC");
    }

    @Test
    void relativeStringPathsResolveAgainstTheGivenBase() {
        assertThat(policy.checkRead("in.ttl", root)).isEqualTo(realPath(root.resolve("in.ttl")));
        assertThatThrownBy(() -> policy.checkRead("../outside/secret.ttl", root)).isInstanceOf(PathNotAllowedException.class);
    }

    @Test
    void unparseablePathIsRefusedNotThrownRaw() {
        assertThatThrownBy(() -> policy.checkRead("in\u0000.ttl", root)).isInstanceOf(PathNotAllowedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CON", "nul.txt", "COM1", "lpt9.ttl", "aux"})
    void reservedDeviceNamesAreRefused(String name) {
        assertThatThrownBy(() -> policy.checkWrite(root.resolve(name), false))
                .isInstanceOf(PathNotAllowedException.class)
                .hasMessageContaining("device");
    }

    @Test
    void alternateDataStreamIsRefusedOnWindows() {
        assumeTrue(windows(), "NTFS streams only");
        assertThatThrownBy(() -> policy.checkWrite(root + "\\in.ttl:hidden", root, false))
                .isInstanceOf(PathNotAllowedException.class);
    }

    @Test
    void existingOutputIsNotOverwrittenUnlessAllowed() {
        Path existing = root.resolve("in.ttl");

        assertThatThrownBy(() -> policy.checkWrite(existing, false))
                .isInstanceOf(PathNotAllowedException.class)
                .hasMessageContaining("overwrite");
        assertThat(policy.checkWrite(existing, true)).isEqualTo(realPath(existing));
    }

    @Test
    void existingOutputDirectoryIsFine() {
        assertThat(policy.checkWrite(root, false)).isEqualTo(realPath(root));
    }

    @Test
    void readOnlyRootCanBeReadButNotWritten() throws Exception {
        PathPolicy split = PathPolicy.builder().readRoot(outside).writeRoot(root).build();

        assertThat(split.checkRead(outside.resolve("secret.ttl"))).isNotNull();
        assertThatThrownBy(() -> split.checkWrite(outside.resolve("x.ttl"), false)).isInstanceOf(PathNotAllowedException.class);
        // Write roots are readable too: commands read back their own outputs.
        assertThat(split.checkRead(root.resolve("in.ttl"))).isNotNull();
    }

    @Test
    void missingReadPathIsRefused() {
        assertThatThrownBy(() -> policy.checkRead(root.resolve("nope.ttl")))
                .isInstanceOf(PathNotAllowedException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void activePolicyAppliesOnlyInsideItsScope() throws Exception {
        assertThat(PathPolicy.active()).isEmpty();
        Path outsideFile = outside.resolve("secret.ttl");
        assertThat(PathPolicy.checkReadIfActive(outsideFile)).isEqualTo(outsideFile);

        PathPolicy.runWith(policy, () -> {
            assertThat(PathPolicy.active()).contains(policy);
            assertThatThrownBy(() -> PathPolicy.checkReadIfActive(outsideFile)).isInstanceOf(PathNotAllowedException.class);
            if (windows()) {
                // Only Windows has UNC paths; on POSIX "//server/share" is the local "/server/share".
                assertThatThrownBy(() -> PathPolicy.refuseNetworkPathIfActive(Path.of("//server/share/x.xml")))
                        .isInstanceOf(PathNotAllowedException.class);
            }
            // Files Core names inside an output folder are replaced; only the location counts.
            assertThat(PathPolicy.checkWriteIfActive(root.resolve("in.ttl"))).isNotNull();
            return null;
        });
        PathPolicy.refuseNetworkPathIfActive(Path.of("//server/share/x.xml")); // no policy: no-op

        assertThat(PathPolicy.active()).isEmpty();
    }

    @Test
    void rootsMustExist() {
        assertThatThrownBy(() -> PathPolicy.builder().root(tempDir.resolve("missing")).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Path realPath(Path p) {
        try {
            return p.toRealPath();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aNullPolicyIsRefusedRatherThanRunningUnrestricted() {
        // A null policy would leave the thread unrestricted even while a context is required.
        assertThatThrownBy(() -> PathPolicy.runWith(null, () -> "ran")).isInstanceOf(NullPointerException.class);
    }
}
