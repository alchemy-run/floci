package io.github.hectorvent.floci.core.common.docker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContainerReachableUrlsTest {

    @Test
    void rewritesLocalhostHttpUrl() {
        assertEquals("http://host.docker.internal:8787/v1/traces",
                ContainerReachableUrls.rewriteLoopbackHosts("http://localhost:8787/v1/traces"));
    }

    @Test
    void rewritesLoopbackIpv4AndIpv6() {
        assertEquals("https://host.docker.internal/collected",
                ContainerReachableUrls.rewriteLoopbackHosts("https://127.0.0.1/collected"));
        assertEquals("http://host.docker.internal:4318",
                ContainerReachableUrls.rewriteLoopbackHosts("http://[::1]:4318"));
    }

    @Test
    void rewritesHostsInsideJsonExporterLists() {
        String raw = "[{\"traces\":{\"url\":\"http://localhost:8787/v1/traces\"},"
                + "\"logs\":{\"url\":\"http://127.0.0.1:8787/v1/logs\"}}]";
        String rewritten = ContainerReachableUrls.rewriteLoopbackHosts(raw);
        assertEquals("[{\"traces\":{\"url\":\"http://host.docker.internal:8787/v1/traces\"},"
                + "\"logs\":{\"url\":\"http://host.docker.internal:8787/v1/logs\"}}]", rewritten);
    }

    @Test
    void leavesFlociDnsAndPublicHostsAlone() {
        assertEquals("https://otel.example.workers.dev/v1/traces",
                ContainerReachableUrls.rewriteLoopbackHosts("https://otel.example.workers.dev/v1/traces"));
        assertEquals("http://bucket.localhost.floci.io:4566",
                ContainerReachableUrls.rewriteLoopbackHosts("http://bucket.localhost.floci.io:4566"));
        assertEquals("not-a-url", ContainerReachableUrls.rewriteLoopbackHosts("not-a-url"));
    }

    @Test
    void passesThroughNullAndEmpty() {
        assertNull(ContainerReachableUrls.rewriteLoopbackHosts(null));
        assertEquals("", ContainerReachableUrls.rewriteLoopbackHosts(""));
    }

    @Test
    void rewritesExecuteApiWssInvokeUrlToPathStyleGateway() {
        assertEquals("wss://127.0.0.1:4566/ws/abc123/test",
                ContainerReachableUrls.rewriteExecuteApiWssToPathStyle(
                        "wss://abc123.execute-api.us-east-1.amazonaws.com/test", 4566));
    }

    @Test
    void rewritesHttpsExecuteApiCallbackUrlsToPathStyleFlociDns() {
        assertEquals("https://localhost.floci.io:4566/execute-api/abc123/test",
                ContainerReachableUrls.rewriteFunctionEnv(
                        "https://abc123.execute-api.us-east-1.amazonaws.com/test", 4566));
    }

    @Test
    void rewritesHttpsExecuteApiCallbackUrlsThatAlreadyHaveAPort() {
        assertEquals("https://localhost.floci.io:4566/execute-api/abc123/test",
                ContainerReachableUrls.rewriteFunctionEnv(
                        "https://abc123.execute-api.us-east-1.amazonaws.com:4566/test", 4566));
    }

    @Test
    void rewriteFunctionEnvAppliesLoopbackAndWssRules() {
        assertEquals("http://host.docker.internal:8787",
                ContainerReachableUrls.rewriteFunctionEnv("http://localhost:8787", 4566));
        assertEquals("wss://127.0.0.1:4566/ws/abc123/test",
                ContainerReachableUrls.rewriteFunctionEnv(
                        "wss://abc123.execute-api.us-east-1.amazonaws.com/test", 4566));
    }
}
