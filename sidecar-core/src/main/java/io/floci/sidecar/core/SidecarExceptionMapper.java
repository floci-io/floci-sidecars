package io.floci.sidecar.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * The contract's error envelope for handler failures: {@code {"error": message}} with 400 for a
 * request the sidecar rejects and 500 for anything it did not expect.
 */
@Provider
public class SidecarExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOG = Logger.getLogger(SidecarExceptionMapper.class);

    private final Instance<BadRequestTypes> badRequestTypes;
    private final SidecarInfo info;

    @Context
    UriInfo uriInfo;

    @Inject
    public SidecarExceptionMapper(Instance<BadRequestTypes> badRequestTypes, SidecarInfo info) {
        this.badRequestTypes = badRequestTypes;
        this.info = info;
    }

    @Override
    public Response toResponse(Throwable exception) {
        int status = isBadRequest(exception) ? 400 : 500;
        if (status == 500) {
            String path = uriInfo == null ? "?" : uriInfo.getPath();
            LOG.warnv(exception, "{0} sidecar failed on {1}", info.name(), path);
        }
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(Json.error(safeMessage(exception)))
                .build();
    }

    private boolean isBadRequest(Throwable exception) {
        if (exception instanceof IllegalArgumentException || exception instanceof JsonProcessingException) {
            return true;
        }
        for (BadRequestTypes registered : badRequestTypes) {
            for (Class<? extends Throwable> type : registered.types()) {
                if (type.isInstance(exception)) {
                    return true;
                }
            }
        }
        return false;
    }

    static String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
