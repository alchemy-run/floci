package io.floci.aws.services.grafana;

import io.floci.aws.core.common.RequestHost;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;
import java.util.Optional;

/**
 * Routes a workspace endpoint host ({@code <id>.grafana-workspace.<region>.<suffix>}, see {@link GrafanaEndpoint})
 * to the path-style proxy {@code /_floci/grafana-workspace/<region>/<id>/<path>}, the client's path and query kept.
 */
@Provider
@PreMatching
@Priority(5)
public class GrafanaDataPlaneRoutingFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext context) {
        URI original = context.getUriInfo().getRequestUri();
        String host = RequestHost.of(context.getHeaderString("Host"), original);
        Optional<GrafanaEndpoint.Target> target = GrafanaEndpoint.parse(host);
        if (target.isEmpty()) {
            return;
        }
        String path = original.getRawPath() == null || original.getRawPath().isEmpty() ? "/" : original.getRawPath();
        if (path.startsWith(GrafanaDataPlaneController.BASE_PREFIX)) {
            return;
        }
        context.setRequestUri(UriBuilder.fromUri(original).host("localhost")
                .replacePath(GrafanaDataPlaneController.BASE_PREFIX + target.get().region() + "/"
                        + target.get().workspaceId() + path).build());
    }
}
