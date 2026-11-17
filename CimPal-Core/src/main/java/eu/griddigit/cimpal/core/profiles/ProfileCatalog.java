/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.core.profiles;

import eu.griddigit.cimpal.core.utils.LogSanitizer;
import eu.griddigit.cimpal.core.utils.PathNotAllowedException;
import eu.griddigit.cimpal.core.utils.PathPolicy;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.shared.JenaException;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;

import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Maps a profile IRI to the SHACL files that constrain it, as the DX-PROF descriptors of a profile
 * library say. An instance file declares its profile ({@code md:Model.profile},
 * {@code dcterms:conformsTo}); the catalog answers which shapes validate it, so no hand-written
 * mapping of constraint files to model files is needed.
 *
 * <p>A library is any folder: every RDF file under it ({@code .rdf}, {@code .xml}, {@code .ttl})
 * that describes a {@code prof:Profile} is a descriptor. Of a profile's resources, those with role
 * {@code constraints} that conform to SHACL are kept. Their artifact is reduced to its file name,
 * since descriptors give it as a bare name or as a web address, and neither says where the file
 * is. The name is looked up in the {@code SHACL} and then the {@code RDFS} folder beside the
 * descriptor's folder, then anywhere under the library; a name found more than once there is not
 * guessed at. Links and junctions are not followed, and under a {@link PathPolicy} a file the
 * policy refuses is treated as absent.
 *
 * <p>What cannot be resolved is a {@linkplain #gaps() gap} and makes its profile
 * {@linkplain Resolution#incomplete() incomplete}, never an exception: a caller decides whether
 * that matters for the files it validates, and must not take an incomplete profile for a clean
 * one.
 *
 * <p>Keys and gap messages come from the descriptors. Gap messages are safe to log; keys are
 * untrusted text and need the usual treatment before they reach a log, a report or an agent.
 */
public final class ProfileCatalog {

    private static final String PROF_NS = "http://www.w3.org/ns/dx/prof/";
    private static final String DCTERMS_NS = "http://purl.org/dc/terms/";
    private static final Resource PROFILE = ResourceFactory.createResource(PROF_NS + "Profile");
    private static final Property HAS_RESOURCE = ResourceFactory.createProperty(PROF_NS, "hasResource");
    private static final Property HAS_ROLE = ResourceFactory.createProperty(PROF_NS, "hasRole");
    private static final Property HAS_ARTIFACT = ResourceFactory.createProperty(PROF_NS, "hasArtifact");
    private static final Property CONFORMS_TO = ResourceFactory.createProperty(DCTERMS_NS, "conformsTo");
    private static final String ROLE_CONSTRAINTS = "role/constraints";
    private static final String SHACL = "http://www.w3.org/ns/shacl";
    /** Folders beside a descriptor's folder that are searched first, in this order. */
    private static final List<String> SIBLING_FOLDERS = List.of("SHACL", "RDFS");
    private static final byte[] PROF_MARK = PROF_NS.getBytes(StandardCharsets.US_ASCII);
    /** Base for relative IRIs in a descriptor: never the file's own location, which is not the caller's to learn. */
    private static final String DESCRIPTOR_BASE = "http://profile-library.invalid/";

    /** A library with more files than this is refused: it is not a profile library. */
    static final int MAX_LIBRARY_FILES = 200_000;
    /** A descriptor is a catalog entry of a few kilobytes; a larger file is not parsed. */
    static final long MAX_DESCRIPTOR_BYTES = 32L * 1024 * 1024;
    /** How much of a file is searched for the PROF namespace, which is declared at its start. */
    private static final int PROF_SCAN_BYTES = 1024 * 1024;

    private final Map<String, ProfileShapes> byKey;
    private final List<ProfileShapes> profiles;
    private final List<String> gaps;
    private final Map<String, List<Path>> filesByName;

    private ProfileCatalog(Map<String, ProfileShapes> byKey, List<ProfileShapes> profiles, List<String> gaps,
                           Map<String, List<Path>> filesByName) {
        this.byKey = Map.copyOf(byKey);
        this.profiles = List.copyOf(profiles);
        this.gaps = List.copyOf(gaps);
        this.filesByName = filesByName;
    }

    /**
     * What {@link #resolve} found.
     *
     * @param shapeFiles the shape files of the mapped profiles together, sorted
     * @param unmapped   the IRIs no profile has as a key
     * @param incomplete the IRIs whose profile is known but lacks shapes: none were resolved, or
     *                   some of its artifacts are missing. Validating with {@code shapeFiles} alone
     *                   would check less than the profile asks for
     */
    public record Resolution(List<Path> shapeFiles, List<String> unmapped, List<String> incomplete) {
        public Resolution {
            shapeFiles = List.copyOf(shapeFiles);
            unmapped = List.copyOf(unmapped);
            incomplete = List.copyOf(incomplete);
        }

        /** True when every IRI has a profile and every profile has all its shapes. */
        public boolean complete() {
            return unmapped.isEmpty() && incomplete.isEmpty();
        }
    }

    /**
     * Reads the descriptors of the given library folders into one catalog.
     *
     * @throws IOException              if a library is not a folder, cannot be walked or holds too
     *                                  many files
     * @throws IllegalArgumentException if no library is given, or two profiles claim the same key
     *                                  with different shape files
     */
    public static ProfileCatalog load(List<Path> libraries) throws IOException {
        return load(libraries, MAX_LIBRARY_FILES);
    }

    /** {@link #load(List)} with a given file limit (tests use a small one). */
    static ProfileCatalog load(List<Path> libraries, int maxFiles) throws IOException {
        if (libraries == null || libraries.isEmpty()) {
            throw new IllegalArgumentException("At least one profile library folder is required");
        }
        Map<String, ProfileShapes> byKey = new TreeMap<>();
        List<ProfileShapes> profiles = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        Map<String, List<Path>> filesByName = new HashMap<>();
        for (Path given : libraries) {
            // serve/mcp/run: checked before anything touches the folder, and read as checked.
            Path library = PathPolicy.checkReadIfActive(given);
            if (!Files.isDirectory(library)) {
                throw new FileNotFoundException("Profile library folder not found: " + label(library));
            }
            Library index = Library.walk(library.toRealPath(), maxFiles, gaps);
            index.byName.forEach((name, files) ->
                    filesByName.computeIfAbsent(name, n -> new ArrayList<>()).addAll(files));
            int before = profiles.size();
            for (Path candidate : index.descriptorCandidates) {
                readDescriptor(candidate, index, profiles, gaps);
            }
            if (profiles.size() == before) {
                gaps.add("no PROF profile found under " + label(library));
            }
        }
        for (ProfileShapes profile : profiles) {
            for (String key : profile.keys()) {
                ProfileShapes other = byKey.putIfAbsent(key, profile);
                if (other != null && !other.shapeFiles().equals(profile.shapeFiles())) {
                    throw new IllegalArgumentException("Profile " + LogSanitizer.forLog(key) + " is described by "
                            + label(other.profFile()) + " and " + label(profile.profFile())
                            + " with different SHACL files; give only one of them");
                }
            }
        }
        return new ProfileCatalog(byKey, profiles, gaps, filesByName);
    }

    /**
     * The files of the libraries with exactly this name, sorted: for shapes that belong to a
     * library but that no descriptor refers to.
     */
    public List<Path> filesNamed(String fileName) {
        return filesByName.getOrDefault(fileName, List.of()).stream().sorted().toList();
    }

    /** The profile that has {@code profileUri} as one of its keys. */
    public Optional<ProfileShapes> find(String profileUri) {
        return Optional.ofNullable(byKey.get(profileUri));
    }

    /** True when {@code profileUri} is a key of a profile in this catalog. */
    public boolean isKey(String profileUri) {
        return byKey.containsKey(profileUri);
    }

    /** The shape files of the given profile IRIs together, and the IRIs without (all of) their shapes. */
    public Resolution resolve(Collection<String> profileUris) {
        Set<Path> shapes = new TreeSet<>();
        Set<String> unmapped = new LinkedHashSet<>();
        Set<String> incomplete = new LinkedHashSet<>();
        for (String uri : profileUris) {
            ProfileShapes profile = byKey.get(uri);
            if (profile == null) {
                unmapped.add(uri);
                continue;
            }
            shapes.addAll(profile.shapeFiles());
            if (!profile.complete()) {
                incomplete.add(uri);
            }
        }
        return new Resolution(new ArrayList<>(shapes), new ArrayList<>(unmapped), new ArrayList<>(incomplete));
    }

    /** Every profile read, in the order the descriptors were found (by path within each library). */
    public List<ProfileShapes> profiles() {
        return profiles;
    }

    /** All keys, sorted. */
    public Set<String> keys() {
        return new TreeSet<>(byKey.keySet());
    }

    /** What the libraries promise but do not deliver, one message each. */
    public List<String> gaps() {
        return gaps;
    }

    // ---- reading one descriptor ----------------------------------------------------------

    private static void readDescriptor(Path file, Library library, List<ProfileShapes> profiles, List<String> gaps) {
        Model model = ModelFactory.createDefaultModel();
        try (InputStream in = Files.newInputStream(file)) {
            if (Files.size(file) > MAX_DESCRIPTOR_BYTES) {
                gaps.add(label(file) + ": too large to be a PROF descriptor; not read");
                return;
            }
            // Only the last path segment of an artifact is used, so any base will do.
            RDFParser.source(in).lang(lang(file)).base(DESCRIPTOR_BASE + "descriptor").parse(model);
        } catch (IOException | JenaException ex) {
            gaps.add(label(file) + ": could not be read as RDF: " + LogSanitizer.forLog(String.valueOf(ex.getMessage())));
            return;
        }
        List<Resource> nodes = model.listSubjectsWithProperty(RDF.type, PROFILE).toList();
        if (nodes.isEmpty()) {
            gaps.add(label(file) + ": no prof:Profile node");
            return;
        }
        nodes.sort((a, b) -> String.valueOf(a).compareTo(String.valueOf(b)));
        for (Resource node : nodes) {
            Set<String> keys = new TreeSet<>();
            if (node.isURIResource() && !node.getURI().startsWith(DESCRIPTOR_BASE)) {
                keys.add(node.getURI());
            }
            for (Property property : List.of(OWL.versionIRI, OWL.priorVersion)) {
                node.listProperties(property).forEach(s -> text(s.getObject())
                        .filter(v -> !v.isEmpty() && !v.startsWith(DESCRIPTOR_BASE)).ifPresent(keys::add));
            }
            if (keys.isEmpty()) {
                gaps.add(label(file) + ": a prof:Profile without an IRI, owl:versionIRI or owl:priorVersion");
                continue;
            }

            Set<Path> shapes = new TreeSet<>();
            Set<String> missing = new TreeSet<>();
            for (RDFNode object : node.listProperties(HAS_RESOURCE).mapWith(s -> s.getObject()).toList()) {
                if (!object.isResource() || !isShaclConstraints(object.asResource())) {
                    continue;
                }
                for (RDFNode artifact : object.asResource().listProperties(HAS_ARTIFACT).mapWith(s -> s.getObject()).toList()) {
                    String name = text(artifact).map(ProfileCatalog::artifactName).orElse(null);
                    if (name == null) {
                        // Still missing: a profile must not look complete without it.
                        String shown = LogSanitizer.forLog(String.valueOf(artifact));
                        gaps.add(label(file) + ": artifact has no usable file name: " + shown);
                        missing.add(shown);
                        continue;
                    }
                    Path resolved = library.find(name, file, gaps);
                    if (resolved == null) {
                        missing.add(name);
                    } else {
                        shapes.add(resolved);
                    }
                }
            }
            if (shapes.isEmpty()) {
                gaps.add(label(file) + ": no resolvable constraints-role SHACL artifacts");
            }
            for (String name : missing) {
                gaps.add(label(file) + ": artifact not found: " + LogSanitizer.forLog(name));
            }
            profiles.add(new ProfileShapes(file, library.root, keys, new ArrayList<>(shapes), new ArrayList<>(missing)));
        }
    }

    /** Only syntaxes that fetch nothing: JSON-LD would load a remote context. */
    private static Lang lang(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ttl") ? Lang.TURTLE : Lang.RDFXML;
    }

    private static boolean isShaclConstraints(Resource descriptor) {
        boolean constraints = descriptor.listProperties(HAS_ROLE).toList().stream()
                .anyMatch(s -> text(s.getObject()).orElse("").endsWith(ROLE_CONSTRAINTS));
        boolean shacl = descriptor.listProperties(CONFORMS_TO).toList().stream()
                .anyMatch(s -> {
                    String value = text(s.getObject()).orElse("");
                    return value.equals(SHACL) || value.equals(SHACL + "#");
                });
        return constraints && shacl;
    }

    private static Optional<String> text(RDFNode node) {
        if (node.isURIResource()) {
            return Optional.of(node.asResource().getURI());
        }
        if (node.isLiteral()) {
            return Optional.of(node.asLiteral().getLexicalForm().strip());
        }
        return Optional.empty();
    }

    /**
     * The file name an artifact value ends with, or null when it has none that is safe to look up.
     * Only the text after the last separator of either kind is kept, so a value can name a file
     * of the library and nothing else: no folder, no parent, no drive, device or stream.
     */
    static String artifactName(String artifact) {
        String value = artifact.strip();
        int cut = indexOfAny(value, "?#");
        if (cut >= 0) {
            value = value.substring(0, cut);
        }
        String name = value.substring(Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\')) + 1).strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf(':') >= 0
                || name.indexOf('%') >= 0 || PathPolicy.isReservedName(name) || LogSanitizer.hasUnsafeCharacters(name)) {
            return null;
        }
        return name;
    }

    private static int indexOfAny(String value, String chars) {
        int first = -1;
        for (char c : chars.toCharArray()) {
            int at = value.indexOf(c);
            if (at >= 0 && (first < 0 || at < first)) {
                first = at;
            }
        }
        return first;
    }

    private static String label(Path path) {
        Path name = path.getFileName();
        return LogSanitizer.forLog(name == null ? path.toString() : name.toString());
    }

    // ---- one library folder --------------------------------------------------------------

    /** The regular files of a library by name, and those among them that may be descriptors. */
    private static final class Library {
        private final Path root;
        private final Map<String, List<Path>> byName = new HashMap<>();
        private final List<Path> descriptorCandidates = new ArrayList<>();
        private int files;

        private Library(Path root) {
            this.root = root;
        }

        /** @param root the library's real path, so every file listed is under it by name and in fact */
        static Library walk(Path root, int maxFiles, List<String> gaps) throws IOException {
            Library library = new Library(root);
            // Links are not followed. Each folder and file is also checked: a junction is not a link
            // to Java, and a policy may exclude a folder inside an allowed root (another job's files).
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(root)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    // .git and the like: large, and never part of a library.
                    return name.startsWith(".") || attrs.isOther() || attrs.isSymbolicLink() || !library.readable(dir)
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile() || !library.readable(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (++library.files > maxFiles) {
                        throw new IOException("The profile library " + label(root) + " holds more than " + maxFiles
                                + " files; give the folder of the library itself");
                    }
                    library.byName.computeIfAbsent(file.getFileName().toString(), n -> new ArrayList<>()).add(file);
                    String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if ((lower.endsWith(".rdf") || lower.endsWith(".xml") || lower.endsWith(".ttl"))
                            && mentionsProf(file)) {
                        library.descriptorCandidates.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            library.descriptorCandidates.sort(null);
            library.byName.values().forEach(list -> list.sort(null));
            return library;
        }

        /** True when {@code path} really lies in the library and the active policy, if any, allows it. */
        private boolean readable(Path path) {
            try {
                if (!PathPolicy.mayReadIfActive(path) || !path.toRealPath().startsWith(root)) {
                    return false;
                }
                PathPolicy.checkReadIfActive(path);
                return true;
            } catch (IOException | PathNotAllowedException ex) {
                return false;
            }
        }

        /**
         * A cheap test before parsing: large vocabularies and shape files do not use the PROF
         * namespace. A UTF-16 file cannot be searched this way and is left to the parser.
         */
        private static boolean mentionsProf(Path file) throws IOException {
            try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
                int matched = 0;
                int b;
                int first = -1;
                for (int read = 0; read < PROF_SCAN_BYTES && (b = in.read()) >= 0; read++) {
                    if (read == 0) {
                        first = b;
                    } else if (read == 1 && ((first == 0xFE && b == 0xFF) || (first == 0xFF && b == 0xFE))) {
                        return true;
                    }
                    if (b == PROF_MARK[matched]) {
                        if (++matched == PROF_MARK.length) {
                            return true;
                        }
                    } else {
                        // The mark has a single 'h', its first byte, so restarting on the current
                        // byte is enough.
                        matched = b == PROF_MARK[0] ? 1 : 0;
                    }
                }
                return false;
            }
        }

        /** The file called {@code name}: beside the descriptor first, then the only one in the library. */
        Path find(String name, Path descriptor, List<String> gaps) {
            List<Path> all = byName.getOrDefault(name, List.of());
            if (all.isEmpty()) {
                return null;
            }
            Path base = descriptor.getParent() == null ? null : descriptor.getParent().getParent();
            if (base != null) {
                for (String folder : SIBLING_FOLDERS) {
                    Path sibling = base.resolve(folder).resolve(name);
                    if (all.contains(sibling)) {
                        return sibling;
                    }
                }
            }
            if (all.size() > 1) {
                gaps.add(label(descriptor) + ": artifact " + LogSanitizer.forLog(name) + " is found "
                        + all.size() + " times in the library and none is beside the descriptor; not used");
                return null;
            }
            return all.getFirst();
        }
    }

    /** For {@link Object#toString()} in logs and test failures. */
    @Override
    public String toString() {
        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("profiles", profiles.size());
        summary.put("keys", byKey.size());
        summary.put("gaps", gaps.size());
        return "ProfileCatalog" + summary;
    }
}
