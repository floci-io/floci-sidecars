package io.floci.sidecar.graphql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.floci.sidecar.core.Json;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A JDK {@link HttpServer} stands in for the caller's resolver callback endpoint, so these tests
 * assert on what {@link GraphqlResource}'s {@code resolve} wiring actually sends over the wire,
 * not just on the final GraphQL response.
 */
@QuarkusTest
class ResolveCallbackTest {

    private HttpServer standIn;
    private final List<JsonNode> receivedRequests = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopStandIn() {
        if (standIn != null) {
            standIn.stop(0);
        }
    }

    @Test
    void oneCallbackCallPerExecutionLevelWithBatchingWithinALevelAndNestedSource() throws Exception {
        startStandIn(request -> {
            String fieldName = request.get("invocations").get(0).get("fieldName").asText();
            if ("post".equals(fieldName)) {
                return okResults(request, invocation -> data(Json.object().put("id", "1")));
            }
            // author and other are siblings on Post: both due at the same level, so they must
            // arrive together as one two-invocation call, each carrying level 1's returned data
            // as its source.
            return okResults(request, invocation -> {
                String id = invocation.get("source").get("id").asText();
                String field = invocation.get("fieldName").asText();
                return data(Json.mapper().getNodeFactory().textNode(field + "-for-" + id));
            });
        });

        String sdl = "type Post { id: String author: String other: String } type Query { post: Post }";
        String query = "{ post { id author other } }";
        Response response = execute(sdl, query, resolveBlock("Query.post", "Post.author", "Post.other"));

        assertThat(response.statusCode(), equalTo(200));
        assertThat(receivedRequests.size(), equalTo(2));
        JsonNode secondLevelRequest = receivedRequests.get(1);
        assertThat(secondLevelRequest.get("invocations").size(), equalTo(2));
        for (JsonNode invocation : secondLevelRequest.get("invocations")) {
            assertThat(invocation.get("source").get("id").asText(), equalTo("1"));
        }
        JsonNode post = json(response).path("data").path("post");
        assertThat(post.path("author").asText(), equalTo("author-for-1"));
        assertThat(post.path("other").asText(), equalTo("other-for-1"));
    }

    @Test
    void maxBatchSplitsOneLevelAcrossMultipleCalls() throws Exception {
        startStandIn(request -> okResults(request,
                invocation -> data(Json.mapper().getNodeFactory().textNode(invocation.get("fieldName").asText()))));

        String sdl = "type Query { a: String b: String c: String }";
        String query = "{ a b c }";
        Response response = execute(sdl, query, resolveBlockWithMaxBatch(1, "Query.a", "Query.b", "Query.c"));

        assertThat(response.statusCode(), equalTo(200));
        assertThat(receivedRequests.size(), equalTo(3));
        for (JsonNode request : receivedRequests) {
            assertThat(request.get("invocations").size(), equalTo(1));
        }
    }

    @Test
    void anErrorResultBecomesAGraphQLErrorWithExtensions() throws Exception {
        startStandIn(request -> okResults(request, invocation -> error("Not found", "NotFound")));

        String sdl = "type Query { thing: String }";
        Response response = execute(sdl, "{ thing }", resolveBlock("Query.thing"));

        assertThat(response.statusCode(), equalTo(200));
        JsonNode body = json(response);
        assertThat(body.path("errors").get(0).path("message").asText(), equalTo("Not found"));
        assertThat(body.path("errors").get(0).path("extensions").path("type").asText(), equalTo("NotFound"));
        assertTrue(body.path("data").path("thing").isNull());
    }

    @Test
    void typenameInReturnedDataResolvesTheUnionMember() throws Exception {
        startStandIn(request -> okResults(request, invocation -> {
            ObjectNode dog = Json.object();
            dog.put("__typename", "Dog");
            dog.put("name", "Rex");
            dog.put("bark", "Woof");
            return data(dog);
        }));

        String sdl = """
                interface Animal { name: String }
                type Dog implements Animal { name: String bark: String }
                type Cat implements Animal { name: String meow: String }
                type Query { pet: Animal }
                """;
        String query = "{ pet { name ... on Dog { bark } ... on Cat { meow } } }";
        Response response = execute(sdl, query, resolveBlock("Query.pet"));

        assertThat(response.statusCode(), equalTo(200));
        JsonNode pet = json(response).path("data").path("pet");
        assertThat(pet.path("name").asText(), equalTo("Rex"));
        assertThat(pet.path("bark").asText(), equalTo("Woof"));
        assertTrue(pet.path("meow").isMissingNode());
    }

    @Test
    void deniedFieldIsNeverSentToTheCallback() throws Exception {
        startStandIn(request -> okResults(request,
                invocation -> data(Json.mapper().getNodeFactory().textNode("should not be seen"))));

        String sdl = "type Query { secret: String }";
        String denyFields = "[{\"typeName\":\"Query\",\"fieldName\":\"secret\","
                + "\"errorType\":\"Unauthorized\",\"message\":\"nope\"}]";
        Response response = execute(sdl, "{ secret }", resolveBlock("Query.secret"), denyFields);

        assertThat(response.statusCode(), equalTo(200));
        assertThat(receivedRequests.size(), equalTo(0));
        assertThat(json(response).path("errors").get(0).path("message").asText(), equalTo("nope"));
    }

    @Test
    void oneChunksCallbackFailureIsIsolatedFromTheOtherChunk() throws Exception {
        startStandIn(request -> {
            String fieldName = request.get("invocations").get(0).get("fieldName").asText();
            if ("b".equals(fieldName)) {
                return new StandInResponse(500, "{\"error\":\"boom\"}");
            }
            return okResults(request, invocation -> data(Json.mapper().getNodeFactory()
                    .textNode("ok-" + invocation.get("fieldName").asText())));
        });

        String sdl = "type Query { a: String b: String }";
        Response response = execute(sdl, "{ a b }", resolveBlockWithMaxBatch(1, "Query.a", "Query.b"));

        assertThat(response.statusCode(), equalTo(200));
        JsonNode body = json(response);
        assertThat(body.path("data").path("a").asText(), equalTo("ok-a"));
        assertTrue(body.path("data").path("b").isNull());
        assertThat(body.path("errors").get(0).path("path").get(0).asText(), equalTo("b"));
        assertThat(body.path("errors").get(0).path("message").asText(), containsString("HTTP 500"));
    }

    private String resolveBlock(String... coordinates) {
        return resolveBlockWithMaxBatch(100, coordinates);
    }

    private String resolveBlockWithMaxBatch(int maxBatch, String... coordinates) {
        ObjectNode resolve = Json.object();
        resolve.put("url", "http://127.0.0.1:" + standIn.getAddress().getPort() + "/resolve");
        resolve.put("token", "test-token");
        resolve.put("maxBatch", maxBatch);
        ArrayNode fields = resolve.putArray("fields");
        for (String coordinate : coordinates) {
            String[] parts = coordinate.split("\\.", 2);
            fields.add(Json.object().put("typeName", parts[0]).put("fieldName", parts[1]));
        }
        return resolve.toString();
    }

    private Response execute(String sdl, String query, String resolveJson) throws Exception {
        return execute(sdl, query, resolveJson, null);
    }

    private Response execute(String sdl, String query, String resolveJson, String denyFieldsJson) throws Exception {
        ObjectNode body = Json.object();
        body.put("sdl", sdl);
        body.put("query", query);
        body.set("resolve", Json.mapper().readTree(resolveJson));
        if (denyFieldsJson != null) {
            body.set("denyFields", Json.mapper().readTree(denyFieldsJson));
        }
        return given().contentType(ContentType.JSON).body(body.toString()).post("/v1/execute");
    }

    private static JsonNode json(Response response) throws Exception {
        return Json.mapper().readTree(response.asString());
    }

    private static ObjectNode data(JsonNode value) {
        ObjectNode node = Json.object();
        node.set("data", value);
        return node;
    }

    private static ObjectNode error(String message, String type) {
        ObjectNode node = Json.object();
        ObjectNode error = node.putObject("error");
        error.put("message", message);
        error.put("type", type);
        return node;
    }

    private void startStandIn(StandInHandler handler) throws IOException {
        standIn = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        standIn.setExecutor(Executors.newFixedThreadPool(4));
        standIn.createContext("/resolve", exchange -> {
            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            JsonNode request = Json.mapper().readTree(requestBytes);
            receivedRequests.add(request);
            StandInResponse response = handler.handle(request);
            byte[] responseBytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
            exchange.close();
        });
        standIn.start();
    }

    private static StandInResponse okResults(JsonNode request, PerInvocation perInvocation) {
        ArrayNode results = Json.mapper().createArrayNode();
        for (JsonNode invocation : request.get("invocations")) {
            ObjectNode result = Json.object();
            result.put("id", invocation.get("id").asText());
            JsonNode fields = perInvocation.resultFieldsFor(invocation);
            fields.fields().forEachRemaining(entry -> result.set(entry.getKey(), entry.getValue()));
            results.add(result);
        }
        ObjectNode root = Json.object();
        root.set("results", results);
        return new StandInResponse(200, root.toString());
    }

    @FunctionalInterface
    private interface StandInHandler {
        StandInResponse handle(JsonNode request);
    }

    @FunctionalInterface
    private interface PerInvocation {
        ObjectNode resultFieldsFor(JsonNode invocation);
    }

    private record StandInResponse(int status, String body) {
    }
}
