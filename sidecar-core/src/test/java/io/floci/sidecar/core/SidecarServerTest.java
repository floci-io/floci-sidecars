package io.floci.sidecar.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SidecarServerTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static SidecarServer server;

    @BeforeAll
    static void start() throws IOException {
        server = SidecarServer.builder("example")
                .defaultPort(9999)
                .version("1.2.3")
                .route("/v1/echo", body -> body)
                .route("/v1/reject", body -> {
                    throw new IllegalArgumentException("nope: " + Json.requiredText(body, "why"));
                })
                .route("/v1/custom", body -> {
                    throw new UnsupportedOperationException("custom means bad request here");
                })
                .badRequestOn(UnsupportedOperationException.class)
                .route("/v1/explode", body -> {
                    throw new IllegalStateException("boom");
                })
                .start(0);
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @Test
    void healthReportsTheContractFields() throws Exception {
        HttpResponse<String> response = get("/health");

        assertThat(response.statusCode(), equalTo(200));
        assertThat(response.headers().firstValue("Content-Type").orElse(""), startsWith("application/json"));
        JsonNode body = Json.mapper().readTree(response.body());
        assertThat(body.get("status").asText(), equalTo("ok"));
        assertThat(body.get("name").asText(), equalTo("example"));
        assertThat(body.get("version").asText(), equalTo("1.2.3"));
        assertThat(body.get("contract").asText(), equalTo(SidecarServer.CONTRACT));
    }

    @Test
    void postRouteReturnsTheHandlerResult() throws Exception {
        HttpResponse<String> response = post("/v1/echo", "{\"hello\":\"world\"}");

        assertThat(response.statusCode(), equalTo(200));
        assertThat(Json.mapper().readTree(response.body()).get("hello").asText(), equalTo("world"));
    }

    @Test
    void getOnAJsonRouteIsMethodNotAllowed() throws Exception {
        HttpResponse<String> response = get("/v1/echo");

        assertThat(response.statusCode(), equalTo(405));
        assertThat(Json.mapper().readTree(response.body()).get("error").asText(), equalTo("Method not allowed"));
    }

    @Test
    void illegalArgumentIsBadRequestWithTheMessage() throws Exception {
        HttpResponse<String> response = post("/v1/reject", "{\"why\":\"because\"}");

        assertThat(response.statusCode(), equalTo(400));
        assertThat(Json.mapper().readTree(response.body()).get("error").asText(), equalTo("nope: because"));
    }

    @Test
    void missingRequiredFieldIsBadRequestNamingTheField() throws Exception {
        HttpResponse<String> response = post("/v1/reject", "{}");

        assertThat(response.statusCode(), equalTo(400));
        assertThat(Json.mapper().readTree(response.body()).get("error").asText(), equalTo("why is required."));
    }

    @Test
    void malformedJsonIsBadRequest() throws Exception {
        HttpResponse<String> response = post("/v1/echo", "{not json");

        assertThat(response.statusCode(), equalTo(400));
    }

    @Test
    void emptyBodyIsBadRequest() throws Exception {
        HttpResponse<String> response = post("/v1/echo", "");

        assertThat(response.statusCode(), equalTo(400));
        assertThat(Json.mapper().readTree(response.body()).get("error").asText(), equalTo("A JSON request body is required."));
    }

    @Test
    void registeredExceptionTypeIsBadRequest() throws Exception {
        HttpResponse<String> response = post("/v1/custom", "{}");

        assertThat(response.statusCode(), equalTo(400));
    }

    @Test
    void unexpectedExceptionIsInternalErrorWithTheMessage() throws Exception {
        HttpResponse<String> response = post("/v1/explode", "{}");

        assertThat(response.statusCode(), equalTo(500));
        assertThat(Json.mapper().readTree(response.body()).get("error").asText(), equalTo("boom"));
    }

    @Test
    void routesMustBeVersioned() {
        SidecarServer.Builder builder = SidecarServer.builder("example");

        assertThrows(IllegalArgumentException.class, () -> builder.route("/echo", body -> body));
    }

    @Test
    void sidecarNeedsAName() {
        assertThrows(IllegalArgumentException.class, () -> SidecarServer.builder(" "));
    }

    @Test
    void versionFallsBackToDevWithoutTheEnvironmentVariable() {
        assertThat(System.getenv(SidecarServer.VERSION_ENV) == null ? SidecarServer.version() : "dev", equalTo("dev"));
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(server.baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create(server.baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
