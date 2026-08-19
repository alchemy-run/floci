package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.apigatewayv2.ApiGatewayV2Service;
import io.github.hectorvent.floci.services.apigatewayv2.model.Api;
import io.github.hectorvent.floci.services.apigatewayv2.model.Stage;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;

/**
 * Routes {@code {apiId}.execute-api.{region}.amazonaws.com} Host headers to
 * the path-style execute-api data plane.
 *
 * <p>AWS invoke URLs are virtual-hosted. Alchemy's test HttpClient rewrites
 * those URLs onto the gateway and keeps the Host, matching the S3-website and
 * AppSync filters. This filter maps:
 *
 * <ul>
 *   <li>REST (v1): {@code /{stage}/{path}} → {@code /execute-api/{apiId}/{stage}/{path}}</li>
 *   <li>HTTP (v2) {@code $default}: {@code /{path}} → {@code /execute-api/{apiId}/$default/{path}}</li>
 *   <li>HTTP (v2) named stage: first path segment is the stage when it exists</li>
 * </ul>
 */
@Provider
@PreMatching
@Priority(6)
public class ApiGatewayExecuteApiRoutingFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(ApiGatewayExecuteApiRoutingFilter.class);

    private final ApiGatewayService apiGatewayService;
    private final ApiGatewayV2Service apiGatewayV2Service;
    private final RegionResolver regionResolver;

    @Inject
    public ApiGatewayExecuteApiRoutingFilter(ApiGatewayService apiGatewayService,
                                             ApiGatewayV2Service apiGatewayV2Service,
                                             RegionResolver regionResolver) {
        this.apiGatewayService = apiGatewayService;
        this.apiGatewayV2Service = apiGatewayV2Service;
        this.regionResolver = regionResolver;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        URI original = requestContext.getUriInfo().getRequestUri();
        // HTTP/2 has no Host header — authority is on the request URI
        // (same fallback as S3VirtualHostFilter). Lambda's fetch() to
        // wss/https execute-api hosts negotiates h2 over TlsProxy, so a
        // Host-only check would leave /{stage}/@connections/... as
        // path-style S3 (POST → InvalidArgument).
        String host = resolveHost(requestContext.getHeaderString("Host"), original);
        String apiId = extractApiId(host);
        if (apiId == null) {
            return;
        }

        String path = original.getRawPath();
        if (path == null) {
            path = "/";
        }
        if (alreadyPathStyle(path)) {
            return;
        }

        String hostRegion = extractRegion(host);
        String preferred = hostRegion != null ? hostRegion : regionResolver.getDefaultRegion();

        String rewritten = rewriteToExecuteApiPath(apiId, path, preferred);
        if (rewritten == null) {
            return;
        }

        URI newUri = UriBuilder.fromUri(original)
                .host("localhost")
                .replacePath(rewritten)
                .build();
        LOG.infov("Routing execute-api Host {0}{1} -> {2}", host, path, newUri.getPath());
        requestContext.setRequestUri(newUri);
    }

    String rewriteToExecuteApiPath(String apiId, String path, String preferredRegion) {
        String v2Region = apiGatewayV2Service.resolveApiRegion(preferredRegion, apiId);
        try {
            Api api = apiGatewayV2Service.getApi(v2Region, apiId);
            String stageName = resolveV2Stage(v2Region, apiId, api.getProtocolType(), path);
            String remaining = remainingAfterStage(path, stageName, api.getProtocolType());
            return executeApiPath(apiId, stageName, remaining);
        } catch (AwsException ignored) {
            // Not a v2 API — try REST
        }

        String v1Region = apiGatewayService.resolveRestApiRegion(preferredRegion, apiId);
        try {
            apiGatewayService.getRestApi(v1Region, apiId);
        } catch (AwsException e) {
            return null;
        }
        String stageName = firstSegment(path);
        if (stageName == null) {
            return null;
        }
        String remaining = remainingAfterFirstSegment(path);
        return executeApiPath(apiId, stageName, remaining);
    }

    private String resolveV2Stage(String region, String apiId, String protocolType, String path) {
        String first = firstSegment(path);
        if (first != null && stageExists(region, apiId, first)) {
            return first;
        }
        if ("WEBSOCKET".equals(protocolType)) {
            return first != null ? first : "$default";
        }
        if (stageExists(region, apiId, "$default")) {
            return "$default";
        }
        List<Stage> stages = apiGatewayV2Service.getStages(region, apiId);
        if (!stages.isEmpty()) {
            return stages.get(0).getStageName();
        }
        return first != null ? first : "$default";
    }

    private boolean stageExists(String region, String apiId, String stageName) {
        try {
            apiGatewayV2Service.getStage(region, apiId, stageName);
            return true;
        } catch (AwsException e) {
            return false;
        }
    }

    public static String extractApiId(String host) {
        String hostname = stripPort(host);
        if (hostname == null || !hostname.contains(".execute-api.")) {
            return null;
        }
        String[] parts = hostname.split("\\.");
        if (parts.length < 3 || !"execute-api".equals(parts[1]) || parts[0].isBlank()) {
            return null;
        }
        return parts[0];
    }

    static String extractRegion(String host) {
        String hostname = stripPort(host);
        if (hostname == null) {
            return null;
        }
        String[] parts = hostname.split("\\.");
        if (parts.length >= 5 && "execute-api".equals(parts[1]) && !parts[2].isBlank()) {
            return parts[2];
        }
        return null;
    }

    static String executeApiPath(String apiId, String stageName, String remainingPath) {
        String remaining = remainingPath == null ? "" : remainingPath;
        if (remaining.startsWith("/")) {
            remaining = remaining.substring(1);
        }
        return "/execute-api/" + apiId + "/" + stageName + "/" + remaining;
    }

    public static boolean alreadyPathStyle(String path) {
        return path.startsWith("/execute-api/")
                || path.startsWith("/restapis/")
                || path.startsWith("/v2/")
                || path.startsWith("/_aws/")
                || path.startsWith("/ws/");
    }

    public static String firstSegment(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return null;
        }
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        int slash = trimmed.indexOf('/');
        String segment = slash < 0 ? trimmed : trimmed.substring(0, slash);
        return segment.isBlank() ? null : segment;
    }

    static String remainingAfterFirstSegment(String path) {
        String first = firstSegment(path);
        if (first == null) {
            return "";
        }
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        if (trimmed.length() <= first.length()) {
            return "";
        }
        return trimmed.substring(first.length());
    }

    static String remainingAfterStage(String path, String stageName, String protocolType) {
        String first = firstSegment(path);
        if (first != null && first.equals(stageName) && !"$default".equals(stageName)) {
            return remainingAfterFirstSegment(path);
        }
        if (first != null && first.equals(stageName) && "WEBSOCKET".equals(protocolType)) {
            return remainingAfterFirstSegment(path);
        }
        if (path == null || "/".equals(path)) {
            return "";
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }

    /**
     * HTTP/1.1 {@code Host}, else the request URI authority (HTTP/2
     * {@code :authority}).
     */
    public static String resolveHost(String hostHeader, URI requestUri) {
        if (hostHeader != null && !hostHeader.isBlank()) {
            return hostHeader;
        }
        return requestUri != null ? requestUri.getAuthority() : null;
    }

    static String stripPort(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        int colonIndex = host.lastIndexOf(':');
        if (colonIndex > 0) {
            String maybePart = host.substring(colonIndex + 1);
            if (!maybePart.isEmpty() && maybePart.chars().allMatch(Character::isDigit)) {
                return host.substring(0, colonIndex);
            }
        }
        return host;
    }
}
