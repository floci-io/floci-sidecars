package io.floci.sidecar.core;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A {@code POST /v1/...} endpoint: takes the parsed request body, returns the response body.
 *
 * <p>Throw {@link IllegalArgumentException}, or any type registered with
 * {@link SidecarServer.Builder#badRequestOn}, to answer {@code 400}; any other exception answers
 * {@code 500}. Both carry {@code {"error": message}}.
 */
@FunctionalInterface
public interface JsonHandler {

    JsonNode handle(JsonNode body) throws Exception;
}
