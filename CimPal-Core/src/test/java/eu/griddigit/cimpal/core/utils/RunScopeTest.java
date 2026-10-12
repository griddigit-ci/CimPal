/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Per-run caches (DEP-8): concurrent runs neither see nor clear each other's remote caches. */
class RunScopeTest {

    @AfterEach
    void clearProcessWide() {
        ValidationTools.REMOTE_IMPORTS_CACHE.clear();
    }

    @Test
    void withoutAScopeTheProcessWideCacheIsUsed() {
        assertThat(ValidationTools.remoteImportsCache()).isSameAs(ValidationTools.REMOTE_IMPORTS_CACHE);
    }

    @Test
    void aRunsCacheIsItsOwnAndAClearLeavesTheOthers() throws Exception {
        Model model = ModelFactory.createDefaultModel();
        CountDownLatch bothFilled = new CountDownLatch(2);
        CountDownLatch aCleared = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> RunScope.runIn(() -> {
                ValidationTools.remoteImportsCache().put("urn:a", model);
                bothFilled.countDown();
                bothFilled.await(10, TimeUnit.SECONDS);
                ValidationTools.clearRemoteCaches(); // the start of A's next run, as every run does
                aCleared.countDown();
                return ValidationTools.remoteImportsCache().size();
            }));
            Future<Boolean> b = pool.submit(() -> RunScope.runIn(() -> {
                ValidationTools.remoteImportsCache().put("urn:b", model);
                bothFilled.countDown();
                aCleared.await(10, TimeUnit.SECONDS);
                // A's clear didn't touch B, and B never saw A's entry.
                return ValidationTools.remoteImportsCache().containsKey("urn:b")
                        && !ValidationTools.remoteImportsCache().containsKey("urn:a");
            }));
            assertThat(a.get()).isZero();
            assertThat(b.get()).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(ValidationTools.REMOTE_IMPORTS_CACHE).as("the process-wide cache is untouched").isEmpty();
    }

    @Test
    void threadsARunStartsShareItsScope() throws Exception {
        RunScope.runIn(() -> {
            ValidationTools.remoteImportsCache().put("urn:x", ModelFactory.createDefaultModel());
            ExecutorService pool = Executors.newFixedThreadPool(1);
            try {
                assertThat(pool.submit(() -> ValidationTools.remoteImportsCache().containsKey("urn:x")).get()).isTrue();
            } finally {
                pool.shutdownNow();
            }
            return null;
        });
        assertThat(ValidationTools.remoteImportsCache()).doesNotContainKey("urn:x");
    }
}
