package io.floci.sidecar.graphql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.sidecar.core.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Calls a resolver callback with one batch of {@link InvocationKey}s and reports back one
 * {@link FieldOutcome} per key, in the same order. A non-200, malformed or unreachable response
 * fails every key in that one call with a {@link FieldOutcome} error rather than throwing, so a
 * batch loader that chunks a level's work across several calls can isolate one chunk's failure
 * from the rest.
 */
final class ResolveCallbackClient {

    // Built lazily, on first actual use at runtime, rather than as a static field: an HttpClient
    // eagerly assigned to a static field gets snapshotted into the native image heap at build
    // time, and GraalVM rejects that because its JDK-internal state is only safe to create at
    // image run time.
    private static volatile HttpClient httpClient;

    private ResolveCallbackClient() {
    }

    private static HttpClient httpClient() {
        HttpClient client = httpClient;
        if (client == null) {
            synchronized (ResolveCallbackClient.class) {
                client = httpClient;
                if (client == null) {
                    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                    httpClient = client;
                }
            }
        }
        return client;
    }

    static CompletableFuture<List<FieldOutcome>> resolve(ResolveConfig config, List<InvocationKey> chunk) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(config.url()))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + config.token())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody(chunk)))
                    .build();
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(failAll(chunk, "Invalid resolve.url: " + config.url()));
        }
        return httpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> parseResponse(response, chunk))
                .exceptionally(throwable -> failAll(chunk, safeMessage(throwable)));
    }

    private static String requestBody(List<InvocationKey> chunk) {
        ArrayNode invocations = Json.mapper().createArrayNode();
        for (int i = 0; i < chunk.size(); i++) {
            InvocationKey key = chunk.get(i);
            ObjectNode node = invocations.addObject();
            node.put("id", String.valueOf(i));
            node.put("typeName", key.typeName());
            node.put("fieldName", key.fieldName());
            node.set("arguments", Json.mapper().valueToTree(key.arguments()));
            node.set("source", Json.mapper().valueToTree(key.source()));
            ArrayNode path = node.putArray("path");
            key.path().forEach(segment -> path.add(Json.mapper().valueToTree(segment)));
            node.set("variables", Json.mapper().valueToTree(key.variables()));
            ArrayNode selectionSetList = node.putArray("selectionSetList");
            key.selectionSetList().forEach(selectionSetList::add);
        }
        ObjectNode root = Json.object();
        root.set("invocations", invocations);
        return root.toString();
    }

    private static List<FieldOutcome> parseResponse(HttpResponse<String> response, List<InvocationKey> chunk) {
        if (response.statusCode() != 200) {
            return failAll(chunk, "Resolver callback returned HTTP " + response.statusCode());
        }
        JsonNode body;
        try {
            body = Json.mapper().readTree(response.body());
        } catch (Exception e) {
            return failAll(chunk, "Resolver callback returned malformed JSON");
        }
        JsonNode results = body == null ? null : body.get("results");
        if (results == null || !results.isArray()) {
            return failAll(chunk, "Resolver callback response is missing results");
        }
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        results.forEach(node -> byId.put(node.path("id").asText(), node));

        List<FieldOutcome> outcomes = new ArrayList<>(chunk.size());
        for (int i = 0; i < chunk.size(); i++) {
            outcomes.add(outcomeFrom(byId.get(String.valueOf(i))));
        }
        return outcomes;
    }

    private static FieldOutcome outcomeFrom(JsonNode node) {
        if (node == null) {
            return FieldOutcome.failure(new FieldOutcome.FieldError(
                    "Resolver callback did not return a result for this field", "MissingResult", null, null));
        }
        if (node.has("error")) {
            JsonNode error = node.get("error");
            String message = error.path("message").asText("Resolver callback reported an error");
            String type = error.hasNonNull("type") ? error.get("type").asText() : null;
            Object data = toPlainValue(error.get("data"));
            Object info = toPlainValue(error.get("info"));
            return FieldOutcome.failure(new FieldOutcome.FieldError(message, type, data, info));
        }
        return FieldOutcome.success(toPlainValue(node.get("data")));
    }

    private static Object toPlainValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return Json.mapper().convertValue(node, Object.class);
    }

    private static List<FieldOutcome> failAll(List<InvocationKey> chunk, String message) {
        FieldOutcome.FieldError error = new FieldOutcome.FieldError(message, "ResolverCallbackFailure", null, null);
        return chunk.stream().map(key -> FieldOutcome.failure(error)).toList();
    }

    private static String safeMessage(Throwable throwable) {
        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
        String message = cause.getMessage();
        return "Resolver callback failed: " + (message == null || message.isBlank() ? cause.getClass().getSimpleName() : message);
    }
}
