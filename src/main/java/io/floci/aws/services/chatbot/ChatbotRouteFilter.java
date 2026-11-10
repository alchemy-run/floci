package io.floci.aws.services.chatbot;

import io.floci.aws.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Routes requests signed for {@code chatbot} to {@link ChatbotController}: Chatbot's operations are {@code POST}s
 * to paths like {@code /tag-resource} and {@code /list-associations}, which mean nothing to other services.
 */
@Provider
@PreMatching
@Priority(4988)
public class ChatbotRouteFilter implements ContainerRequestFilter {
    static final String PREFIX = "/_chatbot";

    @Override
    public void filter(ContainerRequestContext context) {
        String path = context.getUriInfo().getRequestUri().getRawPath();
        if (path.equals(PREFIX) || path.startsWith(PREFIX + "/")) {
            context.abortWith(Response.status(404).build());
            return;
        }
        String host = context.getHeaderString("Host");
        boolean signed = SigV4CredentialScope.serviceName(context.getHeaderString("Authorization"))
                .map("chatbot"::equals)
                .orElse(host != null && host.startsWith("chatbot."));
        if (signed && path.matches("/[a-z-]+")) {
            context.setRequestUri(context.getUriInfo().getRequestUriBuilder().replacePath(PREFIX + path)
                    .buildFromEncoded());
        }
    }
}
