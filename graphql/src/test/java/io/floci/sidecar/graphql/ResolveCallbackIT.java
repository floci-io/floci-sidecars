package io.floci.sidecar.graphql;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * The resolve callback against the packaged sidecar: the native executable, running as a
 * separate process, calls back out to this test's stand-in {@code HttpServer} over loopback,
 * which is the only place the native HTTP client path is actually proven.
 */
@QuarkusIntegrationTest
class ResolveCallbackIT extends ResolveCallbackTest {
}
