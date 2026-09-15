package io.floci.sidecar.cedar;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * The same contract, run against the packaged sidecar: under {@code -Dnative} that is the
 * GraalVM executable with {@code CEDAR_JAVA_FFI_LIB} pointing at this platform's Rust runtime,
 * which is the only place the JNI configuration is actually proven.
 */
@QuarkusIntegrationTest
class CedarSidecarIT extends CedarSidecarTest {
}
