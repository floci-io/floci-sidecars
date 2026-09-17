package io.floci.sidecar.graphql;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * The same contract, run against the packaged sidecar: under {@code -Dnative} that is the
 * GraalVM executable, which is the only place the native-image configuration is actually proven.
 */
@QuarkusIntegrationTest
class GraphqlSidecarIT extends GraphqlSidecarTest {
}
