/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * The address rule of every outbound request CimPal makes on someone else's behalf: the remote
 * imports of {@link ValidationTools} (its second egress tier) and the job callbacks of
 * {@code serve} (DEP-14). A host must resolve only to public addresses, so a URL from untrusted
 * input can't reach loopback, link-local (cloud instance metadata), private or multicast
 * addresses.
 */
public final class EgressPolicy {

    private EgressPolicy() {
    }

    /**
     * Loopback, link-local, private, wildcard and multicast addresses, plus ranges Java's checks
     * miss:
     * <ul>
     *   <li>IPv4 (SEC-5, DEP-14 review): carrier-grade NAT {@code 100.64.0.0/10}, "this network"
     *       {@code 0.0.0.0/8}, IETF protocol assignments {@code 192.0.0.0/24}, benchmarking
     *       {@code 198.18.0.0/15}, reserved and broadcast {@code 240.0.0.0/4};</li>
     *   <li>IPv6: unique-local {@code fc00::/7} ({@code isSiteLocalAddress} only covers the
     *       deprecated {@code fec0::/10}), documentation {@code 2001:db8::/32}, Teredo
     *       {@code 2001::/32}; and the forms that carry an IPv4 address, checked as that address:
     *       NAT64 {@code 64:ff9b::/96}, 6to4 {@code 2002::/16} and IPv4-compatible
     *       {@code ::a.b.c.d} (IPv4-mapped addresses already come back as IPv4).</li>
     * </ul>
     */
    public static boolean isNonPublicAddress(InetAddress addr) {
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                || addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
            return true;
        }
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            return nonPublicIpv4(b);
        }
        int b0 = b[0] & 0xff;
        int b1 = b[1] & 0xff;
        if ((b0 & 0xfe) == 0xfc) {
            return true; // unique-local
        }
        if (b0 == 0x20 && b1 == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) {
            return true; // documentation
        }
        if (b0 == 0x20 && b1 == 0x01 && b[2] == 0 && b[3] == 0) {
            return true; // Teredo: the IPv4 behind it can't be told
        }
        if (b0 == 0x00 && b1 == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b && isZero(b, 4, 12)) {
            return embeddedNonPublic(b, 12); // NAT64 64:ff9b::/96
        }
        if (b0 == 0x20 && b1 == 0x02) {
            return embeddedNonPublic(b, 2); // 6to4 2002:a.b.c.d::/48
        }
        if (isZero(b, 0, 12)) {
            return embeddedNonPublic(b, 12); // IPv4-compatible ::a.b.c.d
        }
        return false;
    }

    private static boolean nonPublicIpv4(byte[] b) {
        int b0 = b[0] & 0xff;
        int b1 = b[1] & 0xff;
        int b2 = b[2] & 0xff;
        return b0 == 0 || b0 == 10 || b0 == 127 || b0 >= 224 // this network, private, loopback, multicast and 240/4
                || (b0 == 100 && (b1 & 0xc0) == 64) // carrier-grade NAT
                || (b0 == 169 && b1 == 254) || (b0 == 172 && (b1 & 0xf0) == 16) || (b0 == 192 && b1 == 168)
                || (b0 == 192 && b1 == 0 && b2 == 0) // IETF protocol assignments
                || (b0 == 198 && (b1 & 0xfe) == 18); // benchmarking
    }

    private static boolean embeddedNonPublic(byte[] b, int from) {
        return nonPublicIpv4(new byte[] {b[from], b[from + 1], b[from + 2], b[from + 3]});
    }

    private static boolean isZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Resolves {@code host} and refuses it unless every address is public. Apply immediately
     * before the request: a name's resolution can change.
     *
     * @return the addresses it resolved to
     * @throws IOException when it can't be resolved or an address isn't public
     */
    public static List<InetAddress> requirePublicHost(String host) throws IOException {
        if (host == null || host.isBlank()) {
            throw new IOException("No host");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IOException("Cannot resolve host: " + LogSanitizer.forLog(host), e);
        }
        for (InetAddress addr : addresses) {
            if (isNonPublicAddress(addr)) {
                throw new IOException("Host resolves to a non-public address: " + LogSanitizer.forLog(host));
            }
        }
        return List.of(addresses);
    }
}
