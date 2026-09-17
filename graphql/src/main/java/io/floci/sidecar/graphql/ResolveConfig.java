package io.floci.sidecar.graphql;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.sidecar.core.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * The optional {@code resolve} block on {@code /v1/execute}: a batched callback into the caller
 * for the listed {@code (typeName, fieldName)} coordinates. Without this, every field keeps
 * graphql-java's default property fetcher over a null root value, exactly as before this existed.
 */
record ResolveConfig(String url, String token, List<Field> fields, int maxBatch) {

    /** One coordinate the callback should be asked about instead of using the default fetcher. */
    record Field(String typeName, String fieldName) {
        String coordinate() {
            return typeName + "." + fieldName;
        }
    }

    static ResolveConfig from(JsonNode body) {
        JsonNode node = body.get("resolve");
        if (node == null || node.isNull()) {
            return null;
        }
        String url = Json.requiredText(node, "url");
        String token = Json.requiredText(node, "token");
        List<Field> fields = new ArrayList<>();
        JsonNode fieldsNode = node.get("fields");
        if (fieldsNode != null && fieldsNode.isArray()) {
            for (JsonNode field : fieldsNode) {
                fields.add(new Field(Json.requiredText(field, "typeName"), Json.requiredText(field, "fieldName")));
            }
        }
        int maxBatch = node.path("maxBatch").asInt(100);
        if (maxBatch < 1) {
            throw new IllegalArgumentException("resolve.maxBatch must be at least 1.");
        }
        return new ResolveConfig(url, token, fields, maxBatch);
    }
}
