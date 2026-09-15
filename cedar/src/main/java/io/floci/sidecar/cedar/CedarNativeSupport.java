package io.floci.sidecar.cedar;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The cedar-java types Jackson reaches reflectively on the way to and from the Rust runtime.
 * Cedar wraps every request in a private inner type before serialising it across JNI, so the
 * native-image analysis cannot infer these from the sidecar's own call sites. Nested classes
 * register with their owner. The JNI callbacks Rust makes into Java are declared in
 * {@code META-INF/native-image/.../jni-config.json}.
 */
@RegisterForReflection(classNames = {
        "com.cedarpolicy.BasicAuthorizationEngine$AuthorizationRequest",
        "com.cedarpolicy.model.AuthorizationRequest",
        "com.cedarpolicy.model.AuthorizationResponse",
        "com.cedarpolicy.model.AuthorizationSuccessResponse",
        "com.cedarpolicy.model.DetailedError",
        "com.cedarpolicy.model.ValidationRequest",
        "com.cedarpolicy.model.ValidationResponse",
        "com.cedarpolicy.serializer.JsonEUID"
})
final class CedarNativeSupport {

    private CedarNativeSupport() {
    }
}
