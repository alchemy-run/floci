package io.github.hectorvent.floci.core.common.docker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings = {"http", "https"})
    void sourceModeRoutesAppSyncToReachablePathStylePreservingSchemeAndPort(String scheme) {
        assertEquals(scheme + "://host.docker.internal:14566/v1/apis/gql123/graphql",
                ContainerReachableUrls.rewriteFunctionEnv(
                        scheme + "://gql123.appsync-api.eu-west-1.localhost.floci.io:14566/graphql",
                        4566, false, "host.docker.internal"));
        assertEquals(scheme + "://host.docker.internal/v1/apis/gql123/graphql",
                ContainerReachableUrls.rewriteFunctionEnv(
                        scheme + "://gql123.appsync-api.eu-west-1.localhost.floci.io/graphql",
                        4566, false, "host.docker.internal"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "https"})
    void dockerModePreservesAppSyncVirtualHosts(String scheme) {
        String endpoint = scheme + "://gql123.appsync-api.eu-west-1.localhost.floci.io:14566/graphql";
        assertEquals(endpoint, ContainerReachableUrls.rewriteFunctionEnv(endpoint, 4566, true, "172.24.0.2"));
    }

    @Test
    void sourceModePreservesEncodedQueriesFragmentsAndJsonValues() {
        String input = "{\"graphql\":\"http://gql123.appsync-api.us-east-1.localhost.floci.io:4566/graphql"
                + "?query=%7Bhello%7D&value=$1#result\","
                + "\"callback\":\"https://abc123.execute-api.us-east-1.amazonaws.com/stage/@connections/a%2Fb"
                + "?value=$2#result\",\"other\":\"https://example.com/graphql\"}";
        String expected = "{\"graphql\":\"http://host.docker.internal:4566/v1/apis/gql123/graphql"
                + "?query=%7Bhello%7D&value=$1#result\","
                + "\"callback\":\"https://host.docker.internal:14566/execute-api/abc123/stage/@connections/a%2Fb"
                + "?value=$2#result\",\"other\":\"https://example.com/graphql\"}";
        assertEquals(expected, ContainerReachableUrls.rewriteFunctionEnv(input, 14566, false, "host.docker.internal"));
        assertEquals(expected, ContainerReachableUrls.rewriteFunctionEnv(expected, 14566, false, "host.docker.internal"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void executeApiCallbacksPreserveHttpsAndExplicitPorts(boolean runningInContainer) {
        String host = runningInContainer ? "localhost.floci.io" : "floci.internal";
        assertEquals("https://" + host + ":8443/execute-api/abc123/stage/@connections/a%2Fb",
                ContainerReachableUrls.rewriteFunctionEnv(
                        "https://abc123.execute-api.us-east-1.amazonaws.com:8443/stage/@connections/a%2Fb",
                        14566, runningInContainer, "floci.internal"));
        assertEquals("https://" + host + ":14566/execute-api/abc123?query=1#result",
                ContainerReachableUrls.rewriteFunctionEnv(
                        "https://abc123.execute-api.us-east-1.amazonaws.com?query=1#result",
                        14566, runningInContainer, "floci.internal"));
        assertEquals("https://" + host + ":4566/execute-api/abc123/stage",
                ContainerReachableUrls.rewriteFunctionEnv(
                        "https://abc123.execute-api.us-east-1.amazonaws.com/stage",
                        0, runningInContainer, "floci.internal"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void existingPathStyleCallbacksUseReachableHostOnlyInSourceMode(boolean runningInContainer) {
        String host = runningInContainer ? "localhost.floci.io" : "host.docker.internal";
        assertEquals("https://" + host + ":8443/execute-api/abc123/stage",
                ContainerReachableUrls.rewriteFunctionEnv(
                        "https://localhost.floci.io:8443/execute-api/abc123/stage",
                        4566, runningInContainer, "host.docker.internal"));
    }

    @Test
    void sourceModeSupportsResolvedIpv6Host() {
        assertEquals("https://[fd00::1]:8443/v1/apis/gql123/graphql",
                ContainerReachableUrls.rewriteFunctionEnv(
                        "https://gql123.appsync-api.us-east-1.localhost.floci.io:8443/graphql",
                        4566, false, "fd00::1"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void routingLeavesUnrelatedUrlsAndValuesUnchanged(boolean runningInContainer) {
        String[] values = {
                "not-a-url",
                "https://example.com/graphql",
                "http://bucket.localhost.floci.io:4566/object",
                "https://gql123.appsync-api.us-east-1.amazonaws.com/graphql",
                "https://gql123.appsync-api.us-east-1.localhost.floci.io.example.com/graphql",
                "https://gql123.appsync-api.us-east-1.localhost.floci.io:4566/other",
                "https://gql123.appsync-api.us-east-1.localhost.floci.io:4566/graphql-other",
                "https://abc123.execute-api.us-east-1.amazonaws.com.example.com/stage",
                "https://abc123.execute-api.us-east-1.amazonaws.com:8443@other.example/stage",
                "http://abc123.execute-api.us-east-1.amazonaws.com/stage",
                "https://localhost.floci.io:4566/unrelated"
        };
        for (String value : values) {
            assertEquals(value, ContainerReachableUrls.rewriteFunctionEnv(
                    value, 4566, runningInContainer, "host.docker.internal"));
        }
        assertNull(ContainerReachableUrls.rewriteFunctionEnv(null, 4566, runningInContainer, "host.docker.internal"));
        assertEquals("", ContainerReachableUrls.rewriteFunctionEnv("", 4566, runningInContainer, "host.docker.internal"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void gatewayLoopbackEndpointsUseSharedHostnameBeforeSigning(boolean runningInContainer) {
        for (String scheme : new String[]{"http", "https", "ws", "wss"}) {
            for (String host : new String[]{"localhost", "127.0.0.1", "[::1]"}) {
                String suffix = ":14566/a%2Fb?key=%2F&value=$1#fragment";
                String expected = scheme + "://localhost.floci.io" + suffix;
                assertEquals(expected, ContainerReachableUrls.rewriteFunctionEnv(
                        scheme + "://" + host + suffix, 14566, runningInContainer, "host.docker.internal"));
                assertEquals(expected, ContainerReachableUrls.rewriteFunctionEnv(
                        expected, 14566, runningInContainer, "host.docker.internal"));
            }
        }
    }

    @Test
    void preservesGatewayAndOtlpPortsInsideTheSameEnvironmentValue() {
        assertEquals("{\"endpoint\":\"https://localhost.floci.io:4566\","
                        + "\"traces\":\"http://host.docker.internal:4318/v1/traces?x=%2F\"}",
                ContainerReachableUrls.rewriteFunctionEnv(
                        "{\"endpoint\":\"https://127.0.0.1:4566\","
                                + "\"traces\":\"http://localhost:4318/v1/traces?x=%2F\"}",
                        4566, false, "host.docker.internal"));
        assertEquals("https://localhost.floci.io/path?x=1", ContainerReachableUrls.rewriteFunctionEnv(
                "https://localhost/path?x=1", 443, false, "host.docker.internal"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void signedUrlsAndSignedEnvironmentDocumentsAreNeverRewritten(boolean runningInContainer) {
        for (String endpoint : new String[]{
                "https://127.0.0.1:4566/bucket/key", "https://localhost.floci.io:4566/bucket/key",
                "https://api.execute-api.us-east-1.amazonaws.com/stage",
                "wss://api.execute-api.us-east-1.amazonaws.com/stage",
                "https://api.appsync-api.us-east-1.localhost.floci.io/graphql"}) {
            String signed = endpoint + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=0123%2Fab";
            assertEquals(signed, ContainerReachableUrls.rewriteFunctionEnv(
                    signed, 4566, runningInContainer, "host.docker.internal"));
            assertEquals(signed, ContainerReachableUrls.rewriteLoopbackHosts(signed));
            assertEquals(signed, ContainerReachableUrls.rewriteExecuteApiHttpsToPathStyle(signed, 4566));
            assertEquals(signed, ContainerReachableUrls.rewriteExecuteApiWssToPathStyle(signed, 4566));
            String document = "{\"url\":\"" + signed + "\",\"payload\":\"http://localhost:4566/unchanged\"}";
            assertEquals(document, ContainerReachableUrls.rewriteFunctionEnv(
                    document, 4566, runningInContainer, "host.docker.internal"));
        }
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
