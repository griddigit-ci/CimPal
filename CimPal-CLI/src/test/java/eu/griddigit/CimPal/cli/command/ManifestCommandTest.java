/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.CimPal.cli.CimPalCli;
import eu.griddigit.CimPal.cli.ExitCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code manifest} with a config file (DOC-2), as {@code mcp}, {@code serve} and {@code run} call it. */
class ManifestCommandTest {

    private static final Path MODEL = Path.of("src/test/resources/fixtures/docker-smoke/models/conforming.xml");

    private static int run(String... args) {
        return new CommandLine(new CimPalCli()).execute(args);
    }

    private static Path models(Path dir) throws Exception {
        Path models = Files.createDirectories(dir.resolve("models"));
        Files.copy(MODEL, models.resolve("EQ.xml"));
        return models;
    }

    @Test
    void theConfigKeysWorkLikeTheFlags(@TempDir Path dir) throws Exception {
        Path models = models(dir);
        Path out = dir.resolve("out/manifest.ttl");
        Files.createDirectories(out.getParent());
        Path config = Files.writeString(dir.resolve("manifest.json"), """
                {"_comment": "ignored", "dir": "%s", "accessUrl": "Instance/Grid", "output": "%s"}
                """.formatted(json(models), json(out)));

        assertThat(run("manifest", "--config", config.toString())).isEqualTo(ExitCode.OK);
        // A relative access URL becomes the folder in front of each file name.
        assertThat(Files.readString(out)).contains("<file:Instance/Grid/EQ.xml>");
    }

    @Test
    void relativePathsResolveAgainstTheConfigFile(@TempDir Path dir) throws Exception {
        models(dir);
        Path config = Files.writeString(dir.resolve("manifest.json"), """
                {"files": ["models/EQ.xml"], "output": "manifest.ttl"}
                """);

        assertThat(run("manifest", "--config", config.toString())).isEqualTo(ExitCode.OK);
        assertThat(dir.resolve("manifest.ttl")).isRegularFile();
    }

    @Test
    void aFlagWinsOverTheConfig(@TempDir Path dir) throws Exception {
        models(dir);
        Path config = Files.writeString(dir.resolve("manifest.json"), """
                {"dir": "models", "output": "from-config.ttl"}
                """);

        assertThat(run("manifest", "--config", config.toString(), "--output", dir.resolve("from-flag.ttl").toString()))
                .isEqualTo(ExitCode.OK);
        assertThat(dir.resolve("from-flag.ttl")).isRegularFile();
        assertThat(dir.resolve("from-config.ttl")).doesNotExist();
    }

    @Test
    void aConfigThatCannotBeReadIsBadInput(@TempDir Path dir) throws Exception {
        Path config = Files.writeString(dir.resolve("manifest.json"), "{not json");

        assertThat(run("manifest", "--config", config.toString())).isEqualTo(ExitCode.INVALID_INPUT);
        assertThat(run("manifest", "--config", dir.resolve("missing.json").toString())).isEqualTo(ExitCode.INVALID_INPUT);
    }

    private static String json(Path p) {
        return p.toAbsolutePath().toString().replace("\\", "/");
    }
}
