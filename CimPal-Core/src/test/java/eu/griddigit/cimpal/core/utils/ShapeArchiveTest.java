/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The SHACL files of a ZIP archive, read into memory for the combined validation workflow. */
class ShapeArchiveTest {

    private static final String TTL = "<urn:test:S> a <http://www.w3.org/ns/shacl#NodeShape> .\n";
    private static final String RDF_XML = """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about="urn:test:S">
                <rdf:type rdf:resource="http://www.w3.org/ns/shacl#NodeShape"/>
              </rdf:Description>
            </rdf:RDF>
            """;

    @TempDir
    Path tempDir;

    private Path zip(String name, String... namesAndContents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                out.putNextEntry(new ZipEntry(namesAndContents[i]));
                out.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return Files.write(tempDir.resolve(name), bytes.toByteArray());
    }

    @Test
    void ttlAndRdfEntriesAreReadAsShapeFilesEachInItsSyntax() throws Exception {
        ShapeArchive archive = ShapeArchive.read(zip("constraints.zip",
                "SSH.rdf", RDF_XML, "EQ.ttl", TTL, "readme.txt", "not RDF"));

        assertThat(archive.entries()).extracting(ShapeArchive.Entry::name).containsExactly("EQ.ttl", "SSH.rdf");
        for (ShapeArchive.Entry entry : archive.entries()) {
            assertThat(entry.read().size()).as(entry.name()).isEqualTo(1);
        }
    }

    @Test
    void externalEntitiesInRdfEntriesAreNotResolved() throws Exception {
        // A third-party .rdf entry must not pull a local file (or, via a UNC path, a network host)
        // into the shapes graph through an XML external entity.
        Path secret = Files.writeString(tempDir.resolve("secret.txt"), "TOP-SECRET-VALUE");
        String rdf = """
                <?xml version="1.0"?>
                <!DOCTYPE rdf:RDF [<!ENTITY leak SYSTEM "%s">]>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:ex="urn:test:">
                  <rdf:Description rdf:about="urn:test:S"><ex:p>&leak;</ex:p></rdf:Description>
                </rdf:RDF>
                """.formatted(secret.toUri());
        ShapeArchive.Entry entry = ShapeArchive.read(zip("constraints.zip", "SSH.rdf", rdf)).entries().getFirst();

        String parsed;
        try {
            parsed = entry.read().listObjects().toList().toString();
        } catch (RuntimeException refused) {
            parsed = ""; // refusing the document is as safe as ignoring the entity
        }

        assertThat(parsed).doesNotContain("TOP-SECRET-VALUE");
    }

    @Test
    void otherRdfEntriesAreReportedAsNotRead() throws Exception {
        // .xml is left out: an archive may hold its instance data next to its constraint files.
        ShapeArchive archive = ShapeArchive.read(zip("constraints.zip",
                "EQ.ttl", TTL, "SV.nt", "", "SSH.jsonld", "{}", "model.xml", "<a/>", "readme.txt", ""));

        assertThat(archive.unreadRdfEntries()).containsExactly("SSH.jsonld", "SV.nt");
    }

    @Test
    void archiveWithoutShapeFilesIsRefused() throws Exception {
        Path model = zip("model.zip", "eq.xml", "<a/>");

        assertThatThrownBy(() -> ShapeArchive.read(model))
                .isInstanceOf(IOException.class)
                .hasMessage("No .ttl or .rdf files found in archive: model.zip");
    }

    @Test
    void entriesWhoseNamesOnlyMatchOnceNormalisedAreDuplicates() throws Exception {
        // Otherwise the later entry would silently replace the earlier one.
        Path archive = zip("constraints.zip", "a/b.ttl", TTL, "a/./b.ttl", TTL);

        assertThatThrownBy(() -> ShapeArchive.read(archive))
                .isInstanceOf(IOException.class)
                .hasMessage("Duplicate ZIP entry: a/b.ttl");
    }

    @Test
    void archiveOutsideTheAllowedRootsIsRefused() throws Exception {
        Path allowed = Files.createDirectories(tempDir.resolve("allowed"));
        Path archive = zip("constraints.zip", "EQ.ttl", TTL);
        PathPolicy policy = PathPolicy.builder().root(allowed).build();

        assertThatThrownBy(() -> PathPolicy.runWith(policy, () -> ShapeArchive.read(archive)))
                .isInstanceOf(PathNotAllowedException.class);
    }

    @Test
    void readingStaysWithinTheBudget() throws Exception {
        Path archive = zip("constraints.zip", "EQ.ttl", "x".repeat(5_000));

        assertThatThrownBy(() -> ShapeArchive.read(archive, new ModelFactory.ZipBudget(10, 100_000, 1_000)))
                .isInstanceOf(IOException.class)
                .hasMessage("Archive entry exceeds the size limit (1000 bytes)");
    }

    @Test
    void urisInsideTheArchiveFolderMapToItsEntries() throws Exception {
        Path zip = zip("constraints.zip", "CGMES/EQ.ttl", TTL);
        ShapeArchive archive = ShapeArchive.read(zip);
        String folder = zip.toUri().toString();
        String folderPath = zip.toUri().getRawPath();

        assertThat(archive.find(folder + "/CGMES/EQ.ttl")).isEqualTo(archive.entries().getFirst());
        assertThat(archive.find(folder + "/CGMES/sub/../EQ.ttl")).isEqualTo(archive.entries().getFirst());
        // Inside the archive but missing: enclosed, and unresolvable.
        assertThat(archive.encloses(folder + "/CGMES/SSH.ttl")).isTrue();
        assertThat(archive.find(folder + "/CGMES/SSH.ttl")).isNull();
        // The same path on another host, or beside the archive, is not inside it.
        assertThat(archive.encloses("file://attacker.example" + folderPath + "/CGMES/EQ.ttl")).isFalse();
        assertThat(archive.encloses(folder + "/../EQ.ttl")).isFalse();
        assertThat(archive.encloses(folder)).isFalse();
    }
}
