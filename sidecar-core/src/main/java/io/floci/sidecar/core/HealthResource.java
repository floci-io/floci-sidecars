package io.floci.sidecar.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** {@code GET /health}: the contract's readiness answer. */
@Path("/health")
public class HealthResource {

    private final SidecarInfo info;

    @Inject
    public HealthResource(SidecarInfo info) {
        this.info = info;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public ObjectNode health() {
        return Json.object()
                .put("status", "ok")
                .put("name", info.name())
                .put("version", info.version())
                .put("contract", SidecarInfo.CONTRACT);
    }
}
