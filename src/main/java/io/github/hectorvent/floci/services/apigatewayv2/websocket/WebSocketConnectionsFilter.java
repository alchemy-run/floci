package io.github.hectorvent.floci.services.apigatewayv2.websocket;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites API Gateway management-API paths onto the path-style execute-api
 * dispatcher before JAX-RS resource matching. S3's catch-all
 * {@code POST /{bucket}/{key}} would otherwise treat
 * {@code /test/@connections/{id}} (and {@code /@connections/{id}} when
 * {@code AWS_ENDPOINT_URL} is the request origin) as a multipart upload
 * and return {@code InvalidArgument}.
 *
 * <p>The actual POST/GET/DELETE is handled by
 * {@code ApiGatewayExecuteController} ({@code @Blocking}), which is
 * required because reading the POST body on the Vert.x IO thread throws
 * {@code BlockingNotAllowedException}.
 *
 * <p>Connection ids are globally unique in {@link WebSocketConnectionManager},
 * so a placeholder apiId/stage is enough to reach the controller.
 */
@Provider
@PreMatching
@Priority(1)
@ApplicationScoped
public class WebSocketConnectionsFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(WebSocketConnectionsFilter.class);

    /** {@code @connections} or percent-encoded {@code %40connections}. */
    private static final Pattern CONNECTIONS_PATH = Pattern.compile(
            "(?:.*/)?(?:@|%40)connections/([^/]+)/?$");

    @Override
    public void filter(ContainerRequestContext requestContext) {
        URI original = requestContext.getUriInfo().getRequestUri();
        String path = original != null ? original.getRawPath() : null;
        if (path == null) {
            return;
        }
        String connectionId = extractConnectionId(path);
        if (connectionId == null) {
            return;
        }
        // Already on the execute-api dispatcher — the @Blocking controller
        // owns POST/GET/DELETE. Do not intercept (and never read the body
        // on the IO thread).
        if (path.startsWith("/execute-api/")) {
            return;
        }
        String rewritten = "/execute-api/_/_/@connections/" + connectionId;
        LOG.infov("Routing ManageConnections {0} {1} -> {2}",
                requestContext.getMethod(), path, rewritten);
        requestContext.setRequestUri(
                UriBuilder.fromUri(original).replacePath(rewritten).build());
    }

    static String extractConnectionId(String path) {
        if (path == null) {
            return null;
        }
        Matcher matcher = CONNECTIONS_PATH.matcher(path);
        return matcher.matches() ? matcher.group(1) : null;
    }
}
