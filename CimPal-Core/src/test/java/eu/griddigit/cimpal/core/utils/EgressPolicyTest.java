/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

/** The address rule of outbound requests (remote imports, serve's callbacks): literals only, no DNS. */
class EgressPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "0.0.0.0", "0.1.2.3",
            "100.64.0.1", "192.0.0.8", "198.18.0.1", "198.19.255.255", "240.0.0.1", "255.255.255.255", "224.0.0.1",
            "::1", "::", "fe80::1", "fc00::1", "fd12::1", "2001:db8::1", "2001:0:4136:e378::1",
            "64:ff9b::a9fe:a9fe", // NAT64 of 169.254.169.254
            "2002:7f00:1::1", // 6to4 of 127.0.0.1
            "::a00:1", // IPv4-compatible 10.0.0.1
            "::ffff:10.0.0.1"}) // IPv4-mapped
    void nonPublicAddressesAreRefused(String literal) throws Exception {
        assertThat(EgressPolicy.isNonPublicAddress(InetAddress.getByName(literal))).as(literal).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "140.82.112.3", "100.128.0.1", "198.20.0.1", "2606:4700::1111",
            "64:ff9b::808:808", "2002:808:808::1"})
    void publicAddressesPass(String literal) throws Exception {
        assertThat(EgressPolicy.isNonPublicAddress(InetAddress.getByName(literal))).as(literal).isFalse();
    }
}
