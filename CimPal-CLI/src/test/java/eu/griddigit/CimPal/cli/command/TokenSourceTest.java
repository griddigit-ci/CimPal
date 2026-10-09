/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Token sources of {@code serve} (DEP-6): the read-only, reloadable token file. */
class TokenSourceTest {

    private static final String A = "token-a-0123456789abcdef0123456789abcdef";
    private static final String B = "token-b-0123456789abcdef0123456789abcdef";
    private static final String C = "token-c-0123456789abcdef0123456789abcdef";

    @TempDir
    Path tempDir;

    private final JobApiTest.MovableClock clock = new JobApiTest.MovableClock();
    private final List<String> warnings = new ArrayList<>();

    private TokenSource.FileTokens open(Path file) {
        return new TokenSource.FileTokens(file, Duration.ofSeconds(60), clock, warnings::add);
    }

    /** Writes the file and moves its modification time on, so the change is seen at once. */
    private Path write(Path file, String content, int generation) throws Exception {
        Files.writeString(file, content);
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            // Like a real secret: not readable by any user (umask 022 would make it so, and warn).
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_700_000_000_000L + generation * 1000L));
        return file;
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    void oneOrTwoTokensAreAcceptedCommentsAndBlankLinesIgnored() throws Exception {
        TokenSource.FileTokens tokens = open(write(tempDir.resolve("token"), "# rotation\n" + A + "\n\n" + B + "\n", 0));

        assertThat(tokens.count()).isEqualTo(2);
        assertThat(tokens.matches(bearer(A))).isTrue();
        assertThat(tokens.matches(bearer(B))).isTrue();
        assertThat(tokens.matches(bearer(C))).isFalse();
        assertThat(tokens.matches(null)).isFalse();
        assertThat(tokens.matches("Basic " + A)).isFalse();
    }

    @Test
    void rotationTakesEffectAfterTheReloadIntervalAndTheOldTokenIsThenRefused() throws Exception {
        Path file = write(tempDir.resolve("token"), A + "\n", 0);
        TokenSource.FileTokens tokens = open(file);

        write(file, A + "\n" + B + "\n", 1); // step 1: both accepted
        assertThat(tokens.matches(bearer(B))).as("not reloaded before the interval").isFalse();
        clock.advance(Duration.ofSeconds(60));
        assertThat(tokens.matches(bearer(B))).isTrue();
        assertThat(tokens.matches(bearer(A))).isTrue();

        write(file, B + "\n", 2); // step 2: the old one goes
        clock.advance(Duration.ofSeconds(61));
        assertThat(tokens.matches(bearer(A))).isFalse();
        assertThat(tokens.matches(bearer(B))).isTrue();
        assertThat(warnings).isEmpty();
    }

    @Test
    void aBrokenFileKeepsTheOldTokensForOneCheckThenRefusesEverything() throws Exception {
        // Revoking must not fail open: a secret being swapped is bridged, a broken one is not.
        Path file = write(tempDir.resolve("token"), A + "\n", 0);
        TokenSource.FileTokens tokens = open(file);

        Files.delete(file);
        clock.advance(Duration.ofSeconds(61));
        assertThat(tokens.matches(bearer(A))).as("bridged for one check").isTrue();
        assertThat(tokens.usable()).isTrue();
        clock.advance(Duration.ofSeconds(61));
        assertThat(tokens.matches(bearer(A))).as("still broken: refused").isFalse();
        assertThat(tokens.usable()).isFalse();

        write(file, A + "\n", 1);
        clock.advance(Duration.ofSeconds(61));
        assertThat(tokens.matches(bearer(A))).as("fixed file: accepted again").isTrue();

        write(file, "short\n", 2);
        clock.advance(Duration.ofSeconds(61));
        assertThat(tokens.matches(bearer(A))).isTrue();
        clock.advance(Duration.ofSeconds(61));
        assertThat(tokens.matches(bearer(A))).isFalse();

        assertThat(warnings).hasSize(4);
        assertThat(warnings.get(0)).contains("until the next check");
        assertThat(warnings.get(1)).contains("every request is refused");
        assertThat(String.join("\n", warnings)).doesNotContain(A).doesNotContain("short");
    }

    @Test
    void anEmptyFileRevokesEveryTokenAtOnce() throws Exception {
        Path file = write(tempDir.resolve("token"), A + "\n", 0);
        TokenSource.FileTokens tokens = open(file);

        write(file, "# all revoked\n", 1);
        clock.advance(Duration.ofSeconds(60));

        assertThat(tokens.matches(bearer(A))).isFalse();
        assertThat(tokens.usable()).isFalse();
        assertThat(warnings).singleElement().asString().contains("no token");
    }

    @Test
    void aReplacementOfTheSameSizeAndTimeIsStillSeen() throws Exception {
        // Change detection is by content: same-length tokens written within one timestamp tick.
        Path file = write(tempDir.resolve("token"), A + "\n", 0);
        TokenSource.FileTokens tokens = open(file);

        write(file, B + "\n", 0);
        clock.advance(Duration.ofSeconds(60));

        assertThat(tokens.matches(bearer(B))).isTrue();
        assertThat(tokens.matches(bearer(A))).isFalse();
    }

    @Test
    void onlyARegularFileOfBoundedSizeIsRead() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("dir"));
        assertThatThrownBy(() -> open(dir)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a regular file");

        Path file = write(tempDir.resolve("token"), A + "\n", 0);
        TokenSource.FileTokens tokens = open(file);
        write(file, A + "\n#" + "x".repeat(TokenSource.FileTokens.MAX_FILE_BYTES), 1); // grew too large
        clock.advance(Duration.ofSeconds(60));
        tokens.matches(bearer(A));
        assertThat(warnings).singleElement().asString().contains("larger than");
    }

    @Test
    void theFileIsNeverWrittenOrDeleted() throws Exception {
        Path file = write(tempDir.resolve("token"), A + "\n", 0);
        FileTime before = Files.getLastModifiedTime(file);
        TokenSource.FileTokens tokens = open(file);
        clock.advance(Duration.ofHours(1));
        tokens.matches(bearer(A));

        assertThat(Files.readString(file)).isEqualTo(A + "\n");
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(before);
    }

    @Test
    void anUnusableFileIsRefusedAtStartWithoutShowingItsContent() throws Exception {
        String shortToken = "too-short";
        for (String content : new String[] {"", "# only a comment\n", shortToken + "\n", A + "\n" + B + "\n" + C + "\n",
                "x".repeat(TokenSource.FileTokens.MAX_FILE_BYTES + 1)}) {
            Path file = Files.writeString(tempDir.resolve("bad"), content);
            assertThatThrownBy(() -> open(file)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Token file").hasMessageNotContaining(shortToken).hasMessageNotContaining(A);
        }
        assertThatThrownBy(() -> open(tempDir.resolve("missing"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aWorldReadableFileGivesAWarning() throws Exception {
        Path file = write(tempDir.resolve("token"), A + "\n", 0);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        open(file);
        assertThat(warnings).singleElement().asString().contains("any user");

        warnings.clear();
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r-----")); // Kubernetes fsGroup
        open(file);
        assertThat(warnings).isEmpty();
    }

    @Test
    void aFixedTokenNeedsAValue() {
        assertThat(TokenSource.fixed(A).matches(bearer(A))).isTrue();
        assertThat(TokenSource.fixed(A).matches(bearer(B))).isFalse();
        assertThatThrownBy(() -> TokenSource.fixed(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
