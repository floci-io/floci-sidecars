package io.floci.sidecar.graphql;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.sidecar.core.Json;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * {@code /v1/schema/validate} reports each parse or validation problem individually, on top of
 * the contract's plain {@code {"error": message}} envelope every other endpoint uses. JAX-RS
 * picks this mapper over {@code sidecar-core}'s generic one because it is the more specific type.
 */
@Provider
public class SchemaValidationExceptionMapper implements ExceptionMapper<SchemaValidationException> {

    @Override
    public Response toResponse(SchemaValidationException exception) {
        ObjectNode body = Json.error(exception.getMessage());
        ArrayNode issues = body.putArray("issues");
        for (SchemaValidationException.Issue issue : exception.issues()) {
            ObjectNode issueNode = issues.addObject();
            issueNode.put("category", issue.category());
            issueNode.put("message", issue.message());
            issueNode.put("line", issue.line());
            issueNode.put("column", issue.column());
        }
        return Response.status(400).type(MediaType.APPLICATION_JSON).entity(body).build();
    }
}
