/*
 * Licensed under the EUPL-1.2-or-later.
 * Copyright (c) 2026, gridDigIt Kft. All rights reserved.
 */
package eu.griddigit.cimpal.main.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Minimal local-only client for an Ollama server. */
public final class OllamaClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public List<String> listModels(String endpoint) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(apiUri(endpoint, "/api/tags"))
                .timeout(TIMEOUT).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        requireSuccess(response);
        JsonNode models = JSON.readTree(response.body()).path("models");
        List<String> names = new ArrayList<>();
        for (JsonNode model : models) {
            String name = model.path("name").asText();
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    public String chat(String endpoint, String model, String systemPrompt, String userPrompt)
            throws IOException, InterruptedException {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("model", model);
        payload.put("stream", false);
        // Qwen3 otherwise spends most of an interactive request in its optional reasoning mode.
        // CimPal needs short, reviewable drafts; deterministic Jena checks provide the guardrail.
        payload.put("think", false);
        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);
        String body = payload.toString();
        HttpRequest request = HttpRequest.newBuilder(apiUri(endpoint, "/api/chat"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        requireSuccess(response);
        String content = JSON.readTree(response.body()).path("message").path("content").asText();
        if (content.isBlank()) {
            throw new IOException("Ollama returned an empty response.");
        }
        return content;
    }

    /** Streams visible response text as Ollama emits it, while returning the complete response. */
    public String chatStreaming(String endpoint, String model, String systemPrompt, String userPrompt,
                                int maxTokens, Consumer<String> onText) throws IOException, InterruptedException {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("model", model);
        payload.put("stream", true);
        payload.put("think", false);
        // Keep the selected local model warm between requests; cold model loading is needless UI latency.
        payload.put("keep_alive", "30m");
        // Avoid a long essay when the user asked for a small query or shape.
        ObjectNode options = payload.putObject("options");
        options.put("num_predict", maxTokens).put("num_ctx", 2048).put("temperature", 0.1);
        // This workstation has 16 logical processors. Let the local inference runtime use them
        // and use a larger prompt batch; the latter trades additional RAM for faster prompt evaluation.
        options.put("num_thread", Runtime.getRuntime().availableProcessors());
        options.put("num_batch", 512);
        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);
        HttpRequest request = HttpRequest.newBuilder(apiUri(endpoint, "/api/chat"))
                .timeout(TIMEOUT).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
        HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        requireSuccess(response);
        StringBuilder complete = new StringBuilder();
        try (Stream<String> lines = response.body()) {
            lines.filter(line -> !line.isBlank()).forEach(line -> {
                String text = JSON.readTree(line).path("message").path("content").asText();
                if (!text.isEmpty()) {
                    complete.append(text);
                    onText.accept(text);
                }
            });
        }
        if (complete.isEmpty()) throw new IOException("Ollama returned an empty response.");
        return complete.toString();
    }

    private static URI apiUri(String endpoint, String path) {
        String normalized = endpoint == null ? "" : endpoint.trim().replaceAll("/+$", "");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Enter the Ollama server address first.");
        }
        URI endpointUri = URI.create(normalized);
        String host = endpointUri.getHost();
        if (host == null || !(host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1"))) {
            throw new IllegalArgumentException("The initial AI release accepts local Ollama addresses only.");
        }
        return URI.create(normalized + path);
    }

    private static void requireSuccess(HttpResponse<?> response) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Ollama returned HTTP " + response.statusCode() + ". " + response.body());
        }
    }
}
