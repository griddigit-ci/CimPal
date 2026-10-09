/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.cimpal.core.utils.LogSanitizer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Request checks and token handling for {@code serve} (SEC-1, gap G1).
 *
 * <p>The daemon is reachable by anything that can open a socket to it, including web pages in
 * the user's browser (via DNS rebinding or simple cross-origin POSTs) and other local users.
 * These checks close that: a per-start bearer token kept in a user-only file, a Host header
 * allowlist, an Origin allowlist, JSON-only POST bodies and a hard body limit.
 */
final class ServeSecurity {

    /** Environment variable that supplies the token instead of a generated one. */
    static final String TOKEN_ENV = "CIMPAL_API_TOKEN";

    /** Environment variable naming a read-only token file, like {@code --token-from-file} (DEP-6). */
    static final String TOKEN_FILE_ENV = "CIMPAL_API_TOKEN_FILE";

    /** Shortest token accepted from {@link #TOKEN_ENV}; a generated token is 43 characters. */
    static final int MIN_ENV_TOKEN_LENGTH = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern IPV4_LOOPBACK = Pattern.compile("127(?:\\.\\d{1,3}){3}");
    private static final List<String> LOOPBACK_NAMES = List.of("localhost", "127.0.0.1", "[::1]");

    private ServeSecurity() {
    }

    /** Thrown by {@link #readBounded} when the body exceeds the limit. */
    static final class BodyTooLargeException extends IOException {
        BodyTooLargeException(long limit) {
            super("Request body exceeds " + limit + " bytes");
        }
    }

    // ---- token -----------------------------------------------------------------------------

    /** 256 random bits, base64url without padding. */
    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * The token from {@link #TOKEN_ENV}, if set. A value shorter than
     * {@link #MIN_ENV_TOKEN_LENGTH} is refused; the message never contains the value.
     */
    static Optional<String> tokenFromEnvironment(Map<String, String> env) {
        String value = env.get(TOKEN_ENV);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String token = value.strip();
        if (token.length() < MIN_ENV_TOKEN_LENGTH) {
            throw new IllegalArgumentException(TOKEN_ENV + " must be at least " + MIN_ENV_TOKEN_LENGTH
                    + " characters long.");
        }
        return Optional.of(token);
    }

    /**
     * True when {@code authorization} is {@code Bearer <expected>}. Both sides are hashed first,
     * so the comparison takes the same time whatever the length or content of the guess.
     */
    static boolean tokenMatches(String expected, String authorization) {
        if (expected == null || authorization == null) {
            return false;
        }
        String prefix = "bearer ";
        if (authorization.length() <= prefix.length()
                || !authorization.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return false;
        }
        String presented = authorization.substring(prefix.length()).strip();
        return MessageDigest.isEqual(sha256(expected), sha256(presented));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Default token file: {@code %LOCALAPPDATA%\CimPal\serve.token} on Windows, otherwise (or when
     * LOCALAPPDATA is unset) {@code ~/.cimpal/serve.token}.
     */
    static Path defaultTokenFile(Map<String, String> env, String osName, Path userHome) {
        String localAppData = env.get("LOCALAPPDATA");
        if (osName != null && osName.toLowerCase(Locale.ROOT).startsWith("windows")
                && localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData).resolve("CimPal").resolve("serve.token");
        }
        return userHome.resolve(".cimpal").resolve("serve.token");
    }

    /**
     * Writes {@code token} to {@code file}, readable and writable by the current user only.
     *
     * <p>Any existing file or link at that path is removed first. The new file is created with
     * {@code CREATE_NEW} and {@code NOFOLLOW_LINKS}, with owner-only permissions set at creation
     * (POSIX mode {@code rw-------}, or on Windows an initial ACL granting only the current user),
     * and the token is written through that same handle. So a link planted at the path is never
     * followed, and no other user can open the file between its creation and the write.
     *
     * <p>Before the token is written, the result is checked. On POSIX the parent directory must
     * belong to the same user as the new file and not be writable by group or others, otherwise
     * another user could replace the file. On Windows the ACL is read back and reset to the user
     * alone if anything else (e.g. an inherited entry) appears. A failed check deletes the empty
     * file and fails, so the token is never written to an unsafe place.
     */
    static void writeTokenFile(Path file, String token) throws IOException {
        Path target = file.toAbsolutePath();
        Path parent = target.getParent();
        boolean posix = supportsPosix(parent);
        if (parent != null && !Files.isDirectory(parent)) {
            if (posix) {
                Files.createDirectories(parent, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectories(parent);
            }
        }
        Files.deleteIfExists(target);

        UserPrincipal windowsUser = posix ? null
                : windowsUser(System.getenv("USERDOMAIN"), System.getProperty("user.name"));
        FileAttribute<?> ownerOnly = posix
                ? PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
                : windowsUser == null ? null : aclAttribute(List.of(ownerEntry(windowsUser)));
        Set<OpenOption> openOptions = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        FileAttribute<?>[] attributes = ownerOnly == null ? new FileAttribute<?>[0] : new FileAttribute<?>[] {ownerOnly};
        boolean written = false;
        try (SeekableByteChannel channel = Files.newByteChannel(target, openOptions, attributes)) {
            if (posix) {
                requirePrivateDirectory(parent, target);
            } else {
                restrictToOnly(target, windowsUser);
            }
            ByteBuffer bytes = ByteBuffer.wrap(token.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            written = true;
        } finally {
            if (!written) {
                Files.deleteIfExists(target);
            }
        }
    }

    private static boolean supportsPosix(Path path) {
        return path != null && path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    /**
     * POSIX: {@code dir} must have the same owner as {@code ownFile} (just created, so it is ours;
     * comparing principals compares uids, which also works without a passwd entry) and must not
     * be writable by group or others.
     */
    private static void requirePrivateDirectory(Path dir, Path ownFile) throws IOException {
        PosixFileAttributes attrs = Files.readAttributes(dir, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Set<PosixFilePermission> perms = attrs.permissions();
        UserPrincipal us = Files.getOwner(ownFile, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.owner().equals(us)
                || perms.contains(PosixFilePermission.GROUP_WRITE)
                || perms.contains(PosixFilePermission.OTHERS_WRITE)) {
            throw new IOException("Token file directory " + dir
                    + " must be owned by you and not writable by other users.");
        }
    }

    /**
     * The current Windows user, looked up by its qualified name {@code DOMAIN\name}. A bare name
     * can resolve to a different account with the same short name (e.g. a local account next to
     * the domain one). Null when the domain is unknown or the lookup fails.
     */
    static UserPrincipal windowsUser(String domain, String name) {
        if (domain == null || domain.isBlank() || name == null || name.isBlank()) {
            return null;
        }
        try {
            return FileSystems.getDefault().getUserPrincipalLookupService()
                    .lookupPrincipalByName(domain.strip() + "\\" + name.strip());
        } catch (IOException | UnsupportedOperationException e) {
            return null;
        }
    }

    /** An {@code acl:acl} attribute, which sets the ACL of a file as it is created. */
    private static FileAttribute<List<AclEntry>> aclAttribute(List<AclEntry> acl) {
        return new FileAttribute<>() {
            @Override
            public String name() {
                return "acl:acl";
            }

            @Override
            public List<AclEntry> value() {
                return acl;
            }
        };
    }

    private static AclEntry ownerEntry(UserPrincipal owner) {
        return AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
    }

    /**
     * Windows: makes {@code user} (or, when it is unknown, the file's owner) the only principal in
     * the file's ACL. Does nothing when the ACL already is exactly that.
     */
    private static void restrictToOnly(Path file, UserPrincipal user) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("Cannot restrict permissions of " + file + ": no POSIX or ACL support");
        }
        UserPrincipal only = user != null ? user : view.getOwner();
        List<AclEntry> acl = view.getAcl();
        if (acl.isEmpty() || !acl.stream().allMatch(entry -> entry.principal().equals(only))) {
            view.setAcl(List.of(ownerEntry(only)));
        }
    }

    // ---- request checks --------------------------------------------------------------------

    /** Literal loopback names only; no DNS lookup, so a hostile resolver can't widen this. */
    static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        String h = host.strip().toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("::1") || h.equals("[::1]") || IPV4_LOOPBACK.matcher(h).matches();
    }

    /**
     * DNS-rebinding defence: the Host header must be a loopback name with the bound port (or, for
     * {@code --allow-remote}, the bound host itself). The port may be omitted only on port 80.
     */
    static boolean isAllowedHost(String hostHeader, int port, String boundHost, boolean allowRemote) {
        return isAllowedHost(hostHeader, port, boundHost, allowRemote, Set.of());
    }

    /**
     * As above, and also the public names of {@code --allowed-host} (DEP-6), the names a reverse
     * proxy or ingress forwards in the Host header. An entry {@code name} matches that name
     * without a port or with {@code :80} or {@code :443}; {@code name:port} matches only that
     * port. Entries come from {@link #allowedHostEntry(String)}.
     */
    static boolean isAllowedHost(String hostHeader, int port, String boundHost, boolean allowRemote,
                                 Set<String> allowedHosts) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        String value = hostHeader.strip().toLowerCase(Locale.ROOT);
        String name;
        String portPart;
        int colon = value.lastIndexOf(':');
        if (colon > 0 && colon > value.lastIndexOf(']')) {
            name = value.substring(0, colon);
            portPart = value.substring(colon + 1);
        } else {
            name = value;
            portPart = null;
        }
        if ((allowedHosts.contains(value) && portPart != null)
                || (allowedHosts.contains(name) && (portPart == null || portPart.equals("80") || portPart.equals("443")))) {
            return true;
        }
        boolean portOk = portPart == null ? port == 80 : portPart.equals(Integer.toString(port));
        if (!portOk) {
            return false;
        }
        if (LOOPBACK_NAMES.contains(name)) {
            return true;
        }
        return allowRemote && boundHost != null && name.equals(boundHost.strip().toLowerCase(Locale.ROOT));
    }

    private static final Pattern HOST_NAME = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*");
    private static final Pattern IPV6_LITERAL = Pattern.compile("\\[[0-9a-f:.]+]");

    /**
     * Normalises one {@code --allowed-host} value: {@code name} or {@code name:port}, a DNS name,
     * an IPv4 address or a bracketed IPv6 address, lower case. No scheme, path, user or wildcard.
     *
     * @throws IllegalArgumentException for anything else
     */
    static String allowedHostEntry(String value) {
        String v = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        String name = v;
        int colon = v.lastIndexOf(':');
        if (colon > 0 && colon > v.lastIndexOf(']')) {
            name = v.substring(0, colon);
            String portText = v.substring(colon + 1);
            int p;
            try {
                p = Integer.parseInt(portText);
            } catch (NumberFormatException e) {
                p = -1;
            }
            if (p < 1 || p > 65535 || !portText.equals(Integer.toString(p))) {
                throw new IllegalArgumentException("--allowed-host " + LogSanitizer.forLog(value)
                        + ": the port must be a number from 1 to 65535.");
            }
        }
        if (!(HOST_NAME.matcher(name).matches() && name.length() <= 253) && !IPV6_LITERAL.matcher(name).matches()) {
            throw new IllegalArgumentException("--allowed-host " + LogSanitizer.forLog(value)
                    + ": expected a host name with an optional port, e.g. cimpal.example.com or cimpal.example.com:8443"
                    + " (no scheme, path or wildcard).");
        }
        return v;
    }

    /** {@code application/json}, optionally with parameters such as a charset. */
    static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        String mediaType = contentType.split(";", 2)[0].strip();
        return mediaType.equalsIgnoreCase("application/json");
    }

    /** Reads at most {@code limit} bytes; one byte more fails with {@link BodyTooLargeException}. */
    static byte[] readBounded(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > limit) {
                throw new BodyTooLargeException(limit);
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
