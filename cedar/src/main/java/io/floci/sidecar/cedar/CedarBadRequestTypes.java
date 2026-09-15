package io.floci.sidecar.cedar;

import com.cedarpolicy.model.exception.AuthException;
import io.floci.sidecar.core.BadRequestTypes;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;

/** Cedar's own parse and evaluation failures describe the request, so they answer 400. */
@ApplicationScoped
public class CedarBadRequestTypes implements BadRequestTypes {

    @Override
    public Set<Class<? extends Throwable>> types() {
        return Set.of(AuthException.class);
    }
}
