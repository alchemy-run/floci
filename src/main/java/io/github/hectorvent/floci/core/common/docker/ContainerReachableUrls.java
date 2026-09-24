package io.github.hectorvent.floci.core.common.docker;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites URLs in a launched container's environment so both the workload
 * and the host-side test client can reach the right process.
 *
 * <p>{@code localhost} / {@code 127.0.0.1} inside a Lambda container is the
 * container itself. Docker Desktop (and Floci's Linux {@code extra-hosts})
 * map {@code host.docker.internal} to the host gateway. This is intentionally
 * <em>not</em> {@link DockerHostResolver}: when Floci itself runs in Docker
 * that resolver returns Floci's address, which is wrong for a collector
 * bound on the host.
 *
 * <p>API Gateway WebSocket invoke URLs are the opposite problem: Alchemy
 * advertises {@code wss://{apiId}.execute-api.{region}.amazonaws.com/{stage}}
 * and the <em>test process</em> dials that string. The host has no Floci
 * DNS, so we rewrite those to the path-style data plane on the published
 * gateway port ({@code wss://127.0.0.1:{port}/ws/{apiId}/{stage}}).
 *
 * <p>HTTPS {@code @connections} callbacks use Floci's path-style execute-api
 * plane. Docker mode uses {@code localhost.floci.io} via embedded DNS;
 * source mode uses the host resolved by {@link DockerHostResolver}.
 * Source-mode AppSync GraphQL URLs also use the reachable host and their
 * path-style endpoint. Loopback URLs on the gateway port use localhost.floci.io
 * in both modes so a presign created inside Lambda is also usable by the host.
 * Only unsigned environment configuration is rewritten, before the runtime signs it.
 */
public final class ContainerReachableUrls {

    public static final String HOST_DOCKER_INTERNAL = "host.docker.internal";
    public static final int DEFAULT_HOST_GATEWAY_PORT = 4566;

    /**
     * {@code http(s)|ws(s)://} followed by a loopback host. The lookahead
     * refuses {@code localhost.floci.io} (a Floci DNS name, not loopback).
     */
    private static final Pattern LOOPBACK_URL_HOST = Pattern.compile(
            "(?i)((?:https?|wss?)://)(localhost|127\\.0\\.0\\.1|\\[::1\\])(:\\d+)?(?=[/?#\"'\\s,}\\]]|$)");

    private static final Pattern SIGNED_VALUE = Pattern.compile(
            "(?i)(?:[?&]|%3f|%26)(?:X-Amz-(?:Signature|Algorithm)|AWSAccessKeyId|Signature)(?:=|%3d)"
                    + "|AWS4-HMAC-SHA256");

    private static final Pattern EXECUTE_API_WSS = Pattern.compile(
            "(?i)wss://([a-z0-9-]+)\\.execute-api\\.[a-z0-9-]+\\.amazonaws\\.com(/[^\"'\\s,}\\]]*)?");

    private static final Pattern EXECUTE_API_HTTPS = Pattern.compile(
            "(?i)(https://)([a-z0-9-]+)\\.execute-api\\.[a-z0-9-]+\\.amazonaws\\.com"
                    + "(:\\d+)?(?=[/?#\"'\\s,}\\]]|$)");

    private static final Pattern APPSYNC_GRAPHQL = Pattern.compile(
            "(?i)(https?://)([a-z0-9-]+)\\.appsync-api\\.[a-z0-9-]+\\.localhost\\.floci\\.io"
                    + "(:\\d+)?/graphql(?=[?#\"'\\s,}\\]]|$)");

    private static final Pattern EXECUTE_API_FLOCI_HTTPS = Pattern.compile(
            "(?i)(https://)localhost\\.floci\\.io(:\\d+)?(?=/execute-api/)");

    /** Embedded-DNS name Lambda containers use to reach Floci (see {@code EmbeddedDnsServer}). */
    public static final String FLOCI_DNS_HOST = "localhost.floci.io";

    private ContainerReachableUrls() {
    }

    /**
     * Rewrite function environment values for a launched Lambda container.
     *
     * @param hostGatewayPort published Floci port on the developer machine
     */
    public static String rewriteFunctionEnv(String value, int hostGatewayPort) {
        return rewriteFunctionEnv(value, hostGatewayPort, true, FLOCI_DNS_HOST);
    }

    /**
     * @param runningInContainer whether Floci itself is running in Docker
     * @param flociHost host returned by {@link DockerHostResolver#resolve()}
     */
    public static String rewriteFunctionEnv(String value, int hostGatewayPort,
                                            boolean runningInContainer, String flociHost) {
        if (value == null || value.isEmpty() || SIGNED_VALUE.matcher(value).find()) {
            return value;
        }
        int port = hostGatewayPort > 0 ? hostGatewayPort : DEFAULT_HOST_GATEWAY_PORT;
        String host = runningInContainer ? FLOCI_DNS_HOST : flociHost;
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        String rewritten = rewriteExecuteApiHttpsToPathStyle(value, port, host);
        if (!runningInContainer) {
            String reachableHost = host;
            rewritten = EXECUTE_API_FLOCI_HTTPS.matcher(rewritten).replaceAll(match ->
                    Matcher.quoteReplacement(match.group(1) + reachableHost
                            + (match.group(2) == null ? "" : match.group(2))));
            rewritten = APPSYNC_GRAPHQL.matcher(rewritten).replaceAll(match ->
                    Matcher.quoteReplacement(match.group(1) + reachableHost
                            + (match.group(3) == null ? "" : match.group(3))
                            + "/v1/apis/" + match.group(2) + "/graphql"));
        }
        return rewriteExecuteApiWssToPathStyle(rewriteLoopbackHosts(rewritten, port), port);
    }

    /**
     * Replace loopback hosts in {@code value} with {@code host.docker.internal}.
     * Safe on JSON blobs (OTLP exporter lists) and plain URLs. {@code null}
     * and strings without a loopback URL are returned unchanged.
     */
    public static String rewriteLoopbackHosts(String value) {
        return rewriteLoopbackHosts(value, -1);
    }

    private static String rewriteLoopbackHosts(String value, int gatewayPort) {
        if (value == null || value.isEmpty() || SIGNED_VALUE.matcher(value).find()) {
            return value;
        }
        return LOOPBACK_URL_HOST.matcher(value).replaceAll(match -> {
            String explicitPort = match.group(3);
            String port = explicitPort == null
                    ? (match.group(1).equalsIgnoreCase("https://") || match.group(1).equalsIgnoreCase("wss://")
                        ? "443" : "80")
                    : explicitPort.substring(1);
            String host = port.equals(Integer.toString(gatewayPort)) ? FLOCI_DNS_HOST : HOST_DOCKER_INTERNAL;
            return Matcher.quoteReplacement(match.group(1) + host + (explicitPort == null ? "" : explicitPort));
        });
    }

    /**
     * {@code wss://{apiId}.execute-api.{region}.amazonaws.com/{stage}} →
     * {@code wss://127.0.0.1:{port}/ws/{apiId}/{stage}}.
     */
    public static String rewriteExecuteApiWssToPathStyle(String value, int hostGatewayPort) {
        if (value == null || value.isEmpty() || SIGNED_VALUE.matcher(value).find()) {
            return value;
        }
        Matcher matcher = EXECUTE_API_WSS.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String apiId = matcher.group(1);
            String path = matcher.group(2) == null ? "" : matcher.group(2);
            String replacement = "wss://127.0.0.1:" + hostGatewayPort + "/ws/" + apiId + path;
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * {@code https://{apiId}.execute-api.{region}.amazonaws.com/{stage}} →
     * {@code https://localhost.floci.io:{port}/execute-api/{apiId}/{stage}}.
     */
    public static String rewriteExecuteApiHttpsToPathStyle(String value, int hostGatewayPort) {
        return rewriteExecuteApiHttpsToPathStyle(value, hostGatewayPort, FLOCI_DNS_HOST);
    }

    private static String rewriteExecuteApiHttpsToPathStyle(String value, int hostGatewayPort, String host) {
        if (value == null || value.isEmpty() || SIGNED_VALUE.matcher(value).find()) {
            return value;
        }
        int port = hostGatewayPort > 0 ? hostGatewayPort : DEFAULT_HOST_GATEWAY_PORT;
        return EXECUTE_API_HTTPS.matcher(value).replaceAll(match ->
                Matcher.quoteReplacement(match.group(1) + host
                        + (match.group(3) == null ? ":" + port : match.group(3))
                        + "/execute-api/" + match.group(2)));
    }
}
