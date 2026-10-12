/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.comparators;

import eu.griddigit.cimpal.core.models.RDFCompareResult;
import eu.griddigit.cimpal.core.models.RDFCompareResultEntry;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** SHACL shape comparison keeps its prefixes per call (DEP-8: concurrent compares in serve). */
class ComparisonSHACLshapesTest {

    /** The same namespace under a different prefix in each pair. */
    private static Model shapes(String prefix, String targetClass) {
        Model model = ModelFactory.createDefaultModel();
        RDFParser.fromString("""
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                @prefix %s: <urn:one#> .
                <urn:shapes#S> a sh:NodeShape ; sh:targetClass %s:%s .
                """.formatted(prefix, prefix, targetClass), Lang.TURTLE).parse(model);
        return model;
    }

    private static List<String> values(RDFCompareResult result) {
        List<String> values = new ArrayList<>();
        for (RDFCompareResultEntry entry : result.getEntries()) {
            values.add(entry.getItem() + " " + entry.getValueModelA() + " " + entry.getValueModelB());
        }
        return values;
    }

    @Test
    void aComparisonShortensValuesWithItsOwnPrefixes() {
        List<String> values = values(new ComparisonSHACLshapes().compare(shapes("x", "Thing"), shapes("x", "Other")));
        assertThat(values).isNotEmpty();
        assertThat(String.join("\n", values)).contains("x:Thing").doesNotContain("urn:one#");
    }

    @Test
    void concurrentComparisonsDoNotUseEachOthersPrefixes() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<List<String>>> xs = new ArrayList<>();
            List<Future<List<String>>> ys = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                xs.add(pool.submit(() -> values(new ComparisonSHACLshapes().compare(shapes("x", "Thing"), shapes("x", "Other")))));
                ys.add(pool.submit(() -> values(new ComparisonSHACLshapes().compare(shapes("y", "Thing"), shapes("y", "Other")))));
            }
            for (Future<List<String>> x : xs) {
                assertThat(String.join("\n", x.get())).contains("x:").doesNotContain("y:");
            }
            for (Future<List<String>> y : ys) {
                assertThat(String.join("\n", y.get())).contains("y:").doesNotContain("x:");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
