/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.utils;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.RDFParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The SHACL shape files of a ZIP archive: its {@code .ttl} and {@code .rdf} entries, nested
 * archives included, read into memory once within an archive budget.
 * <p>
 * Each entry is parsed with the base URI it would have if the archive were a folder of the same
 * name, for example {@code file:///C:/shapes/constraints.zip/CGMES/EQ.ttl}. A relative
 * {@code owl:imports} in it therefore resolves to the URI of another entry, which {@link #find}
 * maps back to that entry. An import that leaves the archive is resolved on disk like any other,
 * so a package can still import a file kept next to it.
 */
final class ShapeArchive {

    /**
     * Uncompressed bytes a shapes archive may hold, in all and per entry. Constraint packages run
     * to megabytes, and their entries are kept in memory, so this is far below the 2 GiB that
     * model archives get.
     */
    static final long MAX_ARCHIVE_BYTES = 256L * 1024 * 1024;

    private final Path archive;
    private final URI folder;
    private final Map<String, byte[]> entries;
    private final List<String> unreadRdfEntries;

    private ShapeArchive(Path archive, Map<String, byte[]> entries, List<String> unreadRdfEntries) {
        this.archive = archive;
        this.folder = archive.toUri();
        this.entries = entries;
        this.unreadRdfEntries = List.copyOf(unreadRdfEntries);
    }

    /**
     * Reads the {@code .ttl} and {@code .rdf} entries of {@code zip}.
     *
     * @throws IOException if the archive cannot be read, exceeds {@link #MAX_ARCHIVE_BYTES}, holds
     *                     an unsafe or a duplicate entry name, or has no shape file at all
     */
    static ShapeArchive read(Path zip) throws IOException {
        return read(zip, ModelFactory.ZipBudget.withTotalLimit(MAX_ARCHIVE_BYTES));
    }

    /** {@link #read(Path)} within a given budget (tests use small limits). */
    static ShapeArchive read(Path zip, ModelFactory.ZipBudget budget) throws IOException {
        Path archive = PathPolicy.checkReadIfActive(zip.toAbsolutePath().normalize());
        Map<String, byte[]> entries = new TreeMap<>();
        List<String> unreadRdfEntries = new ArrayList<>();
        ModelFactory.forEachZipEntry(archive, budget, (name, in) -> {
            if (!isShapeFile(name)) {
                // .xml is left out: an archive may hold its instance data next to its constraints.
                if (RDFLanguages.filenameToLang(name) != null && !name.toLowerCase(Locale.ROOT).endsWith(".xml")) {
                    unreadRdfEntries.add(name);
                }
                return;
            }
            if (entries.containsKey(name)) {
                throw new IOException("Duplicate ZIP entry: " + name);
            }
            entries.put(name, in.readAllBytes());
        });
        if (entries.isEmpty()) {
            // An archive of the wrong kind would otherwise add no shapes, and validate nothing.
            throw new IOException("No .ttl or .rdf files found in archive: " + archive.getFileName());
        }
        Collections.sort(unreadRdfEntries);
        return new ShapeArchive(archive, entries, unreadRdfEntries);
    }

    private static boolean isShapeFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".ttl") || lower.endsWith(".rdf");
    }

    /**
     * Entries in another RDF syntax than Turtle or RDF/XML (N-Triples, JSON-LD, OWL, ...), which
     * were not read as shape files; {@code .xml} entries are not counted. In name order.
     */
    List<String> unreadRdfEntries() {
        return unreadRdfEntries;
    }

    /** One shape source per {@code .ttl} or {@code .rdf} entry, in entry-name order. */
    List<Entry> entries() {
        List<Entry> sources = new ArrayList<>();
        for (String name : entries.keySet()) {
            sources.add(new Entry(this, name));
        }
        return sources;
    }

    /** True when {@code uri} points inside this archive, whether or not an entry is there. */
    boolean encloses(String uri) {
        return entryName(uri) != null;
    }

    /** The entry {@code uri} points at, or null when this archive has no such entry. */
    Entry find(String uri) {
        String name = entryName(uri);
        return name != null && entries.containsKey(name) ? new Entry(this, name) : null;
    }

    private String entryName(String uri) {
        URI target;
        try {
            target = new URI(uri).normalize();
        } catch (URISyntaxException ex) {
            return null;
        }
        String prefix = folder.getPath() + "/";
        String path = target.getPath();
        if (!"file".equalsIgnoreCase(target.getScheme())
                || !Objects.equals(folder.getRawAuthority(), target.getRawAuthority())
                || path == null || !path.startsWith(prefix) || path.length() == prefix.length()) {
            return null;
        }
        return path.substring(prefix.length());
    }

    private String baseUri(String name) {
        try {
            // Entry names are already safe (ModelFactory.safeZipEntryName): relative, no ':'.
            return folder + "/" + new URI(null, null, name, null).getRawPath();
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("Cannot form a base URI for ZIP entry " + name, ex);
        }
    }

    /** One shape file inside a {@link ShapeArchive}. */
    record Entry(ShapeArchive archive, String name) implements ValidationTools.ShapeSource {

        @Override
        public String key() {
            return "zip:" + archive.archive + "!/" + name;
        }

        @Override
        public String displayName() {
            return archive.archive.getFileName() + "!/" + name;
        }

        Model read() {
            Model model = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
            RDFParser.source(new ByteArrayInputStream(archive.entries.get(name)))
                    .lang(name.toLowerCase(Locale.ROOT).endsWith(".rdf") ? Lang.RDFXML : Lang.TURTLE)
                    .base(archive.baseUri(name))
                    .parse(model);
            return model;
        }
    }
}
