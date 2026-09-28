package io.github.hectorvent.floci.services.ecs.container;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Loopback URLs in a task's plain environment are made reachable from the task container the way
 * Lambda containers see them, except those naming a port the task itself listens on.
 */
class EcsContainerManagerLoopbackEnvTest {

    private static final int GATEWAY_PORT = 4566;

    @Test
    void loopbackUrlOnTheDeveloperMachinePointsAtTheDockerHost() {
        assertEquals("http://host.docker.internal:1337",
                EcsContainerManager.containerReachableEnvValue("http://localhost:1337", Set.of(3000), GATEWAY_PORT));
    }

    @Test
    void loopbackUrlsInsideAJsonExporterListAreRewritten() {
        String exporters = "[{\"url\":\"http://localhost:1337\",\"serviceName\":\"svc\"}]";
        assertEquals("[{\"url\":\"http://host.docker.internal:1337\",\"serviceName\":\"svc\"}]",
                EcsContainerManager.containerReachableEnvValue(exporters, Set.of(3000), GATEWAY_PORT));
    }

    @Test
    void loopbackUrlOnFlociPortPointsAtFloci() {
        assertEquals("http://localhost.floci.io:4566/queue",
                EcsContainerManager.containerReachableEnvValue("http://127.0.0.1:4566/queue", Set.of(), GATEWAY_PORT));
    }

    @Test
    void loopbackUrlToASidecarOfTheSameTaskIsLeftAlone() {
        assertEquals("http://localhost:2000",
                EcsContainerManager.containerReachableEnvValue("http://localhost:2000", Set.of(2000), GATEWAY_PORT));
        assertEquals("http://localhost:2000,http://host.docker.internal:1337",
                EcsContainerManager.containerReachableEnvValue(
                        "http://localhost:2000,http://localhost:1337", Set.of(2000), GATEWAY_PORT));
    }

    @Test
    void valuesWithoutLoopbackUrlsOrWithSignaturesAreUnchanged() {
        assertEquals("plain-value",
                EcsContainerManager.containerReachableEnvValue("plain-value", Set.of(3000), GATEWAY_PORT));
        String presigned = "http://localhost:4566/bucket/key?X-Amz-Signature=abc";
        assertEquals(presigned,
                EcsContainerManager.containerReachableEnvValue(presigned, Set.of(3000), GATEWAY_PORT));
        assertEquals(null, EcsContainerManager.containerReachableEnvValue(null, Set.of(3000), GATEWAY_PORT));
    }
}
