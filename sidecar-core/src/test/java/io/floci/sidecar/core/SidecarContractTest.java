package io.floci.sidecar.core;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/** The contract as an HTTP surface, which is the only surface an emulator ever sees. */
@QuarkusTest
class SidecarContractTest {

    @Test
    void healthReportsTheContractFields() {
        when().get("/health")
                .then().statusCode(200)
                .contentType(startsWith("application/json"))
                .body("status", equalTo("ok"))
                .body("name", equalTo("example"))
                .body("version", equalTo("1.2.3"))
                .body("contract", equalTo(SidecarInfo.CONTRACT));
    }

    @Test
    void postRouteReturnsTheHandlerResult() {
        post("/v1/echo", "{\"hello\":\"world\"}")
                .then().statusCode(200).body("hello", equalTo("world"));
    }

    @Test
    void getOnAJsonRouteIsMethodNotAllowed() {
        when().get("/v1/echo")
                .then().statusCode(405).body("error", equalTo("Method not allowed"));
    }

    @Test
    void unknownPathIsNotFoundWithTheEnvelope() {
        when().get("/v1/nothing-here")
                .then().statusCode(404).body("error", equalTo("Not found"));
    }

    @Test
    void illegalArgumentIsBadRequestWithTheMessage() {
        post("/v1/reject", "{\"why\":\"because\"}")
                .then().statusCode(400).body("error", equalTo("nope: because"));
    }

    @Test
    void missingRequiredFieldIsBadRequestNamingTheField() {
        post("/v1/reject", "{}")
                .then().statusCode(400).body("error", equalTo("why is required."));
    }

    @Test
    void malformedJsonIsBadRequest() {
        post("/v1/echo", "{not json")
                .then().statusCode(400);
    }

    @Test
    void emptyBodyIsBadRequest() {
        post("/v1/echo", "")
                .then().statusCode(400).body("error", equalTo("A JSON request body is required."));
    }

    @Test
    void registeredExceptionTypeIsBadRequest() {
        post("/v1/custom", "{}")
                .then().statusCode(400).body("error", equalTo("custom means bad request here"));
    }

    @Test
    void unexpectedExceptionIsInternalErrorWithTheMessage() {
        post("/v1/explode", "{}")
                .then().statusCode(500).body("error", equalTo("boom"));
    }

    private static Response post(String path, String body) {
        return given().contentType(ContentType.JSON).body(body).post(path);
    }
}
