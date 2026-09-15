package io.floci.sidecar.core;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * What a sidecar says about itself on {@code /health}: its name, its own version and the
 * contract major it implements ({@code docs/contract.md}). The version comes from the
 * {@code SIDECAR_VERSION} environment variable the image bakes in, {@code dev} otherwise.
 */
@ApplicationScoped
public class SidecarInfo {

    /** The sidecar contract major this core implements. */
    public static final String CONTRACT = "1";

    private final String name;
    private final String version;

    public SidecarInfo(@ConfigProperty(name = "sidecar.name") String name,
                       @ConfigProperty(name = "sidecar.version", defaultValue = "dev") String version) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A sidecar needs a name: set sidecar.name.");
        }
        this.name = name;
        this.version = version == null || version.isBlank() ? "dev" : version;
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }
}
