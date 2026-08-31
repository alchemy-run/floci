package io.github.hectorvent.floci.services.lexv2;

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
 * Lex Models V2 and Runtime V2 share restJson1 {@code /bots} paths, which collide
 * with S3's {@code /{bucket}} catch-all. SigV4 credential scope {@code lex} is
 * rewritten onto {@code /lex-v2} so the Lex controllers own those routes. Tag
 * APIs stay on {@code /tags/{arn}}.
 */
@Provider
@PreMatching
@Priority(10)
public class LexV2RoutingFilter implements ContainerRequestFilter {

    static final String INTERNAL_PREFIX = "/lex-v2";

    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("Credential=\\S+/\\d{8}/[^/]+/([^/]+)/");

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!isLex(requestContext.getHeaderString("Authorization"))) {
            return;
        }
        URI original = requestContext.getUriInfo().getRequestUri();
        String path = original.getRawPath();
        if (path == null || path.startsWith(INTERNAL_PREFIX + "/")) {
            return;
        }
        if (!"/bots".equals(path) && !path.startsWith("/bots/")) {
            return;
        }
        URI rewritten = UriBuilder.fromUri(original)
                .replacePath(INTERNAL_PREFIX + path)
                .build();
        requestContext.setRequestUri(rewritten);
    }

    static boolean isLex(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return false;
        }
        Matcher matcher = SERVICE_PATTERN.matcher(authorization);
        return matcher.find()
                && LexV2Service.SERVICE.equals(matcher.group(1).toLowerCase(Locale.ROOT));
    }
}
