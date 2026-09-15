package io.floci.sidecar.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The one {@link ObjectMapper} a sidecar uses, plus the field accessors every handler needs. */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static ObjectNode error(String message) {
        return MAPPER.createObjectNode().put("error", message);
    }

    /**
     * Parses a request body. A blank body is a bad request; malformed JSON throws
     * {@link JsonProcessingException}, which the error envelope also reports as a bad request.
     */
    public static JsonNode body(String raw) throws JsonProcessingException {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("A JSON request body is required.");
        }
        JsonNode node = MAPPER.readTree(raw);
        if (node == null || node.isMissingNode()) {
            throw new IllegalArgumentException("A JSON request body is required.");
        }
        return node;
    }

    /** A non-empty string field, or {@link IllegalArgumentException} naming the field. */
    public static String requiredText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.asText().isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return value.asText();
    }

    /** An object field, or {@link IllegalArgumentException} naming the field. */
    public static JsonNode requiredObject(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return value;
    }
}
