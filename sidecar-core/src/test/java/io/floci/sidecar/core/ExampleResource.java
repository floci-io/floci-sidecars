package io.floci.sidecar.core;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** The routes the contract tests exercise; the shape every sidecar resource follows. */
@Path("/v1")
@Produces(MediaType.APPLICATION_JSON)
public class ExampleResource {

    @POST
    @Path("/echo")
    public JsonNode echo(String raw) throws Exception {
        return Json.body(raw);
    }

    @POST
    @Path("/reject")
    public JsonNode reject(String raw) throws Exception {
        throw new IllegalArgumentException("nope: " + Json.requiredText(Json.body(raw), "why"));
    }

    @POST
    @Path("/custom")
    public JsonNode custom(String raw) {
        throw new UnsupportedOperationException("custom means bad request here");
    }

    @POST
    @Path("/explode")
    public JsonNode explode(String raw) {
        throw new IllegalStateException("boom");
    }
}
