package io.floci.aws.services.mediatailor;

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

/**
 * Routes MediaTailor's playback and channel assembly hosts, {@code <id>.mediatailor.<region>....} and
 * {@code <id>.channel-assembly.mediatailor.<region>....}, to {@link MediaTailorDataController}.
 */
@Provider
@PreMatching
@Priority(6)
public class MediaTailorHostFilter implements ContainerRequestFilter {

    private static final Pattern HOST = Pattern.compile(
            "(?i)([a-f0-9]{32})\\.(channel-assembly\\.)?mediatailor\\.([a-z0-9-]+)\\..*");

    @Override
    public void filter(ContainerRequestContext context) {
        URI original = context.getUriInfo().getRequestUri();
        String host = ApiGatewayExecuteApiRoutingFilter.resolveHost(context.getHeaderString("Host"), original);
        if (host == null) {
            return;
        }
        Matcher matcher = HOST.matcher(host.split(":")[0]);
        if (!matcher.matches() || original.getRawPath().startsWith(MediaTailorStore.PATH)) {
            return;
        }
        String kind = matcher.group(2) == null ? MediaTailorStore.PLAYBACK : MediaTailorStore.ASSEMBLY;
        String path = MediaTailorStore.PATH + "/" + kind + "/" + matcher.group(3) + "/" + matcher.group(1)
                + original.getRawPath();
        context.setRequestUri(UriBuilder.fromUri(original).host("localhost").replacePath(path).build());
    }
}
