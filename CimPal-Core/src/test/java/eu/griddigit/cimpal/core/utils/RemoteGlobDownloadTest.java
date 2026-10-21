/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import eu.griddigit.cimpal.core.testsupport.StubHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for glob-matched remote XML inputs in mapping validation.
 * <p>
 * A glob token is expanded through the GitHub Contents API and each matching file is downloaded.
 * A file that failed to download used to be logged and dropped, so the row validated the
 * remaining subset and could report clean. Any failed match must now fail the token.
 * <p>
 * The production downloader refuses loopback hosts by egress policy, so these tests drive
 * {@link ValidationTools#downloadGlobMatches} with a downloader that fetches from a local
 * {@link StubHttpServer} and turns a non-2xx status into an {@link IOException}, as the real
 * one does.
 */
class RemoteGlobDownloadTest {

    private static final String TOKEN = "https://github.com/acme/models/tree/main/igm/*_EQ.xml";

    @TempDir
    Path tempDir;

    private StubHttpServer stub;

    @BeforeEach
    void startStub() {
        stub = StubHttpServer.start();
        stub.serve("/a_EQ.xml", "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"/>",
                "application/xml");
        stub.serve("/c_EQ.xml", "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"/>",
                "application/xml");
        // /b_EQ.xml is not registered: the stub answers 404.
    }

    @AfterEach
    void stopStub() {
        stub.close();
    }

    private ValidationTools.XmlDownloader stubDownloader() {
        HttpClient client = HttpClient.newHttpClient();
        return url -> {
            try {
                HttpResponse<byte[]> response = client.send(
                        HttpRequest.newBuilder(URI.create(url)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode() + ": " + url);
                }
                Path target = tempDir.resolve(Path.of(URI.create(url).getPath()).getFileName().toString());
                Files.write(target, response.body());
                return target;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        };
    }

    @Test
    void oneFailedMatchFailsTheWholeToken() {
        List<ValidationTools.GitHubApiEntry> entries = List.of(
                new ValidationTools.GitHubApiEntry("a_EQ.xml", stub.uri("/a_EQ.xml").toString()),
                new ValidationTools.GitHubApiEntry("b_EQ.xml", stub.uri("/b_EQ.xml") + "?token=s3cr3t"));

        IOException ex = assertThrows(IOException.class, () ->
                ValidationTools.downloadGlobMatches(entries, "*_EQ.xml", TOKEN, stubDownloader()));

        assertTrue(ex.getMessage().contains("b_EQ.xml"), ex.getMessage());
        assertTrue(ex.getMessage().contains("1 of 2"), ex.getMessage());
        // The signed download URL's query string must not reach the report.
        assertFalse(ex.getMessage().contains("s3cr3t"), ex.getMessage());
    }

    @Test
    void allMatchesDownloadedAreReturnedSortedByName() throws IOException {
        List<ValidationTools.GitHubApiEntry> entries = List.of(
                new ValidationTools.GitHubApiEntry("c_EQ.xml", stub.uri("/c_EQ.xml").toString()),
                new ValidationTools.GitHubApiEntry("a_EQ.xml", stub.uri("/a_EQ.xml").toString()),
                // Not matched by the pattern, so never downloaded even though it would 404.
                new ValidationTools.GitHubApiEntry("b_SSH.xml", stub.uri("/b_SSH.xml").toString()));

        List<Path> paths = ValidationTools.downloadGlobMatches(entries, "*_EQ.xml", TOKEN, stubDownloader());

        assertEquals(List.of("a_EQ.xml", "c_EQ.xml"),
                paths.stream().map(p -> p.getFileName().toString()).toList());
        assertEquals(2, stub.requests().size());
    }
}
