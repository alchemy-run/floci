package io.floci.aws.services.opensearchserverless;

import io.floci.aws.services.apigateway.ApiGatewayExecuteApiRoutingFilter;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Routes a collection's endpoint, {@code <id>.<region>.aoss.<suffix>}, to {@link AossDataPlane}. */
@Provider
@PreMatching
@Priority(6)
public class AossHostFilter implements ContainerRequestFilter {

    private static final Pattern HOST = Pattern.compile("(?i)([a-z0-9]{3,40})\\.([a-z0-9-]+)\\.aoss\\..*");

    @Override
    public void filter(ContainerRequestContext context) {
        URI original = context.getUriInfo().getRequestUri();
        String host = ApiGatewayExecuteApiRoutingFilter.resolveHost(context.getHeaderString("Host"), original);
        if (host == null) {
            return;
        }
        Matcher matcher = HOST.matcher(host.split(":")[0]);
        if (!matcher.matches() || original.getRawPath().startsWith(AossDataPlane.PREFIX)) {
            return;
        }
        String path = AossDataPlane.PREFIX + "/" + matcher.group(2).toLowerCase() + "/"
                + matcher.group(1).toLowerCase() + original.getRawPath();
        context.setRequestUri(UriBuilder.fromUri(original).host("localhost").replacePath(path).build());
    }
}
