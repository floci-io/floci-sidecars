package io.floci.sidecar.core;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Routing failures (404, 405 and the like) in the same {@code {"error": message}} envelope. */
@Provider
public class WebApplicationExceptionMapper implements ExceptionMapper<WebApplicationException> {

    @Override
    public Response toResponse(WebApplicationException exception) {
        int status = exception.getResponse().getStatus();
        String message = switch (status) {
            case 404 -> "Not found";
            case 405 -> "Method not allowed";
            default -> SidecarExceptionMapper.safeMessage(exception);
        };
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(Json.error(message))
                .build();
    }
}
