/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import picocli.CommandLine;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code serve} in a JVM of its own for {@link ServeSignalIT}: the real command, real SIGTERM
 * handling, but a stand-in for the CimPal commands. Each "command" sleeps {@code it.jobMillis}
 * and then writes {@code <query>.done} into {@code it.dir}, so the test sees which jobs ran to
 * the end.
 */
public final class ServeSignalItMain {

    private ServeSignalItMain() {
    }

    public static void main(String[] args) {
        long millis = Long.getLong("it.jobMillis", 3000);
        Path dir = Path.of(System.getProperty("it.dir"));
        ServeCommand command = new ServeCommand();
        command.runnerForTest = (name, configFile) -> {
            String query = new ObjectMapper().readTree(configFile.toFile()).path("query").asString();
            Thread.sleep(millis);
            Files.writeString(dir.resolve(query + ".done"), "done");
            return new ServeServer.CommandResult(0, "{\"query\":\"" + query + "\"}");
        };
        System.exit(new CommandLine(command).execute(args));
    }
}
