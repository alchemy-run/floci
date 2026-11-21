package io.floci.aws.services.lexv2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestConstraintCheck;
import io.floci.aws.core.common.SigV4CredentialScope;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Routes requests signed for {@code lex} to the Lex V2 controllers: session paths
 * ({@code /bots/{id}/botAliases/{alias}/botLocales/{locale}/sessions/...}) to {@link LexRuntimeController}, the rest to
 * {@link LexModelsController}. Both APIs' paths ({@code /bots}, {@code /tags}, {@code /policy}, {@code /exports}) are
 * other REST services' paths too.
 */
@Provider
@PreMatching
@Priority(4967)
public class LexRouteFilter implements ContainerRequestFilter {
    static final String MODELS_PREFIX = "/_lexmodels";
    static final String RUNTIME_PREFIX = "/_lexruntime";
    private static final Pattern RUNTIME = Pattern.compile("^/bots/[^/]+/botAliases/[^/]+/botLocales/[^/]+/sessions/.*");

    static final RequestConstraintCheck.Messages MESSAGES = new RequestConstraintCheck.Messages() {
        @Override
        public AwsException type(String path, String type) {
            return LexStore.validation(path + " must be a " + type + ".");
        }

        @Override
        public AwsException bounds(String path, String type, Long min, Long max, long size) {
            String what = "string".equals(type) ? "length" : "list".equals(type) || "map".equals(type) ? "size"
                    : "value";
            return LexStore.validation(path + " has " + what + " " + size + ", outside " + (min == null ? "" : min)
                    + ".." + (max == null ? "" : max) + ".");
        }

        @Override
        public AwsException pattern(String path, String pattern) {
            return LexStore.validation(path + " must match " + pattern + ".");
        }

        @Override
        public AwsException enumValue(String path, List<String> values) {
            return LexStore.validation(path + " must be one of " + String.join(", ", values) + ".");
        }

        @Override
        public AwsException required(String path) {
            return LexStore.validation(path + " is required.");
        }
    };

    @Override
    public void filter(ContainerRequestContext context) {
        String path = context.getUriInfo().getRequestUri().getRawPath();
        for (String prefix : List.of(MODELS_PREFIX, RUNTIME_PREFIX)) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                context.abortWith(Response.status(404).build());
                return;
            }
        }
        String host = context.getHeaderString("Host");
        boolean signed = SigV4CredentialScope.serviceName(context.getHeaderString("Authorization"))
                .map("lex"::equals)
                .orElse(host != null && (host.startsWith("models-v2-lex.") || host.startsWith("runtime-v2-lex.")));
        if (!signed || !path.startsWith("/")) {
            return;
        }
        boolean runtime = RUNTIME.matcher(path).matches() || (host != null && host.startsWith("runtime-v2-lex."));
        context.setRequestUri(context.getUriInfo().getRequestUriBuilder()
                .replacePath((runtime ? RUNTIME_PREFIX : MODELS_PREFIX) + path).buildFromEncoded());
    }

    /** A Lex V2 error: {@code message} in the body and the error code in {@code x-amzn-ErrorType}. */
    static Response error(ObjectMapper mapper, AwsException e) {
        ObjectNode error = mapper.createObjectNode();
        error.put("message", e.getMessage());
        if (e.getExtendedData() != null) {
            e.getExtendedData().forEach((key, value) -> error.set(key, mapper.valueToTree(value)));
        }
        return Response.status(e.getHttpStatus()).type(MediaType.APPLICATION_JSON)
                .header("x-amzn-ErrorType", e.getErrorCode()).entity(error.toString()).build();
    }
}
