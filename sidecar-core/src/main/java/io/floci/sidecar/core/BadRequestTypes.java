package io.floci.sidecar.core;

import java.util.Set;

/**
 * Exception types a sidecar throws to mean "the request is wrong" (400) rather than "I failed"
 * (500). {@link IllegalArgumentException} and malformed JSON are always bad requests; a sidecar
 * exposes a bean of this type to add its own, such as a policy engine's parse exception.
 */
public interface BadRequestTypes {

    Set<Class<? extends Throwable>> types();
}
