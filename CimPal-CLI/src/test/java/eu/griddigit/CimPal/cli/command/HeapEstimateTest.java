/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The heap estimate of {@code serve --workers} admission (DEP-8), {@link ServeCommand#estimateHeap}. */
class HeapEstimateTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MIB = 1L << 20;

    @TempDir
    Path tempDir;

    private static byte[] body(ObjectNode config) {
        return JSON.writeValueAsBytes(config);
    }

    private Path file(String name, int bytes) throws Exception {
        Path file = tempDir.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.write(file, new byte[bytes]);
    }

    @Test
    void tenTimesTheInputAtLeast64MiB() throws Exception {
        Path small = file("small.xml", 1000);
        ObjectNode config = JSON.createObjectNode().put("input", small.toString());
        assertThat(ServeCommand.estimateHeap("convert", body(config))).isEqualTo(ServeCommand.MIN_HEAP_ESTIMATE);

        Path big = file("big.xml", 8 * (int) MIB);
        config.put("input", big.toString());
        assertThat(ServeCommand.estimateHeap("convert", body(config))).isEqualTo(80 * MIB);
    }

    @Test
    void foldersListsAndCommaListsAreSummedAndAPathGivenTwiceCountsOnce() throws Exception {
        Path a = file("models/a.xml", 4 * (int) MIB);
        file("models/sub/b.xml", 4 * (int) MIB);
        ObjectNode sparql = JSON.createObjectNode();
        sparql.putArray("models").add(tempDir.resolve("models").toString()).add(a.toString()).add(a.toString());
        // the folder (8 MiB) plus a.xml once more: duplicates count once, overlaps are not looked for
        assertThat(ServeCommand.estimateHeap("sparql", body(sparql))).isEqualTo(120 * MIB);

        Path c = file("c.xml", 8 * (int) MIB);
        ObjectNode commaList = JSON.createObjectNode().put("models", c + "," + c + " ");
        assertThat(ServeCommand.estimateHeap("sparql", body(commaList))).isEqualTo(80 * MIB);
    }

    @Test
    void textThatIsNoPathAndOutputsCountNothing() throws Exception {
        Path out = file("out.ttl", 50 * (int) MIB);
        ObjectNode sparql = JSON.createObjectNode().put("query", "SELECT * WHERE { ?s ?p ?o }")
                .put("output", out.toString());
        sparql.putArray("models");
        assertThat(ServeCommand.estimateHeap("sparql", body(sparql))).isEqualTo(ServeCommand.MIN_HEAP_ESTIMATE);
    }

    @Test
    void aFolderBeyondTheEntryBudgetRunsAlone() throws Exception {
        for (int i = 0; i < 20; i++) {
            Files.createDirectories(tempDir.resolve("deep").resolve("d" + i)); // folders count, not only files
        }
        ObjectNode config = JSON.createObjectNode();
        config.putArray("models").add(tempDir.resolve("deep").toString());
        assertThat(ServeCommand.estimateHeap("sparql", body(config), 10)).isEqualTo(Long.MAX_VALUE);
        assertThat(ServeCommand.estimateHeap("sparql", body(config), 100)).isEqualTo(ServeCommand.MIN_HEAP_ESTIMATE);
    }

    @Test
    void manyPathsShareOneBudget() throws Exception {
        Path a = file("a.xml", 10);
        ObjectNode config = JSON.createObjectNode();
        var models = config.putArray("models");
        for (int i = 0; i < 20; i++) {
            models.add(file("f" + i + ".xml", 10).toString());
        }
        models.add(a.toString());
        assertThat(ServeCommand.estimateHeap("sparql", body(config), 10)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void aLinkInsideAFolderIsNotFollowed() throws Exception {
        Path outside = file("outside/huge.xml", 16 * (int) MIB);
        Path folder = Files.createDirectories(tempDir.resolve("inputs"));
        file("inputs/own.xml", 1000);
        try {
            Files.createSymbolicLink(folder.resolve("link"), outside.getParent());
        } catch (Exception e) {
            assumeTrue(false, "symbolic links can't be created here: " + e.getMessage());
        }
        ObjectNode config = JSON.createObjectNode();
        config.putArray("models").add(folder.toString());
        assertThat(ServeCommand.estimateHeap("sparql", body(config))).isEqualTo(ServeCommand.MIN_HEAP_ESTIMATE);
    }
}
