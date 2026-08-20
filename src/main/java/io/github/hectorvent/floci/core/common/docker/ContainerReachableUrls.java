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
 * <p>HTTPS {@code @connections} callback URLs keep an {@code https://}
 * prefix (Alchemy fixtures assert that) but are rewritten onto Floci's
 * path-style execute-api plane
 * ({@code https://localhost.floci.io:{port}/execute-api/{apiId}/{stage}}).
 * Embedded DNS maps that host to Floci; the path is already
 * {@code /execute-api/...} so virtual-host filters (and HTTP/2 Host
 * omission) cannot mis-route the POST to S3.
 */
public final class ContainerReachableUrls {

    public static final String HOST_DOCKER_INTERNAL = "host.docker.internal";
    public static final int DEFAULT_HOST_GATEWAY_PORT = 4566;

    /**
     * {@code http(s)|ws(s)://} followed by a loopback host. The lookahead
     * refuses {@code localhost.floci.io} (a Floci DNS name, not loopback).
     */
    private static final Pattern LOOPBACK_URL_HOST = Pattern.compile(
            "(?i)((?:https?|wss?)://)(localhost|127\\.0\\.0\\.1|\\[::1\\])(?=[:/\"'\\s,}\\]]|$)");

    private static final Pattern EXECUTE_API_WSS = Pattern.compile(
            "(?i)wss://([a-z0-9-]+)\\.execute-api\\.[a-z0-9-]+\\.amazonaws\\.com(/[^\"'\\s,}\\]]*)?");

    /**
     * {@code https://{apiId}.execute-api.{region}.amazonaws.com[/stage]}
     * — optional port so a previous :4566 pin is still rewritten to path-style.
     */
    private static final Pattern EXECUTE_API_HTTPS = Pattern.compile(
            "(?i)https://([a-z0-9-]+)\\.execute-api\\.[a-z0-9-]+\\.amazonaws\\.com(?::\\d+)?(/[^\"'\\s,}\\]]*)?");

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
        if (value == null || value.isEmpty()) {
            return value;
        }
        int port = hostGatewayPort > 0 ? hostGatewayPort : DEFAULT_HOST_GATEWAY_PORT;
        return rewriteExecuteApiHttpsToPathStyle(
                rewriteExecuteApiWssToPathStyle(rewriteLoopbackHosts(value), port),
                port);
    }

    /**
     * Replace loopback hosts in {@code value} with {@code host.docker.internal}.
     * Safe on JSON blobs (OTLP exporter lists) and plain URLs. {@code null}
     * and strings without a loopback URL are returned unchanged.
     */
    public static String rewriteLoopbackHosts(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return LOOPBACK_URL_HOST.matcher(value).replaceAll("$1" + HOST_DOCKER_INTERNAL);
    }

    /**
     * {@code wss://{apiId}.execute-api.{region}.amazonaws.com/{stage}} →
     * {@code wss://127.0.0.1:{port}/ws/{apiId}/{stage}}.
     */
    public static String rewriteExecuteApiWssToPathStyle(String value, int hostGatewayPort) {
        if (value == null || value.isEmpty()) {
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
        if (value == null || value.isEmpty()) {
            return value;
        }
        int port = hostGatewayPort > 0 ? hostGatewayPort : DEFAULT_HOST_GATEWAY_PORT;
        Matcher matcher = EXECUTE_API_HTTPS.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String apiId = matcher.group(1);
            String path = matcher.group(2) == null ? "" : matcher.group(2);
            String replacement = "https://" + FLOCI_DNS_HOST + ":" + port
                    + "/execute-api/" + apiId + path;
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
