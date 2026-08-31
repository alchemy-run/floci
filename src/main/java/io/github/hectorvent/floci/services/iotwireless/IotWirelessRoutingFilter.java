package io.github.hectorvent.floci.services.iotwireless;

import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IoT Wireless shares restJson1 paths such as {@code /destinations} with IoT
 * Managed Integrations. Floci serves every service on one port, so SigV4
 * credential scope {@code iotwireless} is rewritten onto an internal prefix
 * that {@link IotWirelessController} owns. Tag APIs stay on {@code /tags}.
 */
@Provider
@PreMatching
@Priority(10)
public class IotWirelessRoutingFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/iot-wireless";

    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("Credential=\\S+/\\d{8}/[^/]+/([^/]+)/");

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!isIotWireless(requestContext.getHeaderString("Authorization"))) {
            return;
        }
        URI original = requestContext.getUriInfo().getRequestUri();
        String path = original.getRawPath();
        if (path == null
                || path.equals(INTERNAL_PREFIX)
                || path.startsWith(INTERNAL_PREFIX + "/")
                || path.equals("/tags")
                || path.startsWith("/tags/")) {
            return;
        }
        URI rewritten = UriBuilder.fromUri(original)
                .replacePath(INTERNAL_PREFIX + path)
                .build();
        requestContext.setRequestUri(rewritten);
    }

    static boolean isIotWireless(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return false;
        }
        Matcher matcher = SERVICE_PATTERN.matcher(authorization);
        return matcher.find()
                && IotWirelessService.SERVICE.equals(matcher.group(1).toLowerCase(Locale.ROOT));
    }
}
