package io.floci.sidecar.core;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;

@ApplicationScoped
public class ExampleBadRequestTypes implements BadRequestTypes {

    @Override
    public Set<Class<? extends Throwable>> types() {
        return Set.of(UnsupportedOperationException.class);
    }
}
