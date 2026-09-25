/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.net.URI;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.InputStream;

/** Small, dependency-free local knowledge retrieval for a user-selected folder. */
public final class AiKnowledgeSearch {
    private static final Set<String> EXTENSIONS = Set.of(".java", ".md", ".txt", ".ttl", ".rdf", ".xml", ".sparql", ".rq", ".shacl", ".json", ".yaml", ".yml", ".properties");
    private static final int MAX_FILES = 400;
    private static final int MAX_MATCHES = 3;
    private static final int MAX_CHARS_PER_MATCH = 3_000;
    private static final int MAX_REMOTE_SOURCES = 3;
    private static final Duration REMOTE_CACHE_TTL = Duration.ofHours(24);
    private static final int MAX_REMOTE_BYTES = 2_000_000;
    private static final Pattern GITHUB_SEGMENT = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._-]{0,98}[A-Za-z0-9])?");
    private static final HttpClient REMOTE_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    private AiKnowledgeSearch() { }

    public static String findRelevant(Path root, String request) throws IOException {
        return AiKnowledgeIndex.search(root, request);
    }

    public static String findRelevant(Path root, String request, String endpoint, String embeddingModel) throws IOException {
        return AiKnowledgeIndex.search(root, request, endpoint, embeddingModel);
    }

    /** Searches every configured local folder and, only when enabled, retrieves configured public URLs. */
    public static String findRelevantSources(String configuredSources, String request, boolean includeRemote) throws IOException {
        return findRelevantSources(configuredSources, request, includeRemote, null, null);
    }

    /** Searches local sources with optional local Ollama semantic reranking. */
    public static String findRelevantSources(String configuredSources, String request, boolean includeRemote,
                                             String embeddingEndpoint, String embeddingModel) throws IOException {
        StringBuilder result = new StringBuilder();
        int remoteSources = 0;
        for (String raw : configuredSources.lines().map(String::trim).filter(source -> !source.isBlank()).limit(12).toList()) {
            if (isGitHubRepository(raw)) {
                if (includeRemote && remoteSources++ < MAX_REMOTE_SOURCES) {
                    Path repository = ensureGitRepository(raw);
                    if (repository != null) appendGitRepositorySource(result, repository, raw, request, embeddingEndpoint, embeddingModel);
                }
                continue;
            }
            if (raw.startsWith("https://")) {
                if (includeRemote && remoteSources++ < MAX_REMOTE_SOURCES) appendRemoteSource(result, raw, request);
            } else {
                Path path = Path.of(raw);
                if (Files.isDirectory(path)) appendLocalSource(result, path, request, embeddingEndpoint, embeddingModel);
            }
        }
        return result.isEmpty() ? "No relevant configured knowledge sources were available." : result.toString();
    }

    /** Refreshes configured public source cache entries. A normal refresh preserves entries younger than 24 hours. */
    public static String refreshRemoteSources(String configuredSources, boolean force) {
        int updated = 0;
        int current = 0;
        int unavailable = 0;
        int remoteSources = 0;
        for (String raw : configuredSources.lines().map(String::trim).filter(source -> !source.isBlank()).toList()) {
            if (isGitHubRepository(raw)) {
                if (remoteSources++ >= MAX_REMOTE_SOURCES) continue;
                if (updateGitRepository(raw) != null) updated++;
                else unavailable++;
                continue;
            }
            if (!raw.startsWith("https://") || remoteSources++ >= MAX_REMOTE_SOURCES) continue;
            try {
                Path cachedFile = remoteCacheFile(raw);
                if (!force && readFreshCache(cachedFile) != null) {
                    current++;
                } else if (downloadRemoteSource(raw, cachedFile) != null) {
                    updated++;
                } else {
                    unavailable++;
                }
            } catch (RuntimeException e) {
                unavailable++;
            }
        }
        return "Remote knowledge refresh: " + updated + " updated, " + current + " already current"
                + (unavailable == 0 ? "." : ", " + unavailable + " unavailable.");
    }

    private static void appendLocalSource(StringBuilder result, Path path, String request, String endpoint, String embeddingModel) {
        try {
            String snippets = findRelevant(path, request, endpoint, embeddingModel);
            if (!snippets.startsWith("No relevant")) result.append(snippets).append('\n');
        } catch (IOException | RuntimeException ignored) {
            // A missing or unreadable source must not prevent the local assistant from answering.
        }
    }

    private static void appendGitRepositorySource(StringBuilder result, Path repository, String source, String request,
                                                  String endpoint, String embeddingModel) {
        try {
            String snippets = findRelevant(repository, request, endpoint, embeddingModel);
            if (!snippets.startsWith("No relevant")) {
                result.append("GitHub repository: ").append(source)
                        .append("\nRevision: ").append(gitRevision(repository)).append('\n')
                        .append(snippets).append('\n');
            }
        } catch (IOException | RuntimeException ignored) {
            // An inaccessible optional repository must not block an assistant request.
        }
    }

    /** No request content is sent: the configured URL alone is fetched as public reference material. */
    private static void appendRemoteSource(StringBuilder result, String source, String userRequest) {
        try {
            validatePublicHttpsUri(source);
            Path cachedFile = remoteCacheFile(source);
            String body = readFreshCache(cachedFile);
            boolean cached = body != null;
            if (body == null) {
                body = downloadRemoteSource(source, cachedFile);
                if (body == null) return;
            }
            String plain = body.replaceAll("(?is)<script.*?</script>|<style.*?</style>|<[^>]+>", " ")
                    .replaceAll("\\s+", " ").trim();
            if (!plain.isBlank()) result.append("Public reference").append(cached ? " (cached)" : "").append(": ").append(source).append('\n')
                    .append(relevantExcerpt(plain, queryTerms(userRequest))).append("\n\n");
        } catch (Exception ignored) {
            // Remote material is optional and a failed source should not fail the assistant request.
        }
    }

    private static String downloadRemoteSource(String source, Path cachedFile) {
        try {
            URI uri = validatePublicHttpsUri(source);
            HttpRequest request = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "CimPal-AI-Knowledge/1.0").build();
            HttpResponse<InputStream> response = REMOTE_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) return null;
            if (response.headers().firstValueAsLong("Content-Length").orElse(0) > MAX_REMOTE_BYTES) return null;
            byte[] bytes;
            try (InputStream stream = response.body()) {
                bytes = stream.readNBytes(MAX_REMOTE_BYTES + 1);
            }
            if (bytes.length > MAX_REMOTE_BYTES) return null;
            String body = new String(bytes, StandardCharsets.UTF_8);
            Files.createDirectories(cachedFile.getParent());
            Files.writeString(cachedFile, body, StandardCharsets.UTF_8);
            return body;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readFreshCache(Path cacheFile) {
        try {
            if (Files.isRegularFile(cacheFile)
                    && Files.getLastModifiedTime(cacheFile).toInstant().plus(REMOTE_CACHE_TTL).isAfter(Instant.now())) {
                return Files.readString(cacheFile, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // A stale or unreadable cache simply falls through to an optional fetch.
        }
        return null;
    }

    private static Path remoteCacheFile(String source) {
        return Path.of(System.getProperty("user.home"), ".cimpal", "ai-knowledge-cache", sha256(source) + ".txt");
    }

    /** GitHub repository lines may include a branch after '#', for example https://github.com/owner/repo#main. */
    private static boolean isGitHubRepository(String source) {
        try {
            validatedGitHubRepositoryUri(source);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Keeps a shallow local checkout; command arguments are fixed and source values are never passed through a shell. */
    private static Path updateGitRepository(String source) {
        try {
            URI uri = validatedGitHubRepositoryUri(source);
            String branch = uri.getFragment();
            String repositoryUrl = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), uri.getQuery(), null).toString();
            Path destination = Path.of(System.getProperty("user.home"), ".cimpal", "ai-knowledge-repositories", sha256(repositoryUrl));
            Files.createDirectories(destination.getParent());
            if (!Files.isDirectory(destination.resolve(".git"))) {
                List<String> clone = new java.util.ArrayList<>(List.of("git", "clone", "--depth", "1"));
                if (branch != null && !branch.isBlank()) clone.addAll(List.of("--branch", branch));
                clone.addAll(List.of(repositoryUrl, destination.toString()));
                return runGit(clone) ? destination : null;
            }
            List<String> pull = branch == null || branch.isBlank()
                    ? List.of("git", "-C", destination.toString(), "pull", "--ff-only")
                    : List.of("git", "-C", destination.toString(), "pull", "--ff-only", "origin", branch);
            return runGit(pull) ? destination : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Opens an existing checkout without network traffic, cloning only if the source has never been cached. */
    private static Path ensureGitRepository(String source) {
        try {
            URI uri = validatedGitHubRepositoryUri(source);
            String branch = uri.getFragment();
            String repositoryUrl = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), uri.getQuery(), null).toString();
            Path destination = Path.of(System.getProperty("user.home"), ".cimpal", "ai-knowledge-repositories", sha256(repositoryUrl));
            if (Files.isDirectory(destination.resolve(".git"))) return destination;
            Files.createDirectories(destination.getParent());
            List<String> clone = new java.util.ArrayList<>(List.of("git", "clone", "--depth", "1"));
            if (branch != null && !branch.isBlank()) clone.addAll(List.of("--branch", branch));
            clone.addAll(List.of(repositoryUrl, destination.toString()));
            return runGit(clone) ? destination : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean runGit(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String gitRevision(Path repository) {
        try {
            Process process = new ProcessBuilder("git", "-C", repository.toString(), "rev-parse", "HEAD")
                    .redirectErrorStream(true).start();
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) return "unknown";
            String revision = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return revision.isBlank() ? "unknown" : revision;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }

    private static String sha256(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Rejects URLs that could turn the opt-in public-reference feature into a local-network client. */
    static URI validatePublicHttpsUri(String source) {
        URI uri = URI.create(source);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()
                || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new IllegalArgumentException("Knowledge sources must be credential-free HTTPS URLs on port 443.");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (!isPublicAddress(address)) throw new IllegalArgumentException("Knowledge sources must not resolve to a private or local address.");
            }
        } catch (java.net.UnknownHostException exception) {
            throw new IllegalArgumentException("Knowledge source host could not be resolved.", exception);
        }
        return uri;
    }

    private static boolean isPublicAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        boolean ipv6UniqueLocal = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        return !address.isAnyLocalAddress() && !address.isLoopbackAddress() && !address.isLinkLocalAddress()
                && !address.isSiteLocalAddress() && !address.isMulticastAddress() && !ipv6UniqueLocal;
    }

    private static URI validatedGitHubRepositoryUri(String source) {
        URI uri = validatePublicHttpsUri(source);
        if (!"github.com".equalsIgnoreCase(uri.getHost()) || uri.getQuery() != null) {
            throw new IllegalArgumentException("Only canonical public GitHub repository URLs are supported.");
        }
        String[] segments = uri.getPath().replaceFirst("^/", "").replaceFirst("\\.git$", "").split("/");
        if (segments.length != 2 || !GITHUB_SEGMENT.matcher(segments[0]).matches() || !GITHUB_SEGMENT.matcher(segments[1]).matches()
                || (uri.getFragment() != null && !GITHUB_SEGMENT.matcher(uri.getFragment()).matches())) {
            throw new IllegalArgumentException("GitHub sources must use owner/repository and an optional simple branch name.");
        }
        return uri;
    }

    private static Match score(Path path, Set<String> terms) {
        try {
            if (Files.size(path) > 1_000_000) return new Match(path, 0, "");
            String text = Files.readString(path, StandardCharsets.UTF_8);
            String lower = text.toLowerCase(Locale.ROOT);
            int score = 0;
            for (String term : terms) if (lower.contains(term)) score++;
            return new Match(path, score, relevantExcerpt(text, terms));
        } catch (IOException | RuntimeException ignored) {
            return new Match(path, 0, "");
        }
    }

    private static boolean isUsefulTextFile(Path path) {
        String normal = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normal.contains("/.git/") || normal.contains("/target/") || normal.contains("/build/") || normal.contains("/node_modules/")) return false;
        int dot = normal.lastIndexOf('.');
        return dot >= 0 && EXTENSIONS.contains(normal.substring(dot));
    }

    private static Set<String> queryTerms(String request) {
        return Pattern.compile("[^a-zA-Z0-9:_-]+")
                .splitAsStream(request.toLowerCase(Locale.ROOT))
                .filter(term -> term.length() >= 3)
                .collect(java.util.stream.Collectors.toSet());
    }

    /** Returns the highest-scoring bounded window, rather than blindly returning the document opening. */
    private static String relevantExcerpt(String text, Set<String> terms) {
        if (text.length() <= MAX_CHARS_PER_MATCH || terms.isEmpty()) return text.substring(0, Math.min(text.length(), MAX_CHARS_PER_MATCH));
        int bestStart = 0;
        int bestScore = -1;
        int step = MAX_CHARS_PER_MATCH - 500;
        String lower = text.toLowerCase(Locale.ROOT);
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(text.length(), start + MAX_CHARS_PER_MATCH);
            String window = lower.substring(start, end);
            int score = 0;
            for (String term : terms) {
                int index = -1;
                while ((index = window.indexOf(term, index + 1)) >= 0) score++;
            }
            if (score > bestScore) {
                bestScore = score;
                bestStart = start;
            }
            if (end == text.length()) break;
        }
        int bestEnd = Math.min(text.length(), bestStart + MAX_CHARS_PER_MATCH);
        return (bestStart == 0 ? "" : "[… ]\n") + text.substring(bestStart, bestEnd) + (bestEnd == text.length() ? "" : "\n[…]");
    }

    private record Match(Path path, int score, String text) { }
}
