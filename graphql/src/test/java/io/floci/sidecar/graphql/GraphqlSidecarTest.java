package io.floci.sidecar.graphql;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.sidecar.core.Json;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class GraphqlSidecarTest {

    private static final String SDL = "type Query { hello: String greet(name: String): String }";
    private static final String INTERFACE_SDL = """
            directive @requiresRole on FIELD_DEFINITION
            interface Animal { name: String }
            type Dog implements Animal { name: String secret: String @requiresRole }
            type Cat implements Animal { name: String }
            type Query { pet: Animal }
            """;
    private static final String SCALAR_SDL = "scalar MyDateTime\ntype Query { echo(t: MyDateTime): MyDateTime }";

    @Test
    void healthDescribesTheGraphqlSidecar() {
        when().get("/health")
                .then().statusCode(200)
                .body("status", equalTo("ok"))
                .body("name", equalTo("graphql"))
                .body("contract", equalTo("1"));
    }

    @Test
    void schemaValidateAcceptsAndRejectsWithIssues() throws Exception {
        Response ok = post("/v1/schema/validate", Json.object().put("sdl", SDL).toString());
        Response bad = post("/v1/schema/validate", Json.object().put("sdl", "type Query {").toString());

        assertThat(ok.statusCode(), equalTo(200));
        assertThat(json(ok).get("valid").asBoolean(), is(true));
        assertThat(bad.statusCode(), equalTo(400));
        assertTrue(json(bad).get("issues").isArray());
        assertFalse(json(bad).get("issues").isEmpty());
    }

    @Test
    void planWalksFragmentsInterfacesAndUnions() throws Exception {
        String query = "{ pet { name ... on Dog { secret } } }";
        Response response = post("/v1/plan", Json.object().put("sdl", INTERFACE_SDL).put("query", query).toString());

        assertThat(response.statusCode(), equalTo(200));
        JsonNode body = json(response);
        assertThat(body.get("operationType").asText(), equalTo("QUERY"));
        assertThat(body.get("fields").size(), equalTo(4));
    }

    @Test
    void planAndExecuteRejectAnAmbiguousDocumentWithoutAnOperationName() throws Exception {
        String query = "query A { hello } query B { hello }";
        Response plan = post("/v1/plan", Json.object().put("sdl", SDL).put("query", query).toString());
        Response execute = post("/v1/execute", Json.object().put("sdl", SDL).put("query", query).toString());

        assertThat(plan.statusCode(), equalTo(400));
        assertThat(json(plan).get("error").asText(), containsString("operationName is required"));
        assertThat(execute.statusCode(), equalTo(400));
        assertThat(json(execute).get("error").asText(), containsString("operationName is required"));
    }

    @Test
    void executeRunsAQueryAndReturnsData() throws Exception {
        Response response = post("/v1/execute", Json.object().put("sdl", SDL).put("query", "{ hello }").toString());

        assertThat(response.statusCode(), equalTo(200));
        assertTrue(json(response).path("data").path("hello").isNull());
    }

    @Test
    void executeNullsOutDeniedFieldsWithTheCallersErrorTypeAndMessage() throws Exception {
        String body = "{\"sdl\":" + Json.mapper().writeValueAsString(SDL) + ","
                + "\"query\":\"{ hello }\","
                + "\"denyFields\":[{\"typeName\":\"Query\",\"fieldName\":\"hello\","
                + "\"errorType\":\"Unauthorized\",\"message\":\"nope\"}]}";
        Response response = post("/v1/execute", body);

        assertThat(response.statusCode(), equalTo(200));
        JsonNode json = json(response);
        assertThat(json.path("errors").get(0).path("message").asText(), equalTo("nope"));
        assertThat(json.path("errors").get(0).path("extensions").path("classification").asText(), equalTo("Unauthorized"));
    }

    @Test
    void executeRejectsAnInvalidLiteralAndAnInvalidVariableForACallerMappedScalar() throws Exception {
        String scalars = "{\"MyDateTime\":\"date-time\"}";
        String literalBody = "{\"sdl\":" + Json.mapper().writeValueAsString(SCALAR_SDL) + ",\"scalars\":" + scalars
                + ",\"query\":\"{ echo(t: \\\"not-a-date\\\") }\"}";
        String variableBody = "{\"sdl\":" + Json.mapper().writeValueAsString(SCALAR_SDL) + ",\"scalars\":" + scalars
                + ",\"query\":\"query($t: MyDateTime) { echo(t: $t) }\",\"variables\":{\"t\":\"not-a-date\"}}";

        Response literal = post("/v1/execute", literalBody);
        Response variable = post("/v1/execute", variableBody);

        assertThat(literal.statusCode(), equalTo(200));
        assertThat(literal.jsonPath().getString("errors[0].message"), containsString("MyDateTime"));
        assertThat(variable.statusCode(), equalTo(200));
        assertThat(variable.jsonPath().getString("errors[0].message"), containsString("MyDateTime"));
    }

    @Test
    void schemaCacheHitProducesTheSameResultAsACold() throws Exception {
        Response first = post("/v1/execute", Json.object().put("sdl", SDL).put("query", "{ hello }").toString());
        Response second = post("/v1/execute", Json.object().put("sdl", SDL).put("query", "{ hello }").toString());

        assertThat(first.statusCode(), equalTo(200));
        assertThat(second.statusCode(), equalTo(200));
        assertThat(json(first).toString(), equalTo(json(second).toString()));
    }

    private static JsonNode json(Response response) throws Exception {
        return Json.mapper().readTree(response.asString());
    }

    private static Response post(String path, String body) {
        return given().contentType(ContentType.JSON).body(body).post(path);
    }
}
