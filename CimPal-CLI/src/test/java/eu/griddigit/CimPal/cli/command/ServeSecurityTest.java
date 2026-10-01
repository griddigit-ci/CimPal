/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Token handling and request checks of {@code serve} (SEC-1, gap G1). */
class ServeSecurityTest {

    @TempDir
    Path tempDir;

    @Test
    void newTokenIs256BitsOfBase64UrlAndDiffersEachTime() {
        String token = ServeSecurity.newToken();

        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
        assertThat(ServeSecurity.newToken()).isNotEqualTo(token);
    }

    @Test
    void bearerTokenMustMatchExactly() {
        String token = ServeSecurity.newToken();

        assertThat(ServeSecurity.tokenMatches(token, "Bearer " + token)).isTrue();
        assertThat(ServeSecurity.tokenMatches(token, "bearer " + token)).isTrue();
        assertThat(ServeSecurity.tokenMatches(token, "Bearer " + token + "x")).isFalse();
        assertThat(ServeSecurity.tokenMatches(token, "Bearer " + token.substring(1))).isFalse();
        assertThat(ServeSecurity.tokenMatches(token, token)).isFalse();
        assertThat(ServeSecurity.tokenMatches(token, "Basic " + token)).isFalse();
        assertThat(ServeSecurity.tokenMatches(token, null)).isFalse();
        assertThat(ServeSecurity.tokenMatches(token, "Bearer ")).isFalse();
    }

    @Test
    void tokenFileIsReadableByTheOwnerOnly() throws Exception {
        Path file = tempDir.resolve("sub/serve.token");
        ServeSecurity.writeTokenFile(file, "secret-token");

        assertThat(Files.readString(file)).isEqualTo("secret-token");
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            assertThat(PosixFilePermissions.toString(posix.readAttributes().permissions())).isEqualTo("rw-------");
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            // The ACL names the current user. That is not always the file owner: files created
            // by an elevated process (e.g. a CI runner) are owned by BUILTIN\Administrators.
            UserPrincipal user = ServeSecurity.windowsUser(System.getenv("USERDOMAIN"), System.getProperty("user.name"));
            UserPrincipal expected = user != null ? user : acl.getOwner();
            List<AclEntry> entries = acl.getAcl();
            assertThat(entries).isNotEmpty().allSatisfy(entry -> assertThat(entry.principal()).isEqualTo(expected));
        }
    }

    @Test
    void existingTokenFileIsReplacedNotAppended() throws Exception {
        Path file = tempDir.resolve("serve.token");
        Files.writeString(file, "old-token-and-more");

        ServeSecurity.writeTokenFile(file, "new");

        assertThat(Files.readString(file)).isEqualTo("new");
    }

    @Test
    void linkPlantedAtTheTokenPathIsReplacedNotFollowed() throws Exception {
        Path victim = Files.writeString(tempDir.resolve("victim.txt"), "keep");
        Path file = tempDir.resolve("serve.token");
        try {
            Files.createSymbolicLink(file, victim);
        } catch (java.io.IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links not available here: " + e.getMessage());
        }

        ServeSecurity.writeTokenFile(file, "secret-token");

        assertThat(Files.readString(victim)).isEqualTo("keep");
        assertThat(Files.isSymbolicLink(file)).isFalse();
        assertThat(Files.readString(file)).isEqualTo("secret-token");
    }

    @Test
    void sharedWritableDirectoryIsRefusedOnPosix() throws Exception {
        Path shared = Files.createDirectory(tempDir.resolve("shared"));
        PosixFileAttributeView posix = Files.getFileAttributeView(shared, PosixFileAttributeView.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(posix != null, "POSIX permissions only");
        posix.setPermissions(PosixFilePermissions.fromString("rwxrwxrwx"));

        assertThatThrownBy(() -> ServeSecurity.writeTokenFile(shared.resolve("serve.token"), "secret-token"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("not writable by other users")
                .hasMessageNotContaining("secret-token");
        assertThat(shared.resolve("serve.token")).doesNotExist();
    }

    @Test
    void windowsUserIsLookedUpByQualifiedNameOnly() throws Exception {
        assertThat(ServeSecurity.windowsUser(null, "someone")).isNull();
        assertThat(ServeSecurity.windowsUser(" ", "someone")).isNull();
        assertThat(ServeSecurity.windowsUser("DOMAIN", null)).isNull();

        String domain = System.getenv("USERDOMAIN");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.getFileAttributeView(tempDir, AclFileAttributeView.class) != null && domain != null,
                "Windows only");
        UserPrincipal me = ServeSecurity.windowsUser(domain, System.getProperty("user.name"));
        assertThat(me).isNotNull();
        assertThat(me.getName()).containsIgnoringCase(System.getProperty("user.name"));

        // The token file's ACL names exactly that qualified account, nobody else.
        Path file = tempDir.resolve("serve.token");
        ServeSecurity.writeTokenFile(file, "secret-token");
        List<AclEntry> acl = Files.getFileAttributeView(file, AclFileAttributeView.class).getAcl();
        assertThat(acl).isNotEmpty().allSatisfy(entry -> assertThat(entry.principal()).isEqualTo(me));
    }

    @Test
    void defaultTokenFileIsPerUser() {
        Path home = tempDir.resolve("home");
        Path local = tempDir.resolve("local");

        assertThat(ServeSecurity.defaultTokenFile(Map.of("LOCALAPPDATA", local.toString()), "Windows 11", home))
                .isEqualTo(local.resolve("CimPal").resolve("serve.token"));
        assertThat(ServeSecurity.defaultTokenFile(Map.of(), "Linux", home))
                .isEqualTo(home.resolve(".cimpal").resolve("serve.token"));
        assertThat(ServeSecurity.defaultTokenFile(Map.of(), "Windows 11", home))
                .isEqualTo(home.resolve(".cimpal").resolve("serve.token"));
    }

    @Test
    void environmentTokenIsUsedWhenStrongEnough() {
        String strong = "x".repeat(32);

        assertThat(ServeSecurity.tokenFromEnvironment(Map.of(ServeSecurity.TOKEN_ENV, strong))).hasValue(strong);
        assertThat(ServeSecurity.tokenFromEnvironment(Map.of())).isEmpty();
        assertThatThrownBy(() -> ServeSecurity.tokenFromEnvironment(Map.of(ServeSecurity.TOKEN_ENV, "short")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ServeSecurity.TOKEN_ENV)
                .hasMessageNotContaining("short");
    }

    @Test
    void hostHeaderMustNameLoopbackAndTheBoundPort() {
        assertThat(ServeSecurity.isAllowedHost("localhost:7474", 7474, "localhost", false)).isTrue();
        assertThat(ServeSecurity.isAllowedHost("127.0.0.1:7474", 7474, "localhost", false)).isTrue();
        assertThat(ServeSecurity.isAllowedHost("[::1]:7474", 7474, "localhost", false)).isTrue();
        assertThat(ServeSecurity.isAllowedHost("localhost:7475", 7474, "localhost", false)).isFalse();
        assertThat(ServeSecurity.isAllowedHost("localhost", 7474, "localhost", false)).isFalse();
        assertThat(ServeSecurity.isAllowedHost("evil.example:7474", 7474, "localhost", false)).isFalse();
        assertThat(ServeSecurity.isAllowedHost("localhost.evil.example:7474", 7474, "localhost", false)).isFalse();
        assertThat(ServeSecurity.isAllowedHost(null, 7474, "localhost", false)).isFalse();
        assertThat(ServeSecurity.isAllowedHost("", 7474, "localhost", false)).isFalse();
        assertThat(ServeSecurity.isAllowedHost("localhost", 80, "localhost", false)).isTrue();
    }

    @Test
    void remoteBindAlsoAcceptsTheBoundHostOnlyWithAllowRemote() {
        assertThat(ServeSecurity.isAllowedHost("cimpal.lan:7474", 7474, "cimpal.lan", true)).isTrue();
        assertThat(ServeSecurity.isAllowedHost("cimpal.lan:7474", 7474, "cimpal.lan", false)).isFalse();
        assertThat(ServeSecurity.isAllowedHost("other.lan:7474", 7474, "cimpal.lan", true)).isFalse();
    }

    @Test
    void loopbackBindAddresses() {
        assertThat(ServeSecurity.isLoopback("localhost")).isTrue();
        assertThat(ServeSecurity.isLoopback("127.0.0.1")).isTrue();
        assertThat(ServeSecurity.isLoopback("127.1.2.3")).isTrue();
        assertThat(ServeSecurity.isLoopback("::1")).isTrue();
        assertThat(ServeSecurity.isLoopback("[::1]")).isTrue();
        assertThat(ServeSecurity.isLoopback("0.0.0.0")).isFalse();
        assertThat(ServeSecurity.isLoopback("192.168.1.10")).isFalse();
        assertThat(ServeSecurity.isLoopback("localhost.evil.example")).isFalse();
    }

    @Test
    void onlyApplicationJsonCountsAsJson() {
        assertThat(ServeSecurity.isJson("application/json")).isTrue();
        assertThat(ServeSecurity.isJson("Application/JSON; charset=UTF-8")).isTrue();
        assertThat(ServeSecurity.isJson("text/plain")).isFalse();
        assertThat(ServeSecurity.isJson("application/x-www-form-urlencoded")).isFalse();
        assertThat(ServeSecurity.isJson("application/jsonp")).isFalse();
        assertThat(ServeSecurity.isJson(null)).isFalse();
    }

    @Test
    void boundedReadStopsAtTheLimit() throws Exception {
        byte[] ok = ServeSecurity.readBounded(new ByteArrayInputStream(new byte[10]), 10);
        assertThat(ok).hasSize(10);

        assertThatThrownBy(() -> ServeSecurity.readBounded(new ByteArrayInputStream(new byte[11]), 10))
                .isInstanceOf(ServeSecurity.BodyTooLargeException.class);
    }
}
