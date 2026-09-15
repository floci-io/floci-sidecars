package io.floci.sidecar.cedar;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.sidecar.core.Json;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class CedarSidecarTest {

    private static final String SCHEMA = "{\"PhotoApp\":{"
            + "\"entityTypes\":{"
            + "\"User\":{\"memberOfTypes\":[\"Group\"],\"shape\":{\"type\":\"Record\",\"attributes\":{\"age\":{\"type\":\"Long\"}}}},"
            + "\"Group\":{\"shape\":{\"type\":\"Record\",\"attributes\":{}}},"
            + "\"Photo\":{\"shape\":{\"type\":\"Record\",\"attributes\":{}}}},"
            + "\"actions\":{\"view\":{\"appliesTo\":{\"principalTypes\":[\"User\"],\"resourceTypes\":[\"Photo\"],"
            + "\"context\":{\"type\":\"Record\",\"attributes\":{\"authenticated\":{\"type\":\"Boolean\"}}}}}}}}";

    @Test
    void healthDescribesTheCedarSidecar() {
        when().get("/health")
                .then().statusCode(200)
                .body("status", equalTo("ok"))
                .body("name", equalTo("cedar"))
                .body("contract", equalTo("1"));
    }

    @Test
    void entityTypeValidateAcceptsAndRejects() throws Exception {
        Response ok = post("/v1/entity-type/validate", "{\"entityType\":\"PhotoApp::User\"}");
        Response bad = post("/v1/entity-type/validate", "{\"entityType\":\"not a type\"}");

        assertThat(ok.statusCode(), equalTo(200));
        assertThat(json(ok).get("valid").asBoolean(), is(true));
        assertThat(bad.statusCode(), equalTo(400));
        assertThat(json(bad).get("error").asText(), containsString("Invalid Cedar entity type"));
    }

    @Test
    void schemaValidateAcceptsAndRejects() throws Exception {
        Response ok = post("/v1/schema/validate", Json.object().put("schema", SCHEMA).toString());
        Response bad = post("/v1/schema/validate", "{\"schema\":\"{\\\"broken\\\":42}\"}");

        assertThat(ok.statusCode(), equalTo(200));
        assertThat(bad.statusCode(), equalTo(400));
    }

    @Test
    void policyParseReturnsEffectAndAst() throws Exception {
        Response permit = post("/v1/policy/parse", "{\"statement\":\"permit(principal, action, resource);\"}");
        Response forbid = post("/v1/policy/parse", "{\"statement\":\"forbid(principal, action, resource);\"}");

        assertThat(permit.statusCode(), equalTo(200));
        assertThat(json(permit).get("effect").asText(), equalTo("Permit"));
        assertThat(json(permit).get("ast").get("effect").asText(), equalTo("permit"));
        assertThat(json(forbid).get("effect").asText(), equalTo("Forbid"));
    }

    @Test
    void policyParseHandlesTemplates() throws Exception {
        Response response = post("/v1/policy/parse",
                "{\"statement\":\"permit(principal == ?principal, action, resource == ?resource);\",\"template\":true}");

        assertThat(response.statusCode(), equalTo(200));
        assertThat(json(response).get("effect").asText(), equalTo("Permit"));
        assertThat(json(response).get("ast").get("principal").get("slot").asText(), equalTo("?principal"));
    }

    @Test
    void policyParseRejectsSyntaxErrors() throws Exception {
        Response response = post("/v1/policy/parse", "{\"statement\":\"permit(principal\"}");

        assertThat(response.statusCode(), equalTo(400));
    }

    @Test
    void policyValidateAcceptsAndRejectsAgainstTheSchema() throws Exception {
        String valid = "permit(principal == PhotoApp::User::\\\"alice\\\", action == PhotoApp::Action::\\\"view\\\", resource);";
        String invalid = "permit(principal == PhotoApp::Nobody::\\\"x\\\", action, resource);";
        Response ok = post("/v1/policy/validate",
                "{\"schema\":" + Json.mapper().writeValueAsString(SCHEMA) + ",\"statement\":\"" + valid + "\"}");
        Response bad = post("/v1/policy/validate",
                "{\"schema\":" + Json.mapper().writeValueAsString(SCHEMA) + ",\"statement\":\"" + invalid + "\"}");

        assertThat(ok.statusCode(), equalTo(200));
        assertThat(json(ok).get("valid").asBoolean(), is(true));
        assertThat(bad.statusCode(), equalTo(400));
        assertThat(json(bad).get("error").asText(), containsString("failed STRICT schema validation"));
    }

    @Test
    void authorizeAllowsAndNamesTheDeterminingPolicy() throws Exception {
        Response response = post("/v1/authorize", "{"
                + "\"request\":{" + request() + ",\"entities\":{\"entityList\":[]},\"context\":{\"contextMap\":{}}},"
                + "\"policies\":[{\"policyId\":\"p1\",\"policyType\":\"STATIC\",\"statement\":\"permit(principal, action, resource);\"}]}");

        assertThat(response.statusCode(), equalTo(200));
        assertThat(json(response).get("decision").asText(), equalTo("ALLOW"));
        assertThat(texts(json(response).get("determiningPolicyIds")), contains("p1"));
    }

    @Test
    void authorizeLetsForbidOverridePermit() throws Exception {
        Response response = post("/v1/authorize", "{"
                + "\"request\":{" + request() + "},"
                + "\"policies\":["
                + "{\"policyId\":\"allow\",\"policyType\":\"STATIC\",\"statement\":\"permit(principal, action, resource);\"},"
                + "{\"policyId\":\"deny\",\"policyType\":\"STATIC\",\"statement\":\"forbid(principal, action, resource);\"}]}");

        assertThat(json(response).get("decision").asText(), equalTo("DENY"));
        assertThat(texts(json(response).get("determiningPolicyIds")), contains("deny"));
    }

    @Test
    void authorizeLinksTemplates() throws Exception {
        Response response = post("/v1/authorize", "{"
                + "\"request\":{" + request() + "},"
                + "\"policies\":[{\"policyId\":\"linked\",\"policyType\":\"TEMPLATE_LINKED\",\"policyTemplateId\":\"t1\","
                + "\"principal\":{\"entityType\":\"PhotoApp::User\",\"entityId\":\"alice\"},"
                + "\"resource\":{\"entityType\":\"PhotoApp::Photo\",\"entityId\":\"vacation.jpg\"}}],"
                + "\"templates\":{\"t1\":{\"statement\":\"permit(principal == ?principal, action, resource == ?resource);\"}}}");

        assertThat(response.statusCode(), equalTo(200));
        assertThat(json(response).get("decision").asText(), equalTo("ALLOW"));
        assertThat(texts(json(response).get("determiningPolicyIds")), contains("linked"));
    }

    @Test
    void authorizeEvaluatesEntityListAttributesParentsAndContext() throws Exception {
        String entities = "{\"entityList\":["
                + "{\"identifier\":{\"entityType\":\"PhotoApp::User\",\"entityId\":\"alice\"},"
                + "\"attributes\":{\"age\":{\"long\":17}},"
                + "\"parents\":[{\"entityType\":\"PhotoApp::Group\",\"entityId\":\"friends\"}]},"
                + "{\"identifier\":{\"entityType\":\"PhotoApp::User\",\"entityId\":\"alice\"},"
                + "\"attributes\":{\"age\":{\"long\":30}},"
                + "\"parents\":[{\"entityType\":\"PhotoApp::Group\",\"entityId\":\"friends\"}]}]}";
        String policy = "permit(principal in PhotoApp::Group::\\\"friends\\\", action, resource)"
                + " when { principal.age >= 18 && context.authenticated };";
        Response response = post("/v1/authorize", "{"
                + "\"request\":{" + request() + ",\"entities\":" + entities
                + ",\"context\":{\"contextMap\":{\"authenticated\":{\"boolean\":true}}}},"
                + "\"policies\":[{\"policyId\":\"p1\",\"policyType\":\"STATIC\",\"statement\":\"" + policy + "\"}]}");

        assertThat(response.asString(), response.statusCode(), equalTo(200));
        assertThat(json(response).get("decision").asText(), equalTo("ALLOW"));
    }

    @Test
    void authorizeAcceptsCedarJsonEntitiesAndContext() throws Exception {
        String cedarEntities = "[{\"uid\":{\"type\":\"PhotoApp::User\",\"id\":\"alice\"},"
                + "\"attrs\":{\"age\":30,\"ip\":{\"__extn\":{\"fn\":\"ip\",\"arg\":\"10.0.0.1\"}}},\"parents\":[]}]";
        String policy = "permit(principal, action, resource) when { principal.age == 30 && context.ok };";
        Response response = post("/v1/authorize", "{"
                + "\"request\":{" + request()
                + ",\"entities\":{\"cedarJson\":" + Json.mapper().writeValueAsString(cedarEntities) + "}"
                + ",\"context\":{\"cedarJson\":\"{\\\"ok\\\":true}\"}},"
                + "\"policies\":[{\"policyId\":\"p1\",\"policyType\":\"STATIC\",\"statement\":\"" + policy + "\"}]}");

        assertThat(response.asString(), response.statusCode(), equalTo(200));
        assertThat(json(response).get("decision").asText(), equalTo("ALLOW"));
    }

    @Test
    void authorizeRejectsAMissingRequest() throws Exception {
        Response response = post("/v1/authorize", "{\"policies\":[]}");

        assertThat(response.statusCode(), equalTo(400));
        assertThat(json(response).get("error").asText(), equalTo("request is required."));
    }

    private static String request() {
        return "\"principal\":{\"entityType\":\"PhotoApp::User\",\"entityId\":\"alice\"},"
                + "\"action\":{\"actionType\":\"PhotoApp::Action\",\"actionId\":\"view\"},"
                + "\"resource\":{\"entityType\":\"PhotoApp::Photo\",\"entityId\":\"vacation.jpg\"}";
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static JsonNode json(Response response) throws Exception {
        return Json.mapper().readTree(response.asString());
    }

    private static Response post(String path, String body) {
        return given().contentType(ContentType.JSON).body(body).post(path);
    }
}
