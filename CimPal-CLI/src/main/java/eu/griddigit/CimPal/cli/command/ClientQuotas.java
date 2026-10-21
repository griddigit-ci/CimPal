/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.cimpal.core.utils.LogSanitizer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-client limits of {@code serve} (DEP-13, {@code --client-quotas}), so one client can't fill
 * the queue or the work area for everyone:
 * <ul>
 *   <li>{@code maxQueued}: jobs a client may have waiting; a submit is refused with 429 once its
 *       queued and running jobs reach {@code maxQueued + maxRunning};</li>
 *   <li>{@code maxRunning}: its jobs running at once; more wait, and other clients' jobs may
 *       overtake them ({@link JobScheduler});</li>
 *   <li>{@code maxUploadBytes}: the largest upload (413);</li>
 *   <li>{@code maxWorkBytes}: what its uploads and job outputs may take in the work area (507);</li>
 *   <li>{@code requestsPerMinute}: authenticated requests per minute, 0 for no limit (429);</li>
 *   <li>{@code callbackAllow} (DEP-14): the URL prefixes its jobs' completion callbacks may go to,
 *       none by default (then a job with a callback is refused, 400). Each prefix is an https URL
 *       whose path ends with {@code /}; http only to a loopback host, for local testing.</li>
 * </ul>
 * The server's flags ({@code --queue-size}, {@code --workers}, {@code --max-upload-bytes},
 * {@code --work-max-bytes}) stay the limits of the whole server, and are each client's defaults
 * ({@code maxQueued} has none: the server's queue bounds it).
 *
 * <p>The file is JSON, read once at start; keys starting with {@code _} are comments:
 * <pre>
 * {"default": {"maxQueued": 2, "requestsPerMinute": 600},
 *  "clients": {"etl-airflow": {"maxQueued": 8, "maxRunning": 2}}}
 * </pre>
 * A client's own entry wins over {@code default}, which wins over the server's flags.
 */
final class ClientQuotas {

    static final long MAX_FILE_BYTES = 1024 * 1024;
    /** {@code maxQueued} when nothing sets one: only the server's {@code --queue-size} bounds a client. */
    static final int NO_QUEUE_LIMIT = 1_000_000;

    /** One client's limits. */
    record Limits(int maxQueued, int maxRunning, long maxUploadBytes, long maxWorkBytes, int requestsPerMinute,
                  List<String> callbackAllow) {

        Limits {
            callbackAllow = List.copyOf(callbackAllow);
        }

        /** Without callbacks. */
        Limits(int maxQueued, int maxRunning, long maxUploadBytes, long maxWorkBytes, int requestsPerMinute) {
            this(maxQueued, maxRunning, maxUploadBytes, maxWorkBytes, requestsPerMinute, List.of());
        }
    }

    /** The parsed file: overrides only (null fields fall back). */
    record Settings(Values defaults, Map<String, Values> clients) {

        /** No file: every client gets the server's flags. */
        static final Settings NONE = new Settings(Values.NONE, Map.of());

        Settings {
            defaults = Objects.requireNonNull(defaults);
            clients = Map.copyOf(clients);
        }
    }

    /** The limits one entry sets; null where it sets none. */
    record Values(Integer maxQueued, Integer maxRunning, Long maxUploadBytes, Long maxWorkBytes,
                    Integer requestsPerMinute, List<String> callbackAllow) {

        static final Values NONE = new Values(null, null, null, null, null, null);

        Limits over(Limits base) {
            return new Limits(maxQueued != null ? maxQueued : base.maxQueued(),
                    maxRunning != null ? maxRunning : base.maxRunning(),
                    maxUploadBytes != null ? maxUploadBytes : base.maxUploadBytes(),
                    maxWorkBytes != null ? maxWorkBytes : base.maxWorkBytes(),
                    requestsPerMinute != null ? requestsPerMinute : base.requestsPerMinute(),
                    callbackAllow != null ? callbackAllow : base.callbackAllow());
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> KEYS =
            Set.of("maxQueued", "maxRunning", "maxUploadBytes", "maxWorkBytes", "requestsPerMinute", "callbackAllow");

    private final Limits defaults;
    private final Map<String, Limits> perClient = new HashMap<>();
    private final InstantSource clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * The limits of every client: {@code settings} over the server's flags ({@code server}). A
     * client's value above the server's limit is lowered to it, except {@code maxQueued} (the
     * server's queue still bounds it).
     */
    ClientQuotas(Settings settings, Limits server, InstantSource clock) {
        this.clock = Objects.requireNonNull(clock);
        this.defaults = cap(settings.defaults().over(server), server);
        settings.clients().forEach((name, values) -> perClient.put(name, cap(values.over(defaults), server)));
    }

    private static Limits cap(Limits l, Limits server) {
        return new Limits(l.maxQueued(), Math.min(l.maxRunning(), server.maxRunning()),
                Math.min(l.maxUploadBytes(), server.maxUploadBytes()), Math.min(l.maxWorkBytes(), server.maxWorkBytes()),
                l.requestsPerMinute(), l.callbackAllow());
    }

    Limits limits(String client) {
        return perClient.getOrDefault(client, defaults);
    }

    /** The clients the file names. */
    Set<String> namedClients() {
        return Set.copyOf(perClient.keySet());
    }

    /**
     * Counts one request of {@code client} against its {@code requestsPerMinute}: 0 when it may go
     * ahead, otherwise the seconds until it may (for {@code Retry-After}).
     */
    long acquireRequest(String client) {
        int perMinute = limits(client).requestsPerMinute();
        if (perMinute <= 0) {
            return 0;
        }
        return buckets.computeIfAbsent(client, c -> new Bucket(perMinute, clock.instant())).take(clock.instant());
    }

    /** A token bucket: {@code perMinute} requests at once, refilled evenly over a minute. */
    private static final class Bucket {
        private static final long MINUTE_NANOS = 60_000_000_000L;
        private final int perMinute;
        private double tokens;
        private Instant last;

        Bucket(int perMinute, Instant now) {
            this.perMinute = perMinute;
            this.tokens = perMinute;
            this.last = now;
        }

        synchronized long take(Instant now) {
            if (now.isAfter(last)) {
                long nanos = Math.min(Duration.between(last, now).toNanos(), MINUTE_NANOS);
                tokens = Math.min(perMinute, tokens + (double) nanos * perMinute / MINUTE_NANOS);
                last = now;
            }
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return Math.max(1, (long) Math.ceil((1 - tokens) * 60 / perMinute));
        }
    }

    // ---- the file --------------------------------------------------------------------------

    /**
     * Reads {@code --client-quotas}.
     *
     * @throws IllegalArgumentException when the file is missing, too large or not valid
     */
    static Settings read(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException(Files.exists(file) ? "not a regular file" : "file not found");
            }
            byte[] content;
            try (InputStream in = Files.newInputStream(file)) {
                content = in.readNBytes((int) MAX_FILE_BYTES + 1);
            }
            if (content.length > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("larger than " + MAX_FILE_BYTES + " bytes");
            }
            return parse(JSON.readTree(content));
        } catch (IOException | JacksonException e) {
            throw new IllegalArgumentException("--client-quotas " + file + ": " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("--client-quotas " + file + ": " + e.getMessage());
        }
    }

    static Settings parse(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        Values defaults = Values.NONE;
        Map<String, Values> clients = new HashMap<>();
        for (Map.Entry<String, JsonNode> e : root.properties()) {
            String key = e.getKey();
            if (key.startsWith("_")) {
                continue;
            }
            switch (key) {
                case "default" -> defaults = values("default", e.getValue());
                case "clients" -> {
                    if (!e.getValue().isObject()) {
                        throw new IllegalArgumentException("'clients' must be an object of client name to limits");
                    }
                    for (Map.Entry<String, JsonNode> c : e.getValue().properties()) {
                        if (c.getKey().startsWith("_")) {
                            continue;
                        }
                        if (!TokenSource.Client.NAME.matcher(c.getKey()).matches()) {
                            throw new IllegalArgumentException("'" + LogSanitizer.forLog(c.getKey())
                                    + "' is not a client name (1 to 64 letters, digits, '.', '_' or '-')");
                        }
                        clients.put(c.getKey(), values("clients." + c.getKey(), c.getValue()));
                    }
                }
                default -> throw new IllegalArgumentException("unknown key '" + LogSanitizer.forLog(key)
                        + "'; allowed: default, clients");
            }
        }
        return new Settings(defaults, clients);
    }

    private static Values values(String where, JsonNode node) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("'" + where + "' must be an object");
        }
        Integer maxQueued = null;
        Integer maxRunning = null;
        Long maxUploadBytes = null;
        Long maxWorkBytes = null;
        Integer perMinute = null;
        List<String> callbackAllow = null;
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            String key = e.getKey();
            if (key.startsWith("_")) {
                continue;
            }
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("unknown key '" + where + "." + LogSanitizer.forLog(key)
                        + "'; allowed: " + String.join(", ", KEYS.stream().sorted().toList()));
            }
            JsonNode v = e.getValue();
            if (key.equals("callbackAllow")) {
                callbackAllow = callbackAllow(where, v);
                continue;
            }
            if (!v.isIntegralNumber() || !v.canConvertToLong()) {
                throw new IllegalArgumentException("'" + where + "." + key + "' must be a whole number");
            }
            long n = v.longValue();
            switch (key) {
                case "maxQueued" -> maxQueued = (int) range(where, key, n, 0, ServeServer.MAX_QUEUE_SIZE);
                case "maxRunning" -> maxRunning = (int) range(where, key, n, 1, ServeServer.MAX_WORKERS);
                case "maxUploadBytes" -> maxUploadBytes = range(where, key, n, 1, Long.MAX_VALUE);
                case "maxWorkBytes" -> maxWorkBytes = range(where, key, n, 1, Long.MAX_VALUE);
                default -> perMinute = (int) range(where, key, n, 0, 1_000_000);
            }
        }
        return new Values(maxQueued, maxRunning, maxUploadBytes, maxWorkBytes, perMinute, callbackAllow);
    }

    /** {@code callbackAllow}: URL prefixes, each https (http only to loopback) with a path ending in {@code /}. */
    private static List<String> callbackAllow(String where, JsonNode node) {
        if (!node.isArray()) {
            throw new IllegalArgumentException("'" + where + ".callbackAllow' must be a list of URL prefixes");
        }
        List<String> prefixes = new java.util.ArrayList<>();
        for (JsonNode p : node) {
            String prefix = p.isString() ? p.asString() : null;
            if (prefix == null || !validCallbackPrefix(prefix)) {
                throw new IllegalArgumentException("'" + where + ".callbackAllow' entries must be https URLs whose"
                        + " path ends with '/' (no user, query or fragment): "
                        + LogSanitizer.forLog(String.valueOf(prefix)));
            }
            prefixes.add(prefix);
        }
        return List.copyOf(prefixes);
    }

    static boolean validCallbackPrefix(String prefix) {
        try {
            java.net.URI uri = new java.net.URI(prefix);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme();
            boolean loopback = uri.getHost() != null && ServeSecurity.isLoopback(uri.getHost());
            return (scheme.equals("https") || (scheme.equals("http") && loopback))
                    && uri.getHost() != null && uri.getUserInfo() == null && uri.getRawQuery() == null
                    && uri.getRawFragment() == null && uri.getRawPath() != null && uri.getRawPath().endsWith("/")
                    && prefix.equals(uri.toString()) && prefix.length() <= 2048;
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    private static long range(String where, String key, long n, long min, long max) {
        if (n < min || n > max) {
            throw new IllegalArgumentException("'" + where + "." + key + "' must be between " + min + " and " + max);
        }
        return n;
    }
}
