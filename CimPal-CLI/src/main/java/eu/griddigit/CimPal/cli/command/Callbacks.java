/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.CimPal.cli.command;

import eu.griddigit.cimpal.core.utils.EgressPolicy;
import eu.griddigit.cimpal.core.utils.LogSanitizer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Completion callbacks of {@code /v1} jobs (DEP-14, D-19): opt-in, to a URL under the client's
 * {@code callbackAllow} prefixes, signed with its secret, delivered at least once.
 *
 * <p><b>Egress (SSRF):</b> a callback is a request {@code serve} makes on a client's behalf, so:
 * <ul>
 *   <li>the URL must be https without credentials, under one of the client's allowed prefixes,
 *       checked when the job is submitted and again before every attempt;</li>
 *   <li>its host must resolve to public addresses only ({@link EgressPolicy}), checked at submit
 *       and before every attempt; a refusal fails the callback for good;</li>
 *   <li>redirects are never followed; only the status counts;</li>
 *   <li>the body carries the job's status and links, never its result, log or any token.</li>
 * </ul>
 * With {@code allowLoopback} (tests only; no command-line option) http to a loopback host is
 * allowed and the address check is skipped.
 *
 * <p><b>Delivery:</b> a worker thread arms the callbacks of jobs that became final (however they
 * ended), and sends the due ones: {@code POST} with {@code Content-Type: application/json} and
 * {@code CimPal-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}. A 2xx
 * answer delivers it; anything else is retried after 10 s, doubling up to 1 h, for 24 h after the
 * job ended. Every attempt is recorded in the {@link CallbackStore}.
 */
final class Callbacks implements AutoCloseable {

    static final Duration FIRST_RETRY = Duration.ofSeconds(10);
    static final Duration MAX_RETRY = Duration.ofHours(1);
    static final Duration GIVE_UP_AFTER = Duration.ofHours(24);
    static final int MAX_URL_CHARS = 2048;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JobStore jobs;
    private final CallbackSecrets secrets;
    private final ClientQuotas quotas;
    private final ServeLog log;
    private final ServeMetrics metrics;
    private final boolean allowLoopback;
    private final Duration tick;
    /** The whole attempt, answer body included: a receiver that holds the connection is cut off. */
    private final Duration attemptTimeout;
    private final HttpClient http;
    private ScheduledExecutorService worker;

    Callbacks(JobStore jobs, CallbackSecrets secrets, ClientQuotas quotas, ServeLog log, ServeMetrics metrics,
              boolean allowLoopback, Duration tick, Duration attemptTimeout) {
        this.attemptTimeout = attemptTimeout;
        this.jobs = jobs;
        this.secrets = secrets;
        this.quotas = quotas;
        this.log = log;
        this.metrics = metrics;
        this.allowLoopback = allowLoopback;
        this.tick = tick;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    // ---- at submit ---------------------------------------------------------------------------------

    /**
     * The callback URL of a submitted job, after every check that doesn't need the job.
     *
     * @throws ServeServer.Rejected 400 with the reason
     */
    URI accept(String client, JsonNode callback) throws ServeServer.Rejected {
        if (!callback.isObject()) {
            throw new ServeServer.Rejected(400, "'callback' must be an object with a 'url'");
        }
        for (String key : callback.propertyNames()) {
            if (!key.equals("url")) {
                throw new ServeServer.Rejected(400, "Unknown field 'callback." + LogSanitizer.forLog(key)
                        + "'; allowed: url");
            }
        }
        JsonNode urlNode = callback.get("url");
        String url = urlNode != null && urlNode.isString() ? urlNode.asString() : null;
        if (url == null || url.isEmpty() || url.length() > MAX_URL_CHARS) {
            throw new ServeServer.Rejected(400, "'callback.url' must be a URL of at most " + MAX_URL_CHARS
                    + " characters");
        }
        if (secrets.secret(client) == null) {
            throw new ServeServer.Rejected(400, "Callbacks are not enabled for this client (no callback secret)");
        }
        String refused = refusal(client, url);
        if (refused != null) {
            throw new ServeServer.Rejected(400, "'callback.url' refused: " + refused);
        }
        return URI.create(url);
    }

    /** Why {@code url} may not be called for {@code client} now, or null. Never follows the URL. */
    private String refusal(String client, String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return "not a URL";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        boolean loopbackHost = uri.getHost() != null && ServeSecurity.isLoopback(uri.getHost());
        if (!scheme.equals("https") && !(allowLoopback && scheme.equals("http") && loopbackHost)) {
            return "only https";
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawFragment() != null) {
            return "no host, or a user or fragment in it";
        }
        String rawPath = uri.getRawPath() == null ? "" : uri.getRawPath().toLowerCase(java.util.Locale.ROOT);
        if (!uri.normalize().equals(uri) || rawPath.contains("%2e") || rawPath.contains("%2f")
                || rawPath.contains("%5c") || url.indexOf('\\') >= 0) {
            return "dot segments or encoded separators in its path"; // could step out of an allowed prefix
        }
        boolean allowed = false;
        for (String prefix : quotas.limits(client).callbackAllow()) {
            if (url.startsWith(prefix)) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            return "not under one of this client's allowed prefixes (callbackAllow)";
        }
        if (!allowLoopback) {
            try {
                EgressPolicy.requirePublicHost(uri.getHost());
            } catch (IOException e) {
                return "its host doesn't resolve to public addresses only";
            }
        }
        return null;
    }

    // ---- delivery ------------------------------------------------------------------------------

    void start() {
        AtomicInteger n = new AtomicInteger();
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cimpal-serve-callbacks-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        worker.scheduleWithFixedDelay(this::tickSafely, tick.toMillis(), tick.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void tickSafely() {
        try {
            tick();
        } catch (RuntimeException e) {
            log.warn("callback.error", "serve: callback delivery paused: " + e.getClass().getSimpleName());
        }
    }

    /** One round: arm the callbacks of jobs that ended, send those due. */
    void tick() {
        CallbackStore store = jobs.callbacks();
        Instant now = jobs.now();
        // A callback is registered just before its job is created: no job yet means not final yet.
        store.arm(id -> {
            Job.Snapshot s = jobs.get(id);
            return s != null && s.finished();
        }, now);
        for (CallbackStore.Delivery d : store.due(now, 16)) {
            deliver(d);
        }
    }

    private void deliver(CallbackStore.Delivery d) {
        CallbackStore store = jobs.callbacks();
        Instant now = jobs.now();
        Job.Snapshot job = jobs.get(d.jobId());
        byte[] secret = secrets.secret(d.client());
        String refused = job == null ? "the job no longer exists"
                : secret == null ? "this client has no callback secret any more"
                : refusal(d.client(), d.url().toString());
        if (refused != null) {
            store.attempted(d.jobId(), now, null, "refused: " + refused, false, null);
            metrics.callback("refused");
            log.warn("callback.refused", "serve: callback of job " + d.jobId() + " refused: " + refused,
                    "jobId", d.jobId().toString(), "client", d.client());
            return;
        }
        byte[] body = body(job);
        long t = now.getEpochSecond();
        Integer status = null;
        String error = null;
        // The client sees lastError: only what it may know, never which exception (that would
        // tell it about the network behind the address it named). The server log has the class.
        java.util.concurrent.CompletableFuture<HttpResponse<Void>> sent = null;
        try {
            HttpRequest request = HttpRequest.newBuilder(d.url()).timeout(attemptTimeout)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "CimPal-serve/" + ServeServer.API_VERSION)
                    .header("CimPal-Signature", signature(secret, t, body))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            sent = http.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            status = sent.get(attemptTimeout.toMillis(), TimeUnit.MILLISECONDS).statusCode();
        } catch (java.util.concurrent.TimeoutException e) {
            sent.cancel(true); // a receiver that holds the connection doesn't hold the worker
            error = "timed out";
        } catch (java.util.concurrent.ExecutionException e) {
            error = "connection failed";
            log.info("callback.error", "serve: callback of job " + d.jobId() + ": "
                    + (e.getCause() == null ? "?" : e.getCause().getClass().getSimpleName()),
                    "jobId", d.jobId().toString());
        } catch (InterruptedException e) {
            if (sent != null) {
                sent.cancel(true);
            }
            Thread.currentThread().interrupt();
            return;
        } catch (IllegalArgumentException e) {
            error = "invalid request";
        }
        boolean delivered = status != null && status >= 200 && status < 300;
        Instant next = null;
        if (!delivered) {
            if (status != null) {
                error = "HTTP " + status; // a redirect counts as a failure: never followed
            }
            long factor = 1L << Math.min(20, d.attempts());
            Duration wait = FIRST_RETRY.multipliedBy(factor);
            Instant at = now.plus(wait.compareTo(MAX_RETRY) > 0 ? MAX_RETRY : wait);
            Instant ended = job.finishedAt() == null ? d.createdAt() : job.finishedAt();
            next = at.isAfter(ended.plus(GIVE_UP_AFTER)) ? null : at;
        }
        store.attempted(d.jobId(), now, status, error, delivered, next);
        String outcome = delivered ? "delivered" : next == null ? "failed" : "retry";
        metrics.callback(outcome);
        log.info("callback." + outcome, "serve: callback of job " + d.jobId() + " " + outcome
                + (status == null ? "" : " (HTTP " + status + ")"), "jobId", d.jobId().toString(),
                "client", d.client(), "status", status);
    }

    /** What a callback says: the job's outcome and where to fetch the rest. */
    static byte[] body(Job.Snapshot s) {
        ObjectNode node = JSON.createObjectNode();
        node.put("event", "job.finished");
        node.put("jobId", s.id().toString());
        node.put("command", s.command());
        node.put("status", s.status().apiName());
        if (s.exitCode() == null) {
            node.putNull("exitCode");
        } else {
            node.put("exitCode", s.exitCode().intValue());
        }
        if (s.hasViolations() == null) {
            node.putNull("hasViolations");
        } else {
            node.put("hasViolations", s.hasViolations().booleanValue());
        }
        node.put("finishedAt", s.finishedAt() == null ? null : s.finishedAt().toString());
        ObjectNode links = node.putObject("links");
        String self = "/v1/jobs/" + s.id();
        links.put("self", self);
        links.put("result", self + "/result");
        links.put("log", self + "/log");
        return JSON.writeValueAsBytes(node);
    }

    /** {@code t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}. */
    static String signature(byte[] secret, long t, byte[] body) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"));
            mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
            return "t=" + t + ",v1=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The job's callback as the API shows it, or null. */
    static ObjectNode view(CallbackStore.Delivery d) {
        if (d == null) {
            return null;
        }
        ObjectNode node = JSON.createObjectNode();
        node.put("url", d.url().toString());
        node.put("state", d.state().apiName());
        node.put("attempts", d.attempts());
        if (d.lastStatus() == null) {
            node.putNull("lastStatus");
        } else {
            node.put("lastStatus", d.lastStatus().intValue());
        }
        node.put("lastAttemptAt", d.lastAttemptAt() == null ? null : d.lastAttemptAt().toString());
        node.put("lastError", d.lastError());
        return node;
    }

    @Override
    public void close() {
        if (worker != null) {
            worker.shutdownNow();
        }
        http.shutdownNow();
    }
}
