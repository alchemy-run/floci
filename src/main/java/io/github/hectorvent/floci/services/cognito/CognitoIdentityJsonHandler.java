package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/** Compatibility entry point backed by the canonical Cognito Identity handler. */
@ApplicationScoped
public class CognitoIdentityJsonHandler {

    // Both packages expose CognitoIdentityJsonHandler; qualify the canonical delegate.
    private final io.github.hectorvent.floci.services.cognitoidentity.CognitoIdentityJsonHandler delegate;

    @Inject
    public CognitoIdentityJsonHandler(CognitoIdentityService service, ObjectMapper objectMapper) {
        this.delegate = service.handler();
    }

    public Response handle(String action, JsonNode request, String region) {
        return delegate.handle(action, request, region);
    }
}
